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
package com.wanderwildwood.kotozute.feature.compose.part

import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.widget.SeekBar
import com.wanderwildwood.kotozute.common.QkMediaPlayer
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.Navigator
import com.wanderwildwood.kotozute.common.base.QkViewHolder
import com.wanderwildwood.kotozute.common.util.Colors
import com.wanderwildwood.kotozute.extensions.isAudio
import com.wanderwildwood.kotozute.extensions.resourceExists
import com.wanderwildwood.kotozute.feature.compose.MessagesAdapter
import com.wanderwildwood.kotozute.model.Message
import com.wanderwildwood.kotozute.model.MmsPart
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.wanderwildwood.kotozute.databinding.MmsAudioPreviewListItemBinding


class AudioBinder @Inject constructor(colors: Colors, private val context: Context) :
    PartBinder() {

    @Inject lateinit var navigator: Navigator

    override val partLayout = R.layout.mms_audio_preview_list_item
    override var theme = colors.theme()

    override fun canBindPart(part: MmsPart) = part.isAudio()

    var audioState = MessagesAdapter.AudioState(-1, QkMediaPlayer.PlayingState.Stopped)

    private fun startSeekBarUpdateTimer() {
        audioState.apply {
            seekBarUpdater?.dispose()
            seekBarUpdater = Observable.interval(500, TimeUnit.MILLISECONDS)
                .subscribeOn(Schedulers.single())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext {
                    viewHolder?.let { MmsAudioPreviewListItemBinding.bind(it.itemView).seekBar.progress = QkMediaPlayer.currentPosition }
                }
                .subscribe()
        }
    }

    private fun uiToPlaying(viewHolder: QkViewHolder) {
        val binding = MmsAudioPreviewListItemBinding.bind(viewHolder.itemView)
        binding.seekBar.max = QkMediaPlayer.duration
        binding.seekBar.isEnabled = true
        binding.seekBar.progress = QkMediaPlayer.currentPosition
        binding.playPause.setImageResource(R.drawable.exo_icon_pause)
        binding.playPause.tag = QkMediaPlayer.PlayingState.Playing
    }

    private fun uiToPaused(viewHolder: QkViewHolder) {
        val binding = MmsAudioPreviewListItemBinding.bind(viewHolder.itemView)
        binding.playPause.setImageResource(R.drawable.exo_icon_play)
        binding.playPause.tag = QkMediaPlayer.PlayingState.Paused
    }

    private fun uiToStopped(viewHolder: QkViewHolder) {
        val binding = MmsAudioPreviewListItemBinding.bind(viewHolder.itemView)
        binding.seekBar.progress = 0
        binding.seekBar.max = 0
        binding.seekBar.isEnabled = false
        binding.playPause.setImageResource(R.drawable.exo_icon_play)
        binding.playPause.tag = QkMediaPlayer.PlayingState.Stopped
    }

    override fun bindPart(
        holder: QkViewHolder,
        part: MmsPart,
        message: Message,
        canGroupWithPrevious: Boolean,
        canGroupWithNext: Boolean,
    ) {
        val binding = MmsAudioPreviewListItemBinding.bind(holder.itemView)
        // play/pause button click handling
        binding.playPause.setOnClickListener {
            when (binding.playPause.tag) {
                QkMediaPlayer.PlayingState.Playing -> {
                    if (audioState.partId == part.id) {
                        QkMediaPlayer.pause()
                        uiToPaused(holder)
                        audioState.state = QkMediaPlayer.PlayingState.Paused

                        // stop progress bar update timer
                        audioState.seekBarUpdater?.dispose()
                    }
                }
                QkMediaPlayer.PlayingState.Paused -> {
                    if (audioState.partId == part.id) {
                        QkMediaPlayer.start()
                        uiToPlaying(holder)
                        audioState.state = QkMediaPlayer.PlayingState.Playing

                        // start progress bar update timer
                        startSeekBarUpdateTimer()
                    }
                }
                else -> {
                    if (part.getUri().resourceExists(context)) {
                        QkMediaPlayer.reset() // make sure reset before trying to (re-)use

                        QkMediaPlayer.setOnPreparedListener {
                            // start media playing
                            QkMediaPlayer.start()

                            uiToPlaying(holder)

                            // set current view holder and part as active
                            audioState.apply {
                                audioState.state = QkMediaPlayer.PlayingState.Playing
                                partId = part.id
                                viewHolder = holder
                            }

                            // start progress bar update timer
                            startSeekBarUpdateTimer()
                        }

                        QkMediaPlayer.setOnCompletionListener {   // also called on error because we don't have an onerrorlistener
                            audioState.apply {
                                // if this part is currently active, set it to stopped and inactive
                                if ((partId == part.id) && (viewHolder != null))
                                    uiToStopped(viewHolder!!)

                                state = QkMediaPlayer.PlayingState.Stopped
                                partId = -1
                                viewHolder = null
                            }
                        }

                        // start the media player play sequence
                        QkMediaPlayer.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)     // music, maybe?? could be voice. don't want to use CONTENT_TYPE_UNKNOWN though
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )

                        QkMediaPlayer.setDataSource(context, part.getUri())

                        QkMediaPlayer.prepareAsync()
                    }
                }
            }
        }

        // if this item is the active active audio item update the active view holder
        if (audioState.partId == part.id)
            audioState.viewHolder = holder
        // else, this is not the active item so ensure the stored view holder is not this one
        else if (audioState.viewHolder == holder)
            audioState.viewHolder = null

        // seek bar listener
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, progress: Int, fromUser: Boolean) {
                // if seek was initiated by the user and this part is currently playing
                if (fromUser)
                    QkMediaPlayer.seekTo(progress)
            }
            override fun onStartTrackingTouch(p0: SeekBar?) { /* nothing */ }
            override fun onStopTrackingTouch(p0: SeekBar?) { /* nothing */ }
        })

        // playPause button state
        binding.playPause.apply {
            if ((audioState.partId == part.id) &&
                (audioState.state == QkMediaPlayer.PlayingState.Playing))
                uiToPlaying(holder)
            else if ((audioState.partId == part.id) &&
                (audioState.state == QkMediaPlayer.PlayingState.Paused))
                uiToPaused(holder)
            else
                uiToStopped(holder)
        }
    }
}
