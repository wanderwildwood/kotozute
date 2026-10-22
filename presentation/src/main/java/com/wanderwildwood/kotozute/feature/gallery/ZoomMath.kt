package com.wanderwildwood.kotozute.feature.gallery

import kotlin.math.min

/**
 * The scale, counted the way PhotoView counts it (1 = the picture fitted to the view), at which
 * one picture pixel is one screen pixel.
 */
fun oneToOneScale(viewW: Float, viewH: Float, pictureW: Int, pictureH: Int): Float {
    val fit = min(viewW / pictureW, viewH / pictureH)
    return 1f / fit
}

/**
 * Where a double tap goes. Enlarged already: back to fitted. Otherwise: to one picture pixel per
 * screen pixel. A picture smaller than the view is already past 1:1 when fitted, and one hardly
 * bigger than the view would show nothing more at 1:1, so those go to twice the fitted size.
 */
fun doubleTapTarget(current: Float, viewW: Float, viewH: Float, pictureW: Int, pictureH: Int): Float {
    if (current > 1.02f) return 1f
    val oneToOne = oneToOneScale(viewW, viewH, pictureW, pictureH)
    return if (oneToOne > 1.1f) oneToOne else 2f
}
