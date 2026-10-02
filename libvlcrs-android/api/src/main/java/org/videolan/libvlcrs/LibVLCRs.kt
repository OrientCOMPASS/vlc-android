package org.videolan.libvlcrs

import android.content.Context

/**
 * Entry point of the libvlcrs Android API.
 *
 * `libvlcrs` is a lightweight Rust re-implementation of the libvlc playback
 * core for `arm64-v8a` with first class spherical video support.  Typical use:
 *
 * ```kotlin
 * val player = RsMediaPlayer(context)
 * player.listener = myListener
 * vrVideoView.player = player          // attaches the SurfaceView
 * player.setMedia(uri)
 * player.projectionMode = ProjectionMode.AUTO
 * player.play()
 * ```
 *
 * The VR controls ([RsMediaPlayer.projectionMode], [RsMediaPlayer.eye],
 * [RsMediaPlayer.fovY], [RsMediaPlayer.gyroEnabled], [RsMediaPlayer.recenter])
 * can be changed at any time, including during playback: they take effect on the
 * next rendered frame and the playback position is preserved.
 */
object LibVLCRs {

    /** Name of the native library. */
    const val LIBRARY_NAME = "vlcrs"

    @Volatile
    private var loaded = false

    @Volatile
    private var loadError: Throwable? = null

    /**
     * Load `libvlcrs.so`.  Safe to call repeatedly; called automatically by
     * [RsMediaPlayer].
     *
     * @throws UnsatisfiedLinkError when the library is missing (e.g. the APK was
     *   built for another ABI — only `arm64-v8a` is supported).
     */
    @JvmStatic
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loadError?.let { throw UnsatisfiedLinkError("libvlcrs failed to load: $it") }
        try {
            System.loadLibrary(LIBRARY_NAME)
            loaded = true
        } catch (t: Throwable) {
            loadError = t
            val error = UnsatisfiedLinkError("cannot load $LIBRARY_NAME: ${t.message}")
            error.initCause(t)
            throw error
        }
    }

    /** `true` once the native library is loaded. */
    @JvmStatic
    fun isLoaded(): Boolean = loaded

    /** Native library version. */
    @JvmStatic
    fun version(): String {
        ensureLoaded()
        return NativeBridge.nativeVersion()
    }

    /**
     * `true` on devices libvlcrs can run on: arm64-v8a, API 26+ (AAudio and the
     * package scoped sensor manager).
     */
    @JvmStatic
    fun isSupported(context: Context? = null): Boolean {
        val abis = android.os.Build.SUPPORTED_64_BIT_ABIS ?: emptyArray()
        val arm64 = abis.any { it == "arm64-v8a" } ||
            android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        val api = android.os.Build.VERSION.SDK_INT >= 26
        if (context != null && (!arm64 || !api)) {
            android.util.Log.w(
                "libvlcrs",
                "unsupported device: arm64=$arm64 api=${android.os.Build.VERSION.SDK_INT}"
            )
        }
        return arm64 && api
    }
}
