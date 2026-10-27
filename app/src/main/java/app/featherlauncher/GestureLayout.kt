package app.featherlauncher

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * A LinearLayout that turns drags into gestures: vertical swipes ([onPull] to follow the
 * finger, or [onSwipeUp]/[onSwipeDown]), sideways swipes, and a long press on empty space.
 *
 * It watches every touch in [dispatchTouchEvent], so it can take a drag over even after a
 * child (a scrolling list) has started handling it and asked parents not to intercept.
 */
class GestureLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null
    /**
     * Follows a vertical drag as it happens: [onPull] gets the distance so far (positive is
     * down), [onPullEnd] the final distance and whether it was flicked that way.
     * Replaces [onSwipeUp]/[onSwipeDown] when set.
     */
    var onPull: ((dy: Float) -> Unit)? = null
    var onPullEnd: ((dy: Float, flung: Boolean) -> Unit)? = null

    /** Sideways swipes; only taken from children when set. */
    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    /** Follows a sideways drag as it happens (positive is right); replaces left/right swipes when set. */
    var onSlide: ((dx: Float) -> Unit)? = null
    var onSlideEnd: ((dx: Float, flung: Boolean) -> Unit)? = null
    /** Called with the press position in this view's coordinates. */
    var onLongPress: ((x: Float, y: Float) -> Unit)? = null

    /** Called when a finger goes down, before anything else sees the touch. */
    var onDown: (() -> Unit)? = null

    /** Whether a drag in this direction (dy < 0 is up) may be taken from the children. */
    var canDrag: (dy: Float) -> Boolean = { true }
    /** False while something else owns the gesture (an icon being dragged). */
    var canSteal: () -> Boolean = { true }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 3f
    private val swipeDistance = 48 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    /** Where the drag was taken over; the pull is measured from here so it doesn't jump. */
    private var originY = 0f
    private var originX = 0f
    private var dragging = false
    private var sideways = false
    private var velocity: VelocityTracker? = null
    /** The touch started on a child that handles drags itself (a picker wheel). */
    private var ownedByChild = false

    private val longPress = Runnable {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke(downX, downY)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start(ev)
                ownedByChild = ownsDrag(this, ev.x, ev.y)
                onDown?.invoke()
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && !ownedByChild && canSteal()) {
                val vertical = isVerticalDrag(ev)
                if (vertical || isSidewaysDrag(ev)) {
                    dragging = true
                    sideways = !vertical
                    originY = ev.y
                    originX = ev.x
                    removeCallbacks(longPress)
                    // Take the gesture away from whichever child had it.
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
            }
        }
        if (dragging) {
            velocity?.addMovement(ev)
            handleDrag(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Touches that reached this view itself: only empty space, so this is where long press lives. */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                if (onLongPress != null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    private fun handleDrag(ev: MotionEvent) {
        val pull = onPull
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                if (sideways) onSlide?.invoke(ev.x - originX) else if (pull != null) pull(ev.y - originY)
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                val slideEnd = onSlideEnd
                if (sideways && slideEnd != null) {
                    val vx = velocity?.xVelocity ?: 0f
                    val dx = ev.x - originX
                    slideEnd(dx, if (dx > 0) vx > minFling else vx < -minFling)
                } else if (sideways) {
                    val dx = ev.x - downX
                    if (dx < -swipeDistance) onSwipeLeft?.invoke()
                    else if (dx > swipeDistance) onSwipeRight?.invoke()
                } else if (pull != null) {
                    val dy = ev.y - originY
                    onPullEnd?.invoke(dy, if (dy > 0) vy > minFling else vy < -minFling)
                } else {
                    val dy = ev.y - downY
                    if (dy < -swipeDistance || vy < -minFling) onSwipeUp?.invoke()
                    else if (dy > swipeDistance || vy > minFling) onSwipeDown?.invoke()
                }
                finishDrag()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (sideways) onSlideEnd?.invoke(0f, false)
                if (!sideways && pull != null) onPullEnd?.invoke(0f, false)
                finishDrag()
            }
        }
    }

    private fun start(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        dragging = false
        sideways = false
        velocity?.recycle()
        velocity = VelocityTracker.obtain().apply { addMovement(ev) }
    }

    private fun finishDrag() {
        dragging = false
        sideways = false
        velocity?.recycle()
        velocity = null
    }

    private fun ownsDrag(group: android.view.ViewGroup, x: Float, y: Float): Boolean {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != VISIBLE) continue
            val cx = x - child.left + group.scrollX
            val cy = y - child.top + group.scrollY
            if (cx < 0 || cy < 0 || cx >= child.width || cy >= child.height) continue
            if (child is OwnsDrag) return true
            if (child is android.view.ViewGroup && ownsDrag(child, cx, cy)) return true
        }
        return false
    }

    private fun isSidewaysDrag(ev: MotionEvent): Boolean {
        if (onSwipeLeft == null && onSwipeRight == null && onSlide == null) return false
        val dx = ev.x - downX
        return abs(dx) > slop && abs(dx) > abs(ev.y - downY) * 1.5f
    }

    private fun isVerticalDrag(ev: MotionEvent): Boolean {
        if (onPull == null && onSwipeUp == null && onSwipeDown == null) return false
        val dy = ev.y - downY
        return abs(dy) > slop && abs(dy) > abs(ev.x - downX) && canDrag(dy) && !scrollsAt(this, downX, downY, dy)
    }

    /**
     * Whether something under ([x], [y]) can still scroll the way the finger is going (a widget's
     * list, say): then the drag is its own, until it's scrolled to the end.
     */
    private fun scrollsAt(group: android.view.ViewGroup, x: Float, y: Float, dy: Float): Boolean {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != VISIBLE) continue
            val cx = x - child.left + group.scrollX - child.translationX
            val cy = y - child.top + group.scrollY - child.translationY
            if (cx < 0 || cy < 0 || cx >= child.width || cy >= child.height) continue
            // Dragging up shows what's further down.
            if (child.canScrollVertically(if (dy < 0) 1 else -1)) return true
            if (child is android.view.ViewGroup && scrollsAt(child, cx, cy, dy)) return true
        }
        return false
    }
}

/** A view that handles every drag that starts on it, so [GestureLayout] never takes one over. */
interface OwnsDrag
