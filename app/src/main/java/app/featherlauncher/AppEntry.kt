package app.featherlauncher

import android.content.ComponentName

class AppEntry(
    val component: ComponentName,
    val label: String,
    val updatedAt: Long,
    val category: Category = Category.OTHER,
) {
    val key: String = component.flattenToShortString()
    val packageName: String get() = component.packageName

    val words: List<String> = Search.words(label)
    val digitWords: List<String> = words.map(Search::toDigits)
}
