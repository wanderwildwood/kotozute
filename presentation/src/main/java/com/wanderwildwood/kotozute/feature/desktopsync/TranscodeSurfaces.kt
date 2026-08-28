/*
 * Copyright (C) 2026 wander wildwood
 *
 * This file is part of Messaging.
 *
 * Messaging is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * The EGL and SurfaceTexture handling here follows the approach in Google's
 * DecodeEditEncodeTest (Apache 2.0), which is the reference for moving frames from one
 * MediaCodec to another.
 */
package com.wanderwildwood.kotozute.feature.desktopsync

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * An EGL context drawing onto the encoder's input surface.
 *
 * The decoder cannot simply be pointed at that surface. Nothing then paces the two against
 * each other: frames land while the encoder is still reading the last one and the result is
 * a picture torn into blocks with green where nothing was written yet. Going through GL
 * makes each frame an explicit draw followed by an explicit swap, which is the pacing.
 */
internal class InputSurface(private val surface: Surface) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        // EGL_RECORDABLE_ANDROID: without it the config may be one the encoder cannot read.
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, configCount, 0)
        ) { "no usable EGL config" }

        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(
            display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    /** The timestamp the encoder stamps on this frame; nanoseconds, not microseconds. */
    fun setPresentationTime(nanoseconds: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanoseconds)
    }

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        surface.release()
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

/**
 * Where the decoder draws: a SurfaceTexture wrapped around an external texture, which can
 * then be drawn onto whatever EGL surface is current.
 *
 * [awaitNewImage] is the other half of the pacing — it blocks until the decoder has actually
 * produced the frame, so nothing is drawn twice or missed.
 */
internal class OutputSurface : SurfaceTexture.OnFrameAvailableListener {

    private val lock = Object()
    private var frameAvailable = false
    private val textureId: Int
    private val surfaceTexture: SurfaceTexture
    val surface: Surface

    private val program: Int
    private val positions: FloatBuffer
    private val textureCoordinates: FloatBuffer
    private val transform = FloatArray(16)

    init {
        program = buildProgram()
        textureId = createTexture()
        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener(this)
        surface = Surface(surfaceTexture)

        positions = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        textureCoordinates = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        synchronized(lock) {
            frameAvailable = true
            lock.notifyAll()
        }
    }

    /** Waits for the frame the decoder was just told to render. */
    fun awaitNewImage(timeoutMs: Long = 2_500): Boolean {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!frameAvailable) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                lock.wait(remaining)
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(transform)
        return true
    }

    fun drawImage() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positions)

        val coordinate = GLES20.glGetAttribLocation(program, "aTextureCoord")
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, textureCoordinates)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, transform, 0)
        val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMVPMatrix"), 1, false, identity, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(coordinate)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun release() {
        surface.release()
        surfaceTexture.release()
    }

    private fun createTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun buildProgram(): Int {
        val vertex = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            }
        """.trimIndent()
        val fragment = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """.trimIndent()

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "shader link failed: " + GLES20.glGetProgramInfoLog(program) }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) { "shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }
}
