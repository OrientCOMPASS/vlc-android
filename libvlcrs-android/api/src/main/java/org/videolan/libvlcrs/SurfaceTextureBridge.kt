package org.videolan.libvlcrs

import android.graphics.SurfaceTexture
import android.view.Surface

/**
 * Helper object handed to the native engine so that the *native* GL context can
 * own the decoder's output surface.
 *
 * The engine creates an external OES texture inside its own EGL context and
 * calls [createSurfaceTexture] from the render thread; because the call happens
 * on that thread, the `SurfaceTexture` attaches to our context instead of the
 * application's UI context.  The returned [Surface] is then given to
 * `AMediaCodec`, so decoded frames land in a texture the projection shader can
 * sample — that is what makes the 360°/180° and stereo layouts possible.
 *
 * Note: this class and its methods must stay `public`.  Kotlin mangles the
 * names of `internal` members, and the native side looks these methods up by
 * name.
 */
class SurfaceTextureBridge {

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    @Volatile
    private var surface: Surface? = null

    private val transformMatrix = FloatArray(16)

    /**
     * Called from the native render thread (our EGL context is current there).
     * Releases any previous texture first: the engine creates exactly one per
     * GL context.
     */
    fun createSurfaceTexture(textureName: Int): Surface? {
        release()
        return try {
            val st = SurfaceTexture(textureName)
            val s = Surface(st)
            surfaceTexture = st
            surface = s
            s
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "createSurfaceTexture failed", t)
            null
        }
    }

    /** Latch the newest decoded frame into the OES texture. */
    fun updateTexImage() {
        try {
            surfaceTexture?.updateTexImage()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "updateTexImage failed", t)
        }
    }

    /** Texture transform (crop/flip) of the latched buffer, column major 4×4. */
    fun getTransformMatrix(): FloatArray {
        val st = surfaceTexture ?: return IDENTITY
        return try {
            st.getTransformMatrix(transformMatrix)
            transformMatrix
        } catch (t: Throwable) {
            IDENTITY
        }
    }

    /** Release the texture and the surface. */
    fun release() {
        try {
            surface?.release()
        } catch (_: Throwable) {
        }
        try {
            surfaceTexture?.release()
        } catch (_: Throwable) {
        }
        surface = null
        surfaceTexture = null
    }

    companion object {
        private const val TAG = "libvlcrs"
        private val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }
}
