#!/usr/bin/env bash
# Fails when the built sherpa-onnx native links what the licence flags forbid.
#
# The GPL-3.0 espeak fork arrives through CMake when SHERPA_ONNX_ENABLE_TTS is
# on, and CMake is invisible to the maven-enforcer-plugin -- a submodule bump
# could flip the default with nothing failing. So the property is executed
# against the artifact, not trusted from the build script: scan the shared
# library's defined symbols for espeak, and for the TTS entry points that
# would carry it.
#
# Usage: tools/check-sherpa-native.sh [path-to-libsherpa-onnx-jni.so]
set -euo pipefail

LIB="${1:-third_party/sherpa-onnx/build/lib/libsherpa-onnx-jni.so}"

if [ ! -f "$LIB" ]; then
  echo "check-sherpa-native: no library at $LIB (build the sherpa profile first)" >&2
  exit 2
fi

# Defined dynamic symbols only: what this library itself carries, not what it
# merely references from elsewhere.
SYMBOLS=$(nm -D --defined-only "$LIB")

if echo "$SYMBOLS" | grep -qi "espeak"; then
  echo "check-sherpa-native: FAIL -- $LIB defines espeak symbols;" >&2
  echo "  the GPL-3.0 espeak fork is linked in. SHERPA_ONNX_ENABLE_TTS must be OFF." >&2
  echo "$SYMBOLS" | grep -i espeak | head -5 >&2
  exit 1
fi

if echo "$SYMBOLS" | grep -q "OfflineTts"; then
  echo "check-sherpa-native: FAIL -- $LIB defines TTS entry points;" >&2
  echo "  TTS was compiled in, which is what pulls the espeak fork." >&2
  exit 1
fi

echo "check-sherpa-native: OK -- no espeak, no TTS in $(basename "$LIB")"
