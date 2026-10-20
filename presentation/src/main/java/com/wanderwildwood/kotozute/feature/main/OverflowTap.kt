package com.wanderwildwood.kotozute.feature.main

/**
 * Tells a choice made in the overflow menu from the second half of a double tap on its button.
 *
 * The open menu draws its first entry right under the button, where the finger still is. On
 * e-ink the menu takes a moment to appear, so a tap that seems not to have worked gets
 * repeated -- and the repeat lands on that entry. It once read the whole inbox that way, with
 * nothing chosen. Nobody reads a freshly drawn menu and picks from it this quickly, so an
 * entry chosen within [SETTLE_MS] of the tap that opened the menu is taken as a stray tap.
 *
 * Only for entries in the overflow. An icon on the bar is chosen by the very tap that lifts,
 * zero milliseconds after it, and has to go through.
 */
object OverflowTap {

    const val SETTLE_MS = 600L

    /**
     * @param fromOverflow whether the entry was in the overflow menu rather than on the bar
     * @param sinceLastTouchUp how long ago the last tap on the screen itself lifted. The
     * menu is a window of its own, so its entries never count here: the last tap is the one
     * on the button that opened it.
     */
    fun isStray(fromOverflow: Boolean, sinceLastTouchUp: Long): Boolean =
        fromOverflow && sinceLastTouchUp in 0 until SETTLE_MS
}
