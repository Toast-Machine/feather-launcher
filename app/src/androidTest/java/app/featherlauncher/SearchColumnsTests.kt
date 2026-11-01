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
            val photo = contactPhoto()
            val activity = startActivitySync(Intent(targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HomeActivity
            home = activity
            Thread.sleep(1200)
            runOnMainSync {
                val contactsField = HomeActivity::class.java.getDeclaredField("contacts").apply { isAccessible = true }
                val contacts = contactsField.get(activity) as Contacts
                val all = Contacts::class.java.getDeclaredField("all").apply { isAccessible = true }
                val samples =
                    (listOf("Barbara Adams", "Carol Baker", "Casey Carter", "Cameron Bell", "Bailey Clark") +
                        (6..12).map { "Bailey Clark $it" }).mapIndexed { i, name ->
                        Contacts.Contact(i.toLong(), name, "415-555-012$i", listOf("415-555-012$i"), if (i == 0) photo else null, true, if (i == 0) "barbara@example.com" else null)
                    }
                all.set(contacts, samples)
                // Keep the fixture stable if the real provider finishes an asynchronous reload.
                contacts.onLoaded = { all.set(contacts, samples) }
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
                val firstDivider = titles.getChildAt(1) as ViewGroup
                val lastDivider = (list.getChildAt(lastPair - list.firstVisiblePosition) as ViewGroup).getChildAt(1) as ViewGroup
                check(firstDivider.paddingTop == Ui.dp(activity, Ui.SPACE_L))
                check(firstDivider.getChildAt(0).top == firstDivider.paddingTop)
                check(lastDivider.paddingBottom == Ui.dp(activity, Ui.SPACE_L))
                check(lastDivider.getChildAt(0).bottom == lastDivider.height - lastDivider.paddingBottom)
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
            runOnMainSync { checkRightAligned(row); checkVisibleDetails(row) }
            var opened: android.net.Uri? = null
            val monitor = object : ActivityMonitor() {
                override fun onStartActivity(intent: Intent): ActivityResult? {
                    if (intent.action != Intent.ACTION_VIEW || intent.data?.authority != android.provider.ContactsContract.AUTHORITY) return null
                    opened = intent.data
                    return ActivityResult(Activity.RESULT_OK, null)
                }
            }
            addMonitor(monitor)
            try {
                runOnMainSync { row.getLocationOnScreen(point) }
                val tapTime = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(tapTime, android.os.SystemClock.uptimeMillis(), action,
                        point[0] + row.width / 2f, point[1] + Ui.dp(activity, 3).toFloat(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    check(uiAutomation.injectInputEvent(event, true))
                    event.recycle()
                }
                settle()
                check(opened?.lastPathSegment == "0") { "An uncovered card tap must open the selected contact: $opened" }
            } finally { removeMonitor(monitor) }
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
            repeat(3) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT) }
            runOnMainSync { check(activity.currentFocus === row) }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            runOnMainSync { check(activity.currentFocus === row) }
            screenshot("columns-card-focus")
            repeat(4) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT) }
            runOnMainSync { check(activity.currentFocus?.tag is AppEntry); check(actions(row).visibility == View.INVISIBLE) }
            runOnMainSync { check(details(row).alpha == 1f) }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            runOnMainSync { check(activity.currentFocus?.tag is Contacts.Contact); check(actions(row).visibility == View.VISIBLE) }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            runOnMainSync { check((activity.currentFocus?.tag as? Contacts.Contact)?.name == "Carol Baker"); check(actions(row).visibility == View.INVISIBLE) }
            screenshot("columns-right-actions")
            runOnMainSync { checkRightAligned(activity.currentFocus as ViewGroup) }
            runOnMainSync { contactRows(list).first().requestFocusFromTouch() }
            settle()
            opened = null
            addMonitor(monitor)
            try {
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                waitForIdleSync()
                check(opened?.lastPathSegment == "0") { "D-pad OK on the card must open the contact" }
            } finally { removeMonitor(monitor) }
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
            result.putString("stream", "PASS: card tap/OK contact opening, no photo action, fitted buttons without scrolling or photo overlap, faded details, divider insets, column geometry, D-pad navigation, files, and settings\n")
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
        check(calls == listOf(ContactAction.CALL, ContactAction.TEXT, ContactAction.EMAIL))
        check(descendants(actions(row)).none { it.contentDescription?.startsWith("Open ") == true })
        checkVisibleDetails(row)
        check(!picture(row).isClickable && !picture(row).isFocusable)
        row.performClick()
        check(calls.last() == ContactAction.OPEN)
        checkResponsiveActions(adapter)
        adapter.searchColumns = false
        val stacked = adapter.getView(0, null, android.widget.FrameLayout(targetContext))
        stacked.performClick()
        check(calls.last() == ContactAction.OPEN)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun contactRows(list: ListView) = descendants(list).filterIsInstance<ViewGroup>().filter { it.tag is Contacts.Contact && it.isFocusable }
    private fun actions(row: ViewGroup) = row.getChildAt(1)
    private fun picture(row: ViewGroup) = (row.getChildAt(0) as ViewGroup).getChildAt(0)
    private fun details(row: ViewGroup) = (row.getChildAt(0) as ViewGroup).getChildAt(1)
    private fun checkVisibleDetails(row: ViewGroup) {
        check(row.getChildAt(0).visibility == View.VISIBLE && row.getChildAt(0).alpha == 1f)
        check(picture(row).visibility == View.VISIBLE && picture(row).alpha == 1f)
        check(details(row).visibility == View.VISIBLE && details(row).alpha in 0.1f..0.5f)
        check(actions(row).left >= picture(row).right + row.getChildAt(0).left) { "Actions must not cover the picture" }
    }
    private fun contactPhoto(): android.net.Uri {
        val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(0xFFB4BDE0.toInt())
        paint.color = 0xFF455685.toInt(); canvas.drawOval(12f, 60f, 84f, 125f, paint)
        paint.color = 0xFF493A43.toInt(); canvas.drawOval(25f, 12f, 71f, 66f, paint)
        paint.color = 0xFFF2C5A7.toInt(); canvas.drawOval(31f, 23f, 65f, 63f, paint)
        paint.color = 0xFF493A43.toInt(); canvas.drawOval(27f, 14f, 69f, 35f, paint)
        val file = File(targetContext.cacheDir, "contact-photo.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return android.net.Uri.fromFile(file)
    }
    private fun checkRightAligned(row: ViewGroup) {
        val toolbar = actions(row) as android.widget.LinearLayout
        val last = toolbar.getChildAt(toolbar.childCount - 1)
        check(last.right == toolbar.width - toolbar.paddingRight) { "Actions must remain right-aligned" }
        check(descendants(row).none { it is android.widget.HorizontalScrollView })
        for (i in 0 until toolbar.childCount) {
            val button = toolbar.getChildAt(i)
            check(button.left >= toolbar.paddingLeft && button.right <= toolbar.width - toolbar.paddingRight)
            check(button.width > 0 && !toolbar.canScrollHorizontally(1) && !toolbar.canScrollHorizontally(-1))
        }
    }
    private fun checkResponsiveActions(adapter: DrawerAdapter) {
        fun sized(width: Int): ViewGroup {
            val row = adapter.getView(0, null, android.widget.FrameLayout(targetContext)) as ViewGroup
            row.performClick()
            row.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(targetContext, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
            checkRightAligned(row)
            checkVisibleDetails(row)
            return row
        }
        fun first(row: ViewGroup) = (actions(row) as ViewGroup).getChildAt(0) as android.widget.ImageView
        fun glyphWidth(button: android.widget.ImageView): Float {
            val box = android.graphics.RectF(0f, 0f, button.drawable.intrinsicWidth.toFloat(), button.drawable.intrinsicHeight.toFloat())
            button.imageMatrix.mapRect(box)
            return box.width()
        }
        val wide = first(sized(220))
        val medium = first(sized(160))
        check(medium.paddingLeft < wide.paddingLeft)
        check(kotlin.math.abs(glyphWidth(medium) - Ui.dp(targetContext, 24)) <= 1f) { "Reduce padding before shrinking the glyphs" }
        val narrow = first(sized(90))
        check(narrow.paddingLeft == 0 && glyphWidth(narrow) < glyphWidth(medium)) { "Shrink buttons only after padding is removed" }
    }
    private fun settle() { waitForIdleSync(); Thread.sleep(500) }
    private fun screenshot(name: String) {
        settle()
        val dir = File(targetContext.getExternalFilesDir(null), "search-ui").apply { mkdirs() }
        uiAutomation.takeScreenshot().let { bitmap -> File(dir, "$shotPrefix$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
