package org.videolan.libvlcrs

import android.view.Surface

/**
 * Raw JNI surface of `libvlcrs.so`.
 *
 * Everything takes the opaque player handle returned by [nativeCreate] as its
 * first argument.  The declarations mirror `Java_org_videolan_libvlcrs_NativeBridge_*`
 * in `crates/vlcrs-lite/src/jni_api.rs`; keep the two in sync.
 *
 * This object is public on purpose: Kotlin mangles `internal` member names, and
 * the native side resolves these methods by name.  Application code should use
 * [RsMediaPlayer] instead.
 */
object NativeBridge {

    // ---- lifecycle -------------------------------------------------------
    external fun nativeCreate(
        callback: NativeCallback,
        surfaceTextureBridge: SurfaceTextureBridge,
        packageName: String
    ): Long

    external fun nativeDestroy(handle: Long)

    // ---- media & transport ----------------------------------------------
    external fun nativeSetMediaUri(handle: Long, uri: String): Int
    external fun nativeSetMediaFd(handle: Long, fd: Int, offset: Long, length: Long): Int
    external fun nativePlay(handle: Long): Int
    external fun nativePause(handle: Long): Int
    external fun nativeResume(handle: Long): Int
    external fun nativeStop(handle: Long): Int
    external fun nativeSeekTo(handle: Long, positionMs: Long): Int
    external fun nativeGetTime(handle: Long): Long
    external fun nativeGetLength(handle: Long): Long
    external fun nativeGetState(handle: Long): Int
    external fun nativeSetVolume(handle: Long, volume: Float): Int

    // ---- surface --------------------------------------------------------
    external fun nativeSetSurface(handle: Long, surface: Surface?): Int
    external fun nativeSurfaceChanged(handle: Long, width: Int, height: Int): Int

    // ---- VR view --------------------------------------------------------
    external fun nativeSetProjectionMode(handle: Long, mode: Int): Int
    external fun nativeGetProjectionMode(handle: Long): Int
    external fun nativeSetEye(handle: Long, eye: Int): Int
    external fun nativeGetEye(handle: Long): Int
    external fun nativeSetSwapEyes(handle: Long, swap: Boolean): Int
    external fun nativeSetFov(handle: Long, fovY: Float): Int
    external fun nativeZoom(handle: Long, factor: Float): Int
    external fun nativeDrag(handle: Long, dxPixels: Float, dyPixels: Float): Int
    external fun nativeDragDegrees(handle: Long, dYawDegrees: Float, dPitchDegrees: Float): Int
    external fun nativeSetGyro(handle: Long, enabled: Boolean, displayRotation: Int): Int
    external fun nativeRecenter(handle: Long): Int
    external fun nativeIsGyroEnabled(handle: Long): Boolean

    // ---- read-outs ------------------------------------------------------
    external fun nativeGetViewInfo(handle: Long, out: FloatArray): Int
    external fun nativeGetMediaInfo(handle: Long, out: IntArray): Int
    external fun nativeGetStats(handle: Long, out: LongArray): Int
    external fun nativeGetHudText(handle: Long): String
    external fun nativeDescribeProjection(handle: Long): String
    external fun nativeVersion(): String
}

/** Callback interface implemented by [RsMediaPlayer]; invoked from engine threads. */
interface NativeCallback {
    /**
     * @param type one of [PlayerEvent.Type]
     * @param arg1 meaning depends on [type]
     * @param arg2 meaning depends on [type]
     */
    fun onNativeEvent(handle: Long, type: Int, arg1: Int, arg2: Int)
}
