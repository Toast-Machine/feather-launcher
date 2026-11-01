package app.featherlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import java.io.File

/** Run on a test emulator: adb shell am instrument -w app.featherlauncher.test/app.featherlauncher.SearchColumnsTests */
class SearchColumnsTests : Instrumentation() {
    private var shotPrefix = ""
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        shotPrefix = arguments?.getString("shotPrefix").orEmpty()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var home: HomeActivity? = null
        try {
            runOnMainSync { checkActionCallbacks() }
            ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
                "pm grant ${targetContext.packageName} android.permission.READ_CONTACTS")).use { it.readBytes() }
            val prefs = Prefs(targetContext)
            prefs.onboarded = true
            prefs.searchColumns = true
            check(Prefs(targetContext).searchColumns)
            val activity = startActivitySync(Intent(targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HomeActivity
            home = activity
            Thread.sleep(1200)
            runOnMainSync {
                val contactsField = HomeActivity::class.java.getDeclaredField("contacts").apply { isAccessible = true }
                val contacts = contactsField.get(activity) as Contacts
                Contacts::class.java.getDeclaredField("all").apply { isAccessible = true }.set(contacts,
                    (listOf("Barbara Adams", "Carol Baker", "Casey Carter", "Cameron Bell", "Bailey Clark") +
                        (6..12).map { "Bailey Clark $it" }).mapIndexed { i, name ->
                        Contacts.Contact(i.toLong(), name, "415-555-012$i", listOf("415-555-012$i"), null, true, if (i == 0) "barbara@example.com" else null)
                    })
                activity.pressDigit(2)
                activity.findViewById<EditText>(R.id.search).setText("22")
            }
            settle()
            val list = activity.findViewById<ListView>(R.id.apps)
            val adapter = list.adapter as DrawerAdapter
            runOnMainSync {
                check(adapter.rows.any { it is DrawerAdapter.Row.Columns && it.left is DrawerAdapter.Row.Person && it.right is DrawerAdapter.Row.Line })
                val titleIndex = adapter.rows.indexOfFirst { it is DrawerAdapter.Row.Columns && it.left is DrawerAdapter.Row.Title && it.right is DrawerAdapter.Row.Title }
                val titles = list.getChildAt(titleIndex - list.firstVisiblePosition) as ViewGroup
                val results = list.getChildAt(titleIndex + 1 - list.firstVisiblePosition) as ViewGroup
                val leftTitle = descendants(titles.getChildAt(0)).filterIsInstance<android.widget.TextView>().first()
                val rightTitle = descendants(titles.getChildAt(2)).filterIsInstance<android.widget.TextView>().first()
                fun point(view: View) = IntArray(2).also { view.getLocationOnScreen(it) }
                check(kotlin.math.abs(point(leftTitle)[1] - point(rightTitle)[1]) <= 1) { "Column headings must share a baseline" }
                val contactContents = (results.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
                val appContents = results.getChildAt(2) as ViewGroup
                check(point(leftTitle)[0] == point(contactContents.getChildAt(0))[0]) { "Contacts heading must align with results" }
                check(point(rightTitle)[0] == point(appContents.getChildAt(0))[0]) { "Apps heading must align with results" }
                check(results.getChildAt(1).width == Ui.dp(activity, 1) && results.getChildAt(1).height == results.height)
                val lastPair = adapter.rows.indexOfLast { it is DrawerAdapter.Row.Columns }
                check(adapter.rows.indexOfFirst { it is DrawerAdapter.Row.Title && it.text == "Settings" } > lastPair)
                val filesTitle = adapter.rows.indexOfFirst { it is DrawerAdapter.Row.Title && it.text == "Files" }
                check(filesTitle == -1 || filesTitle > lastPair)
                check(contactRows(list).all { actions(it).visibility == View.INVISIBLE })
            }
            screenshot("columns-idle")
            var row = contactRows(list).first()
            val point = IntArray(2)
            runOnMainSync { row.getLocationOnScreen(point) }
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action,
                    point[0] + row.width / 2f, point[1] + row.height / 2f, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                check(uiAutomation.injectInputEvent(event, true))
                event.recycle()
            }
            runOnMainSync { check(actions(row).visibility == View.VISIBLE) }
            screenshot("columns-touch-actions")
            runOnMainSync { checkRightAligned(row) }
            runOnMainSync { activity.findViewById<View>(R.id.search_bar).requestFocusFromTouch() }
            var moves = 0
            while (activity.currentFocus?.tag !is Contacts.Contact && moves++ < 5) {
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                settle()
            }
            row = activity.currentFocus as? ViewGroup ?: error("D-pad never reached a contact")
            runOnMainSync {
                check(row.tag is Contacts.Contact) { "D-pad focused ${row.javaClass.name}, tag=${row.tag}" }
                check(actions(row).visibility == View.VISIBLE) { "Focused contact actions hidden: row=$row, focus=${activity.currentFocus}" }
            }
            screenshot("columns-dpad-actions")
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            runOnMainSync { check(activity.currentFocus?.contentDescription?.startsWith("Call ") == true) }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            runOnMainSync { check(activity.currentFocus?.contentDescription?.startsWith("Text ") == true) }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            runOnMainSync { check(activity.currentFocus?.contentDescription?.startsWith("Email ") == true) }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            runOnMainSync { check(activity.currentFocus?.contentDescription?.startsWith("Open ") == true) }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            runOnMainSync { check(activity.currentFocus?.tag is AppEntry); check(actions(row).visibility == View.INVISIBLE) }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            runOnMainSync { check(activity.currentFocus?.tag is Contacts.Contact); check(actions(row).visibility == View.VISIBLE) }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            runOnMainSync { check((activity.currentFocus?.tag as? Contacts.Contact)?.name == "Carol Baker"); check(actions(row).visibility == View.INVISIBLE) }
            screenshot("columns-right-actions")
            runOnMainSync { checkRightAligned(activity.currentFocus as ViewGroup) }
            runOnMainSync {
                val more = descendants(list).filterIsInstance<android.widget.TextView>().first { it.text == "Show more" }
                more.performClick()
            }
            settle()
            runOnMainSync {
                check(adapter.rows.filterIsInstance<DrawerAdapter.Row.Columns>().count { it.left is DrawerAdapter.Row.Person } == 12)
                activity.findViewById<View>(R.id.search_bar).requestFocusFromTouch()
            }
            moves = 0
            while (activity.currentFocus?.tag !is Contacts.Contact && moves++ < 5) sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(11) {
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                settle()
                runOnMainSync { check(activity.currentFocus?.tag is Contacts.Contact) { "Lost contact focus while scrolling: ${activity.currentFocus}" } }
            }
            runOnMainSync { check((activity.currentFocus?.tag as Contacts.Contact).name == "Bailey Clark 12") }
            runOnMainSync { activity.findViewById<EditText>(R.id.search).setText("9999999") }
            settle()
            runOnMainSync { check(adapter.rows.none { it is DrawerAdapter.Row.Columns }) }
            runOnMainSync { activity.findViewById<EditText>(R.id.search).setText("") }
            settle()
            runOnMainSync { check(adapter.rows.filterIsInstance<DrawerAdapter.Row.Columns>().any { (it.left as? DrawerAdapter.Row.Title)?.text == "Favorites" }) }
            // A real owned file must remain a full-width result below the paired rows.
            ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
                "pm grant ${targetContext.packageName} android.permission.READ_MEDIA_IMAGES")).use { it.readBytes() }
            val file = targetContext.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "22-notes.txt")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                })!!
            try {
                runOnMainSync { activity.findViewById<EditText>(R.id.search).setText("22") }
                settle()
                runOnMainSync {
                    val fileIndex = adapter.rows.indexOfFirst { it is DrawerAdapter.Row.Result && it.found.title == "22-notes.txt" }
                    check(fileIndex > adapter.rows.indexOfLast { it is DrawerAdapter.Row.Columns })
                    list.smoothScrollToPosition(fileIndex)
                }
                screenshot("columns-files")
            } finally { targetContext.contentResolver.delete(file, null, null) }
            runOnMainSync { activity.finish() }
            val settings = startActivitySync(Intent(targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            runOnMainSync { settings.open(SettingsActivity.Page.SEARCH) }
            settle()
            screenshot("columns-setting")
            runOnMainSync {
                check(SettingsActivity.Page.SEARCH.title == "Search")
                val toggle = descendants(settings.window.decorView).filterIsInstance<FeatherSwitch>().first()
                (toggle.parent as View).performClick()
                check(!Prefs(targetContext).searchColumns)
                settings.finish()
            }
            val stacked = startActivitySync(Intent(targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HomeActivity
            runOnMainSync { stacked.pressDigit(2) }
            settle()
            runOnMainSync {
                val stackedAdapter = stacked.findViewById<ListView>(R.id.apps).adapter as DrawerAdapter
                check(!stackedAdapter.searchColumns && stackedAdapter.rows.none { it is DrawerAdapter.Row.Columns })
                stacked.finish()
            }
            result.putString("stream", "PASS: columns, full-width Settings/Files, tap/focus actions, D-pad traversal, Show more, empty results, favorites, and settings persistence\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            home?.let { runOnMainSync { it.finish() } }
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun checkActionCallbacks() {
        val calls = ArrayList<ContactAction>()
        val adapter = DrawerAdapter(targetContext, onContact = { _, action -> calls += action }, icon = { null },
            digitFor = { null }, isLocked = { false }, isNew = { false }, onClick = { _, _ -> }, onLongClick = { _, _ -> })
        adapter.palette = Palette(212f, 0.55f, false)
        val contact = Contacts.Contact(1, "Barbara", "4155550123", listOf("4155550123"), null, false, "barbara@example.com")
        adapter.set(listOf(DrawerAdapter.Row.Person(contact)), null)
        adapter.searchColumns = true
        val row = adapter.getView(0, null, android.widget.FrameLayout(targetContext)) as ViewGroup
        check(actions(row).visibility == View.INVISIBLE)
        row.performClick()
        check(actions(row).visibility == View.VISIBLE && calls.isEmpty())
        descendants(actions(row)).filter { it.isClickable }.forEach { it.performClick() }
        check(calls == listOf(ContactAction.CALL, ContactAction.TEXT, ContactAction.EMAIL, ContactAction.OPEN))
        adapter.searchColumns = false
        val stacked = adapter.getView(0, null, android.widget.FrameLayout(targetContext))
        stacked.performClick()
        check(calls.last() == ContactAction.OPEN)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun contactRows(list: ListView) = descendants(list).filterIsInstance<ViewGroup>().filter { it.tag is Contacts.Contact && it.isFocusable }
    private fun actions(row: ViewGroup) = row.getChildAt(1)
    private fun checkRightAligned(row: ViewGroup) {
        val toolbar = actions(row) as android.widget.HorizontalScrollView
        val actions = toolbar.getChildAt(0) as ViewGroup
        val last = actions.getChildAt(actions.childCount - 1)
        check(kotlin.math.abs(last.right - toolbar.scrollX - (toolbar.width - toolbar.paddingRight)) <= 1) {
            "Contact actions must end at the row's right inset: last=${last.right}, scroll=${toolbar.scrollX}, width=${toolbar.width}, inset=${toolbar.paddingRight}"
        }
    }
    private fun settle() { waitForIdleSync(); Thread.sleep(500) }
    private fun screenshot(name: String) {
        settle()
        val dir = File(targetContext.getExternalFilesDir(null), "search-ui").apply { mkdirs() }
        uiAutomation.takeScreenshot().let { bitmap -> File(dir, "$shotPrefix$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
