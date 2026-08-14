#!/usr/bin/env bash
#
# Copyright 2026 Music Wizard contributors
# Licensed under the Apache License, Version 2.0.
#
# Fetches the default rendering soundbank into the local cache, checksummed,
# with provenance beside it — the same pattern the model cache follows. The
# soundbank is never committed; only the audio rendered with it is.
#
# GeneralUser GS (S. Christian Collins) is the default because it is built and
# voiced for FluidSynth, and its licence permits unrestricted use of rendered
# audio. The URL pins a commit, not a branch, so the checksum cannot go stale
# under us. Override with MW_SOUNDFONT=<path> to render with another bank —
# polyphone.io hosts many; check the individual licence before using one, and
# record url, licence and sha256 in a .provenance file beside the download.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cache="$root/.mw-cache/soundfonts"
sf2="$cache/GeneralUser-GS.sf2"
url="https://github.com/mrbumpy409/GeneralUser-GS/raw/684543d5e5ef/GeneralUser-GS.sf2"
sha="9575028c7a1f589f5770fccc8cff2734566af40cd26ed836944e9a5152688cfe"

if [[ -f "$sf2" ]] && echo "$sha  $sf2" | sha256sum --check --status; then
  exit 0
fi

mkdir -p "$cache"
echo "fetching GeneralUser GS soundbank..." >&2
curl -sSL --fail -o "$sf2.part" "$url"
echo "$sha  $sf2.part" | sha256sum --check --status || {
  echo "checksum mismatch for $url" >&2
  rm -f "$sf2.part"
  exit 1
}
mv "$sf2.part" "$sf2"
cat > "$sf2.provenance" <<EOF
url: $url
sha256: $sha
licence: GeneralUser GS licence v2.0 (free use, rendered audio unrestricted)
fetched-by: tools/music-teacher/fetch-soundfont.sh
EOF
