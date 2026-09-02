package com.mblivestudio.filters

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Places the camera feed inside a positioned/scaled box (uRect, in 0..1
 * screen-space) and fills everything outside that box with a solid
 * background color. One filter covers every layout preset (Full, Split
 * Left/Right, Split Up/Down, 4 Corners) — only the rect values change.
 *
 * Structure deliberately mirrors RootEncoder's own GreyScaleFilterRender
 * (verified against the library's real source) so it follows the exact
 * contract BaseFilterRender expects: same full-screen quad geometry,
 * same protected fields (squareVertex, MVPMatrix, STMatrix, previousTexId),
 * same initGlFilter/drawFilter/disableResources/release lifecycle.
 */
class CameraLayoutFilterRender : BaseFilterRender() {

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
    private var uSamplerHandle = -1
    private var uRectHandle = -1
    private var uBgColorHandle = -1

    // Default = full screen, identical to no filter at all. Safest
    // possible baseline — this is what "Full" preset uses.
    private var rect = floatArrayOf(0f, 0f, 1f, 1f)
    private var bgColor = floatArrayOf(0f, 0f, 0f, 1f)

    init {
        squareVertex = ByteBuffer.allocateDirect(squareVertexDataFilter.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        squareVertex.put(squareVertexDataFilter).position(0)
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)
    }

    /** x0,y0,x1,y1 in 0..1 screen space. y=0 is bottom, y=1 is top. */
    fun setRect(x0: Float, y0: Float, x1: Float, y1: Float) {
        rect = floatArrayOf(x0, y0, x1, y1)
    }

    fun setBackgroundColor(r: Float, g: Float, b: Float) {
        bgColor = floatArrayOf(r, g, b, 1f)
    }

    override fun initGlFilter(context: Context) {
        val vertexShader = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val fragmentShader = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D uSampler;
            uniform vec4 uRect;
            uniform vec4 uBgColor;
            void main() {
                if (vTextureCoord.x >= uRect.x && vTextureCoord.x <= uRect.z &&
                    vTextureCoord.y >= uRect.y && vTextureCoord.y <= uRect.w) {
                    vec2 camUV = (vTextureCoord - uRect.xy) / (uRect.zw - uRect.xy);
                    gl_FragColor = texture2D(uSampler, camUV);
                } else {
                    gl_FragColor = uBgColor;
                }
            }
        """.trimIndent()

        program = GlUtil.createProgram(vertexShader, fragmentShader)
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uSamplerHandle = GLES20.glGetUniformLocation(program, "uSampler")
        uRectHandle = GLES20.glGetUniformLocation(program, "uRect")
        uBgColorHandle = GLES20.glGetUniformLocation(program, "uBgColor")
    }

    override fun drawFilter() {
        GLES20.glUseProgram(program)

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
        GLES20.glUniform1i(uSamplerHandle, 0)
        GLES20.glUniform4fv(uRectHandle, 1, rect, 0)
        GLES20.glUniform4fv(uBgColorHandle, 1, bgColor, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
    }

    override fun disableResources() {
        GlUtil.disableResources(aTextureHandle, aPositionHandle)
    }

    override fun release() {
        GLES20.glDeleteProgram(program)
    }

    companion object {
        // Named presets — same filter, different box coordinates.
        // (x0, y0, x1, y1) in 0..1, y measured from the bottom.
        val FULL = floatArrayOf(0f, 0f, 1f, 1f)
        val SPLIT_LEFT = floatArrayOf(0f, 0f, 0.5f, 1f)
        val SPLIT_RIGHT = floatArrayOf(0.5f, 0f, 1f, 1f)
        val SPLIT_TOP = floatArrayOf(0f, 0.5f, 1f, 1f)
        val SPLIT_BOTTOM = floatArrayOf(0f, 0f, 1f, 0.5f)
        val CORNER_TOP_LEFT = floatArrayOf(0f, 0.65f, 0.35f, 1f)
        val CORNER_TOP_RIGHT = floatArrayOf(0.65f, 0.65f, 1f, 1f)
        val CORNER_BOTTOM_LEFT = floatArrayOf(0f, 0f, 0.35f, 0.35f)
        val CORNER_BOTTOM_RIGHT = floatArrayOf(0.65f, 0f, 1f, 0.35f)
    }
}
