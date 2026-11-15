package app.featherlauncher

import android.app.Activity
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ScrollView

/**
 * The keypad's highlight, the same everywhere: a [LINE]dp outline, [GAP]dp outside the edge of what's
 * highlighted, in that thing's own shape. It's drawn once per window, over everything, so no
 * layout clips it, and every screen gets the same ring without drawing its own.
 *
 * What's highlighted is the focused view, or the part of it that's the thing itself:
 * - a view can say so itself ([Target]), as an app icon does (the icon, not its name);
 * - else a [Spot] in its foreground or background (from [Ui.focusRing] or [Ui.pressable]) gives
 *   the corners and, from its bounds, the box;
 * - else the view's own outline, from its background.
 */
object FocusRing {
    /** Room between what's highlighted and the ring (dp). */
    const val GAP = 2
    /** The ring's thickness (dp). */
    const val LINE = 2
    /** How far the ring reaches out from what's highlighted (dp): room to leave for it. */
    const val ROOM = GAP + LINE

    /** A view that says where its highlight goes: fills [out] (in its own coordinates), or returns false for none. */
    interface Target {
        fun focusOutline(out: Shape): Boolean
    }

    /** What's highlighted: a box with rounded corners, or an icon [shape] filling the box's middle. */
    class Shape {
        val rect = RectF()
        /** The box's corners (px); clamped to a pill. */
        var radius = 0f
        var shape: IconShape? = null

        fun set(r: RectF, radius: Float, shape: IconShape? = null) = apply {
            rect.set(r)
            this.radius = radius
            this.shape = shape
        }
    }

    /**
     * Marks what a view's highlight goes around: it draws nothing, but its bounds (an inset
     * drawable's inside them, say) are the box, and [radiusDp] its corners.
     */
    class Spot(private val radiusDp: () -> Int, val shape: IconShape? = null) : Drawable() {
        fun radius(density: Float) = radiusDp() * density
        // It shows focus (through FocusRing), so Android adds no grey wash of its own.
        override fun isStateful() = true
        override fun hasFocusStateSpecified() = true
        override fun onStateChange(state: IntArray) = false
        override fun draw(canvas: Canvas) {}
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSPARENT
    }

    /** Never highlighted, for places the highlight rests unseen. */
    fun none(view: View) = view.setTag(R.id.focus_ring, NONE)

    /** [view]'s highlight goes around [other] instead (a text box inside a bar, say). */
    fun outlineOf(view: View, other: View) = view.setTag(R.id.focus_ring, other)

    private val NONE = Any()

    /** Draws the highlight in [activity]'s window. */
    fun install(activity: Activity) {
        val root = activity.window.decorView
        if (root.getTag(R.id.focus_ring) != null) return
        val ring = Ring(root)
        root.setTag(R.id.focus_ring, ring)
        ring.setBounds(0, 0, root.width, root.height)
        root.overlay.add(ring)
        val tree = root.viewTreeObserver
        tree.addOnPreDrawListener(ring)
        tree.addOnGlobalFocusChangeListener { _, _ -> ring.update() }
        tree.addOnTouchModeChangeListener { ring.update() }
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> ring.setBounds(0, 0, r - l, b - t); ring.update() }
    }

    private class Ring(private val root: View) : Drawable(), ViewTreeObserver.OnPreDrawListener {
        private val density = root.resources.displayMetrics.density
        private val stroke = LINE * density
        /** From the thing's edge to the middle of the line: the gap and half the line. */
        private val out = GAP * density + stroke / 2
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        private val path = Path()
        private val shape = Shape()
        private val matrix = Matrix()

        /** Where the ring is: what's drawn, so it's only redrawn when it moves. */
        private data class Place(val box: RectF, val corner: Float, val icon: IconShape?, val alpha: Float, val clip: RectF?)
        private var place: Place? = null

        override fun onPreDraw(): Boolean {
            update()
            return true
        }

        fun update() {
            val now = build()
            if (now == place) return
            place = now
            path.reset()
            if (now != null) {
                val r = now.box
                if (now.icon != null) {
                    val size = minOf(r.width(), r.height())
                    path.set(now.icon.path(size))
                    path.offset(r.centerX() - size / 2, r.centerY() - size / 2)
                } else path.addRoundRect(r, now.corner, now.corner, Path.Direction.CW)
            }
            invalidateSelf()
        }

        /** Where the ring goes now, or null for nowhere. */
        private fun build(): Place? {
            if (root.isInTouchMode) return null
            val focused = root.findFocus() ?: return null
            var view: View = focused
            // A list's selected row is what's highlighted, not the list.
            if (view is AbsListView) view = view.selectedView ?: return null
            val tag = view.getTag(R.id.focus_ring)
            if (tag === NONE) return null
            if (tag is View) view = tag
            if (!view.isShown || view.width == 0 || view.height == 0) return null
            // This ring is the highlight; no grey wash from Android over it.
            if (view.defaultFocusHighlightEnabled) view.defaultFocusHighlightEnabled = false
            if (focused !== view && focused.defaultFocusHighlightEnabled) focused.defaultFocusHighlightEnabled = false
            val (v, s) = outlineOf(view) ?: return null
            val r = RectF(s.rect)
            if (r.isEmpty || !toRoot(v, matrix)) return null
            val alpha = alphaOf(v)
            if (alpha <= 0f) return null
            val w = r.width()
            matrix.mapRect(r)
            if (covered(v, r)) return null
            val scale = if (w > 0) r.width() / w else 1f
            val corner = minOf(s.radius * scale, minOf(r.width(), r.height()) / 2) + out
            r.inset(-out, -out)
            return Place(r, corner, s.shape, alpha, clipOf(v))
        }

        /** The view and box that are highlighted when [view] has the keypad. */
        private fun outlineOf(view: View): Pair<View, Shape>? {
            if (view is Target) return if (view.focusOutline(shape.set(RectF(), 0f))) view to shape else null
            spotIn(view)?.let { return view to it }
            // A list row whose own highlight is on what's inside it (the widget list's cards).
            if (view is ViewGroup && view.parent is AbsListView) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    if (child is Target) return outlineOf(child)
                    spotIn(child)?.let { return child to it }
                }
            }
            val outline = Outline()
            view.outlineProvider?.getOutline(view, outline)
            val rect = Rect()
            return if (!outline.isEmpty && outline.getRect(rect)) view to shape.set(RectF(rect), outline.radius)
            else view to shape.set(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), 0f)
        }

        private fun spotIn(view: View): Shape? {
            val spot = find(view.foreground) ?: find(view.background) ?: return null
            val b = spot.bounds
            val rect = if (b.isEmpty) RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()) else RectF(b)
            return shape.set(rect, spot.radius(density), spot.shape)
        }

        private fun find(d: Drawable?): Spot? = when (d) {
            is Spot -> d
            is LayerDrawable -> (0 until d.numberOfLayers).firstNotNullOfOrNull { find(d.getDrawable(it)) }
            is DrawableWrapper -> find(d.drawable)
            is DrawableContainer -> find(d.current)
            else -> null
        }

        /** Maps [view]'s coordinates to the window's, through every move, scale and scroll on the way. */
        private fun toRoot(view: View, m: Matrix): Boolean {
            m.reset()
            var v = view
            while (v !== root) {
                if (!v.matrix.isIdentity) m.postConcat(v.matrix)
                m.postTranslate(v.left.toFloat(), v.top.toFloat())
                val parent = v.parent as? View ?: return false
                m.postTranslate(-parent.scrollX.toFloat(), -parent.scrollY.toFloat())
                v = parent
            }
            return true
        }

        /**
         * Whether something drawn over [view] (a sheet's backdrop, say) covers all of [box]: then
         * the view can't be seen, and neither can its ring. Only things that take taps count, so
         * outlines and pictures laid over the page don't.
         */
        private fun covered(view: View, box: RectF): Boolean {
            var child = view
            var parent = view.parent as? ViewGroup
            val m = Matrix()
            while (parent != null) {
                for (i in parent.indexOfChild(child) + 1 until parent.childCount) {
                    val over = parent.getChildAt(i)
                    if (over.visibility != View.VISIBLE || over.alpha <= 0f || !over.isClickable || over.width == 0) continue
                    if (!toRoot(over, m)) continue
                    val r = RectF(0f, 0f, over.width.toFloat(), over.height.toFloat())
                    m.mapRect(r)
                    if (r.contains(box)) return true
                }
                if (parent === root) break
                child = parent
                parent = parent.parent as? ViewGroup
            }
            return false
        }

        /** How see-through [view] is, with everything it's in. */
        private fun alphaOf(view: View): Float {
            var a = 1f
            var v: View? = view
            while (v != null && v !== root) {
                a *= v.alpha
                v = v.parent as? View
            }
            return a
        }

        /** The scrolling list [view] is in, in the window's coordinates, with room for the ring at its edges; null if none. */
        private fun clipOf(view: View): RectF? {
            var v = view.parent as? View
            while (v != null && v !== root) {
                if (v is ScrollView || v is HorizontalScrollView || v is AbsListView) {
                    val m = Matrix()
                    if (!toRoot(v, m)) return null
                    val box = RectF(0f, 0f, v.width.toFloat(), v.height.toFloat())
                    m.mapRect(box)
                    box.inset(-out - stroke, -out - stroke)
                    return box
                }
                v = v.parent as? View
            }
            return null
        }

        override fun draw(canvas: Canvas) {
            val at = place ?: return
            paint.color = (Ui.ringColor(root.context) and 0xFFFFFF) or ((0xD9 * at.alpha).toInt() shl 24)
            canvas.save()
            at.clip?.let { canvas.clipRect(it) }
            canvas.drawPath(path, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
