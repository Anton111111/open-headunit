# Bundled H.264 and HEVC decoders

The checked-in `arm64-v8a` libraries and public headers are built together from
**FFmpeg 9.0.2** (libavcodec 63.1.102, libavutil 61.1.102, libswscale 10.1.102).
The source is unmodified. Only the software **H.264/AVC and HEVC/H.265 decoders
and parsers** are enabled. libswscale supplies pixel conversion for the JNI
renderer.

## Codec selection

The bundled backend supports H.264 and H.265 when software decoding is enabled,
Bundled FFmpeg is selected, and the requested native decoder is available.
Availability is checked separately for each codec. This optional backend does
not add 32-bit support or change the Android 4.x platform decoder path.

The projection session uses the codec actually advertised in ServiceDiscovery,
not the mutable settings preference. Structurally valid parameter sets can
correct that choice at initial decoder creation. Ordinary slices and decoder
timeouts cannot justify switching H.264 to H.265 or vice versa. Restarts retain
the selected codec; repeated cold failures stop retries rather than feeding the
same stream to an opposite-codec decoder.

Android Auto may deliver AVC SPS/PPS separately from picture data. The AVC
decoder uses `AV_CODEC_FLAG2_CHUNKS` so these configuration packets update
decoder state without being rejected as a missing picture. Decode errors are
still reported; they are not swallowed to accommodate configuration packets.
Parameter sets are collected across the full access unit on both backends,
including while MediaCodec is running or a surface is absent. The cache preserves
each syntax ID, replaces updates to that ID, and replays VPS/SPS/PPS in dependency
order into a fresh decoder. Identical sets reuse stored bytes. Encoded picture
bytes are passed through unchanged.
The message type selects the CSD or timestamped-DATA payload offset; start-code
lookalikes at another offset do not change the envelope.
A fragment-loss verdict remains attached to its assembly until LAST or replacement;
surviving middle fragments cannot make an incomplete picture eligible for decoding.

Transport recovery is acknowledged only after a non-corrupt keyframe is decoded
and accepted by the output sink. The repair callback runs outside the decoder
monitor. Repeated consumer stalls can switch either decoder backend to Surface
output; selecting bundled software decoding does not disable that recovery.
SPS dimensions only seed initial configuration. Active dimensions come from the
MediaCodec output format or accepted FFmpeg frames on either output path, not
from an inactive SPS received later. YUV fallback copying handles padded rows,
and new EGL contexts recreate texture storage even when frame sizes are unchanged.


## Source authentication

- Release: <https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz>
- Detached signature: <https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz.asc>
- Signing key: <https://ffmpeg.org/ffmpeg-devel.asc>
- Pinned release-key fingerprint: `FCF986EA15E6E293A5644F10B4322F04D67658D8`
- Archive SHA-256: `8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e`

GnuPG verified a good signature with that exact fingerprint before extraction.
An isolated keyring reports undefined owner trust; the fingerprint, rather than
a locally trusted key name, is the authentication anchor. The official release
and key information is published at <https://ffmpeg.org/download.html>.

## ABI and packaging contract

- Android `arm64-v8a` only, baseline ARMv8-A with runtime CPU feature detection.
- Android NDK **r27b / 27.1.12297006**, Linux x86-64 host, Android **API 21** target.
  API 21 is the minimum for this optional ARM64 component, not a change to the
  application's Android 4.x compatibility. No FFmpeg library is supplied for
  32-bit Android or other ABIs.
- Shared libraries, PIC, pthreads; no external codec libraries, hardware
  accelerators, network support, programs, encoders, muxers or demuxers.
- Every bundled FFmpeg ELF `LOAD` segment has `0x4000` (16 KB) alignment.
  JNI wrapper and final APK alignment require separate integration verification.
- Android's upstream configure branch sets unversioned SONAMEs and install names;
  no source patch or binary SONAME rewrite is used.

Observed installed ELF dependencies:

| Library / SONAME | DT_NEEDED |
| --- | --- |
| `libavcodec.so` | `libavutil.so`, `libc.so` |
| `libavutil.so` | `libm.so`, `libc.so` |
| `libswscale.so` | `libavutil.so`, `libm.so`, `libc.so` |

The libraries are packaged in `app/src/main/jniLibs/arm64-v8a/`. The matching
installed public headers, including generated `libavutil/avconfig.h`, are in
`app/src/main/cpp/ffmpeg/include/`. Replace the header tree as a unit when
upgrading; old headers such as `libavcodec/avfft.h` must not survive the cutover.

## Rebuild recipe

Run from the repository root on Linux with curl, GnuPG, xz/tar, GNU Make, a host
C compiler, rsync, and the specified Android NDK available. Use a fresh work
directory. This recipe records the exact configure flags used for this bundle;
the installation prefix and toolchain paths are embedded in FFmpeg's build
configuration string, so changing paths can change binary hashes.

```sh
set -eu
REPO="$PWD"
WORK=/tmp/ohu-issue-1188/ffmpeg-upgrade
NDK="$HOME/.cache/open-headunit-review/android-sdk/ndk/27.1.12297006"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
mkdir -p "$WORK"
cd "$WORK"
curl --fail --location --output ffmpeg-9.0.2.tar.xz \
  https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz
curl --fail --location --output ffmpeg-9.0.2.tar.xz.asc \
  https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz.asc
curl --fail --location --output ffmpeg-devel.asc \
  https://ffmpeg.org/ffmpeg-devel.asc
mkdir -m 700 gnupg
gpg --homedir "$WORK/gnupg" --import ffmpeg-devel.asc
gpg --homedir "$WORK/gnupg" --status-fd 1 \
  --verify ffmpeg-9.0.2.tar.xz.asc ffmpeg-9.0.2.tar.xz
# Stop unless VALIDSIG contains the pinned fingerprint above.
printf '%s  %s\n' \
  8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e \
  ffmpeg-9.0.2.tar.xz | sha256sum --check -
tar -xJf ffmpeg-9.0.2.tar.xz
cd ffmpeg-9.0.2
./configure \
  --prefix="$WORK/install-arm64" \
  --target-os=android --arch=aarch64 --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$TOOLCHAIN/bin/aarch64-linux-android21-clang" \
  --cxx="$TOOLCHAIN/bin/aarch64-linux-android21-clang++" \
  --ar="$TOOLCHAIN/bin/llvm-ar" \
  --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
  --nm="$TOOLCHAIN/bin/llvm-nm" \
  --strip="$TOOLCHAIN/bin/llvm-strip" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --disable-everything --disable-programs --disable-doc --disable-debug \
  --disable-checkasm --disable-network --disable-autodetect \
  --disable-avdevice --disable-avfilter --disable-avformat --disable-swresample \
  --disable-encoders --disable-muxers --disable-demuxers \
  --enable-avcodec --enable-avutil --enable-swscale \
  --enable-decoder=h264,hevc --enable-parser=h264,hevc \
  --enable-shared --disable-static --enable-pic \
  --extra-cflags='-O3 -fPIC' \
  --extra-ldflags='-Wl,-z,max-page-size=16384'
make -j8
make install
rsync -a --delete "$WORK/install-arm64/include/" \
  "$REPO/app/src/main/cpp/ffmpeg/include/"
cp "$WORK/install-arm64/lib/libavcodec.so" \
   "$WORK/install-arm64/lib/libavutil.so" \
   "$WORK/install-arm64/lib/libswscale.so" \
   "$REPO/app/src/main/jniLibs/arm64-v8a/"
```

Installed library SHA-256 values for the checked-in build:

```text
ac26e98f1ac894cceea97de87944b59d5dc597e87cc798f7ce62e96c4c28be2c  libavcodec.so
d236dacdecc82f5b589939f4fb2c0606f48c11a914def4a5fd90c13c1e6d74f4  libavutil.so
0cb9ae74b267ea0c65be58ed33e4be5fc5b45614904ab512dcdad6447843d64c  libswscale.so
```

## Validation boundary

The production JNI implementation was exercised on Linux against a minimal
dual-codec host build of this FFmpeg source. Only the Android logging and native
window boundaries were substituted. H.264 clips at 480p, 720p and 1080p and an
H.265 clip at 1080p produced every expected frame through both YUV callbacks and
RGBA rendering, including fresh-decoder restarts, nonzero input offsets and
padded output rows. Frame pixels matched an independent FFmpeg 7.0.2 decode;
RGBA comparison used scalar swscale on both sides to avoid SIMD rounding
differences.

Configuration-only packets were tested before playback and midstream, with
parameter sets both combined and sent individually. The no-FFmpeg native build
also rejected both codec IDs, preserving optional-backend availability.
The Kotlin-to-JNI path also exercised parameter sets arriving during a surface
gap, after a long SEI or many NALs, and with multiple active IDs. Recreation
without repeated headers produced the same decoded pixels. Output-side repair
excluded rejected YUV frames and failed Surface submissions. First-creation
failure injection verified the cold retry limit without assuming an earlier
successful decoder instance.
Mixed-resolution SPS inputs also exercised actual output-size transitions through
YUV and RGBA, and a throwing output sink entered the existing recovery path.
The production GL renderer was separately exercised against host EGL/GLES with
padded-plane fallback copying, context recreation, texture allocation and pixel
readback. Android windowing, SurfaceTexture and scheduling were substituted in
that renderer check; it is not a head-unit driver verification.
ARM64 library and JNI wrapper ELF `LOAD` segments have 16 KB alignment.

These checks do not establish native ARM performance or end-to-end Android
projection correctness. Device verification still needs to cover H.264/H.265
projection with both YUV and Surface renderers, unavailable-backend fallback,
and the existing Android 4.x platform path. Final APK ZIP alignment must be
checked after packaging.

License: the configured build reports `LGPL version 2.1 or later`, with no GPL,
nonfree or external codec components enabled. Retain `FFMPEG_LICENSE.md` and
`COPYING.LGPLv2.1` alongside the headers and the upstream source notices.
