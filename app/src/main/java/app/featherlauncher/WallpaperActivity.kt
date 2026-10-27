package app.featherlauncher

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Choosing a wallpaper without leaving the launcher: a photo from the phone, one of the
 * launcher's colors, or a photo from Unsplash photographers (through Picsum, which needs no
 * account). Whatever is picked opens full screen under a mock clock and dock, can be dragged
 * into place, and is set for the home screen, the lock screen or both.
 */
class WallpaperActivity : Activity() {
    private lateinit var palette: Palette
    private lateinit var root: FrameLayout
    private lateinit var overlays: Overlays
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    /** Thumbnails already loaded, up to 16 MB of them. */
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var insets = Rect()

    /** Photos loaded so far, and whether more are being fetched. */
    private val photos = ArrayList<Photo>()
    /** Picsum lists its photos in a fixed order; starting somewhere random shows a fresh mix each time. */
    private var page = (2..30).random()
    private var loading = false
    private var loadFailed = false
    private lateinit var adapter: Adapter
    /** The preview on top of the page, if one is open. */
    private var preview: Preview? = null
    /** Which half of the light and dark pair is being chosen (null: setting a wallpaper normally). */
    private var pairTarget: Boolean? = null

    class Photo(val id: String, val author: String, val width: Int, val height: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        val prefs = Prefs(this)
        palette = Palette.from(this, prefs.colorStyle, prefs.themeMode, prefs.accentColor)
        root = FrameLayout(this).apply { setBackgroundColor(palette.surface) }
        overlays = Overlays(root) { palette }
        setContentView(root)
        window.insetsController?.setSystemBarsAppearance(
            if (palette.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
        root.setOnApplyWindowInsetsListener { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsets.Type.systemBars())
            val next = Rect(bars.left, bars.top, bars.right, bars.bottom)
            if (next != insets) {
                insets = next
                overlays.insets = insets
                build()
            }
            windowInsets
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        build()
        loadMore()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun back() {
        when {
            overlays.dismiss() -> Unit
            preview != null -> closePreview()
            else -> finish()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // region Page

    private fun build() {
        val p = palette
        root.removeAllViews()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = header(p)
        page.addView(header)
        val tint = Ui.scrollTint(header, p.surface, p.surfaceScrolled)
        pairTarget?.let { page.addView(pairBanner(p, it)) }
        adapter = Adapter()
        page.addView(ListView(this).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(0)
            itemsCanFocus = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, insets.bottom + dp(Ui.SPACE_L))
            this.adapter = this@WallpaperActivity.adapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, state: Int) = Unit
                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                    tint(first > 0 || (view.getChildAt(0)?.top ?: 0) < 0)
                    // More photos as the end comes near.
                    if (first + visible >= total - 2) loadMore()
                }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        preview?.let { root.addView(it.view) }
    }

    /** The back arrow and the title. It reaches up behind the status bar, so its tint does too. */
    private fun header(p: Palette) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64) + insets.top
        setPadding(dp(Ui.SPACE_S), insets.top, dp(Ui.SPACE_L), 0)
        addView(ImageView(this@WallpaperActivity).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(p.text)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_PILL)
            isFocusable = true
            contentDescription = "Back"
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@WallpaperActivity).apply {
            text = "Wallpaper"
            style(TextStyle.Title, p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(Ui.SPACE_S)
        })
    }

    /** The page's rows: your photos and colors on top, then the photo grid two to a row, then a footer. */
    private inner class Adapter : BaseAdapter() {
        private val pairs get() = (photos.size + 1) / 2
        override fun getCount() = 2 + pairs + 1
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 4
        override fun getItemViewType(position: Int) = when {
            position == 0 -> 0
            position == 1 -> 1
            position < 2 + pairs -> 2
            else -> 3
        }
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (getItemViewType(position)) {
            0 -> topSection()
            1 -> convertView ?: sectionTitle("Photos", "From Unsplash photographers")
            2 -> photoRow(position - 2, convertView as? LinearLayout)
            else -> footer()
        }
    }

    /** "Your photos" and the row of colors. */
    private fun topSection(): View {
        val p = palette
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(Ui.SPACE_S), 0, 0)
            addView(Components.group(this@WallpaperActivity, p, listOf(
                Components.row(this@WallpaperActivity, p, R.drawable.ic_photo, "Your photos",
                    subtitle = "Pick a picture from the phone", chevron = true, inset = Ui.SPACE_L) { pickPhoto() },
            )))
            addView(sectionTitle("Light and dark", "Switches wallpapers when dark mode turns on or off"))
            addView(pairCard())
            addView(sectionTitle("Colors", null))
            addView(HorizontalScrollView(this@WallpaperActivity).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), 0)
                addView(LinearLayout(this@WallpaperActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    COLORS.forEach { (name, top, bottom) ->
                        addView(colorTile(name, top, bottom), LinearLayout.LayoutParams(dp(72), dp(96)).apply {
                            marginEnd = dp(Ui.SPACE_S)
                        })
                    }
                })
            })
        }
    }

    /** The two halves of the pair, side by side: each shows its picture, or asks to choose one. */
    private fun pairCard(): View {
        val p = palette
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), 0)
            listOf(false, true).forEach { dark ->
                addView(LinearLayout(this@WallpaperActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val file = DarkMode.pairFile(this@WallpaperActivity, dark)
                    addView(FrameLayout(this@WallpaperActivity).apply {
                        background = Ui.rounded(this@WallpaperActivity, p.surfaceHigh, Ui.RADIUS_M)
                        clipToOutline = true
                        foreground = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_M)
                        isFocusable = true
                        contentDescription = if (dark) "Dark mode wallpaper" else "Light mode wallpaper"
                        if (file.exists()) {
                            addView(ImageView(this@WallpaperActivity).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                io.execute {
                                    val b = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })
                                    main.post { setImageBitmap(b) }
                                }
                            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        } else {
                            addView(LinearLayout(this@WallpaperActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER
                                addView(ImageView(this@WallpaperActivity).apply {
                                    setImageResource(R.drawable.ic_add)
                                    imageTintList = ColorStateList.valueOf(p.textSecondary)
                                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                                addView(TextView(this@WallpaperActivity).apply {
                                    text = "Choose"
                                    style(TextStyle.Secondary, p.textSecondary)
                                    gravity = Gravity.CENTER
                                    setPadding(0, dp(Ui.SPACE_XS), 0, 0)
                                })
                            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        }
                        setOnClickListener {
                            pairTarget = dark
                            build()
                        }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
                    addView(LinearLayout(this@WallpaperActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(0, dp(Ui.SPACE_S), 0, 0)
                        addView(ImageView(this@WallpaperActivity).apply {
                            setImageResource(if (dark) R.drawable.ic_moon else R.drawable.ic_sunrise)
                            imageTintList = ColorStateList.valueOf(p.textSecondary)
                        }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(Ui.SPACE_XS + 2) })
                        addView(TextView(this@WallpaperActivity).apply {
                            text = if (dark) "Dark mode" else "Light mode"
                            style(TextStyle.Secondary, p.textSecondary)
                        })
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (!dark) marginEnd = dp(Ui.SPACE_S)
                })
            }
        }.let { card ->
            if (!DarkMode.hasPair(this) && !DarkMode.pairFile(this, false).exists() && !DarkMode.pairFile(this, true).exists()) return card
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(card)
                addView(TextView(this@WallpaperActivity).apply {
                    text = if (DarkMode.hasPair(this@WallpaperActivity)) "Remove wallpaper pair" else "Clear selected wallpaper"
                    style(TextStyle.Label, p.accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(Ui.SPACE_XL), dp(Ui.SPACE_M), dp(Ui.SPACE_XL), 0)
                    background = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_PILL)
                    isFocusable = true
                    setOnClickListener {
                        DarkMode.clearPair(this@WallpaperActivity)
                        adapter.notifyDataSetChanged()
                    }
                })
            }
        }
    }

    /** While choosing one half of the pair: which one, and a way to stop. */
    private fun pairBanner(p: Palette, dark: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_XS), 0)
        background = Ui.rounded(this@WallpaperActivity, p.accentSurface, Ui.RADIUS_M)
        addView(ImageView(this@WallpaperActivity).apply {
            setImageResource(if (dark) R.drawable.ic_moon else R.drawable.ic_sunrise)
            imageTintList = ColorStateList.valueOf(p.text)
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(TextView(this@WallpaperActivity).apply {
            text = "Pick the ${if (dark) "dark" else "light"} mode wallpaper"
            style(TextStyle.Label, p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Ui.SPACE_M) })
        addView(ImageView(this@WallpaperActivity).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(p.textSecondary)
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_PILL)
            isFocusable = true
            contentDescription = "Stop choosing"
            setOnClickListener {
                pairTarget = null
                build()
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), dp(Ui.SPACE_S))
        }
    }

    private fun sectionTitle(title: String, subtitle: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Ui.SPACE_XL), dp(Ui.SPACE_XL), dp(Ui.SPACE_XL), dp(Ui.SPACE_M))
        addView(TextView(this@WallpaperActivity).apply {
            text = title
            style(TextStyle.Title, palette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        })
        if (subtitle != null) addView(TextView(this@WallpaperActivity).apply {
            text = subtitle
            style(TextStyle.Secondary, palette.textSecondary)
            setPadding(0, dp(Ui.SPACE_XS), 0, 0)
        })
    }

    private fun colorTile(name: String, top: Int, bottom: Int) = View(this).apply {
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom),
        ).apply { cornerRadius = dp(Ui.RADIUS_M).toFloat() }
        foreground = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_M)
        isFocusable = true
        contentDescription = name
        setOnClickListener { openPreview(gradient(top, bottom), name, null) }
    }

    /** Two photos side by side, each a portrait tile. */
    private fun photoRow(index: Int, recycled: LinearLayout?): View {
        val row = recycled ?: LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), dp(Ui.SPACE_S))
            repeat(2) { i ->
                addView(ImageView(this@WallpaperActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = Ui.rounded(this@WallpaperActivity, palette.surfaceHigh, Ui.RADIUS_M)
                    clipToOutline = true
                    foreground = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_M)
                    isFocusable = true
                }, LinearLayout.LayoutParams(0, dp(200), 1f).apply { if (i == 0) marginEnd = dp(Ui.SPACE_S) })
            }
        }
        for (i in 0..1) {
            val tile = row.getChildAt(i) as ImageView
            val photo = photos.getOrNull(index * 2 + i)
            tile.visibility = if (photo == null) View.INVISIBLE else View.VISIBLE
            tile.setImageBitmap(null)
            tile.tag = photo?.id
            if (photo == null) continue
            tile.contentDescription = "Photo by ${photo.author}"
            val url = "https://picsum.photos/id/${photo.id}/360/480"
            thumbs.get(url)?.let(tile::setImageBitmap) ?: io.execute {
                val bitmap = download(url)
                main.post {
                    if (bitmap != null) thumbs.put(url, bitmap)
                    if (tile.tag == photo.id) tile.setImageBitmap(bitmap)
                }
            }
            tile.setOnClickListener { openPhoto(photo) }
        }
        return row
    }

    private fun footer(): View {
        val p = palette
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(Ui.SPACE_M), 0, 0)
            if (loadFailed) addView(TextView(this@WallpaperActivity).apply {
                text = "Couldn't load photos. Check the connection, then tap to try again."
                style(TextStyle.Secondary, p.textSecondary)
                gravity = Gravity.CENTER
                setPadding(dp(Ui.SPACE_XL), dp(Ui.SPACE_M), dp(Ui.SPACE_XL), dp(Ui.SPACE_M))
                background = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_M)
                isFocusable = true
                setOnClickListener {
                    loadFailed = false
                    loadMore()
                }
            }) else if (loading) addView(TextView(this@WallpaperActivity).apply {
                text = "Loading photos…"
                style(TextStyle.Secondary, p.textTertiary)
                gravity = Gravity.CENTER
                setPadding(0, dp(Ui.SPACE_M), 0, dp(Ui.SPACE_M))
            })
            addView(Components.group(this@WallpaperActivity, p, listOf(
                Components.row(this@WallpaperActivity, p, R.drawable.ic_open_external, "Other wallpaper apps",
                    subtitle = "Live wallpapers and other pickers", chevron = true, inset = Ui.SPACE_L) {
                    runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) }
                },
            )).apply { (layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(Ui.SPACE_M) })
        }
    }

    /** The next page of photos from Picsum's list. */
    private fun loadMore() {
        if (loading || loadFailed) return
        loading = true
        val pageNumber = page
        io.execute {
            val found = runCatching {
                val json = JSONArray(fetch("https://picsum.photos/v2/list?page=$pageNumber&limit=30"))
                (0 until json.length()).map { i ->
                    val o = json.getJSONObject(i)
                    Photo(o.getString("id"), o.optString("author"), o.optInt("width"), o.optInt("height"))
                }
            }.getOrNull()
            main.post {
                loading = false
                if (found == null) {
                    loadFailed = true
                } else {
                    photos += found
                    page++
                }
                if (::adapter.isInitialized) adapter.notifyDataSetChanged()
            }
        }
    }

    // endregion

    // region Picking a photo from the phone

    private fun pickPhoto() {
        // The system's photo picker needs no permission and shows only what's chosen to the app.
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQUEST_PHOTO)
    }

    @Deprecated("Platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data?.takeIf { requestCode == REQUEST_PHOTO && resultCode == RESULT_OK } ?: return
        loadInto("Your photo", null) { decode(uri) }
    }

    private fun decode(uri: Uri): Bitmap? = runCatching {
        val (w, h) = targetSize()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        // Big enough to cover the screen (and a bit for moving it around), no bigger.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()

    // endregion

    // region Preview

    private fun openPhoto(photo: Photo) {
        val (w, h) = targetSize()
        // Picsum resizes and crops on its side; a little wider than the screen leaves room to move it.
        val width = (w * 1.3f).toInt()
        loadInto("Photo by ${photo.author}", "Unsplash") { download("https://picsum.photos/id/${photo.id}/$width/$h") }
    }

    /** Shows the preview right away with a loading note, then the picture once [load] returns it. */
    private fun loadInto(title: String, source: String?, load: () -> Bitmap?) {
        val p = Preview(title, source)
        preview = p
        root.addView(p.view)
        io.execute {
            val bitmap = load()
            main.post {
                if (preview !== p) return@post
                if (bitmap == null) {
                    closePreview()
                    Toast.makeText(this, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
                } else {
                    p.show(bitmap)
                }
            }
        }
    }

    private fun openPreview(bitmap: Bitmap, title: String, source: String?) {
        val p = Preview(title, source)
        preview = p
        root.addView(p.view)
        p.show(bitmap)
    }

    private fun closePreview() {
        val p = preview ?: return
        preview = null
        p.view.animate().alpha(0f).setDuration(150).withEndAction { root.removeView(p.view) }.start()
    }

    /**
     * Full screen: the picture (dragged to reposition), a mock clock and dock so it's clear how
     * the home screen will look, and the button to set it.
     */
    private inner class Preview(title: String, source: String?) {
        private val image = PanImage()
        private val loadingNote = TextView(this@WallpaperActivity).apply {
            text = "Loading…"
            style(TextStyle.Secondary, 0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        private val hint = TextView(this@WallpaperActivity).apply {
            text = "Drag to position the wallpaper"
            style(TextStyle.Label, 0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), 0)
            background = Ui.rounded(this@WallpaperActivity, 0x66000000, Ui.RADIUS_PILL)
            visibility = View.GONE
        }
        /** Set while choosing one half of the light and dark pair. */
        private val forPair = pairTarget
        private val setButton = Components.pillButton(this@WallpaperActivity,
            when (forPair) {
                true -> "Use for dark mode"
                false -> "Use for light mode"
                null -> "Set wallpaper"
            }, 0xFFFFFFFF.toInt(), 0xFF1B1C1F.toInt()) { if (forPair != null) usePair(forPair) else chooseWhere() }

        val view: View = FrameLayout(this@WallpaperActivity).apply {
            setBackgroundColor(0xFF000000.toInt())
            isClickable = true
            alpha = 0f
            animate().alpha(1f).setDuration(180).start()
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(loadingNote, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            // How to place it, in the open middle of the screen; it fades once the picture is moved.
            addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36), Gravity.CENTER))
            // A sample of the home screen on top, so the picture is judged in place.
            addView(LinearLayout(this@WallpaperActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, insets.top + dp(48), 0, 0)
                addView(TextClock(this@WallpaperActivity).apply {
                    format12Hour = "h:mm"
                    format24Hour = "H:mm"
                    setTextColor(0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    setShadowLayer(dp(8).toFloat(), 0f, 0f, 0x66000000)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                })
                addView(TextClock(this@WallpaperActivity).apply {
                    format12Hour = "EEE, MMMM d"
                    format24Hour = "EEE, MMMM d"
                    setTextColor(0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    setShadowLayer(dp(6).toFloat(), 0f, 0f, 0x66000000)
                    setPadding(0, dp(6), 0, 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                })
                isClickable = false
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            // Back, the title and who took it, at the top.
            addView(LinearLayout(this@WallpaperActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(Ui.SPACE_S), insets.top, dp(Ui.SPACE_L), 0)
                addView(ImageView(this@WallpaperActivity).apply {
                    setImageResource(R.drawable.ic_arrow_back)
                    imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_PILL, focusColor = 0xFFFFFFFF.toInt())
                    isFocusable = true
                    contentDescription = "Back"
                    setOnClickListener { closePreview() }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(TextView(this@WallpaperActivity).apply {
                    text = if (source != null) "$title · $source" else title
                    style(TextStyle.Secondary, 0xE6FFFFFF.toInt())
                    setShadowLayer(dp(4).toFloat(), 0f, 0f, 0x88000000.toInt())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Ui.SPACE_S) })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            // The dock, the hint and the button at the bottom.
            addView(LinearLayout(this@WallpaperActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), insets.bottom + dp(Ui.SPACE_M))
                addView(View(this@WallpaperActivity).apply {
                    background = Ui.rounded(this@WallpaperActivity, 0x33000000, Ui.RADIUS_BAR, 0x26FFFFFF)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Ui.DOCK_HEIGHT)))
                addView(setButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                    topMargin = dp(Ui.SPACE_M)
                })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }

        init {
            setButton.isEnabled = false
            setButton.alpha = 0.5f
        }

        fun show(bitmap: Bitmap) {
            loadingNote.visibility = View.GONE
            image.setPicture(bitmap)
            if (image.canMove) hint.visibility = View.VISIBLE
            image.onMoved = { hint.animate().alpha(0f).setDuration(200).start() }
            setButton.isEnabled = true
            setButton.alpha = 1f
            setButton.requestFocus()
        }

        private fun chooseWhere() {
            val options = listOf("Home screen", "Lock screen", "Home and lock screen")
            val flags = listOf(
                WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK,
                WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
            )
            Components.choiceSheet(this@WallpaperActivity, palette, overlays, "Set wallpaper for", options.map { it to null }, -1) { i ->
                apply(flags[i])
            }
        }

        /** Keeps the picture as one half of the pair, and back to the page to choose the other. */
        private fun usePair(dark: Boolean) {
            val cropped = image.visibleArea(targetSize()) ?: return
            setButton.isEnabled = false
            DarkMode.saveToPair(this@WallpaperActivity, cropped, dark) {
                pairTarget = null
                preview = null
                build()
                Toast.makeText(this@WallpaperActivity,
                    if (DarkMode.hasPair(this@WallpaperActivity)) "Light and dark wallpapers set" else "Now pick the ${if (dark) "light" else "dark"} one",
                    Toast.LENGTH_SHORT).show()
            }
        }

        /** Crops what's on screen to the wallpaper's size and sets it, then goes home to show it. */
        private fun apply(which: Int) {
            // A wallpaper set on its own replaces the pair; otherwise the next mode switch would undo it.
            if (which and WallpaperManager.FLAG_SYSTEM != 0) DarkMode.clearPair(this@WallpaperActivity)
            val cropped = image.visibleArea(targetSize()) ?: return
            setButton.isEnabled = false
            (setButton as TextView).text = "Setting…"
            io.execute {
                val ok = runCatching { WallpaperManager.getInstance(this@WallpaperActivity).setBitmap(cropped, null, true, which) }.isSuccess
                main.post {
                    if (ok) {
                        startActivity(Intent(this@WallpaperActivity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    } else {
                        (setButton as TextView).text = "Set wallpaper"
                        setButton.isEnabled = true
                        Toast.makeText(this@WallpaperActivity, "Couldn't set the wallpaper", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** A picture that fills the screen and can be dragged to choose which part shows. */
    private inner class PanImage : View(this@WallpaperActivity), OwnsDrag {
        private var bitmap: Bitmap? = null
        private val matrix = Matrix()
        private var scale = 1f
        private var dx = 0f
        private var dy = 0f
        private var lastX = 0f
        private var lastY = 0f
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        var onMoved: (() -> Unit)? = null

        /** Whether the picture is bigger than the screen in some direction, so dragging does something. */
        val canMove: Boolean get() = bitmap?.let { it.width * scale > width + 1 || it.height * scale > height + 1 } ?: false

        init {
            isFocusable = false
        }

        fun setPicture(b: Bitmap) {
            bitmap = b
            fit()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fit()

        /** Covers the view, centered. */
        private fun fit() {
            val b = bitmap ?: return
            if (width == 0) return
            scale = max(width / b.width.toFloat(), height / b.height.toFloat())
            dx = (width - b.width * scale) / 2
            dy = (height - b.height * scale) / 2
        }

        override fun onDraw(canvas: Canvas) {
            val b = bitmap ?: return
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            canvas.drawBitmap(b, matrix, paint)
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val b = bitmap ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    // Moves only as far as the picture still covers the whole screen.
                    dx = (dx + event.x - lastX).coerceIn(width - b.width * scale, 0f)
                    dy = (dy + event.y - lastY).coerceIn(height - b.height * scale, 0f)
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                    onMoved?.invoke()
                }
            }
            return true
        }

        /** What's on screen, at [size]. */
        fun visibleArea(size: Pair<Int, Int>): Bitmap? {
            val b = bitmap ?: return null
            val (w, h) = size
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val k = w / width.toFloat()
            Canvas(out).drawBitmap(b, Matrix().apply {
                setScale(scale * k, scale * k)
                postTranslate(dx * k, dy * k)
            }, paint)
            return out
        }
    }

    // endregion

    // region Pictures

    /** The size the wallpaper is set at: the screen's, in pixels. */
    private fun targetSize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        val bounds = windowManager.currentWindowMetrics.bounds
        return max(bounds.width(), m.widthPixels) to max(bounds.height(), m.heightPixels)
    }

    private fun gradient(top: Int, bottom: Int): Bitmap {
        val (w, h) = targetSize()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            })
        }
    }

    private fun fetch(url: String): String = open(url).inputStream.bufferedReader().use { it.readText() }

    private fun download(url: String): Bitmap? = runCatching { open(url).inputStream.use(BitmapFactory::decodeStream) }.getOrNull()

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = true
    }

    private fun dp(value: Int) = Ui.dp(this, value)

    // endregion

    companion object {
        private const val REQUEST_PHOTO = 41

        /** Calm colors that suit the launcher's surfaces: name, top and bottom of a soft gradient. */
        private val COLORS = listOf(
            Triple("Forest", 0xFF2F5D50.toInt(), 0xFF14281F.toInt()),
            Triple("Night", 0xFF2B3A55.toInt(), 0xFF111827.toInt()),
            Triple("Plum", 0xFF5A3A5E.toInt(), 0xFF24152A.toInt()),
            Triple("Clay", 0xFFA0563F.toInt(), 0xFF4A2219.toInt()),
            Triple("Sand", 0xFFE3D5BC.toInt(), 0xFFC2AE8C.toInt()),
            Triple("Sage", 0xFF9DB09A.toInt(), 0xFF5E7260.toInt()),
            Triple("Ocean", 0xFF2E6F8E.toInt(), 0xFF0F2E3F.toInt()),
            Triple("Rose", 0xFFD9A5A0.toInt(), 0xFF8E5B58.toInt()),
            Triple("Graphite", 0xFF3A3D42.toInt(), 0xFF151719.toInt()),
            Triple("Black", 0xFF000000.toInt(), 0xFF000000.toInt()),
        )
    }
}
