package com.wanderwildwood.kotozute.feature.lock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.DarkBar
import com.wanderwildwood.kotozute.common.QKApplication
import com.wanderwildwood.kotozute.lock.AppLock
import timber.log.Timber
import java.util.Arrays

/**
 * Asks for the passphrase while the lock is on ([AppLock]). Shown in front of every screen
 * by the refused start in [QKApplication], the same way the "messages are locked" screen is.
 *
 * No screenshots or recents thumbnail of this screen or what follows it being typed.
 */
class UnlockActivity : Activity() {

    private lateinit var field: EditText
    private lateinit var status: TextView
    private lateinit var button: View
    @Volatile private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!AppLock.isLocked(this)) {
            openApp()
            return
        }
        setContentView(R.layout.unlock_activity)
        DarkBar.watch(this)
        field = findViewById(R.id.unlockPassphrase)
        status = findViewById(R.id.unlockStatus)
        button = findViewById(R.id.unlockButton)
        button.setOnClickListener { attempt() }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { attempt(); true } else false
        }
    }

    private fun attempt() {
        if (working) return
        val text = field.text
        if (text.isNullOrEmpty()) return
        val passphrase = CharArray(text.length).also { text.getChars(0, text.length, it, 0) }
        field.setText("")
        working = true
        status.setText(R.string.lock_unlocking)
        // About a second of Argon2: off the main thread, and one attempt at a time.
        Thread {
            val ok = runCatching { AppLock.unlock(applicationContext, passphrase) }
                .onFailure { Timber.e(it, "lock: unlock failed") }
                .getOrDefault(false)
            Arrays.fill(passphrase, '\u0000')
            runOnUiThread {
                working = false
                if (ok) {
                    (application as QKApplication).unlocked()
                    openApp()
                } else {
                    status.setText(R.string.lock_wrong_passphrase)
                }
            }
        }.start()
    }

    private fun openApp() {
        packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?.let { runCatching { startActivity(it) } }
        finish()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, UnlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
