package app.featherlauncher

import android.content.Context
import org.json.JSONArray

/** Recent selections, shared by every search action. Only identities are kept, never queries. */
class SearchMemory(context: Context) {
    private val prefs = Prefs(context)
    private val sp = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private fun recent(): List<String> = runCatching {
        val array = JSONArray(sp.getString("recent", "[]"))
        (0 until minOf(array.length(), MAX_RESULTS)).map { array.getString(it) }.distinct()
    }.getOrDefault(emptyList())

    fun record(key: String) {
        if (!prefs.rememberSearchSelections) return
        val next = (listOf(key) + recent().filter { it != key }).take(MAX_RESULTS)
        sp.edit().putString("recent", JSONArray(next).toString()).apply()
    }

    fun clear() = sp.edit().clear().apply()

    /** A snapshot for this refresh. Blank searches keep their existing favorites/frequent order. */
    fun ranks(raw: String): Map<String, Int> =
        if (raw.isBlank() || !prefs.rememberSearchSelections) emptyMap()
        else recent().withIndex().associate { it.value to it.index }

    companion object {
        const val STORE = "search_memory"
        private const val MAX_RESULTS = 500

        /** Stable sort: unremembered results retain the search's own relevance ordering. */
        fun <T> promote(results: List<T>, ranks: Map<String, Int>, key: (T) -> String): List<T> =
            if (ranks.isEmpty()) results else results.sortedBy { ranks[key(it)] ?: Int.MAX_VALUE }

        fun app(key: String) = "app:$key"
        // Names distinguish people who share a number; numbers survive provider-ID changes on restore.
        fun contact(contact: Contacts.Contact): String =
            "contact:${Search.normalizeText(contact.name)}:" + contact.numbers.map { it.filter(Char::isDigit) }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(",")
        fun dial(number: String) = "dial:$number"
    }
}
