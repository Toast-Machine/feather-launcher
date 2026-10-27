package app.featherlauncher

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Outline
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import kotlin.math.abs

/**
 * Hosts another app's widget, with Android's rounded widget corners, and adds long-press (to
 * move and resize it) on top of its own touch handling, wherever it's held, buttons and lists too.
 */
class WidgetView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false

    private val longPress = Runnable {
        longPressed = true
        // The widget's buttons and lists stop where they are: no click when the finger lifts.
        val now = android.os.SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0).let {
            super.dispatchTouchEvent(it)
            it.recycle()
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    init {
        // Square widgets get the same corners as Android's own (as they have on the Pixel launcher).
        val radius = resources.getDimension(android.R.dimen.system_app_widget_background_radius)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Tell the widget its real size so it can pick a layout that fits.
        if (w > 0 && h > 0) {
            val density = resources.displayMetrics.density
            updateAppWidgetSize(android.os.Bundle(), listOf(android.util.SizeF(w / density, h / density)))
        }
    }

    // Every touch is watched here, before the widget's own views: a list or button inside can
    // keep touches from the usual interception, and the hold would go unnoticed.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        // After a long press, the rest of the gesture is ours (the drag), not the widget's.
        if (longPressed && ev.actionMasked != MotionEvent.ACTION_DOWN) return true
        return super.dispatchTouchEvent(ev) || true
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                longPressed = false
                removeCallbacks(longPress)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
    }
}
