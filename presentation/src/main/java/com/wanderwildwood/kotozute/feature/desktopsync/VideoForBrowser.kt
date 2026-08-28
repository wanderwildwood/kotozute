/*
 * Copyright (C) 2026 wander wildwood
 *
 * This file is part of Messaging.
 *
 * Messaging is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 */
package com.wanderwildwood.kotozute.feature.desktopsync

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer

/**
 * Makes an MMS video something a browser will actually play.
 *
 * A video that arrives by MMS is frequently H.263 with AMR-NB audio at 176x144 — what the
 * carrier's gateway downscales everything to. No current browser decodes either: Firefox and
 * Chrome dropped both years ago. Serving it correctly, with a length and byte ranges, does
 * not help, because the problem is not delivery.
 *
 * So it is re-encoded here, on the phone, into H.264 and AAC in an mp4 — which every browser
 * plays. The result is cached beside the app, keyed by part id, because a conversation gets
 * scrolled past repeatedly and the work should happen once.
 *
 * Anything already in a format a browser handles is left alone: this is a fallback for the
 * old codecs, not a pipeline every video goes through.
 */
object VideoForBrowser {

    /** Codecs a browser will play out of an mp4 without help. */
    private val PLAYABLE_VIDEO = setOf("video/avc", "video/hevc", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01")
    private val PLAYABLE_AUDIO = setOf("audio/mp4a-latm", "audio/opus", "audio/vorbis")

    /** Beyond this, transcoding on a phone this size is not a courtesy any more. */
    private const val MAX_INPUT_BYTES = 32L * 1024 * 1024

    private const val OUTPUT_VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val OUTPUT_AUDIO = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val AUDIO_BITRATE = 64_000
    private const val TIMEOUT_US = 10_000L

    /**
     * Bumped whenever the encoding changes. It is part of the cache file's name, so a fix to
     * how these are made reaches videos somebody has already watched — without it, the first
     * bad copy of a clip is the copy they keep being served.
     */
    private const val CACHE_VERSION = 2

    /**
     * The file to serve for this part, and the type to serve it as.
     *
     * Returns null when the original is fine as it stands, which is the common case and
     * costs one cheap look at the track formats.
     */
    fun playableCopy(context: Context, partId: Long, uri: Uri, sizeBytes: Long?): File? {
        if (sizeBytes != null && sizeBytes > MAX_INPUT_BYTES) return null

        val cached = File(cacheDir(context), "$partId-v$CACHE_VERSION.mp4")
        if (cached.isFile && cached.length() > 0) return cached

        val needed = runCatching { needsTranscoding(context, uri) }.getOrDefault(false)
        if (!needed) return null

        val working = File(cacheDir(context), "$partId-v$CACHE_VERSION.mp4.part")
        return runCatching {
            transcode(context, uri, working)
            // Renamed only once it is complete, so a process killed mid-encode cannot leave
            // a truncated file behind that later looks like a finished one.
            if (working.renameTo(cached)) cached else null
        }.onFailure {
            Timber.w(it, "Desktop Sync: could not transcode part $partId")
            working.delete()
        }.getOrNull()
    }

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "desktopsync-video").apply { mkdirs() }

    private fun needsTranscoding(context: Context, uri: Uri): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") -> if (mime !in PLAYABLE_VIDEO) return true
                    mime.startsWith("audio/") -> if (mime !in PLAYABLE_AUDIO) return true
                }
            }
            // Every track is something a browser handles, so the original will do.
            return false
        } finally {
            extractor.release()
        }
    }

    /**
     * Decoder to encoder through a Surface rather than through byte buffers.
     *
     * The buffer route means handling whatever colour format this particular chip's decoder
     * feels like emitting; the surface route hands those frames straight to the encoder and
     * lets the hardware agree with itself.
     */
    private fun transcode(context: Context, uri: Uri, output: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val videoTrack = trackOf(extractor, "video/") ?: error("no video track")
        val inputFormat = extractor.getTrackFormat(videoTrack)
        val width = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = runCatching { inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(15)

        val encoderFormat = MediaFormat.createVideoFormat(OUTPUT_VIDEO, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // Generous for a frame this small, and it is the difference between a clip that
            // looks like the original and one that looks like a fax of it.
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 4).coerceAtLeast(256_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceAtLeast(12))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(OUTPUT_VIDEO)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        // Between the two codecs rather than wired straight through: see TranscodeSurfaces.
        val inputSurface = InputSurface(encoder.createInputSurface())
        inputSurface.makeCurrent()
        encoder.start()

        val outputSurface = OutputSurface()
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inputFormat, outputSurface.surface, null, 0)
        decoder.start()
        extractor.selectTrack(videoTrack)

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxVideoTrack = -1
        var muxerStarted = false

        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawDecoderEnd = false
        var sawEncoderEnd = false

        try {
            while (!sawEncoderEnd) {
                if (!sawInputEnd) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!sawDecoderEnd) {
                    val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index >= 0) {
                        val render = info.size > 0
                        val presentationTimeUs = info.presentationTimeUs
                        val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, render)
                        if (render) {
                            // Wait for the frame, draw it, stamp it, hand it over. Each step
                            // has to finish before the next frame is allowed to arrive.
                            if (outputSurface.awaitNewImage()) {
                                outputSurface.drawImage()
                                inputSurface.setPresentationTime(presentationTimeUs * 1000)
                                inputSurface.swapBuffers()
                            }
                        }
                        if (endOfStream) {
                            sawDecoderEnd = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                val index = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxVideoTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        val encoded = encoder.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(muxVideoTrack, encoded, info)
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEncoderEnd = true
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { inputSurface.release() }
            runCatching { outputSurface.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }

        // Audio is carried over separately, and its absence is not fatal: a silent clip that
        // plays beats a correct one that does not. AMR-NB is decoded to PCM and re-encoded
        // as AAC, because AMR is refused by browsers for the same reason H.263 is.
        runCatching { addAudio(context, uri, output) }
            .onFailure { Timber.i(it, "Desktop Sync: video transcoded without its audio") }
    }

    private fun trackOf(extractor: MediaExtractor, prefix: String): Int? =
        (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(prefix)
        }

    /**
     * Re-muxes the encoded video together with a freshly encoded AAC track.
     *
     * MediaMuxer cannot add a track to a finished file, so this writes a second file and
     * swaps it in. Both are small; the clip that needs any of this is a postage stamp.
     */
    private fun addAudio(context: Context, source: Uri, videoOnly: File) {
        val audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(context, source, null)
        val audioTrack = trackOf(audioExtractor, "audio/") ?: run {
            audioExtractor.release()
            return
        }
        val audioFormat = audioExtractor.getTrackFormat(audioTrack)
        val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        audioExtractor.selectTrack(audioTrack)

        val decoder = MediaCodec.createDecoderByType(audioFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(audioFormat, null, null, 0)
        decoder.start()

        val encoderFormat = MediaFormat.createAudioFormat(OUTPUT_AUDIO, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
        }
        val encoder = MediaCodec.createEncoderByType(OUTPUT_AUDIO)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val merged = File(videoOnly.parentFile, videoOnly.name + ".merged")
        val muxer = MediaMuxer(merged.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Copy the already-encoded video across as samples; it does not need touching again.
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(videoOnly.absolutePath)
        val vTrack = trackOf(videoExtractor, "video/") ?: error("transcoded file has no video")
        val videoFormat = videoExtractor.getTrackFormat(vTrack)
        videoExtractor.selectTrack(vTrack)
        val muxVideo = muxer.addTrack(videoFormat)

        var muxAudio = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawDecodeEnd = false
        var sawEncodeEnd = false

        try {
            while (!sawEncodeEnd) {
                if (!sawInputEnd) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = audioExtractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, audioExtractor.sampleTime, 0)
                            audioExtractor.advance()
                        }
                    }
                }

                if (!sawDecodeEnd) {
                    val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index >= 0) {
                        val pcm: ByteBuffer? = decoder.getOutputBuffer(index)
                        val encodeIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (encodeIndex >= 0) {
                            val target = encoder.getInputBuffer(encodeIndex)!!
                            target.clear()
                            if (pcm != null && info.size > 0) {
                                pcm.position(info.offset)
                                pcm.limit(info.offset + info.size)
                                target.put(pcm)
                            }
                            val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.queueInputBuffer(
                                encodeIndex, 0, info.size, info.presentationTimeUs,
                                if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            )
                            if (endOfStream) sawDecodeEnd = true
                        }
                        decoder.releaseOutputBuffer(index, false)
                    }
                }

                val index = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxAudio = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        started = true
                        // Video first, in one pass, now that the muxer will accept samples.
                        val buffer = ByteBuffer.allocate(1 shl 20)
                        val videoInfo = MediaCodec.BufferInfo()
                        while (true) {
                            val size = videoExtractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            videoInfo.offset = 0
                            videoInfo.size = size
                            videoInfo.presentationTimeUs = videoExtractor.sampleTime
                            videoInfo.flags = videoExtractor.sampleFlags
                            muxer.writeSampleData(muxVideo, buffer, videoInfo)
                            videoExtractor.advance()
                        }
                    }
                    index >= 0 -> {
                        val encoded = encoder.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(muxAudio, encoded, info)
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEncodeEnd = true
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { audioExtractor.release() }
            runCatching { videoExtractor.release() }
        }

        if (started && merged.length() > 0) {
            videoOnly.delete()
            merged.renameTo(videoOnly)
        } else {
            merged.delete()
        }
    }
}
