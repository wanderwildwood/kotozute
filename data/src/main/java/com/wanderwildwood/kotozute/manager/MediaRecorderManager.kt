/*
 * Copyright (C) 2025
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QUIK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QUIK.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wanderwildwood.kotozute.manager

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.core.net.toFile
import com.wanderwildwood.kotozute.util.FileUtils
import java.util.UUID

object MediaRecorderManager : MediaRecorder() {

    enum class RecordingState {
        Initial,
        DataSourceConfigured,
        Prepared,
        Recording,
        Error
    }

    const val AUDIO_FILE_PREFIX = "recorded-"
    const val AUDIO_FILE_SUFFIX = ".amr"

    /** Recordings made before the move off AMR-WB. Cleaned up, never written. */
    const val LEGACY_AUDIO_FILE_SUFFIX = ".3ga"

    /** What a Signal voice note is recorded into. See [Format.SIGNAL_AAC]. */
    const val SIGNAL_AUDIO_FILE_SUFFIX = ".aac"

    /**
     * Every suffix a recording has ever been written with.
     *
     * ⚠ Here so the cache sweep has one list to read. A recording lives in the cache until
     * something deletes it, and a sweep that knows about two of the three suffixes does not
     * fail -- it just quietly leaves the third kind behind for ever, on a phone with very
     * little room. Adding a format means adding it here in the same edit.
     */
    val ALL_AUDIO_FILE_SUFFIXES = listOf(
        AUDIO_FILE_SUFFIX,
        LEGACY_AUDIO_FILE_SUFFIX,
        SIGNAL_AUDIO_FILE_SUFFIX
    )

    /**
     * Which rail the recording is for, which decides how it is encoded.
     *
     * ⚠ The two are **not interchangeable**, and this is not a quality preference. Each rail
     * has a format its recipients can actually decode, and sending the other one arrives as a
     * file that will not open rather than as an error anybody can act on.
     */
    enum class Format(val suffix: String) {
        /**
         * MMS: AMR narrowband.
         *
         * Wideband is outside the MMS baseline, so a handset or a carrier transcoder on the
         * far end can receive the part and still have no way to decode it.
         */
        MMS_AMR_NB(AUDIO_FILE_SUFFIX),

        /**
         * Signal: AAC in ADTS, 44.1 kHz mono at 32 kbps.
         *
         * Signal's own numbers, from `MediaRecorderWrapper.java:19-41` -- sample rate,
         * channel count and bit rate all copied rather than chosen, because a voice note is
         * played by every other Signal client and this is the shape they expect. AMR would
         * technically arrive, as a file nothing on the other end offers to play.
         */
        SIGNAL_AAC(SIGNAL_AUDIO_FILE_SUFFIX)
    }

    /** Signal's own recording numbers; see [Format.SIGNAL_AAC]. */
    private const val SIGNAL_SAMPLE_RATE = 44100
    private const val SIGNAL_BIT_RATE = 32000
    private const val SIGNAL_CHANNELS = 1

    private var recordingState: RecordingState = RecordingState.Initial

    var uri: Uri = Uri.EMPTY
        private set

    fun stopRecording(): Uri {
        return try {
            if (recordingState == RecordingState.Recording)
                stop()

            reset()
            recordingState = RecordingState.Initial

            uri
        }
        catch (e: Exception) {
            Uri.EMPTY
        }
    }

    fun startRecording(
        context: Context,
        preferredAudioDevice: AudioDeviceInfo? = null,
        format: Format = Format.MMS_AMR_NB
    ): Uri {
        return try {
            val (newUri, e) = FileUtils.create(
                FileUtils.Location.Cache,
                context,
                "$AUDIO_FILE_PREFIX${UUID.randomUUID()}${format.suffix}",
                ""
            )
            if (e is Exception)
                throw e

            uri = newUri

            // ensure stopped before using again
            stopRecording()

            // configure -- see [Format] for why the two rails differ.
            setAudioSource(AudioSource.MIC)
            when (format) {
                Format.MMS_AMR_NB -> {
                    setOutputFormat(OutputFormat.AMR_NB)
                    setAudioEncoder(AudioEncoder.AMR_NB)
                }

                Format.SIGNAL_AAC -> {
                    // ⚠ ADTS, not the MPEG-4 container. Signal writes a raw ADTS stream and
                    // its receivers expect one; `AAC_ADTS` is also the format that can be cut
                    // short without a moov atom to finish, which matters for a recording that
                    // ends when a finger lifts.
                    setOutputFormat(OutputFormat.AAC_ADTS)
                    setAudioEncoder(AudioEncoder.AAC)
                    setAudioSamplingRate(SIGNAL_SAMPLE_RATE)
                    setAudioEncodingBitRate(SIGNAL_BIT_RATE)
                    setAudioChannels(SIGNAL_CHANNELS)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                preferredDevice = preferredAudioDevice

            recordingState = RecordingState.DataSourceConfigured

            setOutputFile(uri.toFile().path)

            prepare()
            recordingState = RecordingState.Prepared

            start()
            recordingState = RecordingState.Recording

            uri
        }
        catch (e: Exception) {
            Uri.EMPTY
        }
    }

}
