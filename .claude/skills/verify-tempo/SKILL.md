---
name: verify-tempo
description: Independently verify a recording's tempo with estimators that do not share MW's prior (Essentia beat trackers + librosa's prior-free tempogram). Reports each file's octave family and a CONFIRMED / FAMILY / ABSENT verdict against a stated BPM. Use when a sample's stated tempo needs checking, when MW's tempo reading is suspect, or when adding a recording to samples/ or uncommitted/.
---

# verify-tempo

Run `verify_tempo.py` (beside this file) — never MW itself — to check a tempo
claim. The point is independence: MW, and librosa's *default* estimator, share
a 120-BPM-centred prior that halves fast recordings, so agreement between them
is one measurement taken twice, not two. The estimators here were chosen and
validated against the corpus in #349.

```sh
python3 .claude/skills/verify-tempo/verify_tempo.py --stated 140 samples/jazz-251-c-140.mp3
python3 .claude/skills/verify-tempo/verify_tempo.py --stated-map /tmp/map.txt samples/*.mp3
```

`--stated-map` lines are `filename bpm`. First use bootstraps venvs under
`~/.cache/mw-tempo-verify` (override: `MW_TEMPO_VERIFY_CACHE`); allow a few
minutes then. Needs network on first use only.

## Reading the output

- **CONFIRMED** — a beat-tracking estimator reports the stated tempo
  directly. Done; record it in list.txt as "confirmed at X, with the strongest
  envelope pulse at Y" if the tempogram's top peak is elsewhere — that second
  clause is what the beat-tracking story needs.
- **FAMILY** — the stated tempo is in the measured octave family, but no
  estimator names it directly. **The tools cannot settle the level; only a
  musician's tap test can.** Ask Enrico to tap quarters along at least 40 s:
  count N taps, BPM = 60·(N−1)/elapsed, twice from different points, within
  2 BPM. Swung and shuffled material lands here routinely (every prior-free
  estimator reads the half); that does not refute the stated figure.
- **ABSENT** — the stated tempo is in no member of the family (ratios
  1, 2, 1/2, 3, 1/3, 3/2, 2/3, 3/4, 4/3 within 2%). The claim is wrong; do
  not use it as ground truth.

Never treat as confirmation: a confidence figure (Essentia reports "excellent"
grid consistency on octave-halved readings); two prior-sharing tools agreeing;
any tool re-run with its prior pushed toward the stated value.

Compound meters: for a 6/8 file the "stated tempo" may name the dotted
quarter, the quarter, or the eighth — decide which before reading a verdict,
and remember MW's own convention is quarter-note beats (#4).

## Licensing

Essentia is **AGPL** — acceptable only as here: installed into a user cache,
invoked as a separate process, never linked, vendored, or put in the build or
CI (the LilyPond precedent). librosa is ISC. **madmom is deliberately not
used**: its model weights are CC BY-NC-SA, which this project treats as
unusable; if a third independent opinion is ever needed, the licence-clean
route is the QM Vamp Tempo and Beat Tracker via sonic-annotator (GPL, separate
binary), or ask Enrico to tap.

Full survey, measurements and protocol rationale: #349.
