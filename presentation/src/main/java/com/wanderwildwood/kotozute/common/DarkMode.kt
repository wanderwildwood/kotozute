package com.wanderwildwood.kotozute.common

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
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
import android.view.Window
import android.view.WindowInsetsController
import android.view.inspector.WindowInspector
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Dark mode: white ink on a black page. The Views-only form of the file every app of this
 * shop carries (written from it by shared-dark/tools/views-variant.py; no Compose).
 *
 * The apps are drawn in two colours, so dark mode is the same drawing with the two swapped.
 * It is done where the window is put on the screen: each window of the app is drawn into a
 * layer, and the layer goes to the screen through a filter that turns every colour into its
 * opposite. Nothing in the app's own drawing changes, so a screen cannot be half dark. The
 * status bar and the navigation bar are drawn by Android over the window, so their icons are
 * asked for in the light colour while dark mode is on (the dark one over a bar the app paints dark,
 * which the filter turns light).
 *
 * What must keep its own colours (a photograph, a map, a barcode or QR code that a scanner
 * has to read) is marked: [keep] on a view. It is turned twice,
 * which is where it started.
 *
 * Each app has its own switch ([set]). Home (iori), when it is installed, has one switch for
 * all of them: an app asks Home each time it comes to the front, and takes Home's answer when
 * Home's switch has been pressed since the app last looked. So the last press wins, whichever
 * app it was made in, and an app without Home is simply its own switch.
 *
 * Started by [DarkModeInit], a provider in the manifest, before any screen is drawn.
 */
object DarkMode {

    /** Home's provider: one row, `dark` (0 or 1) and `stamp` (changes on each press of Home's switch). */
    val HOME: Uri = Uri.parse("content://com.wanderwildwood.iori.dark/state")

    private const val PREFS = "dark_mode"
    private const val KEY_ON = "on"
    private const val KEY_STAMP = "home_stamp"

    /** Whether dark mode is on. */
    @Volatile
    var on = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val asker = Executors.newSingleThreadExecutor()
    private var app: Context? = null

    /** The windows in hand, and the bar appearance each had before dark mode changed it. */
    private val windows = WeakHashMap<Window, Int?>()
    private var resumed = 0

    private val invert = ColorMatrixColorFilter(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    private val inverting = Paint().apply { colorFilter = invert }

    fun init(application: Application) {
        if (app != null) return
        app = application
        on = prefs(application).getBoolean(KEY_ON, false)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                windows[activity.window] = null
                apply(activity.window)
            }
            override fun onActivityStarted(activity: Activity) = apply(activity.window)
            override fun onActivityResumed(activity: Activity) {
                resumed++
                apply(activity.window)
                askHome()
                watchHome(true)
                if (on) sweep()
            }
            override fun onActivityPaused(activity: Activity) {
                resumed = (resumed - 1).coerceAtLeast(0)
                if (resumed == 0) watchHome(false)
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) { windows.remove(activity.window) }
        })
    }

    /** This app's own switch. */
    fun set(context: Context, dark: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, dark).apply()
        if (Looper.myLooper() == Looper.getMainLooper()) change(dark) else main.post { change(dark) }
    }

    private fun change(dark: Boolean) {
        if (on == dark) return
        on = dark
        windows.keys.toList().forEach { apply(it) }
        others().forEach { layer(it, dark) }
        kept.keys.toList().forEach { keepNow(it) }
        if (dark) sweep()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- windows ----

    private fun apply(window: Window) {
        layer(window.decorView, on)
        bars(window, on)
    }

    /** Draws [root] and everything in it through the filter, or straight again. */
    private fun layer(root: View, dark: Boolean) {
        if (dark) {
            if (root.getTag(TAG_KEY) != true) {
                root.setLayerType(View.LAYER_TYPE_HARDWARE, inverting)
                root.setTag(TAG_KEY, true)
            }
        } else if (root.getTag(TAG_KEY) == true) {
            root.setLayerType(View.LAYER_TYPE_NONE, null)
            root.setTag(TAG_KEY, false)
        }
    }

    /** A key for a view tag that no resource id of the app can be: ids start at 0x7f, the framework's at 0x01. */
    private const val TAG_KEY = 0x7e_da_4b_01

    /**
     * The bars' icons: light while dark mode is on, and back to what the app had asked for
     * when it goes off.
     */
    @Suppress("DEPRECATION")
    private fun bars(window: Window, dark: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = window.decorView.windowInsetsController ?: window.insetsController ?: return
            val both = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            if (dark) {
                if (windows[window] == null) windows[window] = asked(window, controller.systemBarsAppearance and both)
                var want = 0
                if (turnsLight(window.statusBarColor)) want = want or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                if (turnsLight(window.navigationBarColor)) want = want or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                if (controller.systemBarsAppearance and both != want) controller.setSystemBarsAppearance(want, both)
                // The older flags count as well when an app (or androidx for it) has set them.
                val decor = window.decorView
                val old = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                var oldWant = 0
                if (want and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0) oldWant = oldWant or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (want and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0) oldWant = oldWant or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                if (decor.systemUiVisibility and old != oldWant) decor.systemUiVisibility = (decor.systemUiVisibility and old.inv()) or oldWant
            } else {
                windows[window]?.let { controller.setSystemBarsAppearance(it, both) }
                windows[window] = null
            }
        } else {
            val decor = window.decorView
            val both = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            if (dark) {
                if (windows[window] == null) windows[window] = decor.systemUiVisibility and both
                var want = 0
                if (turnsLight(window.statusBarColor)) want = want or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (turnsLight(window.navigationBarColor)) want = want or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                if (decor.systemUiVisibility and both != want) decor.systemUiVisibility = (decor.systemUiVisibility and both.inv()) or want
            } else {
                windows[window]?.let { decor.systemUiVisibility = (decor.systemUiVisibility and both.inv()) or it }
                windows[window] = null
            }
        }
    }

    /**
     * A bar the app paints dark is painted in the window, so the filter turns it light, and its
     * icons then have to be the dark ones. A clear bar shows the page under it, which is dark.
     */
    private fun turnsLight(barColor: Int): Boolean {
        if (barColor ushr 24 < 0x80) return false
        val r = barColor shr 16 and 0xff; val g = barColor shr 8 and 0xff; val b = barColor and 0xff
        return (r * 299 + g * 587 + b * 114) / 1000 < 128
    }

    /**
     * The bar appearance the app asked for, wherever it asked: through the controller, through
     * the older view flags, or in its theme. The controller alone does not report the last two.
     */
    @Suppress("DEPRECATION")
    private fun asked(window: Window, fromController: Int): Int {
        var bits = fromController
        val flags = window.decorView.systemUiVisibility
        val theme = runCatching {
            val a = window.context.obtainStyledAttributes(intArrayOf(android.R.attr.windowLightStatusBar, android.R.attr.windowLightNavigationBar))
            try { booleanArrayOf(a.getBoolean(0, false), a.getBoolean(1, false)) } finally { a.recycle() }
        }.getOrDefault(booleanArrayOf(false, false))
        if (flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0 || theme[0]) bits = bits or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        if (flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0 || theme[1]) bits = bits or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        return bits
    }

    /** Every other window of the app that is up now: dialogs, menus, the selection handles. */
    private fun others(): List<View> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val own = windows.keys.map { it.decorView }
        return runCatching { WindowInspector.getGlobalWindowViews() }.getOrDefault(emptyList()).filter { it !in own }
    }

    /**
     * A dialog or a menu is a window of its own, and Android does not say when one opens.
     * While dark mode is on and the app is in front, the windows are looked over a few times a
     * second and a new one is turned dark. A dialog that calls [window] is dark from its
     * first frame and does not wait for this.
     */
    private var sweeping = false
    private fun sweep() {
        if (sweeping) return
        sweeping = true
        main.post(object : Runnable {
            override fun run() {
                if (!on || resumed == 0) { sweeping = false; return }
                others().forEach { layer(it, true) }
                // A bar's icons can be set dark again by the app's own code; put them back.
                windows.keys.toList().forEach { bars(it, true) }
                main.postDelayed(this, SWEEP_MS)
            }
        })
    }
    private const val SWEEP_MS = 200L

    /** For a window the app makes itself (a dialog, a popup): dark from its first frame. */
    fun window(anyViewInIt: View) = layer(anyViewInIt.rootView, on)

    /**
     * A view that keeps its own colours in dark mode: a picture, a map, a code to be scanned.
     * Call it once, when the view is made; it follows the switch from then on.
     */
    fun keep(view: View) {
        kept[view] = true
        keepNow(view)
    }

    private val kept = WeakHashMap<View, Boolean>()

    private fun keepNow(view: View) {
        if (on) view.setLayerType(View.LAYER_TYPE_HARDWARE, inverting) else view.setLayerType(View.LAYER_TYPE_NONE, null)
    }

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

    /** Asks Home what its switch says, and takes it if the switch was pressed since last asked. */
    private fun askHome() {
        val context = app ?: return
        asker.execute {
            runCatching {
                context.contentResolver.query(HOME, null, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use
                    val dark = c.getInt(c.getColumnIndexOrThrow("dark")) != 0
                    val stamp = c.getLong(c.getColumnIndexOrThrow("stamp"))
                    val p = prefs(context)
                    if (stamp != p.getLong(KEY_STAMP, 0L)) {
                        p.edit().putLong(KEY_STAMP, stamp).putBoolean(KEY_ON, dark).apply()
                        main.post { change(dark) }
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
