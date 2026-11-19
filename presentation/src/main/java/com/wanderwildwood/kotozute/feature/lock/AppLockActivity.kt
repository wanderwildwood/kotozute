package com.wanderwildwood.kotozute.feature.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.util.einkDialog
import com.wanderwildwood.kotozute.common.widget.PreferenceView
import com.wanderwildwood.kotozute.lock.AppLock
import com.wanderwildwood.kotozute.lock.AppLock.lockAfterMinutes
import com.wanderwildwood.kotozute.lock.AppLock.wipeMemoryOnLock
import dagger.android.AndroidInjection
import timber.log.Timber
import java.util.Arrays

/**
 * Settings for the passphrase lock ([AppLock]). Off by default; the switch's summary says
 * plainly what it costs, because the cost is real: while locked, Signal messages and calls
 * wait and Desktop Sync stops.
 */
class AppLockActivity : QkThemedActivity() {

    private lateinit var on: PreferenceView
    private lateinit var after: PreferenceView
    private lateinit var wipe: PreferenceView
    private lateinit var change: PreferenceView
    private lateinit var now: PreferenceView

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.lock_settings_activity)
        title = getString(R.string.lock_settings_title)
        showBackButton(true)
        on = findViewById(R.id.lockOn)
        after = findViewById(R.id.lockAfter)
        wipe = findViewById(R.id.lockWipe)
        change = findViewById(R.id.lockChange)
        now = findViewById(R.id.lockNow)
        on.setOnClickListener { if (AppLock.isOn(this)) askToTurnOff() else askToTurnOn() }
        after.setOnClickListener { chooseAfter() }
        wipe.setOnClickListener { wipeMemoryOnLock = !wipeMemoryOnLock; render() }
        change.setOnClickListener { askToChange() }
        now.setOnClickListener { LockController.lockNow(this, "lock now") }
        render()
    }

    private fun render() {
        val isOn = AppLock.isOn(this)
        on.checkbox.isChecked = isOn
        after.summary = afterLabel(lockAfterMinutes)
        wipe.checkbox.isChecked = wipeMemoryOnLock
        listOf(after, wipe, change, now).forEach { it.visibility = if (isOn) android.view.View.VISIBLE else android.view.View.GONE }
    }

    private val afterChoices = intArrayOf(AppLock.ON_SCREEN_OFF, 1, 5, 15, 30, 60, AppLock.MANUAL_ONLY)

    private fun afterLabel(minutes: Int): String = when (minutes) {
        AppLock.ON_SCREEN_OFF -> getString(R.string.lock_after_screen_off)
        AppLock.MANUAL_ONLY -> getString(R.string.lock_after_manual)
        else -> resources.getQuantityString(R.plurals.lock_after_minutes, minutes, minutes)
    }

    private fun chooseAfter() {
        val labels = afterChoices.map(::afterLabel).toTypedArray()
        einkDialog()
            .setTitle(R.string.lock_after_title)
            .setSingleChoiceItems(labels, afterChoices.indexOf(lockAfterMinutes).coerceAtLeast(0)) { d, i ->
                lockAfterMinutes = afterChoices[i]
                d.dismiss()
                render()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun passwordField(hint: Int): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setHint(hint)
        importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun column(vararg fields: EditText): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        fields.forEach(::addView)
    }

    private fun EditText.take(): CharArray {
        val t = text
        return CharArray(t.length).also { t.getChars(0, t.length, it, 0) }.also { setText("") }
    }

    private fun askToTurnOn() {
        val first = passwordField(R.string.lock_passphrase_hint)
        val second = passwordField(R.string.lock_passphrase_again_hint)
        einkDialog()
            .setTitle(R.string.lock_turn_on_title)
            .setMessage(R.string.lock_turn_on_message)
            .setView(column(first, second))
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(R.string.lock_turn_on_confirm) { _, _ ->
                val a = first.take()
                val b = second.take()
                when {
                    a.size < MIN_LENGTH -> say(R.string.lock_too_short)
                    !a.contentEquals(b) -> say(R.string.lock_mismatch)
                    else -> work(R.string.lock_failed, {
                        AppLock.turnOn(applicationContext, a)
                        LockController.watch(application)
                        true
                    }) { Arrays.fill(a, '\u0000') }
                }
                Arrays.fill(b, '\u0000')
            }
            .show()
    }

    private fun askToTurnOff() {
        val field = passwordField(R.string.lock_passphrase_hint)
        einkDialog()
            .setTitle(R.string.lock_turn_off_title)
            .setView(column(field))
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(R.string.lock_turn_off_confirm) { _, _ ->
                val p = field.take()
                work(R.string.lock_wrong_passphrase, { AppLock.turnOff(applicationContext, p) }) { Arrays.fill(p, '\u0000') }
            }
            .show()
    }

    private fun askToChange() {
        val old = passwordField(R.string.lock_old_passphrase_hint)
        val first = passwordField(R.string.lock_new_passphrase_hint)
        val second = passwordField(R.string.lock_passphrase_again_hint)
        einkDialog()
            .setTitle(R.string.lock_change_title)
            .setView(column(old, first, second))
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(R.string.lock_change_confirm) { _, _ ->
                val o = old.take(); val a = first.take(); val b = second.take()
                when {
                    a.size < MIN_LENGTH -> say(R.string.lock_too_short)
                    !a.contentEquals(b) -> say(R.string.lock_mismatch)
                    else -> work(R.string.lock_wrong_passphrase, { AppLock.change(applicationContext, o, a) }) {
                        Arrays.fill(o, '\u0000'); Arrays.fill(a, '\u0000')
                    }
                }
                Arrays.fill(b, '\u0000')
            }
            .show()
    }

    /** Argon2 takes about a second (twice when benchmarking): off the main thread. */
    private fun work(failure: Int, job: () -> Boolean, cleanup: () -> Unit) {
        val wait = einkDialog().setMessage(R.string.lock_working).setCancelable(false).show()
        Thread {
            val ok = runCatching(job).onFailure { Timber.e(it, "lock: settings change failed") }.getOrDefault(false)
            cleanup()
            runOnUiThread {
                wait.dismiss()
                if (!ok) say(failure)
                render()
            }
        }.start()
    }

    private fun say(message: Int) {
        einkDialog().setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }

    companion object {
        private const val MIN_LENGTH = 6
        fun intent(context: Context): Intent = Intent(context, AppLockActivity::class.java)
    }
}
