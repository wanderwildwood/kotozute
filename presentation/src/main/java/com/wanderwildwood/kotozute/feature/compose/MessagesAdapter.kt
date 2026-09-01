/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wanderwildwood.kotozute.feature.compose

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import com.wanderwildwood.kotozute.common.util.LongClickLinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.net.toUri
import com.jakewharton.rxbinding2.view.clicks
import com.wanderwildwood.kotozute.common.QkMediaPlayer
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkRealmAdapter
import com.wanderwildwood.kotozute.common.base.QkViewHolder
import com.wanderwildwood.kotozute.common.util.Colors
import com.wanderwildwood.kotozute.common.util.DateFormatter
import com.wanderwildwood.kotozute.common.util.TextViewStyler
import com.wanderwildwood.kotozute.common.util.extensions.dpToPx
import com.wanderwildwood.kotozute.common.util.extensions.setBackgroundTint
import com.wanderwildwood.kotozute.common.util.extensions.setPadding
import com.wanderwildwood.kotozute.common.util.extensions.setTint
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.common.util.extensions.withAlpha
import com.wanderwildwood.kotozute.compat.SubscriptionManagerCompat
import com.wanderwildwood.kotozute.extensions.isSmil
import com.wanderwildwood.kotozute.extensions.isText
import com.wanderwildwood.kotozute.extensions.joinTo
import com.wanderwildwood.kotozute.extensions.millisecondsToMinutes
import com.wanderwildwood.kotozute.extensions.truncateWithEllipses
import com.wanderwildwood.kotozute.feature.compose.BubbleUtils.canGroup
import com.wanderwildwood.kotozute.feature.compose.BubbleUtils.getBubble
import com.wanderwildwood.kotozute.feature.compose.part.PartsAdapter
import com.wanderwildwood.kotozute.feature.extensions.isEmojiOnly
import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.model.Message
import com.wanderwildwood.kotozute.model.Recipient
import com.wanderwildwood.kotozute.util.PhoneNumberUtils
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import io.realm.RealmResults
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.wanderwildwood.kotozute.common.widget.AvatarView
import com.wanderwildwood.kotozute.common.widget.QkTextView
import com.wanderwildwood.kotozute.common.widget.TightTextView
import androidx.recyclerview.widget.RecyclerView


/**
 * The views of one message row, found once and kept.
 *
 * The incoming and outgoing layouts do not share every id - only outgoing has a cancel button, a
 * body box or a resend icon, and only incoming has an avatar - so the ones belonging to a single
 * direction are nullable, and asking for the wrong one gives null rather than throwing.
 */
class MessageViewHolder(view: View) : QkViewHolder(view) {
    val timestamp: QkTextView = view.findViewById(R.id.timestamp)
    val sim: ImageView = view.findViewById(R.id.sim)
    val simIndex: QkTextView = view.findViewById(R.id.simIndex)
    val parts: RecyclerView = view.findViewById(R.id.parts)
    val body: TightTextView = view.findViewById(R.id.body)
    val reactions: LinearLayout = view.findViewById(R.id.reactions)
    val reactionText: TextView = view.findViewById(R.id.reactionText)
    val status: QkTextView = view.findViewById(R.id.status)

    val avatar: AvatarView? = view.findViewById(R.id.avatar)
    val cancelFrame: FrameLayout? = view.findViewById(R.id.cancelFrame)
    val cancel: ProgressBar? = view.findViewById(R.id.cancel)
    val cancelIcon: ImageView? = view.findViewById(R.id.cancelIcon)
    val bodyBox: LinearLayout? = view.findViewById(R.id.bodyBox)
    val sendNowIcon: ImageView? = view.findViewById(R.id.sendNowIcon)
    val resendIcon: ImageView? = view.findViewById(R.id.resendIcon)
}

class MessagesAdapter @Inject constructor(
    subscriptionManager: SubscriptionManagerCompat,
    private val context: Context,
    private val colors: Colors,
    private val dateFormatter: DateFormatter,
    private val partsAdapterProvider: Provider<PartsAdapter>,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val prefs: Preferences,
    private val textViewStyler: TextViewStyler,
) : QkRealmAdapter<Message, MessageViewHolder>() {
    class AudioState(
        var partId: Long = -1,
        var state: QkMediaPlayer.PlayingState = QkMediaPlayer.PlayingState.Stopped,
        var seekBarUpdater: Disposable? = null,
        var viewHolder: QkViewHolder? = null
    )

    companion object {
        private const val VIEW_TYPE_MESSAGE_IN = 0
        private const val VIEW_TYPE_MESSAGE_OUT = 1

        private const val MAX_MESSAGE_DISPLAY_LENGTH = 5000
    }

    // click events passed back to compose view model
    val partClicks: Subject<Long> = PublishSubject.create()
    val messageLinkClicks: Subject<Uri> = PublishSubject.create()
    val cancelSendingClicks: Subject<Long> = PublishSubject.create()
    val sendNowClicks: Subject<Long> = PublishSubject.create()
    val resendClicks: Subject<Long> = PublishSubject.create()
    val partContextMenuRegistrar: Subject<View> = PublishSubject.create()

    var data: Pair<Conversation, RealmResults<Message>>? = null
        set(value) {
            if (field === value) return

            field = value
            contactCache.clear()
            textCache.clear()

            updateData(value?.second)
        }

    /**
     * Safely return the conversation, if available
     */
    private val conversation: Conversation?
        get() = data?.first?.takeIf { it.isValid }

    private val contactCache = ContactCache()
    private val expanded = HashMap<Long, Boolean>()
    /**
     * The per-message text work that's worth doing only once: walking the Realm parts to
     * get the body, truncating it, running Linkify, and testing whether it's emoji-only.
     * Invalidated wholesale with [textCache] whenever [data] is replaced.
     */
    private data class RenderedBody(
        val spans: CharSequence,
        val emojiOnly: Boolean,
        val truncated: Boolean
    )

    private val textCache = HashMap<Long, RenderedBody>()
    private val subs = subscriptionManager.activeSubscriptionInfoList

    var theme: Colors.Theme = colors.theme()

    private val audioState = AudioState()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        // Use the parent's context to inflate the layout, otherwise link clicks will crash the app
        val inflater = LayoutInflater.from(parent.context)

        val view = if (viewType == VIEW_TYPE_MESSAGE_OUT) {
            inflater.inflate(R.layout.message_list_item_out, parent,false).apply {
                findViewById<ImageView>(R.id.cancelIcon).setTint(theme.theme)
                findViewById<ProgressBar>(R.id.cancel).setTint(theme.theme)
                findViewById<ImageView>(R.id.sendNowIcon).setTint(theme.theme)
                findViewById<ImageView>(R.id.resendIcon).setTint(theme.theme)
            }
        } else
            inflater.inflate(R.layout.message_list_item_in, parent, false)

        view.findViewById<TightTextView>(R.id.body).hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE

        // register recycler view with compose activity for context menus
        partContextMenuRegistrar.onNext(view.findViewById<RecyclerView>(R.id.parts))

        return MessageViewHolder(view).apply {
            val longClickListener = View.OnLongClickListener {
                getItem(adapterPosition)?.let {
                    toggleSelection(it.id)
                    view.isActivated = isSelected(it.id)
                }
                true
            }

            val clickListener = View.OnClickListener {
                getItem(adapterPosition)?.let {
                    when (toggleSelection(it.id, false)) {
                        true -> view.isActivated = isSelected(it.id)
                        false -> {
                            expanded[it.id] = view.findViewById<QkTextView>(R.id.status).visibility != View.VISIBLE
                            notifyItemChanged(adapterPosition)
                        }
                    }
                }
            }

            view.setOnClickListener(clickListener)
            view.setOnLongClickListener(longClickListener)

            // Also set listeners on body to ensure long press works on message text
            view.findViewById<TightTextView>(R.id.body).setOnLongClickListener(longClickListener)
            view.findViewById<TightTextView>(R.id.body).setOnClickListener(clickListener)
        }
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = getItem(position) ?: return
        val previous = if (position == 0) null else getItem(position - 1)
        val next = if (position == itemCount - 1) null else getItem(position + 1)

        val theme = when (message.isOutgoingMessage()) {
            true -> colors.theme()
            false -> colors.theme(contactCache[message.address])
        }

        // Update the selected state
        holder.containerView.isActivated = isSelected(message.id) || highlight == message.id

        // bind the cancelFrame (cancel button) and send now button
        holder.cancelFrame?.let { cancelFrame ->
            holder.sendNowIcon?.let { sendNowIcon ->
                val isCancellable = message.isSending() && message.date > System.currentTimeMillis()

                if (isCancellable) {
                    cancelFrame.visibility = View.VISIBLE
                    sendNowIcon.visibility = View.VISIBLE

                    cancelFrame.setOnClickListener { cancelSendingClicks.onNext(message.id) }
                    sendNowIcon.setOnClickListener {  sendNowClicks.onNext(message.id) }

                    holder.cancel?.progress = 2

                    val delay = when (prefs.sendDelay.get()) {
                        Preferences.SEND_DELAY_SHORT -> 3000
                        Preferences.SEND_DELAY_MEDIUM -> 5000
                        Preferences.SEND_DELAY_LONG -> 10000
                        else -> 0
                    }
                    val progress =
                        (1 - (message.date - System.currentTimeMillis()) / delay.toFloat()) * 100

                    ObjectAnimator.ofInt(holder.cancel, "progress", progress.toInt(), 100)
                        .setDuration(message.date - System.currentTimeMillis())
                        .start()
                }
                else {
                    cancelFrame.visibility = View.GONE
                    sendNowIcon.visibility = View.GONE

                    cancelFrame.setOnClickListener(null)
                    sendNowIcon.setOnClickListener(null)
                }
            }
        }

        // bind the resend icon view
        holder.resendIcon?.let { resendIcon ->
            if (message.isFailedMessage()) {
                resendIcon.visibility = View.VISIBLE
                // A plain click listener, not clicks().subscribe {}: the Rx version added a
                // fresh, undisposed subscription on every bind, so a failed message that got
                // scrolled past repeatedly would fire resendClicks once per accumulated
                // subscription. setOnClickListener simply replaces the previous one.
                resendIcon.setOnClickListener {
                    resendClicks.onNext(message.id)
                    resendIcon.visibility = View.GONE
                }
            } else {
                resendIcon.visibility = View.GONE
                resendIcon.setOnClickListener(null)
            }
        }

        // Everything derived from the body text is built once per message and cached.
        // It walks the Realm parts (getText), truncates, and runs Linkify, none of which
        // changes between binds. This used to be rebuilt on every single bind and then
        // thrown away whenever the cache already held the spannable.
        val rendered = textCache.getOrPut(message.id) { renderBody(message) }

        // Bind the message status
        bindStatus(holder, rendered.truncated, message, next)

        // Bind the timestamp
        val subscription = subs.find { it.subscriptionId == message.subId }

        holder.timestamp.apply {
            text = dateFormatter.getMessageTimestamp(message.date)
            setVisible(
                    ((message.date - (previous?.date ?: 0))
                        .millisecondsToMinutes() >= BubbleUtils.TIMESTAMP_THRESHOLD) ||
                            (message.subId != previous?.subId) &&
                            (subscription != null)
            )
        }

        holder.simIndex.text = subscription?.simSlotIndex?.plus(1)?.toString()

        // Shown when the SIM changes, and always on the newest message.
        //
        // Marking only the changes means a conversation carried entirely on one SIM is
        // labelled once, at the very top, far out of view -- so the question someone
        // actually has, "which number did that last one go out on", had no answer without
        // scrolling to the beginning. On a dual-SIM phone where one number is work, that
        // matters more than the tidiness of an unmarked thread.
        val simWorthSaying = subscription != null && subs.size > 1 &&
            (message.subId != previous?.subId || next == null)
        holder.sim.setVisible(simWorthSaying)
        holder.simIndex.setVisible(simWorthSaying)

        // Bind the grouping
        holder.containerView.setPadding(
            bottom = if (canGroup(message, next)) 0 else 16.dpToPx(context)
        )

        // Bind the avatar and bubble colour
        if (!message.isMe()) {
            // Stock Kompakt SMS relies on the top-bar contact name only; no
            // per-message avatar. Always GONE (not just XML-default) since
            // this used to be toggled visible/invisible per grouping here.
            holder.avatar?.visibility = View.GONE

            holder.body.apply {
                setTextColor(android.graphics.Color.BLACK)
                // Removed setBackgroundTint to show black outline and white background
                highlightColor = R.attr.bubbleColor.withAlpha(0x5d)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    textSelectHandle?.setTint(R.attr.bubbleColor.withAlpha(0x7d))
                    textSelectHandleLeft?.setTint(R.attr.bubbleColor.withAlpha(0x7d))
                    textSelectHandleRight?.setTint(R.attr.bubbleColor.withAlpha(0x7d))
                }
            }
        } else
            holder.body.apply {
                highlightColor = theme.theme.withAlpha(0x5d)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    textSelectHandle?.setTint(theme.theme.withAlpha(0xad))
                    textSelectHandleLeft?.setTint(theme.theme.withAlpha(0xad))
                    textSelectHandleRight?.setTint(theme.theme.withAlpha(0xad))
                }
            }

        // Bind the body text
        val emojiOnly = rendered.emojiOnly
        textViewStyler.setTextSize(
            holder.body,
            when (emojiOnly) {
                true -> TextViewStyler.SIZE_EMOJI
                false -> TextViewStyler.SIZE_PRIMARY
            }
        )

        val spanString = rendered.spans

        when (prefs.messageLinkHandling.get()) {
            Preferences.MESSAGE_LINK_HANDLING_BLOCK -> holder.body.autoLinkMask = 0
            Preferences.MESSAGE_LINK_HANDLING_ASK -> {
                holder.body.apply {
                    linksClickable = false
                    movementMethod = LongClickLinkMovementMethod.getInstance()
                }
            }
            else -> {
                holder.body.movementMethod = LongClickLinkMovementMethod.getInstance()
            }
        }

        holder.body.apply {
            text = spanString
            setVisible(message.isSms() || spanString.isNotBlank())

            setBackgroundResource(
                getBubble(
                    emojiOnly = emojiOnly,
                    canGroupWithPrevious = canGroup(message, previous) ||
                            message.parts.any { !it.isSmil() && !it.isText() },
                    canGroupWithNext = canGroup(message, next),
                    isMe = message.isMe()
                )
            )
        }

        // Bind the parts.
        //
        // The adapter is created once per view holder and then reused. Assigning a *new*
        // adapter to a RecyclerView throws away its entire recycled view pool and forces a
        // full re-layout of that nested list — and this runs on every bind of every row,
        // including plain SMS with no parts at all, so it was landing on the UI thread
        // continuously while scrolling. Reusing it means a bind is just a DiffUtil pass
        // over the parts list (trivially empty for an SMS).
        //
        // Subscribing to `clicks` also has to happen once here, not per bind: the old code
        // added an undisposed subscription every single time a row was bound, so they piled
        // up for as long as the thread stayed open and each part click fired N times.
        val partsAdapter = (holder.parts.adapter as? PartsAdapter)
            ?: partsAdapterProvider.get().also { adapter ->
                adapter.clicks.subscribe(partClicks) // passed back to the compose view model
                holder.parts.adapter = adapter
            }
        partsAdapter.theme = theme
        partsAdapter.setData(message, previous, next, holder, audioState)
        partsAdapter.contextMenuValue = message.id

        showEmojiReactions(holder, message)
    }

    /** Builds the cached [RenderedBody] for a message. Only runs on a cache miss. */
    private fun renderBody(message: Message): RenderedBody {
        val subject = message.getCleansedSubject()
        var truncated = false

        // get message text to display, which may need to be truncated
        val displayText = subject.joinTo(message.getText(false), "\n").let {
            truncated = (it.length > MAX_MESSAGE_DISPLAY_LENGTH)

            // make subject sub-string bold, if subject is not blank
            if (subject.isNotBlank())
                SpannableString(it.truncateWithEllipses(MAX_MESSAGE_DISPLAY_LENGTH)).apply {
                    setSpan(
                        StyleSpan(Typeface.BOLD),
                        0,
                        subject.length,
                        Spannable.SPAN_INCLUSIVE_EXCLUSIVE
                    )
                }
            else
                it.truncateWithEllipses(MAX_MESSAGE_DISPLAY_LENGTH)
        }

        val span = SpannableStringBuilder(displayText)
        when (prefs.messageLinkHandling.get()) {
            Preferences.MESSAGE_LINK_HANDLING_ASK -> {
                Linkify.addLinks(span, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS)
                span.apply {
                    for (urlSpan in getSpans(0, length, URLSpan::class.java)) {
                        setSpan(
                            object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    messageLinkClicks.onNext(urlSpan.url.toUri())
                                }
                            },
                            getSpanStart(urlSpan),
                            getSpanEnd(urlSpan),
                            getSpanFlags(urlSpan)
                        )
                        removeSpan(urlSpan)
                    }
                }
            }
        }

        return RenderedBody(span, displayText.isEmojiOnly(), truncated)
    }

    private fun showEmojiReactions(holder: MessageViewHolder, message: Message) {
        holder.reactions?.let { reactionsContainer ->
            val reactions = message.emojiReactions
            val hasReactions = reactions.isNotEmpty()

            if (hasReactions) {
                val reactionCounts = reactions.groupBy { it.emoji }
                    .mapValues { it.value.size }
                    .toList()
                    .sortedByDescending { it.second } // Sort by count, most reactions first

                // For now, show just the first (most popular) reaction
                val topReaction = reactionCounts.first()
                val reactionText = if (topReaction.second == 1) {
                    topReaction.first
                } else {
                    // Use a non-breaking space to keep the emoji and count together
                    "${topReaction.first}\u00A0${topReaction.second}"
                }

                holder.reactionText?.text = reactionText
                reactionsContainer.setVisible(true)
                makeRoomForEmojis(holder)
            } else {
                reactionsContainer.setVisible(false)
            }
        }
    }

    private fun makeRoomForEmojis(holder: MessageViewHolder) {
        val paddingBottom = 25.dpToPx(context)

        (holder.reactions?.parent?.parent as? ViewGroup)?.let { parent ->
            parent.setPadding(
                parent.paddingLeft,
                parent.paddingTop,
                parent.paddingRight,
                paddingBottom
            )
        }
    }

    private fun bindStatus(
        holder: MessageViewHolder,
        bodyTextTruncated: Boolean,
        message: Message,
        next: Message?
    ) {
        holder.status.apply {
            text = when {
                message.isSending() -> context.getString(R.string.message_status_sending)
                // Read outranks delivered, and carries no time of its own: the report says
                // that it was read, never when.
                message.isReadByRecipient() -> context.getString(R.string.message_status_read)
                message.isDelivered() -> context.getString(
                    R.string.message_status_delivered,
                    dateFormatter.getTimestamp(message.dateSent)
                )
                message.isFailedMessage() -> context.getString(R.string.message_status_failed)
                bodyTextTruncated -> context.getString(R.string.message_body_too_long_to_display)
                (!message.isMe() && (conversation?.recipients?.size ?: 0) > 1) ->
                    // Incoming group message: sender name only. The time already appears in
                    // the timestamp header above the group, so repeating it here was noise —
                    // most visible on short messages like "Liked an image", where the status
                    // line ended up longer than the message itself.
                    contactCache[message.address]?.getDisplayName() ?: message.address
                else -> dateFormatter.getTimestamp(message.date)
            }

            val age = TimeUnit.MILLISECONDS.toMinutes(
                System.currentTimeMillis() - message.date
            )

            setVisible(
                when {
                    expanded[message.id] == true -> true
                    message.isSending() -> true
                    message.isFailedMessage() -> true
                    bodyTextTruncated -> true
                    expanded[message.id] == false -> false
                    ((conversation?.recipients?.size ?: 0) > 1) &&
                            !message.isMe() && next?.compareSender(message) != true -> true
                    (message.isDelivered() &&
                            (next?.isDelivered() != true) &&
                            (age <= BubbleUtils.TIMESTAMP_THRESHOLD)) -> true

                    else -> false
                }
            )
        }
    }

    override fun getItemId(position: Int): Long {
        return getItem(position)?.id ?: -1
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position) ?: return -1
        return when (message.isMe()) {
            true -> VIEW_TYPE_MESSAGE_OUT
            false -> VIEW_TYPE_MESSAGE_IN
        }
    }

    fun expandMessages(messageIds: List<Long>, expand: Boolean) {
        messageIds.forEach { expanded[it] = expand }
        notifyDataSetChanged()
    }

    /**
     * Cache the contacts in a map by the address, because the messages we're binding don't have
     * a reference to the contact.
     */
    private inner class ContactCache : HashMap<String, Recipient?>() {
        override fun get(key: String): Recipient? {
            if (super.get(key)?.isValid != true)
                set(
                    key,
                    conversation?.recipients?.firstOrNull {
                        phoneNumberUtils.compare(it.address, key)
                    }
                )

            return super.get(key)?.takeIf { it.isValid }
        }

    }
}
