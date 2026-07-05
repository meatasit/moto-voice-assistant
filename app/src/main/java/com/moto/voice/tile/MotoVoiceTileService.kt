package com.moto.voice.tile

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.moto.voice.VoiceAssistActivity

/**
 * Quick Settings tile that starts the voice pipeline without opening the app.
 * Tapping it launches [VoiceAssistActivity] which immediately hands off to
 * VoiceCommandService and finishes — same code path as long-pressing Home.
 *
 * On Android 14+ [startActivityAndCollapse] requires a PendingIntent; on older
 * versions the deprecated Intent overload is fine.
 */
@RequiresApi(Build.VERSION_CODES.N)
class MotoVoiceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Moto Voice"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, VoiceAssistActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = android.app.PendingIntent.getActivity(
                this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
