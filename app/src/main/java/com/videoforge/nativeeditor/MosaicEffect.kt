package com.videoforge.nativeeditor

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.BaseGlShaderProgram
import java.io.IOException

/**
 * GPU pixelation restricted to a normalized rectangular region.
 *
 * Region coordinates are expressed in texture space: left/bottom in [0,1],
 * width/height in [0,1]. Block size is a fraction of the region's smaller edge.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MosaicEffect(
    private val left: Float,
    private val bottom: Float,
    private val width: Float,
    private val height: Float,
    private val blockSize: Float
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return MosaicShaderProgram(context, useHdr, left, bottom, width, height, blockSize)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        return width <= 0f || height <= 0f || blockSize <= 0f
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class MosaicShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val left: Float,
    private val bottom: Float,
    private val width: Float,
    private val height: Float,
    private val blockSize: Float
) : BaseGlShaderProgram(useHdr, 1) {

    private val program: GlProgram

    init {
        try {
            program = GlProgram(
                context,
                R.raw.mosaic_vertex_es2,
                R.raw.mosaic_fragment_es2
            )
        } catch (e: IOException) {
            throw VideoFrameProcessingException(e)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        program.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        program.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        program.setFloatUniform("uRegionLeft", left.coerceIn(0f, 1f))
        program.setFloatUniform("uRegionBottom", bottom.coerceIn(0f, 1f))
        program.setFloatUniform("uRegionWidth", width.coerceIn(0f, 1f))
        program.setFloatUniform("uRegionHeight", height.coerceIn(0f, 1f))
        program.setFloatUniform("uBlockSize", blockSize.coerceIn(0.005f, 0.25f))
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}
