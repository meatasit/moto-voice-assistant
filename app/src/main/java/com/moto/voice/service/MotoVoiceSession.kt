package com.moto.voice.service

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.widget.FrameLayout
import com.moto.voice.data.AppSettings
import com.moto.voice.pipeline.VoiceCommandPipeline

class MotoVoiceSession(context: Context) : VoiceInteractionSession(context) {

    private var pipeline: VoiceCommandPipeline? = null

    override fun onCreateContentView(): View = FrameLayout(context).apply {
        visibility = View.INVISIBLE
        layoutParams = FrameLayout.LayoutParams(1, 1)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        hide()
        pipeline = VoiceCommandPipeline(context, AppSettings(context), onFinished = { finish() })
        pipeline?.start()
    }

    override fun onDestroy() {
        pipeline?.stop()
        super.onDestroy()
    }
}
