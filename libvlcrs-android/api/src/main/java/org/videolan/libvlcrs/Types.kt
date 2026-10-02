package org.videolan.libvlcrs

/**
 * Projection formats supported by libvlcrs.
 *
 * The matrix is *coverage* × *stereo layout*, plus [AUTO] (resolved from the
 * container metadata) and [PLANAR] (forced flat 2D).  Any mode can be forced on
 * any source, so an ordinary 2D file can be rendered as 360°/180° SBS/TB.
 *
 * The integer ids are the ABI shared with `vlcrs_vr::ProjectionMode` and with
 * `include/vlcrs.h`.
 */
enum class ProjectionMode(val id: Int, val label: String) {
    /** Resolve coverage and layout from the container metadata. */
    AUTO(0, "Auto"),

    /** Force flat 2D rendering. */
    PLANAR(1, "Planar 2D"),

    /** 360° equirectangular, single eye. */
    E360_MONO(2, "360° Mono"),

    /** 360° equirectangular, side-by-side stereo source. */
    E360_SBS(3, "360° SBS"),

    /** 360° equirectangular, top-bottom stereo source. */
    E360_TB(4, "360° TB"),

    /** 180° equirectangular hemisphere, single eye. */
    E180_MONO(5, "180° Mono"),

    /** 180° hemisphere, side-by-side stereo source. */
    E180_SBS(6, "180° SBS"),

    /** 180° hemisphere, top-bottom stereo source. */
    E180_TB(7, "180° TB");

    companion object {
        /** All modes, in UI order. */
        val ALL: List<ProjectionMode> = values().toList()

        fun fromId(id: Int): ProjectionMode = values().firstOrNull { it.id == id } ?: AUTO
    }
}

/** Which eye of a stereo layout is rendered. */
enum class Eye(val id: Int, val label: String) {
    LEFT(0, "Left"),
    RIGHT(1, "Right");

    /** The other eye. */
    fun other(): Eye = if (this == LEFT) RIGHT else LEFT

    companion object {
        fun fromId(id: Int): Eye = if (id == 1) RIGHT else LEFT
    }
}

/** Engine state, mirroring `vlcrs::api::PlayerState`. */
enum class PlayerState(val id: Int, val label: String) {
    IDLE(0, "Idle"),
    OPENING(1, "Opening"),
    PREPARED(2, "Prepared"),
    BUFFERING(3, "Buffering"),
    PLAYING(4, "Playing"),
    PAUSED(5, "Paused"),
    STOPPED(6, "Stopped"),
    END_REACHED(7, "EndReached"),
    ERROR(8, "Error");

    companion object {
        fun fromId(id: Int): PlayerState = values().firstOrNull { it.id == id } ?: ERROR
    }
}

/** Error codes reported through [RsMediaPlayerListener.onError]. */
enum class ErrorCode(val id: Int, val label: String) {
    UNKNOWN(0, "Unknown error"),
    OPEN_FAILED(1, "Cannot open the media"),
    NO_TRACKS(2, "No playable track"),
    VIDEO_DECODER_FAILED(3, "No video decoder"),
    VIDEO_CONFIGURE_FAILED(4, "Video decoder rejected the format"),
    AUDIO_DECODER_FAILED(5, "No audio decoder"),
    AUDIO_OUTPUT_FAILED(6, "Audio output unavailable"),
    RENDER_FAILED(7, "OpenGL/EGL initialisation failed"),
    UNSUPPORTED_PROJECTION(8, "Unsupported projection (cubemap/mesh)"),
    DECODE_FAILED(9, "Decode error");

    companion object {
        fun fromId(id: Int): ErrorCode = values().firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** Event ids delivered by the native engine (`vlcrs::api::EventType`). */
object PlayerEvent {
    const val PREPARED = 1
    const val PLAYING = 2
    const val PAUSED = 3
    const val STOPPED = 4
    const val END_REACHED = 5
    const val BUFFERING = 6
    const val VIDEO_SIZE = 7
    const val PROJECTION_CHANGED = 8
    const val BOUNDARY_REACHED = 9
    const val ERROR = 10
    const val LOG = 11
    const val SEEKED = 12
    const val FIRST_FRAME = 13
}

/** Coverage/layout the renderer resolved for the current item. */
enum class Coverage(val id: Int, val label: String) {
    UNKNOWN(-1, "unknown"),
    PLANAR(0, "2D"),
    FULL_360(1, "360°"),
    HALF_180(2, "180°");

    companion object {
        fun fromId(id: Int): Coverage = values().firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** Stereo packing of the source. */
enum class StereoLayout(val id: Int, val label: String) {
    UNKNOWN(-1, "unknown"),
    MONO(0, "mono"),
    SIDE_BY_SIDE(1, "SBS"),
    TOP_BOTTOM(2, "TB");

    companion object {
        fun fromId(id: Int): StereoLayout = values().firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** Container family as seen by the prober. */
enum class Container(val id: Int, val label: String) {
    UNKNOWN(0, "unknown"),
    MP4(1, "mp4"),
    MATROSKA(2, "mkv"),
    OTHER(3, "other");

    companion object {
        fun fromId(id: Int): Container = values().firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** Where the active projection came from. */
enum class ProjectionSource(val id: Int, val label: String) {
    FORCED(0, "forced"),
    METADATA(1, "metadata"),
    FALLBACK(2, "fallback");

    companion object {
        fun fromId(id: Int): ProjectionSource = values().firstOrNull { it.id == id } ?: FALLBACK
    }
}

/**
 * One HUD sample — the read-outs shown while looking around
 * (yaw / pitch / FOV / mode / eye / limits / position …).
 *
 * Filled from the compact float array published by the render thread, so
 * reading it never blocks playback.
 */
data class HudSnapshot(
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val fovY: Float = 0f,
    val fovX: Float = 0f,
    val zoom: Float = 1f,
    val mode: ProjectionMode = ProjectionMode.AUTO,
    val coverage: Coverage = Coverage.PLANAR,
    val layout: StereoLayout = StereoLayout.MONO,
    val eye: Eye = Eye.LEFT,
    val source: ProjectionSource = ProjectionSource.FALLBACK,
    val atYawLimit: Boolean = false,
    val atPitchLimit: Boolean = false,
    val converging: Boolean = false,
    val resistance: Float = 0f,
    val gyroEnabled: Boolean = false,
    val gyroReady: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val fps: Float = 0f
) {
    /** Single line summary, e.g. for a status bar. */
    fun compact(): String =
        "yaw %+.1f° pitch %+.1f° fov %.0f°×%.0f° %s %s%s [%s]".format(
            yaw, pitch, fovY, fovX, coverage.label, layout.label,
            if (layout == StereoLayout.MONO) "" else " eye ${eye.label}",
            source.label
        )

    companion object {
        /** Number of floats in the native representation. */
        const val FLOAT_COUNT = 20

        // field indices, kept in sync with vlcrs_vr::hud::field
        private const val YAW = 0
        private const val PITCH = 1
        private const val ROLL = 2
        private const val FOV_Y = 3
        private const val FOV_X = 4
        private const val MODE = 5
        private const val COVERAGE = 6
        private const val LAYOUT = 7
        private const val EYE = 8
        private const val SOURCE = 9
        private const val AT_YAW_LIMIT = 10
        private const val AT_PITCH_LIMIT = 11
        private const val CONVERGING = 12
        private const val RESISTANCE = 13
        private const val GYRO = 14
        private const val GYRO_READY = 15
        private const val ZOOM = 16
        private const val POSITION_S = 17
        private const val DURATION_S = 18
        private const val FPS = 19

        /** Build a snapshot from the native float array. */
        fun from(v: FloatArray): HudSnapshot {
            if (v.size < FLOAT_COUNT) return HudSnapshot()
            return HudSnapshot(
                yaw = v[YAW],
                pitch = v[PITCH],
                roll = v[ROLL],
                fovY = v[FOV_Y],
                fovX = v[FOV_X],
                zoom = v[ZOOM],
                mode = ProjectionMode.fromId(v[MODE].toInt()),
                coverage = Coverage.fromId(v[COVERAGE].toInt()),
                layout = StereoLayout.fromId(v[LAYOUT].toInt()),
                eye = Eye.fromId(v[EYE].toInt()),
                source = ProjectionSource.fromId(v[SOURCE].toInt()),
                atYawLimit = v[AT_YAW_LIMIT] > 0.5f,
                atPitchLimit = v[AT_PITCH_LIMIT] > 0.5f,
                converging = v[CONVERGING] > 0.5f,
                resistance = v[RESISTANCE],
                gyroEnabled = v[GYRO] > 0.5f,
                gyroReady = v[GYRO_READY] > 0.5f,
                positionMs = (v[POSITION_S] * 1000f).toLong(),
                durationMs = (v[DURATION_S] * 1000f).toLong(),
                fps = v[FPS]
            )
        }
    }
}

/** Static information about the loaded item. */
data class MediaInfo(
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
    val hasSphericalMetadata: Boolean = false,
    val coverage: Coverage = Coverage.UNKNOWN,
    val layout: StereoLayout = StereoLayout.UNKNOWN,
    val container: Container = Container.UNKNOWN,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val fps: Float = 0f,
    val durationMs: Long = 0
) {
    companion object {
        /** Number of ints in the native representation. */
        const val INT_COUNT = 10

        fun from(v: IntArray, durationMs: Long): MediaInfo {
            if (v.size < INT_COUNT) return MediaInfo(durationMs = durationMs)
            return MediaInfo(
                width = v[0],
                height = v[1],
                rotation = v[2],
                hasSphericalMetadata = v[3] != 0,
                coverage = Coverage.fromId(v[4]),
                layout = StereoLayout.fromId(v[5]),
                container = Container.fromId(v[6]),
                sampleRate = v[7],
                channels = v[8],
                fps = v[9] / 100f,
                durationMs = durationMs
            )
        }
    }
}

/** Runtime counters. */
data class PlayerStats(
    val decodedFrames: Long = 0,
    val renderedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val audioUnderruns: Long = 0,
    val seeks: Long = 0,
    val fps: Float = 0f,
    val bufferedMs: Long = 0,
    val eventsPosted: Long = 0
) {
    companion object {
        /** Number of longs in the native representation. */
        const val LONG_COUNT = 8

        fun from(v: LongArray): PlayerStats {
            if (v.size < LONG_COUNT) return PlayerStats()
            return PlayerStats(
                decodedFrames = v[0],
                renderedFrames = v[1],
                droppedFrames = v[2],
                audioUnderruns = v[3],
                seeks = v[4],
                fps = v[5] / 100f,
                bufferedMs = v[6],
                eventsPosted = v[7]
            )
        }
    }
}

/** Status of a control call (`vlcrs::api::Status`). */
enum class Status(val code: Int) {
    OK(0),
    BAD_HANDLE(-1),
    BAD_ARGUMENT(-2),
    BAD_STATE(-3),
    UNSUPPORTED(-4),
    FAILED(-5);

    val isOk: Boolean get() = code == 0

    companion object {
        fun from(code: Int): Status = values().firstOrNull { it.code == code } ?: FAILED
    }
}
