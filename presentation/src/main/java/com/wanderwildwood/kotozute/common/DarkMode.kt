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
 * asked for in the light colour while dark mode is on. A bar the app paints dark is painted in
 * its opposite, which the filter turns back, so every bar is dark in dark mode; a see-through bar
 * over something the filter does not reach (a map, a camera) keeps the icons the app asked for.
 *
 * What must keep its own colours (a photograph, a map, a barcode or QR code that a scanner
 * has to read) is marked: [keep] on a view. It is turned twice,
 * which is where it started.
 *
 * What decides, in this order: the app's own switch ([set]), once it has been pressed to say
 * something else than the phone does; then the phone's dark setting, where the system has one
 * (KompaktOS); then Home (iori), which has one switch for every app where the system has none
 * (MuditaOS). An app asks Home each time it comes to the front. Pressing the app's switch back
 * to what the phone or Home says lets go of it, and the app follows them again.
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
        carryOver(application)
        on = decide(application)
        application.registerComponentCallbacks(object : ComponentCallbacks {
            // The phone's dark setting was changed while the app was up.
            override fun onConfigurationChanged(config: Configuration) = follow()
            override fun onLowMemory() {}
        })
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                windows[activity.window] = null
                apply(activity.window)
            }
            override fun onActivityStarted(activity: Activity) = apply(activity.window)
            override fun onActivityResumed(activity: Activity) {
                resumed++
                follow()
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
        scrims(window, dark)
        paint(window, dark)
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = window.decorView.windowInsetsController ?: window.insetsController ?: return
            val both = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            if (dark) {
                if (windows[window] == null) windows[window] = asked(window, controller.systemBarsAppearance and both)
                val was = windows[window] ?: 0
                var want = 0
                if (lightBar(window, true, was and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0)) want = want or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                if (lightBar(window, false, was and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0)) want = want or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
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
                val was = windows[window] ?: 0
                var want = 0
                if (lightBar(window, true, was and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0)) want = want or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (lightBar(window, false, was and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0)) want = want or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                if (decor.systemUiVisibility and both != want) decor.systemUiVisibility = (decor.systemUiVisibility and both.inv()) or want
            } else {
                windows[window]?.let { decor.systemUiVisibility = (decor.systemUiVisibility and both.inv()) or it }
                windows[window] = null
            }
        }
    }

    /**
     * Whether a bar colour is opaque and dark, so the filter would turn it light. A clear bar
     * shows the page under it, which is dark.
     */
    private fun turnsLight(barColor: Int): Boolean {
        if (barColor ushr 24 < 0x80) return false
        val r = barColor shr 16 and 0xff; val g = barColor shr 8 and 0xff; val b = barColor and 0xff
        return (r * 299 + g * 587 + b * 114) / 1000 < 128
    }

    /**
     * Whether a bar takes the dark icons (Android's "light bar") while dark mode is on. A bar
     * the app paints goes by its colour, turned (after [paint], always dark: the light icons). A see-through bar shows what is under it: the
     * page, which the filter has turned dark, unless what is there is drawn outside the filter
     * (a SurfaceView, as a map or a camera draws in, or a view kept in its own colours). Then
     * the bar is as the app asked for it without dark mode ([asked]).
     */
    private fun lightBar(window: Window, status: Boolean, asked: Boolean): Boolean {
        val color = if (status) window.statusBarColor else window.navigationBarColor
        if (color ushr 24 >= 0x80) return turnsLight(color)
        val decor = window.decorView
        val bar = barRect(decor, status) ?: return false
        return walk(decor, bar, android.graphics.Rect()) == true && asked
    }

    /** Where the bar lies on the window, or null while it is hidden or not laid out yet. */
    @Suppress("DEPRECATION")
    private fun barRect(decor: View, status: Boolean): android.graphics.Rect? {
        val insets = decor.rootWindowInsets ?: return null
        val w = decor.width; val h = decor.height
        if (w <= 0 || h <= 0) return null
        var l: Int; var t: Int; var r: Int; var b: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(if (status) android.view.WindowInsets.Type.statusBars() else android.view.WindowInsets.Type.navigationBars())
            l = i.left; t = i.top; r = i.right; b = i.bottom
        } else if (status) {
            l = 0; t = insets.stableInsetTop; r = 0; b = 0
        } else {
            l = insets.stableInsetLeft; t = 0; r = insets.stableInsetRight; b = insets.stableInsetBottom
        }
        return when {
            status && t > 0 -> android.graphics.Rect(0, 0, w, t)
            !status && b > 0 -> android.graphics.Rect(0, h - b, w, h)
            !status && r > 0 -> android.graphics.Rect(w - r, 0, w, h)
            !status && l > 0 -> android.graphics.Rect(0, 0, l, h)
            else -> null
        }
    }

    /**
     * What is on top under [bar], in drawing order: true for something drawn outside the filter
     * (a SurfaceView, a kept view), false for an opaque view of the page, null for nothing that
     * covers the bar. Something covers it when it holds at least half of the bar.
     */
    private fun walk(view: View, bar: android.graphics.Rect, r: android.graphics.Rect): Boolean? {
        if (view.visibility != View.VISIBLE || view.alpha == 0f) return null
        var top: Boolean? = null
        if (view.getGlobalVisibleRect(r) && r.intersect(bar) && r.width().toLong() * r.height() * 2 >= bar.width().toLong() * bar.height()) {
            if (view is android.view.SurfaceView || kept.containsKey(view)) return true
            if (view.background?.opacity == android.graphics.PixelFormat.OPAQUE) top = false
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), bar, r)?.let { top = it }
        }
        return top
    }

    /**
     * The bar colours, per window: the app's status bar colour, what dark mode put there, and
     * the same two for the navigation bar.
     */
    private val painted = WeakHashMap<Window, IntArray>()
    private const val UNSET = 0x00_12_34_56

    /**
     * A bar the app paints dark is painted in the window, so the filter would turn it into a
     * light band at the top or foot of a dark screen. While dark mode is on it is painted in its
     * opposite, which the filter turns back to the app's own dark; when dark mode goes off it
     * gets the app's colour back. A colour the app sets meanwhile is taken as its new one.
     */
    private fun paint(window: Window, dark: Boolean) {
        val p = painted[window]
        if (dark) {
            val q = p ?: intArrayOf(UNSET, UNSET, UNSET, UNSET).also { painted[window] = it }
            val s = window.statusBarColor
            if (s != q[1]) {
                q[0] = s; q[1] = if (turnsLight(s)) turned(s) else s
                if (q[1] != s) window.statusBarColor = q[1]
            }
            val n = window.navigationBarColor
            if (n != q[3]) {
                q[2] = n; q[3] = if (turnsLight(n)) turned(n) else n
                if (q[3] != n) window.navigationBarColor = q[3]
            }
        } else if (p != null) {
            painted.remove(window)
            if (p[1] != p[0] && window.statusBarColor == p[1]) window.statusBarColor = p[0]
            if (p[3] != p[2] && window.navigationBarColor == p[3]) window.navigationBarColor = p[2]
        }
    }

    private fun turned(color: Int): Int = (color and 0xff000000.toInt()) or (color.inv() and 0xffffff)

    /**
     * Android lays a scrim over a see-through bar for its icons' sake. It is drawn in the window,
     * so the filter turns it to a grey band over the dark page; it is put away while dark mode
     * is on, and back as the app had it when dark mode goes off.
     */
    private val contrast = WeakHashMap<Window, BooleanArray>()

    private fun scrims(window: Window, dark: Boolean) {
        if (Build.VERSION.SDK_INT < 29) return
        if (dark) {
            if (contrast[window] == null) contrast[window] = booleanArrayOf(window.isStatusBarContrastEnforced, window.isNavigationBarContrastEnforced)
            if (window.isStatusBarContrastEnforced) window.isStatusBarContrastEnforced = false
            if (window.isNavigationBarContrastEnforced) window.isNavigationBarContrastEnforced = false
        } else {
            contrast.remove(window)?.let {
                window.isStatusBarContrastEnforced = it[0]
                window.isNavigationBarContrastEnforced = it[1]
            }
        }
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
