package app.featherlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.ContentValues
import android.content.ContentProviderOperation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.ContactsContract
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Platform-only integration tests; all outgoing intents are intercepted, including calls. */
class SearchMemoryTests : Instrumentation() {
    private lateinit var home: HomeActivity
    private lateinit var prefs: Prefs
    private lateinit var memory: SearchMemory
    private var shotPrefix = ""
    private val intents = ArrayList<Intent>()
    private val insertedContacts = ArrayList<Uri>()
    private val monitor = object : ActivityMonitor() {
        override fun onStartActivity(intent: Intent): ActivityResult? {
            if (intent.component?.packageName == targetContext.packageName) return null
            intents += Intent(intent)
            return ActivityResult(Activity.RESULT_OK, null)
        }
    }
    private val adapter get() = home.findViewById<ListView>(R.id.apps).adapter as DrawerAdapter
    private val list get() = home.findViewById<ListView>(R.id.apps)
    private val people = listOf("Anna Adams", "Bella Adams", "Cara Adams", "Dana Adams", "Eva Adams", "Zoe Adams")
        .mapIndexed { i, name -> Contacts.Contact(700L + i, name, "41555501$i", listOf("41555501$i"), null, i == 0, "person$i@example.com") }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        shotPrefix = arguments?.getString("shotPrefix").orEmpty()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var original: JSONObject? = null
        var status = Activity.RESULT_OK
        try {
            original = JSONObject(Backup.export(targetContext)).getJSONObject("stores")
            prefs = Prefs(targetContext)
            memory = SearchMemory(targetContext)
            checkStorageAndBackup()
            shell("pm grant ${targetContext.packageName} android.permission.READ_CONTACTS")
            shell("pm grant ${targetContext.packageName} android.permission.CALL_PHONE")
            shell("pm grant ${targetContext.packageName} android.permission.READ_MEDIA_IMAGES")
            insertContacts()
            prefs.onboarded = true
            prefs.rememberSearchSelections = true
            prefs.searchColumns = false
            memory.clear()
            home = startActivitySync(Intent(targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HomeActivity
            settle(1200)
            runOnMainSync {
                val contacts = field(home, "contacts") as Contacts
                Contacts::class.java.getDeclaredField("all").apply { isAccessible = true }.set(contacts, people)
                home.pressDigit(2)
            }
            addMonitor(monitor)
            try {
                checkContactActions()
                checkAppsAndCategories()
                checkOtherResults()
                checkMediaLimit()
                runOnMainSync { home.finish() }
            } finally { removeMonitor(monitor) }
            checkSettings()
            result.putString("stream", "PASS: opt-in, MRU/dedup/cap, disabled/reset, backup round trip and legacy restore; contact open/call/text/email and hardware Call; app selection/launch/Enter; settings/shortcuts/files; match-only promotion, both layouts, Show more, hidden apps, and media before limit\n")
        } catch (error: Throwable) {
            if (::home.isInitialized) runOnMainSync { home.finish() }
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            status = Activity.RESULT_CANCELED
        } finally {
            original?.let { Backup.restoreStores(targetContext, it) }
            uiAutomation.adoptShellPermissionIdentity("android.permission.WRITE_CONTACTS")
            try { insertedContacts.forEach { context.contentResolver.delete(it, null, null) } }
            finally { uiAutomation.dropShellPermissionIdentity() }
        }
        finish(status, result)
    }

    private fun checkStorageAndBackup() {
        val launcher = targetContext.getSharedPreferences("launcher", 0)
        launcher.edit().remove("remember_search_selections").commit()
        memory.clear()
        check(!Prefs(targetContext).rememberSearchSelections)
        memory.record("app:disabled")
        prefs.rememberSearchSelections = true
        check(memory.ranks("a").isEmpty())
        repeat(505) { memory.record("app:$it") }
        var ranks = SearchMemory(targetContext).ranks("a")
        check(ranks.size == 500 && ranks["app:504"] == 0 && "app:4" !in ranks)
        memory.record("app:20")
        ranks = memory.ranks("a")
        check(ranks.size == 500 && ranks["app:20"] == 0)
        check(memory.ranks("").isEmpty())
        check(SearchMemory.promote(listOf("new", "app:20", "other", "app:504"), ranks) { it } == listOf("app:20", "app:504", "new", "other"))
        prefs.rememberSearchSelections = false
        memory.record("app:paused")
        check(memory.ranks("a").isEmpty())
        prefs.rememberSearchSelections = true
        check("app:paused" !in memory.ranks("a"))
        val stores = JSONObject(Backup.export(targetContext)).getJSONObject("stores")
        check(stores.getJSONObject(SearchMemory.STORE).has("recent"))
        check(stores.getJSONObject("launcher").getJSONObject("remember_search_selections").getBoolean("v"))
        memory.clear()
        prefs.rememberSearchSelections = false
        Backup.restoreStores(targetContext, stores)
        check(prefs.rememberSearchSelections && memory.ranks("a") == ranks)
        stores.remove(SearchMemory.STORE)
        Backup.restoreStores(targetContext, stores)
        check(memory.ranks("a").isEmpty())
        val restored = Contacts.Contact(99, people[0].name, people[0].number, people[0].numbers, null, false)
        check(SearchMemory.contact(restored) == SearchMemory.contact(people[0]))
        val sameNumber = Contacts.Contact(100, "Another person", people[0].number, people[0].numbers, null, false)
        check(SearchMemory.contact(sameNumber) != SearchMemory.contact(people[0]))
    }

    private fun insertContacts() {
        uiAutomation.adoptShellPermissionIdentity("android.permission.WRITE_CONTACTS")
        try {
            people.forEach { person ->
                context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                    "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?", arrayOf(person.number), null)?.use { cursor ->
                    while (cursor.moveToNext()) context.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI,
                        "${ContactsContract.RawContacts.CONTACT_ID} = ?", arrayOf(cursor.getLong(0).toString()))
                }
                val operations = arrayListOf(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .withValue(ContactsContract.RawContacts.STARRED, if (person.starred) 1 else 0).build(),
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, person.name).build(),
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, person.number).build(),
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, person.email).build(),
                )
                insertedContacts += context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)[0].uri!!
            }
        } finally { uiAutomation.dropShellPermissionIdentity() }
    }

    private fun checkContactActions() {
        for (action in ContactAction.entries) {
            memory.clear()
            query("ad")
            val target = people.last()
            runOnMainSync { invoke(home, "openContact", arrayOf(Contacts.Contact::class.java, ContactAction::class.java), target, action) }
            check(memory.ranks("ad")[SearchMemory.contact(target)] == 0) { "$action wasn't recorded" }
            check(intents.last().action == when (action) {
                ContactAction.OPEN -> Intent.ACTION_VIEW
                ContactAction.CALL -> Intent.ACTION_CALL
                else -> Intent.ACTION_SENDTO
            })
            query("ad")
            check(contactRows().firstOrNull()?.contact?.name == target.name) {
                "After $action: contacts=${(field(home, "contacts") as Contacts).all.map { it.name }}, rows=${adapter.rows.map { it.javaClass.simpleName }}, query=${adapter.query}, searching=${field(home, "searching")}, columns=${adapter.searchColumns}"
            }
        }
        memory.clear()
        query("ad")
        runOnMainSync {
            val personIndex = adapter.rows.indexOfFirst { it is DrawerAdapter.Row.Person }
            val view = adapter.getView(personIndex, null, FrameLayout(home))
            val button = descendants(view).first { it.contentDescription?.toString() == "Text ${people[0].name}" }
            button.performClick()
            check(memory.ranks("ad")[SearchMemory.contact(people[0])] == 0)
            val visible = descendants(list).first { (it.tag as? Contacts.Contact)?.name == people[1].name && it.isFocusable }
            visible.requestFocusFromTouch()
        }
        runOnMainSync {
            // Dispatch here because Android's emulator consumes injected CALL as a system key.
            descendants(list).first { (it.tag as? Contacts.Contact)?.name == people[1].name && it.isFocusable }.requestFocusFromTouch()
            home.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CALL))
            home.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CALL))
        }
        check(memory.ranks("ad")[SearchMemory.contact(people[1])] == 0) { "Hardware Call path did not remember the focused contact" }
        check(intents.last().data?.schemeSpecificPart == people[1].number)
        // The compact toolbar's reveal is not an action; all four actual buttons remember it.
        prefs.searchColumns = true
        query("ad")
        for (label in listOf("Call", "Text", "Email", "Open")) {
            memory.clear()
            runOnMainSync {
                val row = adapter.getView(adapter.rows.indexOfFirst { it is DrawerAdapter.Row.Columns && it.left is DrawerAdapter.Row.Person }, null, FrameLayout(home))
                val person = descendants(row).first { it.tag is Contacts.Contact }
                person.performClick()
                check(memory.ranks("ad").isEmpty())
                descendants(person).first { it.contentDescription?.toString()?.startsWith("$label ") == true }.performClick()
            }
            check(memory.ranks("ad").isNotEmpty())
        }
    }

    private fun checkAppsAndCategories() {
        prefs.searchColumns = false
        memory.clear()
        query("a")
        val baselineApps = appRows()
        check(baselineApps.size >= 2)
        val selected = baselineApps.last()
        screenshot("before")
        var picked: AppEntry? = null
        runOnMainSync {
            HomeActivity::class.java.getDeclaredField("pick").apply { isAccessible = true }.set(home, { app: AppEntry -> picked = app })
            invoke(home, "choose", arrayOf(AppEntry::class.java, View::class.java), selected, null)
        }
        check(picked === selected && memory.ranks("a")[SearchMemory.app(selected.key)] == 0)
        settle()
        runOnMainSync { home.pressDigit(2) }
        memory.record(SearchMemory.contact(people.last()))
        query("a")
        check(appRows().first().key == selected.key && contactRows().first().contact.name == people.last().name)
        screenshot("stacked")
        prefs.searchColumns = true
        query("a")
        check(adapter.rows.filterIsInstance<DrawerAdapter.Row.Columns>().first { it.left is DrawerAdapter.Row.Person }.let { (it.left as DrawerAdapter.Row.Person).contact.name } == people.last().name)
        check(adapter.rows.filterIsInstance<DrawerAdapter.Row.Columns>().first { it.right is DrawerAdapter.Row.Line }.let { (it.right as DrawerAdapter.Row.Line).app.key } == selected.key)
        screenshot("columns")
        // A remembered item must not be introduced when it doesn't match.
        query("no-such-result-xyz")
        check(appRows().isEmpty() && contactRows().isEmpty())
        prefs.searchColumns = false
        query("ad")
        check(contactRows().first().contact.name == people.last().name)
        query("")
        check(contactRows().map { it.contact.name } == listOf(people.first().name))
        memory.clear()
        runOnMainSync {
            invoke(home, "appMenu", arrayOf(AppEntry::class.java, View::class.java), selected, list)
            check(memory.ranks("a").isEmpty())
            clickText(home.window.decorView, "Hide from app list")
        }
        check(memory.ranks("a")[SearchMemory.app(selected.key)] == 0) { "App context-menu action wasn't remembered" }
        query("a")
        check(appRows().none { it.key == selected.key })
        prefs.setHidden(selected.key, false)
        (field(home, "prefs") as Prefs).reload()
        memory.clear()
        val fake = AppEntry(ComponentName("app.searchmemory.missing", "Missing"), "Missing", 0)
        runOnMainSync { home.launch(fake, null) }
        check(memory.ranks("a")[SearchMemory.app(fake.key)] == 0)
        memory.clear()
        query("a")
        // IME Enter and hardware OK both route through choose/launch.
        val primary = adapter.primary!!
        fun picker() {
            HomeActivity::class.java.getDeclaredField("pick").apply { isAccessible = true }.set(home, { _: AppEntry -> })
        }
        runOnMainSync {
            picker()
            home.findViewById<EditText>(R.id.search).onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_GO)
        }
        check(memory.ranks("a")[SearchMemory.app(primary.key)] == 0)
        settle()
        runOnMainSync { home.pressDigit(2) }
        query("a")
        runOnMainSync { picker(); home.findViewById<View>(R.id.search_bar).requestFocusFromTouch() }
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        check(memory.ranks("a")[SearchMemory.app(primary.key)] == 0)
        settle()
        runOnMainSync { home.pressDigit(2) }
    }

    private fun checkOtherResults() {
        query("st")
        val settings = adapter.rows.filterIsInstance<DrawerAdapter.Row.Result>().filter { it.found.key.startsWith("setting:") }
        check(settings.size >= 2)
        val chosen = settings.last().found
        runOnMainSync {
            adapter.getView(adapter.rows.indexOfFirst { it is DrawerAdapter.Row.Result && it.found.key == chosen.key }, null, FrameLayout(home)).performClick()
        }
        check(memory.ranks("st")[chosen.key] == 0)
        query("st")
        check(adapter.rows.filterIsInstance<DrawerAdapter.Row.Result>().first { it.found.key.startsWith("setting:") }.found.key == chosen.key)
        // Every Found kind uses the same adapter callback, including shortcuts without host access.
        for (key in listOf("shortcut:example:id", "media:content://example/7")) {
            var opened = false
            runOnMainSync {
                adapter.set(listOf(DrawerAdapter.Row.Result(Found("Example", "Test", key) { opened = true })), null)
                adapter.getView(0, null, FrameLayout(home)).performClick()
            }
            check(opened && memory.ranks("st")[key] == 0)
        }
        for (row in listOf(false, true)) {
            prefs.searchCallRow = row
            query("5550123")
            runOnMainSync {
                if (row) adapter.getView(0, null, FrameLayout(home)).performClick()
                else home.findViewById<View>(R.id.search_call).performClick()
            }
            check(memory.ranks("5")[SearchMemory.dial("5550123")] == 0)
        }
    }

    private fun checkMediaLimit() {
        val files = (0..24).map { i -> targetContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "memory-test-$i.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.DATE_MODIFIED, 1000L + i)
        })!! }
        try {
            fun found(): List<Found> {
                val done = CountDownLatch(1)
                var results = emptyList<Found>()
                runOnMainSync { (field(home, "smartSearch") as SmartSearch).media("memory-test", 20) { results = it; done.countDown() } }
                check(done.await(10, TimeUnit.SECONDS))
                return results
            }
            memory.clear()
            val before = found()
            check(before.size == 20)
            val key = "media:" + android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), android.content.ContentUris.parseId(files.first()))
            check(before.none { it.key == key })
            memory.record(key)
            check(found().first().key == key) { "Old remembered file fell outside the media limit" }
        } finally { files.forEach { targetContext.contentResolver.delete(it, null, null) } }
    }

    private fun checkSettings() {
        val settings = startActivitySync(Intent(targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        runOnMainSync { settings.open(SettingsActivity.Page.SEARCH) }
        settle()
        runOnMainSync {
            descendants(settings.window.decorView).filterIsInstance<TextView>().first { it.text == "Remember search selections" }
                .let { it.requestRectangleOnScreen(android.graphics.Rect(0, 0, it.width, it.height), true) }
        }
        screenshot("settings")
        runOnMainSync {
            clickText(settings.window.decorView, "Remember search selections")
            check(!prefs.rememberSearchSelections && memory.ranks("a").isEmpty())
            clickText(settings.window.decorView, "Remember search selections")
            check(prefs.rememberSearchSelections && memory.ranks("a").isNotEmpty())
            clickText(settings.window.decorView, "Reset search memory")
            check(memory.ranks("a").isEmpty())
            settings.finish()
        }
    }

    private fun query(text: String) {
        runOnMainSync {
            val contacts = field(home, "contacts") as Contacts
            check(contacts.allowed) { "Fixture lacks contacts permission" }
            Contacts::class.java.getDeclaredField("all").apply { isAccessible = true }.set(contacts, people)
            check(field(home, "searching") == true) { "Search closed before querying $text" }
            home.findViewById<EditText>(R.id.search).apply { setText(""); setText(text) }
        }
        settle()
    }
    private fun contactRows() = adapter.rows.flatMap { if (it is DrawerAdapter.Row.Columns) listOfNotNull(it.left, it.right) else listOf(it) }.filterIsInstance<DrawerAdapter.Row.Person>()
    private fun appRows() = adapter.rows.flatMap { row -> when (row) {
        is DrawerAdapter.Row.Columns -> listOfNotNull((row.right as? DrawerAdapter.Row.Line)?.app)
        is DrawerAdapter.Row.Tiles -> row.apps
        is DrawerAdapter.Row.Line -> listOf(row.app)
        else -> emptyList()
    } }
    private fun field(instance: Any, name: String): Any = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)!!
    private fun invoke(instance: Any, name: String, types: Array<Class<*>>, vararg args: Any?) = instance.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(instance, *args)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun clickText(root: View, text: String) {
        val label = descendants(root).filterIsInstance<TextView>().first { it.text.toString() == text }
        generateSequence<View>(label) { it.parent as? View }.first { it.isClickable }.performClick()
    }
    private fun shell(command: String) { ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
    private fun settle(delay: Long = 350) { waitForIdleSync(); Thread.sleep(delay) }
    private fun screenshot(name: String) {
        settle()
        val dir = File(targetContext.getExternalFilesDir(null), "search-memory").apply { mkdirs() }
        uiAutomation.takeScreenshot().let { bitmap -> File(dir, "$shotPrefix$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
