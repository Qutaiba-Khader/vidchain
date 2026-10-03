#!/usr/bin/env bash
# Generate every fixture media file locally with ffmpeg lavfi (nothing copyrighted, nothing downloaded).
# usage: gen_media.sh <out-dir>
set -Eeuo pipefail
OUT=${1:?usage: gen_media.sh <out-dir>}
mkdir -p "$OUT/hls/plain/v360" "$OUT/hls/plain/v720" "$OUT/hls/aes" "$OUT/dash"
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }
SRC=(-f lavfi -i "testsrc2=size=640x360:rate=25:duration=4" -f lavfi -i "sine=frequency=440:duration=4")
ff "${SRC[@]}" -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest -movflags +faststart "$OUT/clip.mp4"
ff "${SRC[@]}" -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$OUT/clip-moov-end.mp4"
ff "${SRC[@]}" -c:v libvpx-vp9 -b:v 300k -c:a libopus -shortest "$OUT/clip.webm"
ff -f lavfi -i "testsrc2=size=640x360:rate=25:duration=4" -c:v libx264 -pix_fmt yuv420p -an -movflags +faststart "$OUT/video-only.mp4"
ff -f lavfi -i "sine=frequency=440:duration=4" -c:a aac "$OUT/audio-only.m4a"
# HLS: two renditions + master, and an AES-128 encrypted stream
for r in 360 720; do
  w=$((r*16/9)); w=$((w/2*2))
  ff -f lavfi -i "testsrc2=size=${w}x${r}:rate=25:duration=6" -f lavfi -i "sine=frequency=440:duration=6" \
     -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest -hls_time 2 -hls_playlist_type vod \
     -hls_segment_filename "$OUT/hls/plain/v$r/seg%03d.ts" "$OUT/hls/plain/v$r/index.m3u8"
done
cat > "$OUT/hls/plain/master.m3u8" <<M3U
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
v360/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
v720/index.m3u8
M3U
openssl rand 16 > "$OUT/hls/aes/key.bin"
printf 'https://hls.vidchain.test/aes/key.bin\n%s\n%s\n' "$OUT/hls/aes/key.bin" "$(openssl rand -hex 16)" > "$OUT/hls/aes/keyinfo"
ff "${SRC[@]}" -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest -hls_time 2 -hls_playlist_type vod \
   -hls_key_info_file "$OUT/hls/aes/keyinfo" -hls_segment_filename "$OUT/hls/aes/seg%03d.ts" "$OUT/hls/aes/index.m3u8"
rm -f "$OUT/hls/aes/keyinfo"
# DASH with separate video and audio adaptation sets
( cd "$OUT/dash" && ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=640x360:rate=25:duration=6" \
    -f lavfi -i "sine=frequency=440:duration=6" -map 0:v -map 1:a -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest \
    -seg_duration 2 -adaptation_sets "id=0,streams=v id=1,streams=a" -f dash manifest.mpd )
ls -R "$OUT" | head -40
