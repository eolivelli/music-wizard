#!/usr/bin/env bash
# Fails when the built sherpa-onnx native links what the licence flags forbid.
#
# The GPL-3.0 espeak fork arrives through CMake when SHERPA_ONNX_ENABLE_TTS is
# on, and CMake is invisible to the maven-enforcer-plugin -- a submodule bump
# could flip the default with nothing failing. So the property is executed
# against the artifact, not trusted from the build script.
#
# The full symbol table, NOT the dynamic one: the JNI library is linked with a
# version script that keeps only Java_* entry points dynamic, and everything
# else -- espeak included, were it linked -- is a local symbol from a static
# archive. Scanning `nm -D` of this library can never fail, which is what the
# first version of this script did.
#
# Usage: tools/check-sherpa-native.sh [path-to-libsherpa-onnx-jni.so]
set -euo pipefail

LIB="${1:-third_party/sherpa-onnx/build/lib/libsherpa-onnx-jni.so}"

if [ ! -f "$LIB" ]; then
  echo "check-sherpa-native: no library at $LIB (build the sherpa profile first)" >&2
  exit 2
fi

SYMBOLS=$(nm --defined-only "$LIB")

if [ -z "$SYMBOLS" ]; then
  echo "check-sherpa-native: FAIL -- $LIB has no readable symbol table;" >&2
  echo "  a stripped library cannot be checked, so it does not pass." >&2
  exit 1
fi

if echo "$SYMBOLS" | grep -qi "espeak"; then
  echo "check-sherpa-native: FAIL -- $LIB carries espeak symbols;" >&2
  echo "  the GPL-3.0 espeak fork is linked in. SHERPA_ONNX_ENABLE_TTS must be OFF." >&2
  echo "$SYMBOLS" | grep -i espeak | head -5 >&2
  exit 1
fi

if echo "$SYMBOLS" | grep -q "OfflineTts"; then
  echo "check-sherpa-native: FAIL -- $LIB carries TTS entry points;" >&2
  echo "  TTS was compiled in, which is what pulls the espeak fork." >&2
  exit 1
fi

echo "check-sherpa-native: OK -- no espeak, no TTS in $(basename "$LIB")"
