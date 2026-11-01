package app.featherlauncher

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.FrameLayout
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import android.content.ContentProviderOperation
import android.provider.ContactsContract
import java.io.File

/** Platform-only regression checks. Run: adb shell am instrument -w app.featherlauncher.test/app.featherlauncher.SearchUiTests */
class SearchUiTests : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        try {
            runOnMainSync { checkNumberHighlight() }
            captureNumberSearch()
            result.putString("stream", "PASS: formatted phone digits, name priority, and unmatched numbers\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun captureNumberSearch() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
            "pm grant ${targetContext.packageName} android.permission.READ_CONTACTS")).use { it.readBytes() }
        uiAutomation.adoptShellPermissionIdentity("android.permission.WRITE_CONTACTS")
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Alice Morgan").build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, "+1 (415) 555-0123").build(),
        )
        val inserted = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)[0].uri!!
        uiAutomation.dropShellPermissionIdentity()
        try {
            targetContext.getSharedPreferences("launcher", 0).edit().putBoolean("onboarded", true).commit()
            val activity = startActivitySync(Intent(targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HomeActivity
            Thread.sleep(1500)
            runOnMainSync { activity.pressDigit(5); activity.findViewById<TextView>(R.id.search).text = "55501" }
            waitForIdleSync()
            Thread.sleep(700)
            val dir = File(targetContext.getExternalFilesDir(null), "search-ui").apply { mkdirs() }
            uiAutomation.takeScreenshot().let { bitmap -> File(dir, "number-highlight.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            runOnMainSync { activity.finish() }
        } finally {
            uiAutomation.adoptShellPermissionIdentity("android.permission.WRITE_CONTACTS")
            context.contentResolver.delete(inserted, null, null)
            uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun checkNumberHighlight() {
        val p = Palette(212f, 0.55f, false)
        val adapter = DrawerAdapter(targetContext, icon = { null }, digitFor = { null },
            isLocked = { false }, isNew = { false }, onClick = { _, _ -> }, onLongClick = { _, _ -> })
        adapter.palette = p
        val contact = Contacts.Contact(1, "Alice", "+1 (415) 555-0123", listOf("+1 (415) 555-0123"), null, false)
        adapter.set(listOf(DrawerAdapter.Row.Person(contact)), null)
        fun number(query: String): CharSequence {
            adapter.query = query
            return texts(adapter.getView(0, null, FrameLayout(targetContext))).first { it.text.toString() == contact.number }.text
        }
        val highlighted = number("55501") as Spanned
        val bold = highlighted.getSpans(0, highlighted.length, StyleSpan::class.java)
        check(bold.map { highlighted[highlighted.getSpanStart(it)] }.joinToString("") == "55501")
        check(bold.map { highlighted.getSpanStart(it) } == listOf(9, 10, 11, 13, 14))
        check(highlighted.getSpans(0, highlighted.length, ForegroundColorSpan::class.java).any { it.foregroundColor == p.onWall })
        check(number("254") !is Spanned) // Alice matches by T9 name.
        check(number("999") !is Spanned || (number("999") as Spanned).getSpans(0, contact.number.length, StyleSpan::class.java).isEmpty())
    }

    private fun texts(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
}


