package app.featherlauncher

import android.content.pm.ApplicationInfo

/** The app list's categories. Apps say what they are; common apps that don't are recognized by name. */
enum class Category(val label: String) {
    COMMUNICATION("Communication"),
    MEDIA("Media"),
    PRODUCTIVITY("Productivity"),
    TRAVEL("Travel"),
    READING("Reading"),
    GAMES("Games"),
    TOOLS("Tools"),
    OTHER("Other");

    companion object {
        private val keywords = listOf(
            COMMUNICATION to listOf("dialer", "contacts", "messaging", "mms", "whatsapp", "telegram", "signal", "android.gm",
                "mail", "textnow", "chat", "slack", "teams", "messenger", "discord", "zoom"),
            MEDIA to listOf("camera", "photos", "gallery", "music", "youtube", "spotify", "podcast", "antennapod", "shazam",
                "video", "kolvids", "audible", "netflix"),
            TRAVEL to listOf("maps", "waze", "uber", "lyft", "transit", "ravkav", "nycdot", "airbnb"),
            READING to listOf("libby", "overdrive", "kindle", "news", "torah", "daf", "books"),
            PRODUCTIVITY to listOf("calendar", "docs", "drive", "keep", "notes", "office", "calcom", "github", "claude",
                "chatgpt", "bard", "gemini", "linkedin", "tfilon", "zmanim", "saleshandy", "todo", "tasks"),
            TOOLS to listOf("settings", "calculator", "files", "clock", "vending", "keymapper", "chrome", "browser",
                "safety", "wallet", "notifilter", "screenzen", "translate", "adm", "credentialmanager", "verifier"),
        )

        fun of(packageName: String, info: ApplicationInfo?): Category {
            val pkg = packageName.lowercase()
            keywords.firstOrNull { (_, words) -> words.any { it in pkg } }?.let { return it.first }
            return when (info?.category) {
                ApplicationInfo.CATEGORY_SOCIAL -> COMMUNICATION
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> MEDIA
                ApplicationInfo.CATEGORY_GAME -> GAMES
                ApplicationInfo.CATEGORY_NEWS -> READING
                ApplicationInfo.CATEGORY_MAPS -> TRAVEL
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> PRODUCTIVITY
                ApplicationInfo.CATEGORY_ACCESSIBILITY -> TOOLS
                else -> if (info != null && info.flags and ApplicationInfo.FLAG_SYSTEM != 0) TOOLS else OTHER
            }
        }
    }
}
