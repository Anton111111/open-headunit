# Preflight clips

Synthetic clips for the onboarding video test. They look like a navigation screen and contain no
third-party content: the map, the streets and the arrow are generated from a fixed seed.

Each clip is a raw Annex B stream, not a container, because that is exactly what the decoder
receives from the phone. They are 150 frames at 30 fps (five seconds), with one slice per frame,
one IDR at the start and repeated parameter sets. The copies used by the app live in
`app/src/main/res/raw/` with the `preflight_` prefix.

| Clip | Codec | Profile | Level | Size |
|---|---|---|---|---|
| `test_h264_480p.h264` | H.264 | Constrained Baseline | 3.1 | 233 KB |
| `test_h264_720p.h264` | H.264 | Constrained Baseline | 3.1 | 380 KB |
| `test_h264_1080p.h264` | H.264 | Constrained Baseline | 4.0 | 543 KB |
| `test_h265_1080p.h265` | H.265 | Main | 4.0 | 406 KB |

SHA-256:

```
ec397a9c50261293c9fd399299f23ce22e12e36e995831a1e7ad3a9d4049db46  test_h264_1080p.h264
e399621fc1f45d767e0fdabe22b9576a1c63974ba0689568ff7c149d0c341a55  test_h264_480p.h264
ba52cd14e75428b64a2542055d47ddbae5da2db5bc0f6f0a8777692a5c38d4c6  test_h264_720p.h264
08fd1f89ed820a20446e953519668817a79d94a22ad5fe8790bf8ffba3bd7d7e  test_h265_1080p.h265
```

## Frame marker

Every frame carries its number in the bottom right corner: eight white or black blocks in two
rows of four, least significant bit first, and the number printed above them. The geometry is
44 pixel blocks with a 48 pixel gap on a 1080 line picture, anchored 260 pixels from the right
edge and 330 from the bottom; smaller clips scale it by height. `FrameMarker.kt` in the app reads
the same layout.

## Regenerate

```
bash gen_clips.sh <output_dir>
```

The script needs `ffmpeg` with `libx264` and `libx265`, Python 3 and the DejaVu fonts. Generated
with ffmpeg 6.1.1 and Python 3.12.3 on 2026-10-15; the same versions produce the same bytes.

## Check a clip

Decode to raw gray frames and read the marker:

```
ffmpeg -i test_h264_480p.h264 -pix_fmt gray -f rawvideo frames.gray
python3 check_marker.py frames.gray 800 480 684 333 20 21
```

The last four numbers are the marker's x, y, block and gap for that clip. They are also written
to `marker_<width>x<height>.txt` when the clips are generated. The checker prints the frame count,
the number of frames whose marker is wrong and the luminance of the brightest "off" and darkest
"on" blocks.
