package app.featherlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.view.ViewGroup
import java.io.File

/** Run: adb shell am instrument -w app.featherlauncher.test/app.featherlauncher.SearchBarTests */
class SearchBarTests : Instrumentation() {
    private var shotPrefix = ""
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); shotPrefix = arguments?.getString("shotPrefix").orEmpty(); start() }

    override fun onStart() {
        val result = Bundle()
        var activity: HomeActivity? = null
        try {
            ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
                "pm grant ${targetContext.packageName} android.permission.CALL_PHONE")).use { it.readBytes() }
            targetContext.getSharedPreferences("launcher", 0).edit().putBoolean("onboarded", true).commit()
            Prefs(targetContext).searchCallRow = false
            val home = startActivitySync(Intent(targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HomeActivity
            activity = home
            runOnMainSync { home.pressDigit(5); home.findViewById<EditText>(R.id.search).setText("5550123") }
            waitForIdleSync()
            Thread.sleep(600)
            val call = home.findViewById<View>(R.id.search_call)
            val clear = home.findViewById<View>(R.id.search_clear)
            val search = home.findViewById<EditText>(R.id.search)
            runOnMainSync {
                check(call.visibility == View.VISIBLE && clear.visibility == View.VISIBLE)
                check(search.paddingEnd == Ui.dp(home, 48))
                val bar = home.findViewById<View>(R.id.search_bar)
                check(call.parent === bar.parent && call.left >= bar.right + Ui.dp(home, 8))
                check(call.width == Ui.dp(home, 48) && call.height == Ui.dp(home, 48))
            }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            runOnMainSync { check(clear.hasFocus()) }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            runOnMainSync { check(call.hasFocus()) }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            runOnMainSync { check(clear.hasFocus()) }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            screenshot("search-bar-call")
            val point = IntArray(2)
            runOnMainSync { call.getLocationOnScreen(point) }
            uiAutomation.takeScreenshot().let { bitmap ->
                check(bitmap.getPixel(point[0] + call.width / 2, point[1] + Ui.dp(home, 6)) == 0xFF2E9E5B.toInt()) { "Call button must be green" }
                bitmap.recycle()
            }
            var called: String? = null
            val monitor = object : ActivityMonitor() {
                override fun onStartActivity(intent: Intent): ActivityResult? {
                    if (intent.action != Intent.ACTION_CALL) return null
                    called = intent.data?.schemeSpecificPart
                    return ActivityResult(Activity.RESULT_OK, null)
                }
            }
            addMonitor(monitor)
            try {
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                check(called == "5550123") { "Call button dialed $called" }
            } finally { removeMonitor(monitor) }
            runOnMainSync {
                search.setText("alice")
                check(call.visibility == View.GONE)
                check(search.paddingEnd == Ui.dp(home, 48))
                check(home.currentFocus?.id == R.id.search_bar)
                search.setText("+123*#")
                check(call.visibility == View.VISIBLE)
                clear.performClick()
                check(call.visibility == View.GONE && clear.visibility == View.GONE)
                val adapter = home.findViewById<android.widget.ListView>(R.id.apps).adapter as DrawerAdapter
                check(adapter.rows.none { it.javaClass.simpleName == "Dial" })
            }
            runOnMainSync { home.finish() }
            chooseLocation("Result row")
            check(Prefs(targetContext).searchCallRow)
            val rowHome = startActivitySync(Intent(targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HomeActivity
            activity = rowHome
            runOnMainSync { rowHome.pressDigit(5); rowHome.findViewById<EditText>(R.id.search).setText("5550123") }
            waitForIdleSync(); Thread.sleep(600)
            runOnMainSync {
                check(rowHome.findViewById<View>(R.id.search_call).visibility == View.GONE)
                val list = rowHome.findViewById<android.widget.ListView>(R.id.apps)
                val adapter = list.adapter as DrawerAdapter
                check((adapter.rows.first() as DrawerAdapter.Row.Dial).number == "5550123")
            }
            screenshot("search-call-row")
            addMonitor(monitor)
            called = null
            try {
                runOnMainSync { rowHome.findViewById<android.widget.ListView>(R.id.apps).getChildAt(0).performClick() }
                check(called == "5550123")
            } finally { removeMonitor(monitor) }
            runOnMainSync {
                rowHome.findViewById<EditText>(R.id.search).setText("alice")
                val adapter = rowHome.findViewById<android.widget.ListView>(R.id.apps).adapter as DrawerAdapter
                check(adapter.rows.none { it is DrawerAdapter.Row.Dial })
                rowHome.finish()
            }
            chooseLocation("Beside search bar")
            check(!Prefs(targetContext).searchCallRow)
            result.putString("stream", "PASS: separate green Call button, Clear/Call D-pad navigation, dial intents, visibility, Search settings, and both persisted call locations\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            activity?.let { runOnMainSync { it.finish() } }
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun chooseLocation(label: String) {
        val settings = startActivitySync(Intent(targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        runOnMainSync { settings.open(SettingsActivity.Page.SEARCH) }
        waitForIdleSync(); Thread.sleep(300)
        runOnMainSync {
            check(SettingsActivity.Page.SEARCH.title == "Search")
            clickText(settings.window.decorView, "Call button location")
        }
        screenshot("search-call-locations")
        runOnMainSync { clickText(settings.window.decorView, label) }
        screenshot("search-call-setting")
        runOnMainSync { settings.finish() }
    }

    private fun clickText(root: View, text: String) {
        val label = descendants(root).filterIsInstance<TextView>().first { it.text.toString() == text }
        generateSequence<View>(label) { it.parent as? View }.first { it.isClickable }.performClick()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun screenshot(name: String) {
        waitForIdleSync(); Thread.sleep(500)
        val dir = File(targetContext.getExternalFilesDir(null), "search-ui").apply { mkdirs() }
        uiAutomation.takeScreenshot().let { bitmap -> File(dir, "$shotPrefix$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
