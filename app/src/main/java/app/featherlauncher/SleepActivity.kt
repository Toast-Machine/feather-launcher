package app.featherlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * What a locked app shows during Sleep time: the user's message, and a button that opens the
 * app after a wait. The wait only counts while this screen stays in front; leaving starts it over.
 */
class SleepActivity : Activity() {
    private lateinit var sleep: SleepTime
    private lateinit var palette: Palette
    private lateinit var pkg: String
    private lateinit var label: String
    private lateinit var button: TextView
    private lateinit var progress: ClipDrawable
    private lateinit var until: TextView
    /** When the wait started (elapsed realtime), or 0 when not waiting. */
    private var waitStarted = 0L

    private val tick = object : Runnable {
        override fun run() {
            update()
            button.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        sleep = SleepTime.get(this)
        val prefs = Prefs(this)
        palette = Palette.from(this, prefs.colorStyle, prefs.themeMode, prefs.accentColor)
        read(intent)
        build()

        // Newer Android sends Back here instead of as a key press.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goHome() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
        build()
    }

    private fun read(intent: Intent) {
        pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("This app")
        waitStarted = 0
    }

    private fun build() {
        val p = palette
        val root = FrameLayout(this).apply { setBackgroundColor(p.searchScrim) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            column.setPadding(dp(Ui.SPACE_XL), bars.top + dp(Ui.SPACE_XL), dp(Ui.SPACE_XL), bars.bottom + dp(Ui.SPACE_L))
            insets
        }

        // The message, in the middle of the screen.
        column.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        column.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_moon)
            imageTintList = android.content.res.ColorStateList.valueOf(p.onWall)
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        column.addView(TextView(this).apply {
            text = sleep.message
            style(TextStyle.Title, p.onWall)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Ui.SPACE_L)
        })
        until = TextView(this).apply {
            style(TextStyle.Secondary, p.onWallSecondary)
            gravity = Gravity.CENTER
        }
        column.addView(until, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Ui.SPACE_M)
        })
        column.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // The app, and the way into it.
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(ImageView(this@SleepActivity).apply {
                val repo = Launcher.repo(this@SleepActivity)
                val entry = repo.apps.firstOrNull { it.packageName == pkg }
                val bitmap = entry?.let(repo::icon)
                if (bitmap != null) setImageBitmap(bitmap) else runCatching { setImageDrawable(packageManager.getApplicationIcon(pkg)) }
                IconBadges.apply(this, asleep = true, isNew = false, sizeDp = 28)
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(TextView(this@SleepActivity).apply {
                text = label
                style(TextStyle.Label, p.onWall)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(Ui.SPACE_S)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(Ui.SPACE_L)
        })

        progress = ClipDrawable(Ui.rounded(this, 0x33FFFFFF, Ui.RADIUS_PILL), Gravity.START, ClipDrawable.HORIZONTAL)
        button = TextView(this).apply {
            gravity = Gravity.CENTER
            style(TextStyle.Label, p.onWall)
            background = LayerDrawable(arrayOf(Ui.rounded(this@SleepActivity, p.glass, Ui.RADIUS_PILL, p.hairline), progress))
            foreground = Ui.pressable(this@SleepActivity, Ui.RADIUS_PILL)
            isFocusable = true
            setOnClickListener { onButton() }
        }
        column.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        column.addView(TextView(this).apply {
            text = "Close"
            gravity = Gravity.CENTER
            style(TextStyle.Label, p.onWallSecondary)
            background = Ui.pressable(this@SleepActivity, Ui.RADIUS_PILL)
            isFocusable = true
            setOnClickListener { goHome() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(Ui.SPACE_XS) })

        setContentView(root)
        button.requestFocus()
        update()
    }

    override fun onResume() {
        super.onResume()
        // Sleep time may have ended, or the app was opened in the meantime.
        if (!sleep.isLocked(pkg)) return openApp()
        button.post(tick)
    }

    override fun onPause() {
        super.onPause()
        button.removeCallbacks(tick)
        // Leaving this screen starts the wait over.
        waitStarted = 0
    }

    private fun onButton() {
        val now = System.currentTimeMillis()
        if (now < sleep.readyAt(pkg)) return
        waitStarted = if (waitStarted == 0L) SystemClock.elapsedRealtime() else 0L
        update()
    }

    private fun update() {
        val now = System.currentTimeMillis()
        until.text = sleep.nightEnd(now)?.let { "Sleep time until ${SleepTime.formatTime(this, it)}" } ?: ""
        val ready = sleep.readyAt(pkg)
        val minutes = sleep.openMinutes
        when {
            now < ready -> {
                val left = ((ready - now + 999) / 1000).toInt()
                button.text = "Can open again in %d:%02d".format(left / 60, left % 60)
                button.alpha = 0.6f
                progress.level = 0
            }
            waitStarted != 0L -> {
                val total = sleep.waitSeconds * 1000L
                val elapsed = SystemClock.elapsedRealtime() - waitStarted
                if (elapsed >= total) {
                    sleep.open(pkg)
                    return openApp()
                }
                val left = ((total - elapsed + 999) / 1000).toInt()
                button.text = "Opening in $left sec · Tap to cancel"
                button.alpha = 1f
                progress.level = (elapsed * 10000 / total).toInt()
            }
            else -> {
                button.text = "Open for $minutes min"
                button.alpha = 1f
                progress.level = 0
            }
        }
    }

    private fun openApp() {
        button.removeCallbacks(tick)
        packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
            runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        finish()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goHome()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) goHome()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dp(value: Int) = Ui.dp(this, value)

    companion object {
        private const val EXTRA_PACKAGE = "package"

        fun intent(context: Context, pkg: String): Intent =
            Intent(context, SleepActivity::class.java)
                .putExtra(EXTRA_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
