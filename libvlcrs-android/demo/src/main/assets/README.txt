Self-test media lives here at build time.

CI generates it with tools/make_test_media.sh (ffmpeg + the spherical metadata
injector from the vlc repository), so that the demo APK ships with clips covering
the whole format matrix:

  planar_16x9.mp4   ordinary 16:9 clip, untagged  → Auto resolves to planar
  vr360_mono.mp4    2:1 equirectangular, tagged   → Auto = 360° mono
  vr360_sbs.mp4     side-by-side, left eye = moving pattern, right eye = bars
  vr360_tb.mp4      top-bottom,  top = moving pattern, bottom = bars
  vr180_mono.mp4    1:1 equirectangular, tagged   → Auto = 180° mono
  vr180_sbs.mp4     side-by-side with equi crop bounds → Auto = 180° SBS
  vr360_mono.mkv    the Matroska/WebM variant     → Auto = 360° mono (EBML path)

Nothing here is committed: the files are produced per build.
