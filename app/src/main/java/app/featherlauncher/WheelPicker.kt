package app.featherlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.Scroller
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One scrolling column of choices (hours, minutes), read off the middle row. Flick or drag it
 * with a finger, or move it with the keypad's up and down keys when it has focus.
 * [cyclic] columns wrap around (59 is followed by 00).
 */
@SuppressLint("ViewConstructor")
class WheelPicker(
    context: Context,
    private val items: List<String>,
    initial: Int,
    private val cyclic: Boolean,
    private val color: Int,
    private val focusColor: Int,
) : View(context), OwnsDrag {
    private val rowHeight = Ui.dp(context, ROW_HEIGHT).toFloat()
    /** Scroll position in pixels; row i is in the middle when this is i * rowHeight. */
    private var offset = initial * rowHeight
    private val scroller = Scroller(context)
    private var velocity: VelocityTracker? = null
    private var lastY = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = Ui.dp(context, 22).toFloat()
        typeface = Ui.medium
    }

    var onChange: ((Int) -> Unit)? = null

    val value: Int get() = wrap((offset / rowHeight).roundToInt())

    init {
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(Ui.dp(context, 64), widthMeasureSpec), (rowHeight * VISIBLE).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val center = height / 2f
        val first = floor(offset / rowHeight).toInt() - VISIBLE / 2 - 1
        for (i in first..first + VISIBLE + 2) {
            if (!cyclic && i !in items.indices) continue
            val y = center + i * rowHeight - offset
            val distance = abs(y - center) / rowHeight
            if (distance > VISIBLE / 2f + 0.5f) continue
            // Rows fade and shrink away from the middle, like a drum turning.
            val mid = distance < 0.5f
            paint.color = if (mid && isFocused) focusColor else color
            paint.alpha = (255 * (1f - distance / (VISIBLE / 2f + 0.6f))).roundToInt().coerceIn(0, 255)
            val scale = 1f - 0.12f * distance.coerceAtMost(2f)
            canvas.save()
            canvas.scale(scale, scale, width / 2f, y)
            canvas.drawText(items[wrap(i)], width / 2f, y - (paint.descent() + paint.ascent()) / 2, paint)
            canvas.restore()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                parent.requestDisallowInterceptTouchEvent(true)
                velocity = VelocityTracker.obtain()
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                offset = clamp(offset + lastY - event.y)
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                velocity?.recycle()
                velocity = null
                if (abs(vy) > 300) {
                    scroller.fling(0, offset.toInt(), 0, -vy.toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
                    // Land exactly on a row.
                    val target = clamp((scroller.finalY / rowHeight).roundToInt() * rowHeight)
                    scroller.finalY = target.toInt()
                } else {
                    snap()
                }
                postInvalidateOnAnimation()
            }
        }
        velocity?.addMovement(event)
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offset = scroller.currY.toFloat()
            postInvalidateOnAnimation()
            if (scroller.isFinished) onChange?.invoke(value)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> return super.onKeyDown(keyCode, event)
        }
        val target = clamp(((offset / rowHeight).roundToInt() + step) * rowHeight)
        scroller.startScroll(0, offset.toInt(), 0, (target - offset).toInt(), 150)
        postInvalidateOnAnimation()
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    private fun snap() {
        val target = clamp((offset / rowHeight).roundToInt() * rowHeight)
        scroller.startScroll(0, offset.toInt(), 0, (target - offset).toInt(), 180)
    }

    private fun clamp(value: Float) = if (cyclic) value else value.coerceIn(0f, (items.size - 1) * rowHeight)

    private fun wrap(i: Int) = ((i % items.size) + items.size) % items.size

    companion object {
        const val ROW_HEIGHT = 44
        /** Rows shown at once; odd, so one sits in the middle. */
        const val VISIBLE = 5
    }
}
