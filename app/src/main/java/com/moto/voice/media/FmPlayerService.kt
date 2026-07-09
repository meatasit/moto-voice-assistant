package com.moto.voice.media

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.moto.voice.MainActivity
import com.moto.voice.MotoVoiceApplication.Companion.CH_RADIO
import com.moto.voice.nlu.ErrorSpeech
import com.moto.voice.tts.ThaiTTS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground media playback service backed by [MediaSessionService], so system media
 * controls (lock-screen widget, headset play/pause, Android Auto) work.
 *
 * Note on foreground promotion (bug §3 fix): MediaSessionService's automatic foreground
 * promotion only kicks in when the framework binds it via a MediaController connection.
 * When we start the service directly with a custom ACTION_PLAY intent, the framework
 * does NOT auto-promote, and the OS kills us within 5s without any audio ever playing.
 * So we call [startForeground] ourselves as the first thing in ACTION_PLAY handling.
 */
class FmPlayerService : MediaSessionService() {

    companion object {
        const val ACTION_PLAY = "com.moto.voice.FM_PLAY"
        const val ACTION_STOP = "com.moto.voice.FM_STOP"
        /** Soft pause — service stays alive so [ACTION_RESUME] can pick up exactly where we left off. */
        const val ACTION_PAUSE = "com.moto.voice.FM_PAUSE"
        const val ACTION_RESUME = "com.moto.voice.FM_RESUME"
        /**
         * Yield our MediaSession's active state for the YouTube nudge (v1.3.7).
         *
         * v1.3.6 sent ACTION_STOP here which killed the service — the rider could
         * still voice-resume via [com.moto.voice.nlu.LocalIntercept.ResumeLastRadio]
         * because `AppMemory.lastStationUrl` is set independently, but the notification
         * disappeared and any next play required a fresh service start.
         *
         * ACTION_YIELD_SESSION takes a lighter touch: [Player.stop] transitions the
         * player to STATE_IDLE, which the framework reports as PlaybackState.STATE_STOPPED —
         * dropping us out of the media button routing chain so KEYCODE_MEDIA_PLAY
         * dispatched next lands on YouTube's session. The service stays alive and the
         * foreground notification stays visible; a subsequent [ACTION_RESUME] detects
         * the STATE_IDLE, re-prepares the same [currentUrl], and playback resumes.
         *
         * Common flow this enables — rider swaps radio ↔ YouTube on the highway:
         *   1. "เปิดวิทยุ 106"     → FM playing
         *   2. "เปิด YouTube ..."  → SCO teardown, YouTube intent, 3s later nudge
         *                              → we YIELD, YouTube receives MEDIA_PLAY
         *   3. Rider watches video → FM notification still visible (not "หยุด")
         *   4. "เปิดวิทยุ"          → ResumeLastRadio → ACTION_PLAY → same URL,
         *                              resume from cold — the notification never left,
         *                              the service was never respawned.
         */
        const val ACTION_YIELD_SESSION = "com.moto.voice.FM_YIELD_SESSION"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_LABEL = "label"
        private const val TAG = "FmPlayerService"
        private const val NOTIF_ID = 43
        private const val MAX_RETRIES = 2
    }

    private var mediaSession: MediaSession? = null
    private var currentUrl: String? = null
    private var currentLabel: String = "วิทยุ"
    private var retryCount = 0

    /** Ephemeral scope for the "retry exhausted → speak error → stop" flow. */
    private val ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var errorSpeakJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        // Icecast + Shoutcast streams commonly redirect HTTPS→HTTP and require a real
        // User-Agent. ExoPlayer's default rejects cross-protocol redirects, which is
        // why some FM streams (e.g. radiosolo.ru) played silently on build-22. Also
        // give the connection a generous timeout — streaming servers under load can
        // take a couple of seconds to start pushing bytes.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("MotoVoice/1.1 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true   // handleAudioFocus — pause on call / nav, resume when they finish
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(playerListener)

        val activityPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(activityPi)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPlayback()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                Log.d(TAG, "pause requested")
                mediaSession?.player?.playWhenReady = false
                // Foreground notification stays — service remains alive so RESUME can
                // continue instantly without a fresh SCO/prepare cycle.
            }
            ACTION_YIELD_SESSION -> {
                Log.d(TAG, "yield-session requested (YouTube nudge)")
                // Player.stop() → STATE_IDLE → session becomes ineligible for media
                // button routing. Service + notification stay alive so ACTION_PLAY /
                // ACTION_RESUME can revive from currentUrl. See ACTION_YIELD_SESSION kdoc.
                mediaSession?.player?.stop()
                FmPlaybackState.setPlaying(false)
            }
            ACTION_RESUME -> {
                Log.d(TAG, "resume requested")
                val player = mediaSession?.player
                val url = currentUrl
                if (player?.playbackState == Player.STATE_IDLE && !url.isNullOrBlank()) {
                    // Resuming from ACTION_YIELD_SESSION — player is IDLE so
                    // playWhenReady=true alone won't help; need to rebuild the item.
                    Log.d(TAG, "resume after yield — re-preparing $url")
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    player?.playWhenReady = true
                }
                FmPlaybackState.clearAssistantPaused()
            }
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_STREAM_URL)
                if (url.isNullOrBlank()) {
                    stopPlayback()
                    return START_NOT_STICKY
                }
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "วิทยุ"
                currentLabel = label
                // Promote to foreground FIRST — otherwise the OS kills us before the stream loads.
                promoteToForeground(label)
                play(url, label)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player?.playWhenReady != true || player.mediaItemCount == 0) stopPlayback()
    }

    override fun onDestroy() {
        errorSpeakJob?.cancel()
        ttsScope.cancel()
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Speak the FM error line via a temporary TTS instance, then stop playback.
     * Bounded by a timeout so a broken TTS engine can't keep the service alive forever.
     */
    private fun speakFatalErrorThenStop() {
        // Prevent silently stacking multiple TTS jobs if onPlayerError fires again.
        if (errorSpeakJob?.isActive == true) return
        errorSpeakJob = ttsScope.launch {
            withTimeoutOrNull(6_000L) {
                val tts = ThaiTTS(this@FmPlayerService)
                tts.speakAwait(ErrorSpeech.FM_STREAM_FAILED)
                tts.stop()
            }
            stopPlayback()
        }
    }

    // ─── Foreground + notification ──────────────────────────────────────────

    private fun promoteToForeground(label: String) {
        val notif = buildNotification(label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(label: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, FmPlayerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val mainPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_RADIO)
            .setContentTitle("Moto Voice — $label")
            .setContentText("กำลังเล่นอยู่  แตะเพื่อหยุด")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPi)
            .addAction(android.R.drawable.ic_media_pause, "หยุด", stopPi)
            .setOngoing(true)
            .build()
    }

    // ─── Playback control ───────────────────────────────────────────────────

    private fun play(url: String, label: String) {
        val session = mediaSession ?: return
        currentUrl = url
        retryCount = 0
        Log.d(TAG, "play: $url")
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(label)
                    .setArtist("Moto Voice")
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
        session.player.apply {
            setMediaItem(item)
            prepare()
            playWhenReady = true
            play()
        }
    }

    private fun stopPlayback() {
        Log.d(TAG, "stopPlayback")
        mediaSession?.player?.apply { stop(); clearMediaItems() }
        currentUrl = null
        retryCount = 0
        FmPlaybackState.setPlaying(false)
        FmPlaybackState.clearAssistantPaused()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    // ─── Retry & error handling ─────────────────────────────────────────────

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback error ${error.errorCodeName} (${error.errorCode}): ${error.message}", error)
            val url = currentUrl
            if (url != null && retryCount < MAX_RETRIES) {
                retryCount++
                Log.d(TAG, "retry $retryCount/$MAX_RETRIES for $url")
                mediaSession?.player?.apply {
                    setMediaItem(MediaItem.fromUri(url))
                    prepare()
                    playWhenReady = true
                    play()
                }
            } else {
                // Spec §6.5: after MAX_RETRIES the rider deserves a clear spoken reason
                // instead of the FM just going silent. Spin up a short-lived ThaiTTS
                // just for this line and stop the service once it finishes speaking.
                Log.w(TAG, "retry budget exhausted — announcing and stopping")
                speakFatalErrorThenStop()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> { Log.d(TAG, "STATE_READY"); retryCount = 0 }
                Player.STATE_BUFFERING -> Log.d(TAG, "STATE_BUFFERING")
                Player.STATE_ENDED -> { Log.d(TAG, "STATE_ENDED"); stopPlayback() }
                Player.STATE_IDLE -> Log.d(TAG, "STATE_IDLE")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "isPlaying=$isPlaying")
            FmPlaybackState.setPlaying(isPlaying)
        }
    }
}
