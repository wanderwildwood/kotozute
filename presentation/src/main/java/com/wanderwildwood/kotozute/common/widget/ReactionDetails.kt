package com.wanderwildwood.kotozute.common.widget

import android.content.Context
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.util.einkDialog

/**
 * Who gave which reaction, opened by tapping the reactions under a message.
 *
 * Signal's `ReactionsBottomSheetDialogFragment` does this with a tab per emoji over a list of
 * people. Here it is one list in the same order as the line it was opened from -- most-given
 * emoji first -- because tabs are a repaint each on e-ink and a message rarely carries more
 * reactions than fit on the panel at once. As in Signal's `ReactionRecipientsAdapter`, this
 * account's own is named "You" and tapping it takes it back.
 */
object ReactionDetails {

    /** One reaction: the emoji, who gave it as a reader would know them, and whether it is ours. */
    data class Row(val emoji: String, val name: String, val mine: Boolean)

    fun show(context: Context, rows: List<Row>, onRemoveMine: (emoji: String) -> Unit) {
        if (rows.isEmpty()) return
        val order = rows.groupBy { it.emoji }.entries
            .sortedByDescending { it.value.size }
            .flatMap { (_, group) -> group.sortedByDescending { it.mine } }
        val labels = order.map { row ->
            val who = if (row.mine) {
                context.getString(R.string.signal_reaction_you_remove)
            } else {
                row.name
            }
            "${row.emoji}  $who"
        }
        context.einkDialog()
            .setItems(labels.toTypedArray()) { _, which ->
                order[which].takeIf { it.mine }?.let { onRemoveMine(it.emoji) }
            }
            .show()
    }
}
