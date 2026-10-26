package app.featherlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Something placed on the home screen or in the dock. Positions and sizes are in grid cells. */
class HomeItem(
    val id: Long,
    var page: Int,
    var x: Int,
    var y: Int,
    var w: Int,
    var h: Int,
    val type: Type,
    /** App component key, app widget id, or empty for built-ins. */
    val ref: String,
    /** A name the user gave it, shown instead of the app's own. */
    var label: String? = null,
    /** For folders: the app keys inside, in order. */
    val apps: MutableList<String> = mutableListOf(),
) {
    enum class Type { APP, WIDGET, CLOCK, FOLDER }

    val inDock get() = page == Workspace.DOCK
    val isWidget get() = type == Type.WIDGET || type == Type.CLOCK
}

/** The home screen layout: pages of cells plus the dock row, saved as JSON. */
class Workspace(context: Context) {
    private val file = File(context.filesDir, "workspace.json")

    val items = mutableListOf<HomeItem>()
    var pageCount = 1
        private set
    /** True when no layout was saved yet, so the caller should set up defaults. */
    var isNew = false
        private set

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) {
            isNew = true
            return
        }
        runCatching {
            val json = JSONObject(file.readText())
            pageCount = json.optInt("pages", 1).coerceAtLeast(1)
            val array = json.getJSONArray("items")
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                items += HomeItem(
                    o.getLong("id"), o.getInt("page"), o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"),
                    HomeItem.Type.valueOf(o.getString("type")), o.optString("ref"),
                    o.optString("label").ifEmpty { null },
                    o.optJSONArray("apps")?.let { a -> MutableList(a.length()) { a.getString(it) } } ?: mutableListOf(),
                )
            }
        }.onFailure { isNew = true }
    }

    fun save() {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().apply {
                put("id", it.id); put("page", it.page); put("x", it.x); put("y", it.y)
                put("w", it.w); put("h", it.h); put("type", it.type.name); put("ref", it.ref)
                it.label?.let { label -> put("label", label) }
                if (it.apps.isNotEmpty()) put("apps", JSONArray(it.apps))
            })
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("pages", pageCount).put("items", array).toString())
        tmp.renameTo(file)
        isNew = false
    }

    fun nextId(): Long = (items.maxOfOrNull { it.id } ?: 0L) + 1

    fun add(page: Int, x: Int, y: Int, w: Int, h: Int, type: HomeItem.Type, ref: String): HomeItem =
        HomeItem(nextId(), page, x, y, w, h, type, ref).also { items += it }

    fun on(page: Int) = items.filter { it.page == page }

    fun cols(page: Int) = if (page == DOCK) DOCK_COLS else COLS
    fun rows(page: Int) = if (page == DOCK) 1 else ROWS

    fun isFree(page: Int, x: Int, y: Int, w: Int, h: Int, ignore: HomeItem? = null): Boolean {
        if (x < 0 || y < 0 || x + w > cols(page) || y + h > rows(page)) return false
        return on(page).none { it !== ignore && x < it.x + it.w && it.x < x + w && y < it.y + it.h && it.y < y + h }
    }

    /** First free spot, reading order. */
    fun findSpot(page: Int, w: Int, h: Int): Pair<Int, Int>? {
        for (y in 0..rows(page) - h) for (x in 0..cols(page) - w) if (isFree(page, x, y, w, h)) return x to y
        return null
    }

    /** The free spot closest to where the item is being held. */
    fun nearestSpot(page: Int, x: Int, y: Int, w: Int, h: Int, ignore: HomeItem?): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestDistance = Int.MAX_VALUE
        for (cy in 0..rows(page) - h) for (cx in 0..cols(page) - w) {
            if (!isFree(page, cx, cy, w, h, ignore)) continue
            val d = abs(cx - x) + abs(cy - y)
            if (d < bestDistance) {
                bestDistance = d
                best = cx to cy
            }
        }
        return best
    }

    fun addPage(): Int = pageCount++

    /** Drops empty pages after the first, moving later pages back. Returns true if any were removed. */
    fun removeEmptyPages(): Boolean {
        var removed = false
        var page = pageCount - 1
        while (page > 0) {
            if (on(page).isEmpty()) {
                items.filter { it.page > page }.forEach { it.page-- }
                pageCount--
                removed = true
            }
            page--
        }
        return removed
    }

    companion object {
        const val DOCK = -1
        const val COLS = 4
        /**
         * Rows on each page. Five on short screens (keypad phones); taller phones get another row
         * for each 120dp or so of height, so icons aren't spaced far apart. Set once at startup.
         */
        var ROWS = 5
            private set

        fun fitRows(context: Context) {
            val m = context.resources.displayMetrics
            // The page's share of the screen: everything but the status bar, dock, page dots and gesture bar.
            val pageDp = m.heightPixels / m.density - 24 - 92 - 20 - 48
            ROWS = (pageDp / 120f).toInt().coerceIn(5, 7)
        }
        const val DOCK_COLS = 4
    }
}
