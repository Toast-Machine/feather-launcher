package app.featherlauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import java.util.concurrent.Executors

/** Contacts for Smart Search, loaded once in the background and kept in memory. */
class Contacts(private val context: Context) {
    /**
     * A person, shown and called at [number]: their primary number, or the one a search
     * matched. [numbers] has all of them.
     */
    class Contact(
        val id: Long,
        val name: String,
        val number: String,
        val numbers: List<String>,
        val photo: Uri?,
        val starred: Boolean,
    ) {
        val words = Search.words(name)
        val digitWords = words.map(Search::toDigits)

        /** The number whose digits contain [digits], if any. */
        fun numberMatching(digits: String) = numbers.firstOrNull { n -> digits in n.filter { it.isDigit() } }

        fun withNumber(n: String) = Contact(id, name, n, numbers, photo, starred)
    }

    var all: List<Contact> = emptyList()
        private set
    var onLoaded: (() -> Unit)? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var loading = false

    val allowed get() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun load() {
        if (!allowed || loading) return
        loading = true
        io.execute {
            // Every number per person, the primary one first.
            val numbers = LinkedHashMap<Long, MutableList<String>>()
            val details = HashMap<Long, Triple<String, Uri?, Boolean>>()
            runCatching {
                context.contentResolver.query(
                    Phone.CONTENT_URI,
                    arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.PHOTO_THUMBNAIL_URI, Phone.STARRED, Phone.IS_SUPER_PRIMARY),
                    null, null, "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        val number = c.getString(2) ?: continue
                        val list = numbers.getOrPut(id) { ArrayList() }
                        val digits = number.filter { it.isDigit() }
                        // The same number saved twice (with and without +1, say) counts once.
                        if (list.any { it.filter(Char::isDigit).let { d -> d.endsWith(digits) || digits.endsWith(d) } }) continue
                        if (c.getInt(5) == 1) list.add(0, number) else list.add(number)
                        details.getOrPut(id) { Triple(name, c.getString(3)?.let(Uri::parse), c.getInt(4) == 1) }
                    }
                }
            }
            main.post {
                val next = numbers.map { (id, list) ->
                    val (name, photo, starred) = details.getValue(id)
                    Contact(id, name, list.first(), list, photo, starred)
                }
                val changed = next.map { it.id to it.numbers } != all.map { it.id to it.numbers }
                all = next
                loading = false
                if (changed) onLoaded?.invoke()
            }
        }
    }

    /** Matches by name (letters or T9) and, for digits, by phone number too. */
    fun search(raw: String): List<Contact> {
        val t9 = Search.isT9(raw)
        val query = if (t9) raw else Search.normalizeText(raw)
        return all.mapNotNull { c ->
            Search.score(query, if (t9) c.digitWords else c.words)?.let { return@mapNotNull c to it }
            // Typed digits that are part of one of their numbers: shown with that number.
            val number = if (t9 && raw.length >= 3) c.numberMatching(raw) else null
            number?.let { c.withNumber(it) to 4 }
        }.sortedWith(compareBy<Pair<Contact, Int>> { it.second }.thenBy { !it.first.starred }).map { it.first }
    }

    val favorites get() = all.filter { it.starred }
}
