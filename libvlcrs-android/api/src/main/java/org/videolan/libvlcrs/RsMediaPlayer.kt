package org.videolan.libvlcrs

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface

/**
 * A libvlcrs playback engine.
 *
 * Mirrors the shape of libvlc-android's `MediaPlayer` so an existing player
 * framework can be ported by swapping the type: [setMedia], [play], [pause],
 * [stop], [seekTo], [time], [length], [state], [volume] and a listener for
 * events.  On top of that it exposes the spherical video controls that upstream
 * libvlc does not have ([projectionMode], [eye], [fovY], [gyroEnabled],
 * [recenter], [hud], …).
 *
 * One instance owns one native engine with its own threads; call [release] when
 * done.  All methods are safe to call from any thread.
 */
class RsMediaPlayer(
    private val context: Context,
    private val surfaceTextureBridge: SurfaceTextureBridge = SurfaceTextureBridge()
) : NativeCallback {

    /** Opaque native handle; `0` after [release]. */
    @Volatile
    var handle: Long = 0L
        private set

    /** Events are delivered on the main thread through this listener. */
    @Volatile
    var listener: RsMediaPlayerListener? = null

    private val main = Handler(Looper.getMainLooper())
    private val viewBuffer = FloatArray(HudSnapshot.FLOAT_COUNT)
    private val infoBuffer = IntArray(MediaInfo.INT_COUNT)
    private val statsBuffer = LongArray(PlayerStats.LONG_COUNT)
    private var assetFd: ParcelFileDescriptor? = null
    private var assetDescriptor: AssetFileDescriptor? = null
    private var attachedSurface: Surface? = null

    init {
        LibVLCRs.ensureLoaded()
        val created = NativeBridge.nativeCreate(this, surfaceTextureBridge, context.packageName)
        require(created != 0L) { "libvlcrs could not create an engine instance" }
        handle = created
        Log.i(TAG, "engine created (handle=$created, libvlcrs ${NativeBridge.nativeVersion() ?: "?"})")
    }

    private val h: Long
        get() = handle

    private fun call(status: Int): Status {
        val s = Status.from(status)
        if (s != Status.OK && s != Status.BAD_STATE) {
            Log.w(TAG, "native call returned $s")
        }
        return s
    }

    // ---------------------------------------------------------------- media

    /**
     * Set the item to play.
     *
     * `content://` and `file://` URIs are opened as a descriptor so that the
     * container prober can read the spherical metadata; `http(s)://` and other
     * schemes are handed to the platform demuxer directly.
     */
    fun setMedia(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "content" || scheme == "file" || scheme == null) {
            if (setMediaFromDescriptor(uri)) return true
            if (scheme == null) {
                // a plain path: let the extractor open it
                return call(NativeBridge.nativeSetMediaUri(h, uri.toString())).isOk
            }
        }
        return call(NativeBridge.nativeSetMediaUri(h, uri.toString())).isOk
    }

    /** Set a plain filesystem path. */
    fun setMedia(path: String): Boolean =
        call(NativeBridge.nativeSetMediaUri(h, path)).isOk

    /** Set an asset opened with [android.content.res.AssetManager.openFd]. */
    fun setMedia(afd: AssetFileDescriptor): Boolean {
        closeAsset()
        assetDescriptor = afd
        return call(
            NativeBridge.nativeSetMediaFd(
                h,
                afd.parcelFileDescriptor.fd,
                afd.startOffset,
                afd.declaredLength.takeIf { it > 0 } ?: afd.length
            )
        ).isOk
    }

    private fun setMediaFromDescriptor(uri: Uri): Boolean {
        return try {
            val resolver = context.contentResolver
            val afd = runCatching { resolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
            if (afd != null) {
                return setMedia(afd)
            }
            val pfd = resolver.openFileDescriptor(uri, "r") ?: return false
            closeAsset()
            assetFd = pfd
            val length = pfd.statSize.takeIf { it > 0 } ?: 0L
            call(NativeBridge.nativeSetMediaFd(h, pfd.fd, 0L, length)).isOk
        } catch (t: Throwable) {
            Log.w(TAG, "cannot open $uri: ${t.message}")
            false
        }
    }

    private fun closeAsset() {
        runCatching { assetDescriptor?.close() }
        runCatching { assetFd?.close() }
        assetDescriptor = null
        assetFd = null
    }

    // ------------------------------------------------------------ transport

    /** Start playback of the current item. */
    fun play(): Boolean = call(NativeBridge.nativePlay(h)).isOk

    /** Pause; the view can still be looked around. */
    fun pause(): Boolean = call(NativeBridge.nativePause(h)).isOk

    /** Resume after [pause]. */
    fun resume(): Boolean = call(NativeBridge.nativeResume(h)).isOk

    /** Stop and unload the item. */
    fun stop(): Boolean = call(NativeBridge.nativeStop(h)).isOk

    /** Seek to an absolute position in milliseconds. */
    fun seekTo(positionMs: Long): Boolean = call(NativeBridge.nativeSeekTo(h, positionMs)).isOk

    /** Current position in milliseconds. */
    val time: Long
        get() = if (handle == 0L) 0L else NativeBridge.nativeGetTime(h).coerceAtLeast(0L)

    /** Duration in milliseconds, `0` while unknown. */
    val length: Long
        get() = if (handle == 0L) 0L else NativeBridge.nativeGetLength(h)

    /** Current engine state. */
    val state: PlayerState
        get() = if (handle == 0L) PlayerState.IDLE
        else PlayerState.fromId(NativeBridge.nativeGetState(h))

    /** `true` while playing. */
    val isPlaying: Boolean
        get() = state == PlayerState.PLAYING

    /** Output volume, `0.0 … 1.0`. */
    var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (handle != 0L) NativeBridge.nativeSetVolume(h, field)
        }

    // -------------------------------------------------------------- surface

    /** Attach the output surface (usually done by [VrVideoView]). */
    fun setSurface(surface: Surface?, width: Int, height: Int) {
        if (handle == 0L) return
        attachedSurface = surface
        NativeBridge.nativeSetSurface(h, surface)
        if (surface != null && width > 0 && height > 0) {
            NativeBridge.nativeSurfaceChanged(h, width, height)
        }
    }

    /** Detach the output surface; decoding continues, rendering pauses. */
    fun clearSurface() {
        if (handle == 0L) return
        attachedSurface = null
        NativeBridge.nativeSetSurface(h, null)
    }

    /** Notify a surface size change. */
    fun surfaceChanged(width: Int, height: Int) {
        if (handle != 0L) NativeBridge.nativeSurfaceChanged(h, width, height)
    }

    // ------------------------------------------------------------------- VR

    /** Active projection mode; switching keeps the current position. */
    var projectionMode: ProjectionMode = ProjectionMode.AUTO
        set(value) {
            field = value
            if (handle != 0L) NativeBridge.nativeSetProjectionMode(h, value.id)
        }
        get() = if (handle == 0L) field
        else ProjectionMode.fromId(NativeBridge.nativeGetProjectionMode(h))

    /** Active eye for stereo layouts; switching keeps the current position. */
    var eye: Eye = Eye.LEFT
        set(value) {
            field = value
            if (handle != 0L) NativeBridge.nativeSetEye(h, value.id)
        }
        get() = if (handle == 0L) field else Eye.fromId(NativeBridge.nativeGetEye(h))

    /** Invert the eye packing order advertised by the container. */
    var swapEyes: Boolean = false
        set(value) {
            field = value
            if (handle != 0L) NativeBridge.nativeSetSwapEyes(h, value)
        }

    /** Vertical field of view in degrees (25…120). */
    var fovY: Float = DEFAULT_FOV
        set(value) {
            field = value.coerceIn(MIN_FOV, MAX_FOV)
            if (handle != 0L) NativeBridge.nativeSetFov(h, field)
        }

    /** Pinch zoom: `factor > 1` zooms in. */
    fun zoom(factor: Float) {
        if (handle != 0L) NativeBridge.nativeZoom(h, factor)
    }

    /** Single finger drag in pixels; the picture follows the finger. */
    fun drag(dxPixels: Float, dyPixels: Float) {
        if (handle != 0L) NativeBridge.nativeDrag(h, dxPixels, dyPixels)
    }

    /** Single finger drag already expressed in degrees. */
    fun dragDegrees(dYaw: Float, dPitch: Float) {
        if (handle != 0L) NativeBridge.nativeDragDegrees(h, dYaw, dPitch)
    }

    /** Gyroscope look-around ("turn the device to look around"). */
    var gyroEnabled: Boolean = false
        set(value) {
            field = value
            if (handle != 0L) NativeBridge.nativeSetGyro(h, value, displayRotation)
        }
        get() = handle != 0L && NativeBridge.nativeIsGyroEnabled(h)

    /** Display rotation handed to the head tracker (0/90/180/270). */
    var displayRotation: Int = 90
        set(value) {
            field = ((value % 360) + 360) % 360
            if (handle != 0L) NativeBridge.nativeSetGyro(h, gyroEnabled, field)
        }

    /** "视角摆正": reset the manual offsets and re-align the head tracker. */
    fun recenter() {
        if (handle != 0L) NativeBridge.nativeRecenter(h)
    }

    // ------------------------------------------------------------ read-outs

    /** The current HUD sample (yaw/pitch/FOV/mode/eye/limits/position…). */
    val hud: HudSnapshot
        get() {
            if (handle == 0L) return HudSnapshot()
            val n = NativeBridge.nativeGetViewInfo(h, viewBuffer)
            return if (n > 0) HudSnapshot.from(viewBuffer) else HudSnapshot()
        }

    /** The formatted multi-line HUD text produced by the engine. */
    val hudText: String
        get() = if (handle == 0L) "" else NativeBridge.nativeGetHudText(h).orEmpty()

    /** One line description of the resolved projection. */
    val projectionDescription: String
        get() = if (handle == 0L) "" else NativeBridge.nativeDescribeProjection(h).orEmpty()

    /** Static information about the loaded item. */
    val mediaInfo: MediaInfo
        get() {
            if (handle == 0L) return MediaInfo()
            val n = NativeBridge.nativeGetMediaInfo(h, infoBuffer)
            return if (n > 0) MediaInfo.from(infoBuffer, length) else MediaInfo(durationMs = length)
        }

    /** Runtime counters. */
    val stats: PlayerStats
        get() {
            if (handle == 0L) return PlayerStats()
            val n = NativeBridge.nativeGetStats(h, statsBuffer)
            return if (n > 0) PlayerStats.from(statsBuffer) else PlayerStats()
        }

    /** The surface currently attached, if any. */
    val surface: Surface?
        get() = attachedSurface

    // ------------------------------------------------------------- lifecycle

    /** Release the engine.  Safe to call twice. */
    fun release() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        listener = null
        runCatching { NativeBridge.nativeStop(current) }
        runCatching { NativeBridge.nativeSetSurface(current, null) }
        runCatching { NativeBridge.nativeDestroy(current) }
        surfaceTextureBridge.release()
        closeAsset()
        attachedSurface = null
        Log.i(TAG, "engine released")
    }

    // -------------------------------------------------------------- callback

    /** Invoked from an engine thread; dispatched to the main thread. */
    override fun onNativeEvent(handle: Long, type: Int, arg1: Int, arg2: Int) {
        if (handle != this.handle) return
        val target = listener ?: return
        main.post { dispatch(target, type, arg1, arg2) }
    }

    private fun dispatch(l: RsMediaPlayerListener, type: Int, arg1: Int, arg2: Int) {
        try {
            when (type) {
                PlayerEvent.PREPARED -> l.onPrepared(arg1.toLong(), arg2 != 0)
                PlayerEvent.PLAYING -> l.onPlaying()
                PlayerEvent.PAUSED -> l.onPaused()
                PlayerEvent.STOPPED -> l.onStopped()
                PlayerEvent.END_REACHED -> l.onEndReached()
                PlayerEvent.BUFFERING -> l.onBuffering(arg1)
                PlayerEvent.VIDEO_SIZE -> l.onVideoSize(arg1, arg2)
                PlayerEvent.PROJECTION_CHANGED ->
                    l.onProjectionChanged(ProjectionMode.fromId(arg1), Eye.fromId(arg2))
                PlayerEvent.BOUNDARY_REACHED ->
                    l.onBoundaryReached(arg1 and 1 != 0, arg1 and 2 != 0)
                PlayerEvent.SEEKED -> l.onSeeked(arg1.toLong())
                PlayerEvent.FIRST_FRAME -> l.onFirstFrame()
                PlayerEvent.ERROR -> l.onError(ErrorCode.fromId(arg1), arg2)
                PlayerEvent.LOG -> l.onLog(arg1, arg2)
                else -> Log.d(TAG, "unknown event $type ($arg1, $arg2)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "listener threw", t)
        }
    }

    companion object {
        private const val TAG = "libvlcrs"

        /** Default vertical field of view in degrees. */
        const val DEFAULT_FOV = 75f

        /** Smallest supported vertical field of view. */
        const val MIN_FOV = 25f

        /** Largest supported vertical field of view. */
        const val MAX_FOV = 120f
    }
}

/**
 * Player events.  Every method has a default no-op implementation, so listeners
 * only override what they need.
 */
interface RsMediaPlayerListener {
    /** The item is open.  [hasSphericalMetadata] tells whether `Auto` has data. */
    fun onPrepared(durationMs: Long, hasSphericalMetadata: Boolean) {}

    fun onPlaying() {}
    fun onPaused() {}
    fun onStopped() {}
    fun onEndReached() {}

    /** Buffering percentage (0…100). */
    fun onBuffering(percent: Int) {}

    /** Coded picture size. */
    fun onVideoSize(width: Int, height: Int) {}

    /** The resolved projection changed (mode switch, eye switch, new item). */
    fun onProjectionChanged(mode: ProjectionMode, eye: Eye) {}

    /** The view reached a coverage boundary (180° sources). */
    fun onBoundaryReached(yaw: Boolean, pitch: Boolean) {}

    /** A seek completed. */
    fun onSeeked(positionMs: Long) {}

    /** The first frame was rendered. */
    fun onFirstFrame() {}

    /** A fatal or recoverable error. */
    fun onError(code: ErrorCode, detail: Int) {}

    /** Engine log notification. */
    fun onLog(level: Int, code: Int) {}
}
