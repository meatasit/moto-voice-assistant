package com.moto.voice.service

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.widget.FrameLayout
import com.moto.voice.pipeline.VoiceCommandPipeline

class MotoVoiceSession(context: Context) : VoiceInteractionSession(context) {

    private var pipeline: VoiceCommandPipeline? = null

    override fun onCreateContentView(): View {
        // Headless session — invisible placeholder; all interaction is audio-only.
        return FrameLayout(context).apply {
            visibility = View.INVISIBLE
            layoutParams = FrameLayout.LayoutParams(1, 1)
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        hide()  // Dismiss the window overlay; the session itself keeps running.

        pipeline = VoiceCommandPipeline(context, onFinished = { finish() })
        pipeline?.start()
    }

    override fun onDestroy() {
        pipeline?.stop()
        super.onDestroy()
    }
}
