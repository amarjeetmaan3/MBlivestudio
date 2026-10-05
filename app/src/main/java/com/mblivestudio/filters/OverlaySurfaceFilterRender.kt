package com.mblivestudio.filters

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import android.view.View
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HARDWARE OVERLAY (beta)
 *
 * Instead of copying a full bitmap to the GPU for every overlay frame, the overlay Views are drawn by the GPU
 * straight into a Surface (UI thread, hardware canvas). This filter reads that Surface as an external texture
 * and lays it over the camera picture. Whole frames only (the system buffer queue guarantees it), no bitmap
 * copy, no race between threads.
 *
 * Structure mirrors CameraLayoutFilterRender (same BaseFilterRender contract).
 */
class OverlaySurfaceFilterRender : BaseFilterRender() {

    private val squareVertexDataFilter = floatArrayOf(
        // X,   Y,  Z,   U,   V
        -1f, -1f, 0f, 0f, 0f, // bottom left
        1f, -1f, 0f, 1f, 0f, // bottom right
        -1f, 1f, 0f, 0f, 1f, // top left
        1f, 1f, 0f, 1f, 1f  // top right
    )

    private var program = -1
    private var aPositionHandle = -1
    private var aTextureHandle = -1
    private var uMVPMatrixHandle = -1
    private var uSTMatrixHandle = -1
    private var uOverlaySTHandle = -1
    private var uSamplerHandle = -1
    private var uOverlayHandle = -1
    private var uHasOverlayHandle = -1
    private var uFlipYHandle = -1

    private var overlayTexId = -1
    @Volatile private var surfaceTexture: SurfaceTexture? = null
    @Volatile var surface: Surface? = null
        private set
    private val frameAvailable = AtomicBoolean(false)
    @Volatile private var hasFrame = false
    private val overlayST = FloatArray(16)

    @Volatile private var bufferW = 1280
    @Volatile private var bufferH = 720

    /** Only needed if the overlay ever shows upside-down on some device. */
    @Volatile var flipY = false

    init {
        squareVertex = ByteBuffer.allocateDirect(squareVertexDataFilter.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        squareVertex.put(squareVertexDataFilter).position(0)
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)
        Matrix.setIdentityM(overlayST, 0)
    }

    override fun initGlFilter(context: Context) {
        val vertexShader = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            uniform mat4 uOverlayST;
            varying vec2 vTextureCoord;
            varying vec2 vOverlayCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;
                vOverlayCoord = (uOverlayST * vec4(aTextureCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            varying vec2 vOverlayCoord;
            uniform sampler2D uSampler;
            uniform samplerExternalOES uOverlay;
            uniform float uHasOverlay;
            uniform float uFlipY;
            void main() {
                vec4 cam = texture2D(uSampler, vTextureCoord);
                if (uHasOverlay > 0.5) {
                    vec2 oc = vOverlayCoord;
                    if (uFlipY > 0.5) { oc.y = 1.0 - oc.y; }
                    vec4 ov = texture2D(uOverlay, oc);
                    gl_FragColor = vec4(ov.rgb + cam.rgb * (1.0 - ov.a), 1.0);
                } else {
                    gl_FragColor = cam;
                }
            }
        """.trimIndent()

        program = GlUtil.createProgram(vertexShader, fragmentShader)
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uOverlaySTHandle = GLES20.glGetUniformLocation(program, "uOverlayST")
        uSamplerHandle = GLES20.glGetUniformLocation(program, "uSampler")
        uOverlayHandle = GLES20.glGetUniformLocation(program, "uOverlay")
        uHasOverlayHandle = GLES20.glGetUniformLocation(program, "uHasOverlay")
        uFlipYHandle = GLES20.glGetUniformLocation(program, "uFlipY")

        // external texture that the overlay Surface feeds
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        overlayTexId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, overlayTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        val st = SurfaceTexture(overlayTexId)
        st.setDefaultBufferSize(bufferW, bufferH)
        st.setOnFrameAvailableListener { frameAvailable.set(true) }
        hasFrame = false
        frameAvailable.set(false)
        surfaceTexture = st
        surface = Surface(st)
    }

    override fun drawFilter() {
        GLES20.glUseProgram(program)

        // pick up the newest overlay frame (must happen on the GL thread)
        val st = surfaceTexture
        if (st != null && frameAvailable.getAndSet(false)) {
            try {
                st.updateTexImage()
                st.getTransformMatrix(overlayST)
                hasFrame = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        squareVertex.position(SQUARE_VERTEX_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false,
            SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        squareVertex.position(SQUARE_VERTEX_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(aTextureHandle, 2, GLES20.GL_FLOAT, false,
            SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
        GLES20.glEnableVertexAttribArray(aTextureHandle)

        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, MVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, STMatrix, 0)
        GLES20.glUniformMatrix4fv(uOverlaySTHandle, 1, false, overlayST, 0)
        GLES20.glUniform1i(uSamplerHandle, 0)
        GLES20.glUniform1i(uOverlayHandle, 1)
        GLES20.glUniform1f(uHasOverlayHandle, if (hasFrame) 1f else 0f)
        GLES20.glUniform1f(uFlipYHandle, if (flipY) 1f else 0f)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, overlayTexId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
    }

    override fun release() {
        GLES20.glDeleteProgram(program)
        hasFrame = false
        try { surface?.release() } catch (_: Exception) {}
        surface = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        if (overlayTexId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(overlayTexId), 0)
            overlayTexId = -1
        }
    }

    // ------------------------- UI-thread side -------------------------

    fun setBufferSize(w: Int, h: Int) {
        if (w == bufferW && h == bufferH) return
        bufferW = w
        bufferH = h
        try { surfaceTexture?.setDefaultBufferSize(w, h) } catch (_: Exception) {}
    }

    /**
     * Draws [view] into the overlay Surface with the GPU. Call on the UI thread.
     * @return 0 = drawn, 1 = previous frame not picked up yet (try again soon), 2 = surface not ready yet
     */
    fun drawView(view: View, scale: Float, offsetX: Float, offsetY: Float): Int {
        val s = surface ?: return 2
        if (!s.isValid) return 2
        // never queue more than one unread frame (keeps the UI thread from ever blocking)
        if (frameAvailable.get()) return 1

        var locked: Canvas? = null
        try {
            val c: Canvas = s.lockHardwareCanvas()
            locked = c
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            c.save()
            c.scale(scale, scale)
            c.translate(offsetX, offsetY)
            view.draw(c)
            c.restore()
        } catch (e: Exception) {
            e.printStackTrace()
            return 2
        } finally {
            val l = locked
            if (l != null) {
                try { s.unlockCanvasAndPost(l) } catch (_: Exception) {}
            }
        }
        return 0
    }
}
