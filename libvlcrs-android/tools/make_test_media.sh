#!/usr/bin/env bash
# Generate the self-test media bundled with the demo APK.
#
# The clips are produced with ffmpeg (preinstalled on GitHub runners) and tagged
# with libvlcrs' own metadata injector, so the `Auto` projection mode can be
# validated on a real device without committing binary fixtures.
#
# Every clip has a stereo sine audio track (440 Hz left / 880 Hz right) so the
# audio path and the downmixer are exercised as well.
#
# Usage: make_test_media.sh <output-dir> [duration-seconds]
set -euo pipefail

OUT="${1:?output directory}"
DUR="${2:-8}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The injector lives in the vlc repository (libvlcrs/tools).  CI exports
# VLCRS_INJECTOR; for a local run the sibling checkout is tried as well.
INJECTOR="${VLCRS_INJECTOR:-}"
if [ -z "$INJECTOR" ] || [ ! -f "$INJECTOR" ]; then
    for candidate in \
        "$HERE/../../../../vlc/libvlcrs/tools/spherical_inject.py" \
        "$HERE/../../../../vlc-src/libvlcrs/tools/spherical_inject.py" \
        "$VLCRS_SRC/libvlcrs/tools/spherical_inject.py"; do
        if [ -n "${candidate:-}" ] && [ -f "$candidate" ]; then INJECTOR="$candidate"; break; fi
    done
fi
if [ -z "${INJECTOR:-}" ] || [ ! -f "$INJECTOR" ]; then
    echo "error: spherical_inject.py not found (set VLCRS_INJECTOR)" >&2
    exit 1
fi

mkdir -p "$OUT"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "== test media: duration ${DUR}s, output $OUT"
echo "   injector: $INJECTOR"

encode() {  # encode <name> <filtergraph> <width> <height>
    local name="$1" filter="$2" w="$3" h="$4"
    ffmpeg -v error -y \
        -f lavfi -i "$filter" \
        -f lavfi -i "aevalsrc=440*sin(2*PI*t)|880*sin(2*PI*t):s=48000:d=${DUR}" \
        -t "$DUR" -r 30 \
        -c:v libx264 -preset veryfast -crf 32 -pix_fmt yuv420p -profile:v baseline \
        -c:a aac -b:a 96k -ac 2 \
        -movflags +faststart \
        "$TMP/$name.mp4"
    echo "   encoded $name.mp4 (${w}x${h})"
}

# 1. ordinary 16:9 clip, deliberately untagged → Auto must resolve to planar
encode planar_16x9 "testsrc2=size=1280x720:rate=30" 1280 720

# 2. 360° mono: full 2:1 equirectangular frame
encode vr360_mono "testsrc2=size=1536x768:rate=30" 1536 768

# 3. 360° side-by-side: left eye = moving pattern, right eye = colour bars, so
#    switching the eye is unmistakable on device
ffmpeg -v error -y \
    -f lavfi -i "testsrc2=size=768x768:rate=30" \
    -f lavfi -i "smptebars=size=768x768:rate=30" \
    -f lavfi -i "aevalsrc=440*sin(2*PI*t)|880*sin(2*PI*t):s=48000:d=${DUR}" \
    -filter_complex "[0:v][1:v]hstack=inputs=2[v]" -map "[v]" -map 2:a \
    -t "$DUR" -r 30 -c:v libx264 -preset veryfast -crf 32 -pix_fmt yuv420p \
    -profile:v baseline -c:a aac -b:a 96k -ac 2 -movflags +faststart \
    "$TMP/vr360_sbs.mp4"
echo "   encoded vr360_sbs.mp4 (left=testsrc2, right=smptebars)"

# 4. 360° top-bottom: top = moving pattern, bottom = bars
ffmpeg -v error -y \
    -f lavfi -i "testsrc2=size=1536x384:rate=30" \
    -f lavfi -i "smptehdbars=size=1536x384:rate=30" \
    -f lavfi -i "aevalsrc=440*sin(2*PI*t)|880*sin(2*PI*t):s=48000:d=${DUR}" \
    -filter_complex "[0:v][1:v]vstack=inputs=2[v]" -map "[v]" -map 2:a \
    -t "$DUR" -r 30 -c:v libx264 -preset veryfast -crf 32 -pix_fmt yuv420p \
    -profile:v baseline -c:a aac -b:a 96k -ac 2 -movflags +faststart \
    "$TMP/vr360_tb.mp4"
echo "   encoded vr360_tb.mp4 (top=testsrc2, bottom=bars)"

# 5. 180° mono: 1:1 frame
encode vr180_mono "testsrc2=size=768x768:rate=30" 768 768

# 6. 180° side-by-side: 2:1 frame, bounds metadata makes it authoritative
ffmpeg -v error -y \
    -f lavfi -i "testsrc2=size=768x768:rate=30" \
    -f lavfi -i "smptebars=size=768x768:rate=30" \
    -f lavfi -i "aevalsrc=440*sin(2*PI*t)|880*sin(2*PI*t):s=48000:d=${DUR}" \
    -filter_complex "[0:v][1:v]hstack=inputs=2[v]" -map "[v]" -map 2:a \
    -t "$DUR" -r 30 -c:v libx264 -preset veryfast -crf 32 -pix_fmt yuv420p \
    -profile:v baseline -c:a aac -b:a 96k -ac 2 -movflags +faststart \
    "$TMP/vr180_sbs.mp4"
echo "   encoded vr180_sbs.mp4"

# 7. a Matroska/WebM variant to exercise the EBML prober
ffmpeg -v error -y \
    -f lavfi -i "testsrc2=size=1536x768:rate=30" \
    -f lavfi -i "aevalsrc=440*sin(2*PI*t)|880*sin(2*PI*t):s=48000:d=${DUR}" \
    -t "$DUR" -r 30 -c:v libx264 -preset veryfast -crf 32 -pix_fmt yuv420p \
    -profile:v baseline -c:a libvorbis -b:a 96k -ac 2 \
    "$TMP/vr360_mono.mkv"
echo "   encoded vr360_mono.mkv"

tag() {  # tag <in> <out> <coverage> <stereo>
    python3 "$INJECTOR" "$TMP/$1" "$OUT/$2" --coverage "$3" --stereo "$4"
}

# untagged control clip
cp "$TMP/planar_16x9.mp4" "$OUT/planar_16x9.mp4"

tag vr360_mono.mp4 vr360_mono.mp4 360 mono
tag vr360_sbs.mp4  vr360_sbs.mp4  360 sbs
tag vr360_tb.mp4   vr360_tb.mp4   360 tb
tag vr180_mono.mp4 vr180_mono.mp4 180 mono
tag vr180_sbs.mp4  vr180_sbs.mp4  180 sbs
tag vr360_mono.mkv vr360_mono.mkv 360 mono

echo "== generated:"
ls -l "$OUT"
du -sh "$OUT"
