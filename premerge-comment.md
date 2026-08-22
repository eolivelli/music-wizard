`tools/premerge.sh` on this branch merged with current `origin/main` (no drift: the branch point is `origin/main`, and no baseline file has moved on main since).

```
=== baseline drift since the branch point (prompt, not a gate) ===
=== 1/10 build (suites left to CI; --full runs them here) ===
=== 2/10 model harness vs baseline ===
compared 48 of 48 baselined rows in tools/baselines/score-samples.txt
=== 3/10 chart harness vs baseline ===
compared 14 of 14 baselined rows in tools/baselines/score-chart.txt
=== 4/10 lyric harness vs baseline ===
compared 1 of 1 baselined rows in tools/baselines/score-lyrics.txt
=== 5/10 transcription harness vs baseline ===
SKIP sere-doltremare.mp3: run tools/build-sherpa-native.sh for this loop
compared 0 of 1 baselined rows in tools/baselines/score-asr.txt; 1 skipped, so this step certified nothing
=== 6/10 synthetic harness vs baseline ===
compared 18 of 18 baselined rows in tools/baselines/score-synthetic.txt
=== 7/10 melody harness vs baseline ===
compared 18 of 18 baselined rows in tools/baselines/score-melody.txt
=== 8/10 melody harness vs baseline, on real singing ===
compared 40 of 40 baselined rows in tools/baselines/score-melody-vocadito.txt
=== 9/10 melody harness vs baseline, through the separated vocal ===
compared 18 of 18 baselined rows in tools/baselines/score-melody-separated.txt
=== 10/10 melody harness vs baseline, real singing through the separated vocal ===
compared 40 of 40 baselined rows in tools/baselines/score-melody-vocadito-separated.txt
=== what this run certified ===
1 of 198 baselined rows were not compared here:
  score-asr.txt sere-doltremare.mp3 — run tools/build-sherpa-native.sh for this loop
score-asr.txt: every row skipped, so that step certified nothing.
all of them expected per /home/eolivelli/.config/music-wizard/premerge-skips.txt.
SUMMARY: 1 of 198 rows not compared, all expected here; score-asr.txt certified nothing
=== verdict ===
PREMERGE: PASS-WITH-SKIPS (build + harnesses; 1 of 198 rows not compared, all expected here; score-asr.txt certified nothing)
```

No harness moved: the two regenerated melody baselines are committed here, and every other row compared clean. The one uncompared row is the declared `score-asr` skip (no sherpa native on this machine).

**How far a kept gesture reaches past the band.** The one thing about this rule worth worrying at is a chain of steps carrying a gesture from inside the band to far outside it, so I measured it. Over both corpora and the four field recordings, at the shipped setting:

- read through the stem, four benchmarks have a gesture kept because it touches the band at all; the furthest note in any of them lies two semitones outside, the longest such gesture is seven notes;
- read as given, one does; three semitones outside, five notes.
