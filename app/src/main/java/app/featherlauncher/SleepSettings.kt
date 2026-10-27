package app.featherlauncher

import android.content.Intent
import android.content.res.ColorStateList
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The Sleep time page in settings: when it runs, which apps it locks, and the lock screen. */
class SleepSettings(private val s: SettingsActivity) {
    private val sleep = SleepTime.get(s)

    fun build(p: Palette) {
        val content = s.content
        val on = sleep.enabled

        content.addView(s.spacer())
        content.addView(Components.group(s, p, listOf(
            Components.toggleRow(s, p, R.drawable.ic_moon, "Sleep time", on) {
                sleep.enabled = it
                s.build()
            },
        )))
        content.addView(Components.footnote(s, p,
            "Chosen apps are locked at night. Opening one shows your message and a button to open it for a few minutes."))
        if (!on) return

        if (!LauncherService.isEnabled(s)) {
            content.addView(s.heading(p, "Needs access"))
            content.addView(s.spacer())
            content.addView(Components.group(s, p, listOf(
                Components.row(s, p, R.drawable.ic_unlock, "Accessibility access", "Off", color = p.danger,
                    chevron = true, inset = Ui.SPACE_L) {
                    Setup.ACCESSIBILITY.start(s)
                },
            )))
            content.addView(Components.footnote(s, p,
                "Sleep time needs this to lock apps however they're opened. In Accessibility, open Feather Launcher and turn it on."))
        }


        content.addView(s.heading(p, "Schedule"))
        content.addView(s.spacer())
        content.addView(Components.group(s, p, listOf(
            LinearLayout(s).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(timeCell(p, R.drawable.ic_moon, "Bedtime", sleep.start) {
                    Components.timeSheet(s, p, s.overlays, "Bedtime", sleep.start) {
                        sleep.start = it
                        s.build()
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(View(s).apply { setBackgroundColor(divider(p)) },
                    LinearLayout.LayoutParams(s.dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(0, s.dp(Ui.SPACE_M), 0, s.dp(Ui.SPACE_M)) })
                addView(timeCell(p, R.drawable.ic_sunrise, "Wake up", sleep.end) {
                    Components.timeSheet(s, p, s.overlays, "Wake up", sleep.end) {
                        sleep.end = it
                        s.build()
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            },
            View(s).apply {
                setBackgroundColor(divider(p))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, s.dp(1)).apply {
                    setMargins(s.dp(Ui.SPACE_L), 0, s.dp(Ui.SPACE_L), 0)
                }
            },
            Components.dayPicker(s, p, sleep.days) { sleep.days = it },
        )))

        content.addView(s.heading(p, "Apps"))
        content.addView(s.spacer())
        val count = sleep.apps.count { pkg -> s.visibleApps.any { it.packageName == pkg } }
        content.addView(Components.group(s, p, listOf(
            Components.row(s, p, R.drawable.ic_lock, "Locked apps",
                if (count == 0) "Choose" else if (count == 1) "1 app" else "$count apps", chevron = true, inset = Ui.SPACE_L) { appsSheet() },
        )))

        content.addView(s.heading(p, "Lock screen"))
        content.addView(s.spacer())
        content.addView(Components.group(s, p, listOf(
            Components.row(s, p, R.drawable.ic_message, "Message", subtitle = sleep.message, chevron = true, inset = Ui.SPACE_L) {
                s.overlays.showInput("Message", sleep.message, SleepTime.DEFAULT_MESSAGE) {
                    sleep.message = it
                    s.build()
                }
            },
            Components.row(s, p, R.drawable.ic_hourglass, "Wait before opening", SleepTime.formatDuration(sleep.waitSeconds),
                chevron = true, inset = Ui.SPACE_L) {
                durationSheet("Wait before opening", listOf(10, 15, 30, 60, 120), sleep.waitSeconds) { sleep.waitSeconds = it }
            },
            Components.row(s, p, R.drawable.ic_time, "Opens for", SleepTime.formatDuration(sleep.openMinutes * 60),
                chevron = true, inset = Ui.SPACE_L) {
                durationSheet("Opens for", listOf(1, 2, 5, 10, 15, 30).map { it * 60 }, sleep.openMinutes * 60) { sleep.openMinutes = it / 60 }
            },
            Components.row(s, p, R.drawable.ic_history, "Cooldown", SleepTime.formatDuration(sleep.cooldownMinutes * 60),
                chevron = true, inset = Ui.SPACE_L) {
                durationSheet("Cooldown", listOf(0, 1, 2, 5, 10, 15, 30).map { it * 60 }, sleep.cooldownMinutes * 60) {
                    sleep.cooldownMinutes = it / 60
                }
            },
        )))
        val wait = SleepTime.formatDuration(sleep.waitSeconds)
        val open = SleepTime.formatDuration(sleep.openMinutes * 60)
        val cooldown = if (sleep.cooldownMinutes == 0) "" else
            " Then it can't be opened again for ${SleepTime.formatDuration(sleep.cooldownMinutes * 60)}."
        content.addView(Components.footnote(s, p,
            "After tapping the button, wait $wait without leaving the screen. The app opens for $open, then locks again.$cooldown"))
    }

    /** Half of the schedule: an icon and label over the time in large type. */
    private fun timeCell(p: Palette, icon: Int, label: String, minutes: Int, onClick: () -> Unit) = LinearLayout(s).apply {
        orientation = LinearLayout.VERTICAL
        // Inset and rounded like the group's other rows (set before the padding, which it would replace).
        background = android.graphics.drawable.InsetDrawable(Ui.pressable(s, Components.GROUP_RADIUS - 4), s.dp(4))
        setPadding(s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_M), s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_M))
        isFocusable = true
        addView(LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(s).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(p.textSecondary)
            }, LinearLayout.LayoutParams(s.dp(18), s.dp(18)).apply { marginEnd = s.dp(Ui.SPACE_S) })
            addView(TextView(s).apply {
                text = label
                style(TextStyle.Secondary, p.textSecondary)
            })
        })
        addView(TextView(s).apply {
            text = SleepTime.formatTime(s, minutes)
            style(TextStyle.Title, p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(0, s.dp(Ui.SPACE_S), 0, 0)
        })
        setOnClickListener { onClick() }
    }

    private fun divider(p: Palette) = if (p.dark) 0x1AFFFFFF else 0x14000000

    /** Every app, chosen ones first so they're easy to find and remove. */
    private fun appsSheet() {
        val chosen = sleep.apps.toMutableSet()
        val apps = s.visibleApps
            .filter { it.packageName != s.packageName }
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.packageName !in chosen }, { it.label.lowercase() }))
        Components.multiChoiceSheet(s, s.palette, s.overlays, "Locked apps",
            apps.map { e -> e.label to ImageView(s).apply { setImageBitmap(s.repo.icon(e)) } },
            apps.indices.filter { apps[it].packageName in chosen }.toSet(),
            fill = true,
            onToggle = { i, checked ->
                if (checked) chosen += apps[i].packageName else chosen -= apps[i].packageName
                sleep.apps = chosen
            },
            onClosed = { s.build() })
    }

    private fun durationSheet(title: String, seconds: List<Int>, current: Int, onPick: (Int) -> Unit) {
        Components.choiceSheet(s, s.palette, s.overlays, title, seconds.map { SleepTime.formatDuration(it) to null },
            seconds.indexOf(current)) {
            onPick(seconds[it])
            s.build()
        }
    }
}
