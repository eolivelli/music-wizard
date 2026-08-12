#!/usr/bin/env bash
# Builds the sherpa-onnx JNI native from the submodule, licence flags pinned.
#
# One entrypoint for CI and a fresh clone alike, because the flags ARE the
# licence position: TTS off keeps the GPL-3.0 espeak fork out of the link
# (checked afterwards against the artifact by check-sherpa-native.sh), and
# everything unused is off so there is less to build and less to audit.
#
# Output: third_party/sherpa-onnx/build/lib with libsherpa-onnx-jni.so and
# the libonnxruntime.so it loads, side by side -- the layout sherpa's own
# LibraryUtils expects from sherpa_onnx.native.path.
set -euo pipefail

if [ "$(uname -s)" != "Linux" ]; then
  # nproc, the .so names and the symbol scan are all GNU/Linux; saying so
  # beats dying half-way with a glob error.
  echo "build-sherpa-native: only Linux is supported today ($(uname -s))" >&2
  exit 2
fi

cd "$(dirname "$0")/.."
SRC=third_party/sherpa-onnx
BUILD=$SRC/build

if [ ! -f "$SRC/CMakeLists.txt" ]; then
  echo "build-sherpa-native: submodule missing; run: git submodule sync && git submodule update --init" >&2
  exit 2
fi

# The tree must be at the recorded pin. An existing clone keeps the OLD
# submodule URL in .git/config after a URL change, and update --init then
# leaves the old commit checked out with exit 128 -- and this script would
# happily build a native without the change the pin exists for.
PINNED=$(git rev-parse "HEAD:$SRC")
ACTUAL=$(git -C "$SRC" rev-parse HEAD)
if [ "$PINNED" != "$ACTUAL" ]; then
  echo "build-sherpa-native: submodule is at $ACTUAL, the pin is $PINNED;" >&2
  echo "  run: git submodule sync && git submodule update --init" >&2
  exit 2
fi

cmake -S "$SRC" -B "$BUILD" \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON \
  -DSHERPA_ONNX_ENABLE_JNI=ON \
  -DSHERPA_ONNX_ENABLE_TTS=OFF \
  -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
  -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
  -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF \
  -DSHERPA_ONNX_ENABLE_BINARY=OFF \
  -DSHERPA_ONNX_ENABLE_TESTS=OFF \
  -DSHERPA_ONNX_ENABLE_PYTHON=OFF

cmake --build "$BUILD" --target sherpa-onnx-jni -j "$(nproc)"

# The JNI lib finds onnxruntime through a build-tree rpath, which dies the
# moment the directory is copied anywhere. Put the two side by side so the
# lib directory is self-contained.
cp -f "$BUILD"/_deps/onnxruntime-src/lib/libonnxruntime.so* "$BUILD/lib/"

tools/check-sherpa-native.sh "$BUILD/lib/libsherpa-onnx-jni.so"
