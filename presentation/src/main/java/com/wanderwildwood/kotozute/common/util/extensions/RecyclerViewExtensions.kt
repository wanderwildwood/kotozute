package com.wanderwildwood.kotozute.common.util.extensions

import androidx.recyclerview.widget.RecyclerView

/**
 * No motion when a row arrives or leaves, per STYLE.md: "Nothing animates."
 *
 * RecyclerView's default animator fades and slides every inserted row. On a panel that redraws in
 * full, that is an extra refresh and a smear before the row that was wanted appears, and it is
 * most visible exactly where it matters most: a message landing in a thread, a conversation
 * moving to the top of the inbox.
 *
 * ⚠ It also removes a hazard rather than only a cosmetic. An item animator keeps a ViewHolder
 * alive for the length of the animation, and over a **live Realm collection** that ViewHolder can
 * outlive the row it draws -- which is the shape of the crash this app has already had once from
 * an adapter over live Realm objects.
 *
 * Called from the adapter rather than the view, because an adapter knows it is a list and every
 * screen that shows one goes through an adapter; setting it at each of the nineteen call sites
 * that build a RecyclerView is the version that gets forgotten at the twentieth.
 */
fun RecyclerView.stopAnimatingItems() {
    itemAnimator = null
}
