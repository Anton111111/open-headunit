#!/usr/bin/env bash
# Preflight clips: a procedural night city map with street names, a route, the car arrow and a
# navigation style interface. Five seconds at 30 fps (150 frames).
# Usage: gen_clips.sh <output_dir>
set -euo pipefail
OUT=$1; HERE=$(cd "$(dirname "$0")" && pwd); mkdir -p "$OUT"
FONT=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf
FONTB=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf; [ -f "$FONTB" ] || FONTB=$FONT
FPS=30; FRAMES=150

python3 -I "$HERE/gen_map.py" "$OUT"

# Street names over the map (once, on the still image).
LF=""
while read -r x y o t; do
  LF+="drawtext=fontfile=$FONT:text='$t':x=$x:y=$y:fontsize=22:fontcolor=0xb8c4cf:borderw=3:bordercolor=0x242c33,"
done < "$OUT/labels.txt"
ffmpeg -hide_banner -loglevel error -y -i "$OUT/map.ppm" -vf "${LF%,}" "$OUT/map.png"

sc() { awk -v v="$1" -v h="$H" 'BEGIN{printf "%d", v*h/1080 + 0.5}'; }

ui() {
  local W=$1 H=$2 f=""
  # Sidebar with app icons.
  f+="drawbox=x=0:y=0:w=$(sc 150):h=$H:c=0x0d0f12:t=fill"
  local i=0; for c in 0x4285f4 0x34a853 0xfbbc05 0xea4335; do
    f+=",drawbox=x=$(sc 37):y=$(sc $((60 + i*150))):w=$(sc 76):h=$(sc 76):c=$c:t=fill"
    f+=",drawbox=x=$(sc 57):y=$(sc $((80 + i*150))):w=$(sc 36):h=$(sc 36):c=0x0d0f12@0.55:t=fill"; i=$((i+1)); done
  f+=",drawbox=x=$(sc 45):y=$((H - $(sc 150))):w=$(sc 60):h=$(sc 60):c=0x9aa0a6:t=fill"
  # Turn card with a countdown distance.
  f+=",drawbox=x=$(sc 190):y=$(sc 30):w=$(sc 640):h=$(sc 200):c=0x0b6e4f:t=fill"
  f+=",drawbox=x=$(sc 220):y=$(sc 60):w=$(sc 30):h=$(sc 110):c=white:t=fill,drawbox=x=$(sc 220):y=$(sc 60):w=$(sc 90):h=$(sc 30):c=white:t=fill"
  f+=",drawtext=fontfile=$FONTB:text='%{eif\:400-n*2\:d} m':x=$(sc 340):y=$(sc 55):fontsize=$(sc 64):fontcolor=white"
  f+=",drawtext=fontfile=$FONT:text='Turn right onto Market St':x=$(sc 340):y=$(sc 140):fontsize=$(sc 34):fontcolor=0xd8f0e6"
  # Next instruction.
  f+=",drawbox=x=$(sc 190):y=$(sc 230):w=$(sc 640):h=$(sc 60):c=0x09573f:t=fill"
  f+=",drawtext=fontfile=$FONT:text='Then  Station Rd':x=$(sc 220):y=$(sc 245):fontsize=$(sc 30):fontcolor=0xd8f0e6"
  # Speed and arrival time.
  f+=",drawbox=x=$(sc 190):y=$((H - $(sc 260))):w=$(sc 110):h=$(sc 110):c=0xf1f3f4:t=fill"
  f+=",drawtext=fontfile=$FONTB:text='%{eif\:42+mod(floor(n/20),5)\:d}':x=$(sc 210):y=$((H - $(sc 235))):fontsize=$(sc 52):fontcolor=0x202124"
  f+=",drawbox=x=$((W - $(sc 470))):y=$(sc 30):w=$(sc 440):h=$(sc 130):c=0x202124:t=fill"
  f+=",drawtext=fontfile=$FONTB:text='12\:%{eif\:41+floor(n/90)\:d}':x=$((W - $(sc 440))):y=$(sc 50):fontsize=$(sc 50):fontcolor=0x81c995"
  f+=",drawtext=fontfile=$FONT:text='18 min  ·  9.4 km':x=$((W - $(sc 440))):y=$(sc 110):fontsize=$(sc 30):fontcolor=0xbdc1c6"
  # Music bar with progress.
  f+=",drawbox=x=$(sc 150):y=$((H - $(sc 120))):w=$((W - $(sc 150))):h=$(sc 120):c=0x16191d:t=fill"
  f+=",drawbox=x=$(sc 175):y=$((H - $(sc 105))):w=$(sc 90):h=$(sc 90):c=0x8e44ad:t=fill"
  f+=",drawbox=x=$(sc 195):y=$((H - $(sc 85))):w=$(sc 50):h=$(sc 50):c=0xd2a8e6:t=fill"
  f+=",drawtext=fontfile=$FONTB:text='Night Drive':x=$(sc 290):y=$((H - $(sc 100))):fontsize=$(sc 36):fontcolor=white"
  f+=",drawtext=fontfile=$FONT:text='The Open Roads  ·  Highway Songs':x=$(sc 290):y=$((H - $(sc 55))):fontsize=$(sc 28):fontcolor=0x9aa0a6"
  f+=",drawbox=x=$(sc 150):y=$((H - $(sc 124))):w=$((W - $(sc 150))):h=$(sc 4):c=0x3c4043:t=fill"
  f+=",drawbox=x=$(sc 150):y=$((H - $(sc 124))):w='$(sc 600)+t*$(sc 40)':h=$(sc 4):c=0x8ab4f8:t=fill"
  # Frame number marker (8 bits), on a black panel.
  local B=$(sc 44) G=$(sc 48) PX=$((W - $(sc 260))) PY=$((H - $(sc 330)))
  f+=",drawbox=x=$((PX - $(sc 16))):y=$((PY - $(sc 66))):w=$(sc 236):h=$(sc 176):c=black:t=fill"
  f+=",drawtext=fontfile=$FONT:text='frame %{n}':x=$PX:y=$((PY - $(sc 54))):fontsize=$(sc 34):fontcolor=white"
  for b in 0 1 2 3 4 5 6 7; do
    f+=",drawbox=x=$((PX + (b % 4) * G)):y=$((PY + (b / 4) * G)):w=$B:h=$B:c=white:t=fill:enable='mod(floor(n/$((1 << b))),2)'"
  done
  echo "$f"
  echo "$PX $PY $B $G" > "$OUT/marker_${W}x${H}.txt"
}

render() { # W H codec output
  local W=$1 H=$2 codec=$3 dst=$4
  local AR=$(sc 64)
  # The map is scaled to the resolution and panned by a window that advances, so the car follows
  # the route.
  local graph="[0:v]scale=iw*$H/1080:-1,loop=loop=-1:size=1,fps=$FPS,format=yuv420p,"
  graph+="crop=$W:$H:x='$(sc 380)+t*$(sc 70)':y='$(sc 250)+t*$(sc 95)'[m];"
  graph+="[1:v]scale=$AR:$AR,format=rgba[a];[m][a]overlay=x=($W-$AR)/2+$(sc 80):y=$H*0.58,"
  graph+="$(ui "$W" "$H"),format=yuv420p[v]"
  local enc
  if [ "$codec" = h264 ]; then
    enc=(-c:v libx264 -profile:v baseline -bf 0 -refs 1 -g 9999 -crf 23 -x264-params repeat-headers=1 -f h264)
  else
    enc=(-c:v libx265 -profile:v main -x265-params bframes=0:keyint=9999:repeat-headers=1:log-level=error -crf 26 -f hevc)
  fi
  ffmpeg -hide_banner -loglevel error -y -i "$OUT/map.png" -i "$OUT/arrow.png" -filter_complex "$graph" \
    -map "[v]" -frames:v $FRAMES -r $FPS -an "${enc[@]}" "$dst"
}

for res in 800x480 1280x720 1920x1080; do
  W=${res%x*}; H=${res#*x}
  render "$W" "$H" h264 "$OUT/test_h264_${H}p.h264"
done
W=1920; H=1080; render $W $H hevc "$OUT/test_h265_1080p.h265"
