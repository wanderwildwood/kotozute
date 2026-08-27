package com.wanderwildwood.einkmessaging.common.util

import android.content.Context
import android.util.Log

/**
 * Access to the Kompakt's e-ink refresh controller.
 *
 * ⚠ CURRENTLY UNUSED — kept for the reverse-engineering notes below, not because anything
 * calls it. Driving [FAST] while the message list scrolled was tried on 2026-08-07 and
 * reverted: it produced no measurable improvement (jank across five samples was
 * indistinguishable from [AUTO]) and the fast waveform's ghosting made the screen visibly
 * worse to read. R8 strips this class from release builds while it stays unreferenced.
 *
 * MuditaOS ships an undocumented system service, `meink`, whose `android.meink.MeinkManager`
 * lets an app pick the waveform the panel uses to draw. Reverse-engineered from
 * `/system/framework/framework.jar` (the AIDL) and `KompaktEbookReader.apk` (the mode
 * values and how they're applied) — the reader calls `setDisplayMode(context, ordinal)`
 * with the ordinals below and logs "Applied <MODE> (<n>) on meink".
 *
 * This matters here because scrolling on this device is display-bound, not CPU-bound: a
 * Perfetto trace of a message-list scroll showed 2.7s of RenderThread time blocked in
 * `dequeueBuffer` waiting on the panel, against only 174ms of actual view binding. The
 * panel's refresh mode is the one lever that touches that.
 *
 * None of this is public API. Every method is flagged SDK/TEST-API in framework.jar, so
 * it isn't hidden-API blocked, and KompaktEbookReader declares no permission to use it —
 * but the service may still enforce a privileged caller check, and this app is not a
 * priv-app. So every call is best-effort: if anything at all goes wrong we log once and
 * disable ourselves permanently rather than throwing on a hot path.
 */
object EinkDisplayMode {

    const val AUTO = 0
    const val FAST = 1
    const val FAST_DITHER = 2
    const val TEXT = 3
    const val QUALITY = 4

    private const val TAG = "EinkDisplayMode"
    private const val SERVICE = "meink"

    /** Null until first use; false once we know the service is unusable on this device. */
    private var supported: Boolean? = null
    private var manager: Any? = null
    private var setDisplayMode: java.lang.reflect.Method? = null

    /** The mode currently applied, so repeat calls during a scroll are free. */
    private var current: Int = AUTO

    /**
     * Switch the panel to [mode]. No-op if the service is missing or refused us, and a
     * no-op if [mode] is already applied — this gets called on every scroll state change.
     */
    @Synchronized
    fun apply(context: Context, mode: Int) {
        if (supported == false || mode == current) return

        if (supported == null) {
            supported = try {
                val service = context.applicationContext.getSystemService(SERVICE)
                if (service == null) {
                    Log.i(TAG, "no '$SERVICE' system service on this device")
                    false
                } else {
                    manager = service
                    setDisplayMode = service.javaClass.getMethod(
                        "setDisplayMode",
                        Context::class.java,
                        Int::class.javaPrimitiveType
                    )
                    Log.i(TAG, "meink available via ${service.javaClass.name}")
                    true
                }
            } catch (e: Throwable) {
                Log.i(TAG, "meink unavailable: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (supported == false) return
        }

        try {
            setDisplayMode?.invoke(manager, context, mode)
            current = mode
            Log.d(TAG, "applied mode $mode")
        } catch (e: Throwable) {
            // Most likely a SecurityException from a privileged-caller check. Whatever it
            // is, stop trying — this runs on scroll and must never become a per-frame cost.
            Log.i(TAG, "setDisplayMode($mode) refused, disabling: ${e.cause ?: e}")
            supported = false
        }
    }
}
