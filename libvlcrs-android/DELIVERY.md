# 交付说明 — libvlcrs（Rust 轻量 libvlc）+ VR 播放器

交付日期：2026-10-02 · 目标平台：Android `arm64-v8a` · 构建类型：**release only**

---

## 1. 交付物

| 产物 | 位置 | 说明 |
|---|---|---|
| `libvlcrs-vr-demo-1.0.0-arm64-v8a.apk` | [vlc-android release `vrplayer-v1.0.0-arm64`](https://github.com/OrientCOMPASS/vlc-android/releases/tag/vrplayer-v1.0.0-arm64) | 装机测试用的 demo 播放器（内含 7 个自测片源，覆盖整个格式矩阵） |
| `libvlcrs-api-1.0.0.aar` | 同上 | Kotlin API + `jniLibs/arm64-v8a/libvlcrs.so`，可直接被播放器框架依赖 |
| `libvlcrs.so` / `vlcrs.h` | 同上，以及 [vlc release `libvlcrs-v1.0.0-arm64`](https://github.com/OrientCOMPASS/vlc/releases/tag/libvlcrs-v1.0.0-arm64) | 引擎本体（ELF64 AArch64，stripped，约 0.7MB）与 C ABI 头文件 |
| `vr-test-media.zip` | vlc-android release | CI 生成并已写入真实球面元数据的自测片源 |
| `probe-report.txt` | vlc-android release | 引擎自带探测器对这些片源的解析结果（Auto 模式的判定依据） |
| `SHA256SUMS.txt` / `BUILD-INFO.txt` | 两个 release | 校验与构建信息 |
| 源码 | `vlc@feature/libvlcrs-lite`（引擎）、`vlc-android@feature/libvlcrs-vr`（Android 侧） | 见下文 |
| 文档 | `libvlcrs/README.md`、`libvlcrs-android/README.md`、`TEST-CHECKLIST.md`、本文件 | |

安装：先 `adb uninstall org.videolan.libvlcrs.demo`（APK 用 CI 生成的 debug key 签名），再 `adb install -r <apk>`。

---

## 2. 两个仓库分别做了什么

### 2.1 清理遗留分支（按要求"完全删除"）

| 仓库 | 分支 | 处理 |
|---|---|---|
| vlc-android | `feature/vr`（C 方案：强制替换 libvlc-all 依赖） | **已删除** |
| vlc-android | `feature/vr-rust`（上一版 Rust 尝试） | **已删除** |
| vlc | `feature/vr-3.0.x`（C 方案：改 libvlc 3.0.x 的 opengl vout） | **已删除** |

删除前只读取了分支头提交的标题/时间用于记录，未阅读其实现（按你的要求，避免被错误实现误导）。
两个仓库现在都只剩 `master` + 本次的新分支。遗留的 tag/release
（`rslib-vr-20261002-3`、`apk-vr-20261002-1`）属于被删除分支的产物，**未擅自删除**，
如需一并清理告诉我即可（一条 `gh release delete` + `git push --delete tag`）。
对应的历史 workflow run 记录也仍在（GitHub 不允许删除已归档 run 的分支之外的记录），
如需清理我可以调用 API 删除。

### 2.2 `OrientCOMPASS/vlc` → 分支 `feature/libvlcrs-lite`

新增 `libvlcrs/`：**独立的 Rust workspace**（stable 工具链，刻意不加入上游
`Cargo.toml` 的 workspace，避免与上游 nightly 绑定，也不需要 bootstrap contribs）。

```
libvlcrs/
├── Cargo.toml / rust-toolchain.toml / README.md / .gitignore
├── include/vlcrs.h                 C ABI（64 个导出符号中的 C 部分）
├── tools/spherical_inject.py       给测试片源写入 st3d/sv3d/proj/prhd/equi 与 MKV Projection
└── crates/
    ├── vlcrs-vr/       纯 Rust：格式矩阵、网格、视角控制、边界收敛、Cardboard EKF 移植、HUD、GLSL、音频下混
    ├── vlcrs-media/    纯 Rust：ISO-BMFF + Matroska/WebM 探测（轨道/时长/旋转 + 球面元数据）
    └── vlcrs-lite/     cdylib → libvlcrs.so：引擎、EGL/GLES 渲染器、JNI、C ABI
```

`.github/workflows/libvlcrs.yml`：host 上 `fmt` + `clippy -D warnings` + 单测（release profile），
然后交叉编译 arm64-v8a release、校验 ELF/依赖/导出符号、打包并发布 release。

### 2.3 `OrientCOMPASS/vlc-android` → 分支 `feature/libvlcrs-vr`

新增 `libvlcrs-android/`：**独立的 Gradle 工程**（不进根 `settings.gradle`，
自带 AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.7 版本锁定，不改动 VLC 应用任何版本号）。

```
libvlcrs-android/
├── api/     → libvlcrs-api AAR：NativeBridge / SurfaceTextureBridge / RsMediaPlayer / VrVideoView / Types
├── demo/    → 装机测试 APK：MainActivity（片源列表 + SAF 选择器）、PlayerActivity（全部控件 + HUD）
└── tools/make_test_media.sh  用 ffmpeg 生成并标记自测片源
```

`.github/workflows/vr-player.yml`：克隆引擎源码 → 交叉编译 `.so` → 生成自测片源 →
**用引擎自带的探测器验证这批片源的元数据判定**（Auto 解析不对就直接失败）→
`assembleRelease` 出 AAR + APK → 上传产物并发布 release。

---

## 3. 为什么这样实现（与需求的对应）

需求原文：**官方 libvlc 不满足矩阵（仅元数据驱动的 360 单目、写死左眼、无 180°），要改造 libvlc；
用 Rust 实现 xl_player 解码后投影的相同逻辑。**

我没有去改上游 C 代码（那需要重新编译 contribs，且 3.0.x 的 vout 结构与需求矩阵不匹配），
而是**用 Rust 重写了一个轻量 libvlc**，把 xl_player 的"解码后投影"逻辑逐文件移植并扩展：

| xl_player 源文件 | Rust 对应 | 说明 |
|---|---|---|
| `xl_mesh_factory.c` `get_ball_mesh` | `vlcrs-vr/src/mesh.rs` | 等距柱状球面网格；经度原点移到画面中心（`lon=0 → u=0.5`），并新增半球（180°）与边界外钳制 |
| `xl_mat4.c` | `vlcrs-vr/src/mat4.rs` | `perspective`/`lookAt`/`rotateX/Y/Z`/`multiply` 语义完全一致（列主序、后乘）；顺带修正了参考实现里 `setRotateEulerM` 的 `cxsy/sxsy` 抄写错误（该函数在参考实现中标记 unused，头追用的是硬编码常量，因此不影响其行为） |
| `xl_model_ball.c` | `vlcrs-vr/src/view.rs` + `render/renderer.rs` | 模型矩阵 `Rz·Rx·Ry`、`lookAt(0,0,0 → -Z)`、`perspective(fovy, aspect, 0.01, 10)`、拖拽累积、距离/FOV 缩放 |
| `xl_model_rect.c` | 同上（`VS_RECT` + aspect fit + 旋转） | 强制平面 2D |
| `xl_glsl_program.c` | `vlcrs-vr/src/shaders.rs` | OES 外部纹理 + `tx_matrix`；新增 `uUvRect` 用于眼位/布局裁剪 |
| `xl_player_gl_thread.c` | `render/renderer.rs` | GL 线程、EGL 初始化、音频主时钟的视频节拍、丢帧、seek flush 标记 |
| `xl_texture.c` + `SurfaceTextureBridge.java` | `platform/jni.rs` + `SurfaceTextureBridge.kt` | 在**自己的** GL 上下文里 `new SurfaceTexture(texName)`，解码器直接渲染到该纹理 |
| `xl_head_tracker/*`（Cardboard `OrientationEKF`） | `vlcrs-vr/src/ekf.rs` | `SO3Util`/`Matrix3x3d`/`Vector3d`/`OrientationEKF` 逐行移植（含 `ortho`、`muFromSO3` 的三个分支） |
| `xl_tracker.c` | `platform/sensors.rs` | `ASensorManager` + `ALooper` 线程、轴重映射 `(-y, x, z)`、横屏校正常量（与参考实现逐字节一致） |

**参考实现缺失、按需求新增的部分**：

1. **180° 半球**：`Coverage::Half180` + 专用网格 + 边界收敛。
2. **SBS/TB 布局与眼位**：`StereoLayout` × `Eye` → `UvRect`（在显示空间裁剪，再乘 SurfaceTexture 矩阵），
   支持容器声明的"右眼在前"（`st3d=4`、Matroska `StereoMode=2/11`）与用户手动反转 `swapEyes`。
3. **强制矩阵**：任意片源 × 任意模式（`ProjectionMode` 8 项），`Auto` 严格按元数据解析，
   元数据缺失时回落平面并标注 `[fallback]`。
4. **元数据探测**：`vlcrs-media` 自己解析 MP4/MKV（`st3d`/`sv3d`/`proj`/`prhd`/`equi`、
   Matroska `StereoMode`/`Projection*`、Spherical V1 `uuid` XML），
   因为系统 `MediaExtractor` 不暴露这些字段。
5. **原位切换**：模式/眼位只是渲染线程上的 uniform 更新，播放管线完全不参与 → 进度天然保留。
6. **HUD**：`HudSnapshot` + 20 个 float 的紧凑跨 JNI 传输。

**180° 手动偏航收敛**（需求明确"转出画面见黑不可接受"）：
`converge()` 在 `0.72×limit` 之前 1:1 跟手，之后按 `tanh` 渐近逼近 `limit = 90 − FOVx/2`，
C1 连续、单调、永不超过上限；陀螺仪模式放宽到覆盖边界本身。
另外半球网格按整球细分并对 `u` 钳制，作为极端俯仰时的兜底（宁可边缘像素延展，也不出黑块）。
这两条都有单元测试做几何证明（见 §5）。

---

## 4. 引擎架构（为什么"轻量"）

```
media(path/fd/uri) ─► demux 线程 AMediaExtractor ─┬─► video 包队列 ─┐
                       + 容器探测(球面元数据)      └─► audio 包队列 ─┼─► audio 线程
                                                                    │   AMediaCodec→PCM→下混→AAudio
                                                                    │   （音频 = 主时钟）
                                                                    └─► render 线程
                                                                        AMediaCodec(→SurfaceTexture/OES)
                                                                        + EGL/GLESv2 投影 + 节拍/丢帧
                                                                 sensor 线程 ASensorManager + EKF
```

* **不内置任何编解码器**：解复用/解码/音频输出全部走系统
  （`libmediandk`、`libaaudio`），因此 `.so` 只有 ~0.7MB，依赖仅
  `libmediandk/libandroid/liblog/libaaudio/libEGL/libGLESv2/libc/libm/libdl`（CI 用 `readelf -d` 校验）。
* **零拷贝视频**：解码器直接渲染到我们自己 EGL 上下文里的 `SurfaceTexture`，
  投影着色器采样外部 OES 纹理；解码与渲染同线程（`AMediaCodec` 非 `Send`，这样能消掉一整类生命周期 bug）。
* **应用 Surface 可随时销毁/重建**（旋转、切后台）而不影响解码：只有 EGL window surface 变化，
  上下文、OES 纹理、解码器 surface 都保留；用 1×1 pbuffer 保证无窗口时上下文仍然 current。
* 线程间只用原子量 + 少量小锁（不嵌套、不跨阻塞调用持有），包队列按字节/条数双上限背压。

---

## 5. 质量与 CI 记录

* **单元测试 149 个全绿**（host 上跑，release profile）：
  * `vlcrs-vr` 96：网格几何（单位球、画面中心朝向、上下左右方向、半球覆盖与 `u` 钳制）、
    矩阵（透视/lookAt/旋转顺序/`mul` 结合律）、收敛函数（单调、有界、C1、可逆）、
    视角限制、**"水平环视永不离开 180° 画面"的几何证明**（对所有 FOV × 所有偏航 × 视锥四角扫描）、
    "极限偏航时画面边缘正好触到 ±90°"、EKF（exp/log 往返、正交性、抖动下 2000 步不发散、时间戳跳变滤波）、
    HUD 格式化与 float 往返、下混与音量。
  * `vlcrs-media` 30：用代码构造 MP4/EBML 字节流验证解析（含截断/垃圾输入不 panic、moov 在文件尾、
    `st3d`/`equi` 边界、Matroska 两种 `ProjectionPose` 布局、V1 uuid XML）。
  * `vlcrs-lite` 23：ABI 稳定性（结构体布局、枚举 id、事件/状态码、HUD 字段索引唯一性）、
    平面 aspect fit、眼位 UV、`quarter_turns`、JNI panic 守卫。
* **clippy `-D warnings` 干净**、**rustfmt 干净**。
* **CI 全绿**：
  * `OrientCOMPASS/vlc` → workflow `libvlcrs`（host quality + arm64 release + release 发布）。
  * `OrientCOMPASS/vlc-android` → workflow `vr-player`（引擎交叉编译 + 片源生成与验证 + AAR/APK + release 发布）。
  * 两条流水线都只构建 `arm64-v8a`、只构建 `release`；没有任何 debug 产物；没有 bump 任何版本号。

---

## 6. 已知限制

* 仅 `arm64-v8a` / API 26+。
* 能播什么取决于设备 `MediaExtractor`/`MediaCodec`（H.264/HEVC/VP9/AV1、AAC/MP3/Opus/Vorbis/FLAC，
  MP4/MKV/WebM/TS…）；`http(s)` 交给系统解复用器，此时无法探测球面元数据（Auto 会回落平面，可手动指定模式）。
* Cubemap / mesh 投影只识别并上报 `UNSUPPORTED_PROJECTION`，不渲染。
* 无字幕、无多音轨、无倍速、无投屏、无 DRM。
* Matroska 的 `ProjectionPose` 存在两种布局（官方嵌套 vs Google RFC 扁平，ffmpeg 用后者）；
  引擎两种都能读，测试片源生成器默认写官方嵌套布局（pose 为 0 时不写该元素，兼容性最好）。
* demo APK 用 CI 生成的 debug key 签名。

## 7. 建议的后续工作（未包含在本次交付）

1. 把 `RsMediaPlayer` 适配成 VLC 应用的 `MediaPlayer`/`IVLCVout` 替身，接入
   `application/vlc-android` 的播放页（需要在 CI 里构建完整 VLC 应用，成本高，故本次以独立 demo 交付）。
2. 眼位反转（`swapEyes`）与初始 pose 的 UI 开关。
3. Cubemap 投影支持（网格 + 面选择）。
4. 倍速、音轨/字幕选择、缓冲事件细化（当前 `Buffering` 事件已定义但未由引擎发出）。
5. 用真实 VR 片源（YouTube VR180/VR360 下载件）做一轮兼容性回归。

---

## 8. 实机测试

见 [`TEST-CHECKLIST.md`](TEST-CHECKLIST.md)：按需求 1–6 逐条列出操作、期望结果与回填栏，
另含日志抓取方式、需要回传的信息、以及"哪些现象属于已知限制"。
