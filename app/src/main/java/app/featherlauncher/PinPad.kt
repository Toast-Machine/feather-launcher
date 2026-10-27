package app.featherlauncher

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest

/**
 * Asking for the hidden apps PIN: a row of dots and a number pad in a sheet. The phone's own
 * number keys work too (the activity passes them to [PinPad.active]).
 */
class PinPad private constructor(
    private val context: Context,
    private val palette: Palette,
    private val overlays: Overlays,
    private val title: String,
    private val check: (String) -> Boolean,
    private val done: (String) -> Unit,
    /** Choosing a new PIN: nothing to check against, so a Next button moves on. */
    private val onNext: ((String) -> Unit)? = null,
) {
    private var entered = ""
    private val dots = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val message = TextView(context)

    private fun show() {
        active = this
        message.apply {
            style(TextStyle.Secondary, palette.textSecondary)
            gravity = Gravity.CENTER
            text = " "
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), dp(Ui.SPACE_L))
            addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
            addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Ui.SPACE_S)
                bottomMargin = dp(Ui.SPACE_M)
            })
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(if (onNext != null) ">" else "", "0", "<")).forEach { keys ->
                addView(LinearLayout(context).apply {
                    keys.forEach { k -> addView(key(k), LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
                })
            }
        }
        renderDots()
        overlays.showSheet(title, column) { if (active === this) active = null }
    }

    private fun key(label: String): View {
        if (label.isEmpty()) return View(context)
        val view = if (label == ">") ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_forward)
            imageTintList = ColorStateList.valueOf(palette.onAccent)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Next"
        } else if (label == "<") ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(palette.text)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Delete"
        } else TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            style(TextStyle.Title, palette.text)
        }
        return view.apply {
            background = Ui.rounded(context, if (label == ">") palette.accent else palette.surfaceHigh, Ui.RADIUS_M)
            foreground = Ui.pressable(context, Ui.RADIUS_M)
            isFocusable = true
            setOnClickListener {
                when (label) {
                    "<" -> delete()
                    ">" -> confirm()
                    else -> type(label[0])
                }
            }
        }
    }

    fun type(digit: Char) {
        if (entered.length >= MAX) return
        entered += digit
        renderDots()
        if (entered.length >= MIN && check(entered)) {
            val pin = entered
            active = null
            overlays.dismiss()
            done(pin)
        }
    }

    fun delete() {
        entered = entered.dropLast(1)
        renderDots()
    }

    /** For a PIN being set, OK confirms what's been typed so far. */
    fun submit() {
        if (entered.length < MIN) {
            message.text = "At least $MIN digits"
            return
        }
        if (!check(entered)) {
            message.text = "Wrong PIN"
            dots.performHapticFeedback(HapticFeedbackConstants.REJECT)
            entered = ""
            renderDots()
        }
    }

    private fun renderDots() {
        dots.removeAllViews()
        val count = maxOf(entered.length, MIN)
        for (i in 0 until count) {
            dots.addView(View(context).apply {
                background = Ui.rounded(context, if (i < entered.length) palette.text else palette.surfaceHigh, Ui.RADIUS_PILL)
            }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
    }

    private fun dp(v: Int) = Ui.dp(context, v)

    companion object {
        private const val MIN = 4
        private const val MAX = 8

        /** The pad on screen, if any; number keys go to it. */
        var active: PinPad? = null
            private set

        fun hash(pin: String): String =
            MessageDigest.getInstance("SHA-256").digest("launcher:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

        /** Asks for the saved PIN; [unlocked] runs once it's right. */
        fun ask(context: Context, palette: Palette, overlays: Overlays, savedHash: String, unlocked: () -> Unit) {
            var pad: PinPad? = null
            pad = PinPad(context, palette, overlays, "Enter PIN", { hash(it) == savedHash }, { unlocked() })
            pad.show()
        }

        /** Asks for a new PIN twice; [set] gets it once both match. */
        fun create(context: Context, palette: Palette, overlays: Overlays, set: (String) -> Unit) {
            PinPad(context, palette, overlays, "New PIN", { false }, {}) { first ->
                PinPad(context, palette, overlays, "Confirm PIN", { it == first }, set).show()
            }.show()
        }
    }

    /** OK on the keypad (or the Next button): moves on with a new PIN, or reports a wrong one. */
    fun confirm() {
        val next = onNext
        if (next == null) return submit()
        if (entered.length < MIN) {
            message.text = "At least $MIN digits"
            return
        }
        val pin = entered
        active = null
        overlays.dismiss()
        // The next sheet opens once this one has gone.
        dots.postDelayed({ next(pin) }, 160)
    }
}
