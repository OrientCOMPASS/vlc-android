# libvlcrs-android — Kotlin API + demo player for the Rust engine

This is the Android half of **libvlcrs**, the lightweight Rust re-implementation
of the libvlc playback core that lives in the
[`vlc` repository](https://github.com/OrientCOMPASS/vlc/tree/feature/libvlcrs-lite/libvlcrs).
It contains:

```
libvlcrs-android/            an *independent* Gradle project (not part of the
                             VLC for Android build — it pins its own AGP/Kotlin
                             versions and never bumps them)
├── api/                     → libvlcrs-api-1.0.0.aar
│   └── src/main/java/org/videolan/libvlcrs/
│       ├── NativeBridge.kt         JNI declarations (mirrors jni_api.rs)
│       ├── SurfaceTextureBridge.kt lets the native GL context own the decoder
│       │                           output surface
│       ├── LibVLCRs.kt             library loader, device support check
│       ├── RsMediaPlayer.kt        the player API + listener
│       ├── VrVideoView.kt          SurfaceView with VR gestures and a HUD
│       └── Types.kt                ProjectionMode, Eye, PlayerState,
│                                   HudSnapshot, MediaInfo, PlayerStats …
├── demo/                    → libvlcrs-vr-demo-1.0.0-arm64-v8a.apk
│   └── src/main/java/…/demo/       MainActivity (media list) + PlayerActivity
└── tools/make_test_media.sh  generates the self-test media with ffmpeg and tags
                              it with real spherical metadata
```

Only **arm64-v8a** is built, only in **release** profile, and no existing version
number is touched.

## Why this exists

Upstream libvlc renders 360° video from container metadata only: it has no 180°
geometry, it always takes the left eye of a stereo source, and it cannot render a
spherical source as flat 2D (or a flat source as spherical). The required matrix
is therefore implemented in the engine and surfaced here:

| coverage \ layout | mono | side-by-side | top-bottom |
|---|---|---|---|
| **360°** | `E360_MONO` | `E360_SBS` | `E360_TB` |
| **180°** | `E180_MONO` | `E180_SBS` | `E180_TB` |

plus `AUTO` (resolved from `st3d`/`sv3d`/`proj`/`prhd`/`equi`, Matroska
`StereoMode`/`Projection`, or Spherical Video V1 `uuid` XML) and `PLANAR` (forced
flat 2D). Every mode can be forced on any source, and the eye (`LEFT`/`RIGHT`) is
selectable at runtime.

## Embedding it in a player framework

```kotlin
val player = RsMediaPlayer(context)          // loads libvlcrs.so, starts the engine
player.listener = object : RsMediaPlayerListener {
    override fun onPrepared(durationMs: Long, hasSphericalMetadata: Boolean) { … }
    override fun onProjectionChanged(mode: ProjectionMode, eye: Eye) { … }
    override fun onBoundaryReached(yaw: Boolean, pitch: Boolean) { … }
    override fun onError(code: ErrorCode, detail: Int) { … }
}

val view = VrVideoView(context)              // SurfaceView + gestures + HUD
setContentView(view)
view.player = player                         // attaches the surface

player.setMedia(uri)                         // content://, file://, http(s)://, path
player.projectionMode = ProjectionMode.AUTO  // or any explicit mode
player.play()
```

Transport: `play()`, `pause()`, `resume()`, `stop()`, `seekTo(ms)`, `time`,
`length`, `state`, `volume`.
VR: `projectionMode`, `eye`, `swapEyes`, `fovY`, `zoom(factor)`, `drag(dx, dy)`,
`gyroEnabled`, `displayRotation`, `recenter()`.
Read-outs: `hud` (`HudSnapshot`), `hudText`, `projectionDescription`, `mediaInfo`,
`stats`.

The shape deliberately mirrors libvlc-android (`MediaPlayer` + a video view +
an event listener), so a framework can swap engines without restructuring.

### Behaviour guarantees

* **In-place switching.** Changing `projectionMode`, `eye` or `swapEyes` is a
  uniform update on the render thread: no re-open, no seek, the playback position
  is preserved exactly.
* **180° boundary convergence.** Manual yaw/pitch converge smoothly at
  `±(90 − fov/2)`, so rotating out of a 180° picture can never show black; the
  hemisphere is additionally tessellated with clamped texture coordinates as a
  safety net. Gyroscope mode relaxes the limit to the coverage edge.
* **Gesture mapping.** One finger drag = look around (1:1 by default: a full
  screen width sweep rotates by the horizontal FOV); pinch = FOV (25°…120°);
  double tap = switch eye; single tap = toggle the HUD.
* **Surface lifecycle.** The decoder renders into a `SurfaceTexture` owned by the
  engine's own EGL context, so the application surface may be destroyed and
  recreated (rotation, backgrounding) without interrupting decoding.
* **Audio.** AAudio output, always stereo: multichannel sources are folded down by
  the engine, and audio is the master clock.

## Building

```sh
cd libvlcrs-android

# 1. the engine (from a checkout of the vlc repository)
cd ../vlc-src/libvlcrs     # or wherever the vlc repo lives
export NDK=$ANDROID_NDK_HOME
export TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$TOOLCHAIN/bin/aarch64-linux-android26-clang
export AR_aarch64_linux_android=$TOOLCHAIN/bin/llvm-ar
cargo build --release --target aarch64-linux-android -p vlcrs-lite
cp target/aarch64-linux-android/release/libvlcrs.so \
   <path-to>/libvlcrs-android/api/src/main/jniLibs/arm64-v8a/

# 2. optional: the self-test media
VLCRS_INJECTOR=<vlc>/libvlcrs/tools/spherical_inject.py \
  bash tools/make_test_media.sh demo/src/main/assets 10

# 3. the AAR and the APK (release, arm64-v8a)
gradle :api:assembleRelease :demo:assembleRelease
```

Or simply download the artefacts from the
[`vrplayer-v1.0.0-arm64` release](https://github.com/OrientCOMPASS/vlc-android/releases).

Requirements: JDK 17, Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24, Android SDK 34 with
build-tools 34.0.0, NDK r26+ and the `aarch64-linux-android` Rust target.

## CI

`.github/workflows/vr-player.yml` (on pushes to `feature/libvlcrs-vr`):

1. clones the engine at `OrientCOMPASS/vlc@feature/libvlcrs-lite`;
2. cross compiles `libvlcrs.so` (release, `aarch64-linux-android`, API 26) and
   strips it;
3. generates the self-test media and verifies it with the engine's own prober
   (the workflow fails if `Auto` does not resolve the matrix correctly);
4. builds `:api:assembleRelease` and `:demo:assembleRelease`;
5. uploads the artefacts and publishes/updates the
   `vrplayer-v1.0.0-arm64` release (APK, AAR, `.so`, header, test media,
   probe report, SHA256SUMS).

The APK is signed with the CI-generated debug key, so uninstall any previous
build before installing a new one.

## Known limitations

* arm64-v8a and API 26+ only (AAudio, package scoped sensor manager).
* Demuxing and decoding are delegated to the platform: formats supported by
  `MediaExtractor`/`MediaCodec` on the device (H.264/HEVC/VP9/AV1, AAC/MP3/Opus/
  Vorbis/FLAC in MP4/MKV/WebM/TS …). There is no bundled codec, which is exactly
  what keeps the engine at ~0.7 MB.
* Cubemap and mesh projections are *detected* and reported
  (`ErrorCode.UNSUPPORTED_PROJECTION`) but not rendered; only equirectangular
  360°/180° and flat 2D are.
* No subtitles, no audio track selection, no playback rate change, no chromecast:
  this is a playback core for the VR use case, not a full libvlc replacement.
* Matroska `ProjectionPose` is written in the official nested layout by the test
  media generator; ffmpeg follows Google's flat layout instead. The engine's
  prober reads **both**.
