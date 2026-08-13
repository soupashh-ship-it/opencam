package com.opencam.encode

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rotates camera frames through OpenGL before they enter MediaCodec.
 *
 * @param mirrored flips the output left/right (a mirror effect). Works in
 * combination with any rotation, because the flip is applied to the output
 * corners, not to the raw input texcoords.
 */
class GlRotator(
    encoderSurface: Surface,
    inputWidth: Int,
    inputHeight: Int,
    rotationDegrees: Int,
    mirrored: Boolean = false,
) {
    private val rotation = ((rotationDegrees % 360) + 360) % 360
    private val outputWidth = inputWidth
    private val outputHeight = inputHeight

    private val thread = HandlerThread("opencam-rotate").apply { start() }
    private val handler = Handler(thread.looper)
    private val released = AtomicBoolean(false)

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var program = 0
    private var textureId = 0
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var stMatrixHandle = -1
    private var textureHandle = -1
    private val stMatrix = FloatArray(16)

    private val vertexBuffer: FloatBuffer =
        floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoordBuffer: FloatBuffer = floatBuffer(texCoordsFor(rotation, mirrored))

    private var surfaceTexture: SurfaceTexture? = null

    /** Surface the Camera2 capture session targets. */
    val inputSurface: Surface

    init {
        require(inputWidth > 0 && inputHeight > 0) { "Invalid GL input size" }
        val latch = CountDownLatch(1)
        var createdSurface: Surface? = null
        var failure: Throwable? = null
        handler.post {
            try {
                createdSurface = setup(encoderSurface, inputWidth, inputHeight)
            } catch (t: Throwable) {
                failure = t
                cleanupGl()
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "Timed out while creating GL rotator" }
        failure?.let {
            thread.quitSafely()
            throw IllegalStateException("GL init failed", it)
        }
        inputSurface = createdSurface ?: run {
            thread.quitSafely()
            throw IllegalStateException("GL init produced no input surface")
        }
    }

    private fun setup(encoderSurface: Surface, inputWidth: Int, inputHeight: Int): Surface {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        eglDisplay = display
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "eglChooseConfig failed" }
        val config = checkNotNull(configs[0])

        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglContext = context

        val windowSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            encoderSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(windowSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        eglSurface = windowSurface
        check(EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) { "eglMakeCurrent failed" }

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        textureHandle = GLES20.glGetUniformLocation(program, "sTexture")
        check(positionHandle >= 0 && texCoordHandle >= 0 && stMatrixHandle >= 0 && textureHandle >= 0) {
            "Required GL handle missing"
        }
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        textureId = texIds[0]
        check(textureId != 0) { "glGenTextures failed" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val texture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(inputWidth, inputHeight)
            setOnFrameAvailableListener({ renderFrame() }, handler)
        }
        surfaceTexture = texture
        return Surface(texture)
    }

    private fun renderFrame() {
        if (released.get()) return
        val texture = surfaceTexture ?: return
        val display = eglDisplay ?: return
        val surface = eglSurface ?: return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(stMatrix)
            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionHandle)
            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            val timestamp = texture.timestamp
            if (timestamp > 0L) EGLExt.eglPresentationTimeANDROID(display, surface, timestamp)
            EGL14.eglSwapBuffers(display, surface)
        } catch (_: Exception) {
            // A rebuild may release the camera/codec while a frame callback is queued.
        }
    }

    /** Completes GL destruction before returning so the encoder can be stopped safely. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        val latch = CountDownLatch(1)
        handler.post {
            try {
                try { inputSurface.release() } catch (_: Exception) {}
                cleanupGl()
            } finally {
                latch.countDown()
                thread.quitSafely()
            }
        }
        try { latch.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { thread.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun cleanupGl() {
        try { surfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Exception) {}
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null

        val display = eglDisplay
        val context = eglContext
        val surface = eglSurface
        if (display != null && display != EGL14.EGL_NO_DISPLAY) {
            try {
                if (context != null && context != EGL14.EGL_NO_CONTEXT &&
                    surface != null && surface != EGL14.EGL_NO_SURFACE
                ) {
                    EGL14.eglMakeCurrent(display, surface, surface, context)
                    if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    if (program != 0) GLES20.glDeleteProgram(program)
                }
            } catch (_: Exception) {
            }
            try {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
            } catch (_: Exception) {}
            try {
                if (surface != null && surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            } catch (_: Exception) {}
            try {
                if (context != null && context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            } catch (_: Exception) {}
            try { EGL14.eglTerminate(display) } catch (_: Exception) {}
            try { EGL14.eglReleaseThread() } catch (_: Exception) {}
        }
        textureId = 0
        program = 0
        eglSurface = null
        eglContext = null
        eglDisplay = null
    }

    private fun texCoordsFor(rotation: Int, mirrored: Boolean): FloatArray {
        val positions = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val ox = (positions[i * 2] + 1f) / 2f
            val oy = (positions[i * 2 + 1] + 1f) / 2f
            val (u, v) = when (rotation) {
                90 -> (1f - oy) to ox
                180 -> (1f - ox) to (1f - oy)
                270 -> oy to (1f - ox)
                else -> ox to oy
            }
            out[i * 2] = u
            out[i * 2 + 1] = v
        }
        if (mirrored) {
            // Vertices are a triangle strip ordered (x=-1,y=-1), (x=1,y=-1),
            // (x=-1,y=1), (x=1,y=1). Swapping the texcoords of each horizontal
            // corner pair mirrors the *output* left/right regardless of rotation.
            for (i in 0 until 4 step 2) {
                val tmpU = out[i * 2]
                val tmpV = out[i * 2 + 1]
                out[i * 2] = out[(i + 1) * 2]
                out[i * 2 + 1] = out[(i + 1) * 2 + 1]
                out[(i + 1) * 2] = tmpU
                out[(i + 1) * 2 + 1] = tmpV
            }
        }
        return out
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .apply { position(0) }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val result = GLES20.glCreateProgram()
        try {
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "GL program link failed: ${GLES20.glGetProgramInfoLog(result)}" }
            return result
        } catch (t: Throwable) {
            GLES20.glDeleteProgram(result)
            throw t
        } finally {
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("GL shader compile failed: $message")
        }
        return shader
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
