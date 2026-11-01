package app.featherlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * Draws each of Feather's widgets at every size it can be made, from its smallest to a whole
 * page, into a picture per widget (and per style, for those with Solid and Transparent),
 * in the app's files folder under widgets/. For checking every size from a computer:
 *
 *     adb shell am broadcast -n app.featherlauncher/.SetupImport --es widgetsheet 1
 *     adb pull /sdcard/Android/data/app.featherlauncher/files/widgets
 */
object WidgetSheet {
    private class Sample(val name: String, val kind: FeatherKind, val solid: Boolean)

    fun draw(context: Context, done: (String) -> Unit) {
        val (cellW, cellH) = CellLayout.pageCell ?: return done("Open the home screen first")
        val samples = FeatherKind.entries.flatMap { kind ->
            if (kind == FeatherKind.CLOCK || kind == FeatherKind.UP_NEXT)
                listOf(Sample("${kind.name.lowercase()}-solid", kind, true), Sample("${kind.name.lowercase()}-transparent", kind, false))
            else listOf(Sample(kind.name.lowercase(), kind, true))
        }
        var id = -90_000L
        val views = samples.associateWith { s ->
            // Only the sizes it can be made.
            (s.kind.minH..Workspace.ROWS).flatMap { h -> (s.kind.minW..Workspace.COLS).map { w -> w to h } }.map { (w, h) ->
                val item = HomeItem(id--, 0, 0, 0, w, h, HomeItem.Type.CLOCK, s.kind.ref)
                val view = CardWidget.create(context, item) ?: ClockWidget(context, item)
                when (view) {
                    is ClockWidget -> view.setSolid(s.solid)
                    is UpNextWidget -> view.solid = s.solid
                }
                lay(view, (w * cellW).toInt(), (h * cellH).toInt())
                (w to h) to view
            }
        }
        // Laid out once, they settle what fits (some work it out after their first layout).
        val main = Handler(Looper.getMainLooper())
        main.postDelayed({
            val folder = java.io.File(context.getExternalFilesDir(null), "widgets").apply { mkdirs() }
            views.forEach { (s, list) ->
                list.forEach { (_, v) ->
                    (v as? CardWidget)?.refresh()
                    (v as? ClockWidget)?.refresh()
                    lay(v, v.width, v.height)
                }
            }
            main.postDelayed({
                views.forEach { (s, list) -> save(java.io.File(folder, "${s.name}.png"), list, cellW, cellH) }
                done(folder.path)
            }, 500)
        }, 1500)
    }

    private fun lay(view: View, w: Int, h: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    /** One row per height, the widths side by side, each on a slice of sky like a wallpaper, with its size above it. */
    private fun save(file: java.io.File, list: List<Pair<Pair<Int, Int>, View>>, cellW: Float, cellH: Float) {
        val gap = 24
        val label = 28
        val rows = list.groupBy { it.first.second }
        val width = (1..Workspace.COLS).sumOf { (it * cellW).toInt() + gap } + gap
        val height = rows.keys.sumOf { (it * cellH).toInt() + label + gap } + gap
        val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFF20242C.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; textSize = 20f }
        val sky = Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(), 0xFF8FB4E3.toInt(), 0xFF3F6FB5.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        var y = gap
        rows.toSortedMap().forEach { (h, row) ->
            var x = gap
            row.sortedBy { it.first.first }.forEach { (size, v) ->
                canvas.drawText("${size.first} × ${size.second}", x.toFloat(), (y + 20).toFloat(), text)
                val top = y + label
                canvas.drawRect(x.toFloat(), top.toFloat(), (x + v.width).toFloat(), (top + v.height).toFloat(), sky)
                canvas.save()
                canvas.translate(x.toFloat(), top.toFloat())
                v.draw(canvas)
                canvas.restore()
                x += (size.first * cellW).toInt() + gap
            }
            y += (h * cellH).toInt() + label + gap
        }
        file.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
