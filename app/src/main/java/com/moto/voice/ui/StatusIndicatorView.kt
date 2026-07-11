package com.moto.voice.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.moto.voice.pipeline.PipelineState
import kotlin.math.min

/**
 * Big central circle used by [com.moto.voice.RidingModeActivity] to communicate
 * pipeline state visually — the counterpart to the earcon audio language.
 *
 * Spec v1.3.9 §4:
 *   * [PipelineState.State.Listening] → green, breathing pulse (radius modulated ±10%).
 *   * [PipelineState.State.Thinking]  → yellow, static (no animation, saves battery).
 *   * [PipelineState.State.Idle]      → gray, static.
 *
 * Animation only runs while the view is attached AND onStart..onStop, so the
 * activity can pause it in onStop to save battery when the phone screen is off
 * or another activity is on top.
 *
 * Custom-drawn (canvas, no external lib) so it stays lightweight and matches
 * whatever background color the layout picks — the circle self-scales to the
 * shorter dimension of the available bounds.
 */
class StatusIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private var currentState: PipelineState.State = PipelineState.State.Idle
    private var pulsePhase: Float = 0f  // 0..1
    private var pulseAnimator: ValueAnimator? = null

    /** Called by the activity when [PipelineState] emits — swaps color and starts/stops the pulse. */
    fun setState(state: PipelineState.State) {
        if (currentState == state) return
        currentState = state
        when (state) {
            PipelineState.State.Listening -> startPulsing()
            PipelineState.State.Thinking, PipelineState.State.Idle -> stopPulsing()
        }
        invalidate()
    }

    private fun startPulsing() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulsePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulsing() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulsePhase = 0f
    }

    override fun onDetachedFromWindow() {
        stopPulsing()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f - 8f
        val (fill, ring) = colorsFor(currentState)

        // Breathing modulation: ±10% radius around the base circle for Listening;
        // static for Thinking and Idle.
        val modulated = if (currentState == PipelineState.State.Listening) {
            val breathe = 0.90f + 0.10f * (0.5f - 0.5f * kotlin.math.cos(pulsePhase * 2f * Math.PI.toFloat()))
            maxR * breathe
        } else {
            maxR * 0.90f
        }

        paint.color = fill
        canvas.drawCircle(cx, cy, modulated, paint)

        ringPaint.color = ring
        canvas.drawCircle(cx, cy, maxR, ringPaint)
    }

    private fun colorsFor(state: PipelineState.State): Pair<Int, Int> = when (state) {
        PipelineState.State.Listening -> Color.parseColor("#22CC66") to Color.parseColor("#88EEBB")
        PipelineState.State.Thinking -> Color.parseColor("#FFCC33") to Color.parseColor("#FFDD77")
        PipelineState.State.Idle -> Color.parseColor("#666666") to Color.parseColor("#AAAAAA")
    }

    private companion object {
        const val PULSE_PERIOD_MS = 1_200L
    }
}
