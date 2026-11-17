package com.wanderwildwood.kotozute.common

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsetsController
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Dark mode: white ink on a black page. The Views-only form of the file every app of this
 * shop carries (written from it by shared-dark/tools/views-variant.py; no Compose).
 *
 * The apps are drawn in two colours, so dark mode is the same drawing with the two swapped:
 * each window of the app is drawn into a layer, and the layer goes to the screen through a
 * filter that turns every colour into its opposite. Nothing else changes. The one thing done
 * beyond that: while dark mode is on, an activity asks for light bar icons, because the page
 * under the bars is now black.
 *
 * What must keep its own colours (a photograph, a map, a code a scanner has to read) is
 * marked: [keep] on a view. It is turned twice, which is where
 * it started. A dialog or popup the app makes calls [window] (or [window]).
 *
 * What decides, in this order: the app's own switch ([set]), once pressed to something else
 * than the phone says; then the phone's dark setting (KompaktOS); then Home's switch (iori),
 * for a system without one (MuditaOS), asked each time the app comes to the front.
 *
 * Started by [DarkModeInit], a provider in the manifest, before any screen is drawn.
 */
object DarkMode {

    /** Home's provider: one row, `dark` (0 or 1) and `stamp` (changes on each press of Home's switch). */
    val HOME: Uri = Uri.parse("content://com.wanderwildwood.iori.dark/state")

    private const val PREFS = "dark_mode"
    private const val KEY_ON = "on"
    private const val KEY_STAMP = "home_stamp"
    /** True while the app's own switch holds against the phone and Home. */
    private const val KEY_OWN = "own"
    /** Home's switch as last heard. */
    private const val KEY_HOME = "home_dark"

    /** Whether dark mode is on. */
    @Volatile
    var on = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val asker = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var resumed = 0

    /** Activity windows, and the bar icons each asked for before dark mode changed them. */
    private val windows = WeakHashMap<Window, Int?>()
    /** Dialogs and popups that called [window]. */
    private val others = WeakHashMap<View, Boolean>()
    /** Views that keep their own colours. */
    private val kept = WeakHashMap<View, Boolean>()

    private val inverting = Paint().apply {
        colorFilter = ColorMatrixColorFilter(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    }

    fun init(application: Application) {
        if (app != null) return
        app = application
        carryOver(application)
        on = decide(application)
        application.registerComponentCallbacks(object : ComponentCallbacks {
            // The phone's dark setting was changed while the app was up.
            override fun onConfigurationChanged(config: Configuration) = follow()
            override fun onLowMemory() {}
        })
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity.window !in windows) windows[activity.window] = null
                apply(activity.window)
            }
            override fun onActivityResumed(activity: Activity) {
                resumed++
                follow()
                apply(activity.window)
                // Once more before the first frame, after the app's own start-up code has set its bars.
                val decor = activity.window.decorView
                decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        decor.viewTreeObserver.removeOnPreDrawListener(this)
                        bars(activity.window, on)
                        return true
                    }
                })
                askHome()
                watchHome(true)
            }
            override fun onActivityPaused(activity: Activity) {
                resumed = (resumed - 1).coerceAtLeast(0)
                if (resumed == 0) watchHome(false)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) { windows.remove(activity.window) }
        })
    }

    /**
     * This app's own switch. Pressed to something else than the phone and Home say, it holds
     * until it is pressed back; pressed back, the app follows them again.
     */
    fun set(context: Context, dark: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, dark).putBoolean(KEY_OWN, dark != followed(context)).apply()
        if (Looper.myLooper() == Looper.getMainLooper()) change(dark) else main.post { change(dark) }
    }

    /** What the phone's dark setting or Home's switch asks for. */
    private fun followed(context: Context): Boolean =
        systemDark(context) || prefs(context).getBoolean(KEY_HOME, false)

    /** The phone's own dark setting. A system without one never says yes. */
    private fun systemDark(context: Context): Boolean =
        context.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun decide(context: Context): Boolean {
        val p = prefs(context)
        return if (p.getBoolean(KEY_OWN, false)) p.getBoolean(KEY_ON, false) else followed(context)
    }

    /** Takes up what the phone and Home say now, unless the app's own switch holds. */
    private fun follow() {
        val context = app ?: return
        val dark = decide(context)
        if (dark != prefs(context).getBoolean(KEY_ON, false)) prefs(context).edit().putBoolean(KEY_ON, dark).apply()
        change(dark)
    }

    /**
     * From before the phone's setting was followed, when the last press won: a switch left on
     * in an app that never heard from Home was pressed in the app, and holds; one that heard
     * from Home is taken as Home's.
     */
    private fun carryOver(context: Context) {
        val p = prefs(context)
        if (p.contains(KEY_OWN)) return
        val was = p.getBoolean(KEY_ON, false)
        val heardHome = p.getLong(KEY_STAMP, 0L) != 0L
        p.edit().putBoolean(KEY_OWN, was && !heardHome).putBoolean(KEY_HOME, was && heardHome).apply()
    }

    private fun change(dark: Boolean) {
        if (on == dark) return
        on = dark
        windows.keys.toList().forEach { apply(it) }
        others.keys.toList().forEach { layer(it, dark) }
        kept.keys.toList().forEach { keepNow(it) }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- windows ----

    private fun apply(window: Window) {
        layer(window.decorView, on)
        bars(window, on)
    }

    /** Draws [root] and everything in it through the filter, or straight again. */
    private fun layer(root: View, dark: Boolean) {
        if (dark == (root.getTag(TAG_KEY) == true)) return
        root.setLayerType(if (dark) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, if (dark) inverting else null)
        root.setTag(TAG_KEY, dark)
    }

    /** A key for a view tag that no resource id of the app can be: ids start at 0x7f, the framework's at 0x01. */
    private const val TAG_KEY = 0x7e_da_4b_01

    /**
     * Light bar icons while dark mode is on (the page under the bars is black now); the app's
     * own again when it goes off.
     */
    private fun bars(window: Window, dark: Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        val controller = window.insetsController ?: return
        val both = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        if (dark) {
            if (windows[window] == null) windows[window] = asked(window, controller.systemBarsAppearance and both)
            controller.setSystemBarsAppearance(0, both)
        } else {
            windows[window]?.let { controller.setSystemBarsAppearance(it, both) }
            windows[window] = null
        }
    }

    /** The bar icons the app asked for: through the controller, the older view flags, or its theme. */
    @Suppress("DEPRECATION")
    private fun asked(window: Window, fromController: Int): Int {
        val flags = window.decorView.systemUiVisibility
        val a = window.context.obtainStyledAttributes(intArrayOf(android.R.attr.windowLightStatusBar, android.R.attr.windowLightNavigationBar))
        val theme = try { booleanArrayOf(a.getBoolean(0, false), a.getBoolean(1, false)) } finally { a.recycle() }
        var bits = fromController
        if (flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0 || theme[0]) bits = bits or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        if (flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0 || theme[1]) bits = bits or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        return bits
    }

    /** For a window the app makes itself (a dialog, a popup): dark from its first frame, and it follows the switch. */
    fun window(anyViewInIt: View) {
        val root = anyViewInIt.rootView
        others[root] = true
        layer(root, on)
    }

    /**
     * A view that keeps its own colours in dark mode: a picture, a map, a code to be scanned.
     * Call it once, when the view is made; it follows the switch from then on.
     */
    fun keep(view: View) {
        kept[view] = true
        keepNow(view)
    }

    private fun keepNow(view: View) = layer(view, on)

    // ---- Home's switch ----

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = askHome()
    }
    private var watching = false

    private fun watchHome(watch: Boolean) {
        val context = app ?: return
        if (watch == watching) return
        watching = watch
        runCatching {
            if (watch) context.contentResolver.registerContentObserver(HOME, false, observer)
            else context.contentResolver.unregisterContentObserver(observer)
        }
    }

    /** Asks Home what its switch says. */
    private fun askHome() {
        val context = app ?: return
        asker.execute {
            runCatching {
                context.contentResolver.query(HOME, null, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use
                    val dark = c.getInt(c.getColumnIndexOrThrow("dark")) != 0
                    val stamp = c.getLong(c.getColumnIndexOrThrow("stamp"))
                    val p = prefs(context)
                    if (stamp != p.getLong(KEY_STAMP, 0L) || dark != p.getBoolean(KEY_HOME, false)) {
                        p.edit().putLong(KEY_STAMP, stamp).putBoolean(KEY_HOME, dark).apply()
                        main.post { follow() }
                    }
                }
            }
        }
    }
}

/** Starts [DarkMode] before the app's first screen. Declared in the manifest; holds no data. */
class DarkModeInit : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let { DarkMode.init(it) }
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
