package com.wanderwildwood.kotozute.common

import android.app.Activity
import android.app.Dialog
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.inspector.WindowInspector
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import java.util.WeakHashMap

/**
 * Messaging's ways into [DarkMode.window]. Dark mode turns each activity window by itself, but a
 * dialog, a picker or a popup menu is a window of its own, and only one that is handed to
 * [DarkMode.window] turns with it.
 */

/** Dark with the app from its first frame. Call before show(); einkDialog() does it for its dialogs. */
fun <D : Dialog> D.darkWindow(): D = apply { window?.decorView?.let(DarkMode::window) }

/**
 * The toolbar's overflow menu (⋮). appcompat makes that popup inside itself and never hands it
 * out, so its button is taken over: it opens the menu as before, and once the popup's window is
 * up, and before its first frame, the app's windows other than its screens go through
 * [DarkMode.window]. Called from QkActivity.setSupportActionBar, so every screen's bar has it.
 */
object DarkOverflow {

    private val hooked = WeakHashMap<View, Boolean>()

    fun watch(toolbar: Toolbar) {
        if (hooked.put(toolbar, true) == true) return
        // The overflow button is made, and made again, as the menu is laid out.
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> hook(toolbar) }
        hook(toolbar)
    }

    private fun hook(toolbar: Toolbar) {
        for (i in 0 until toolbar.childCount) {
            val menuView = toolbar.getChildAt(i) as? ActionMenuView ?: continue
            for (j in 0 until menuView.childCount) {
                val button = menuView.getChildAt(j)
                if ((button.layoutParams as? ActionMenuView.LayoutParams)?.isOverflowButton != true) continue
                if (hooked.put(button, true) == true) continue
                button.setOnClickListener {
                    button.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    toolbar.showOverflowMenu()
                    // showOverflowMenu posts the popup's opening; this runs after it.
                    toolbar.post { darkenPopups(toolbar) }
                }
            }
        }
    }

    /** Every window of the app that is not one of its screens: here, the menu just opened. */
    private fun darkenPopups(anyView: View) {
        if (Build.VERSION.SDK_INT < 29) return
        val screen = anyView.rootView
        runCatching { WindowInspector.getGlobalWindowViews() }.getOrDefault(emptyList())
            .filter { it !== screen && !isScreen(it) }
            .forEach { DarkMode.window(it) }
    }

    private fun isScreen(root: View): Boolean {
        var c = root.context
        while (c is ContextWrapper) {
            if (c is Activity) return c.window?.decorView === root
            c = c.baseContext
        }
        return false
    }
}
