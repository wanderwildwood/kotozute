package com.wanderwildwood.kotozute.common.util

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.darkWindow

/**
 * An AlertDialog drawn as STYLE.md's `EInkDialog`: no scrim, no animation, a white frame with a
 * 2dp rim of ink and a 12dp corner. Every slot of AlertDialog's builder is kept, so a dialog
 * moves over by changing its first line. See the `EInkDialog` style. In dark mode it is dark
 * from its first frame, like the screen under it.
 */
fun Context.einkDialog(): AlertDialog.Builder = EInkBuilder(this)

private class EInkBuilder(context: Context) : AlertDialog.Builder(context, R.style.EInkDialog) {
    override fun create(): AlertDialog = super.create().darkWindow()
}
