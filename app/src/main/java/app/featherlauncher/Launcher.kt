package app.featherlauncher

import android.content.Context

/** Things shared by the home screen and the settings page while the app is running. */
object Launcher {
    private var repo: AppRepository? = null

    /** One app list for the whole app, so settings sees the same icons as the home screen. */
    fun repo(context: Context): AppRepository =
        repo ?: AppRepository(context.applicationContext, Prefs(context).iconShape).also { repo = it }
}
