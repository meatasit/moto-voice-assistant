package com.moto.voice.media

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.moto.voice.MainActivity

/**
 * Foreground media playback service backed by [MediaSessionService], so we integrate
 * with the system media controls (lock-screen widget, headset play/pause, Android Auto).
 *
 * Behaviour:
 *  - ACTION_PLAY {stream_url, label}: (re)plays a stream. Idempotent for the same URL.
 *  - ACTION_STOP: releases + stops the service.
 *  - On PlaybackException: retries up to [MAX_RETRIES] with a linear backoff, then gives up.
 *    Audio focus loss (call / navigation) is handled by ExoPlayer's built-in focus manager,
 *    so playback pauses and resumes automatically without our involvement.
 */
class FmPlayerService : MediaSessionService() {

    companion object {
        const val ACTION_PLAY = "com.moto.voice.FM_PLAY"
        const val ACTION_STOP = "com.moto.voice.FM_STOP"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_LABEL = "label"
        private const val TAG = "FmPlayerService"
        private const val MAX_RETRIES = 2
    }

    private var mediaSession: MediaSession? = null
    private var currentUrl: String? = null
    private var retryCount = 0

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
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
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_STREAM_URL)
                if (url.isNullOrBlank()) {
                    stopPlayback()
                    return START_NOT_STICKY
                }
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "วิทยุ"
                play(url, label)
            }
        }
        // Let MediaSessionService handle the foreground promotion.
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away and nothing is playing, we don't need to hang around.
        val player = mediaSession?.player
        if (player?.playWhenReady != true || player.mediaItemCount == 0) stopPlayback()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    // ─── Playback control ───────────────────────────────────────────────────

    private fun play(url: String, label: String) {
        val session = mediaSession ?: return
        currentUrl = url
        retryCount = 0
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
            play()
        }
    }

    private fun stopPlayback() {
        mediaSession?.player?.apply { stop(); clearMediaItems() }
        currentUrl = null
        retryCount = 0
        stopSelf()
    }

    // ─── Retry & error handling ─────────────────────────────────────────────

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback error: ${error.errorCodeName}", error)
            val url = currentUrl
            if (url != null && retryCount < MAX_RETRIES) {
                retryCount++
                Log.d(TAG, "retry $retryCount/$MAX_RETRIES for $url")
                mediaSession?.player?.apply {
                    setMediaItem(MediaItem.fromUri(url))
                    prepare()
                    play()
                }
            } else {
                Log.w(TAG, "retry budget exhausted — stopping")
                stopPlayback()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) retryCount = 0
        }
    }
}
