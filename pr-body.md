Closes #196.

## Why

`BeatTracker`'s spacing penalty is written as `-(log2(gap / period))^2` and weighted at 1, with a comment quoting Ellis as suggesting "around 1". Ellis writes that penalty in **natural** logs and the reference implementations weight it at **100**. The two forms differ by `1 / (ln 2)^2`, so the shipped penalty was **one forty-eighth** of the published one. That is not a loose setting of Ellis's algorithm; it is a different algorithm, which is why no constant downstream could compensate for it and why PR #207's nine rounds of arithmetic could not find it.

At that weight, inserting one extra beat — two gaps of half a period where there was one — costs `2 * log2(1/2)^2 = 2` on an envelope normalised to unit variance. Any offbeat worth two standard deviations buys itself a beat. `samples/gmajorblues.mp3` is a shuffle, so its loudest events are the swung eighths two thirds of the way through each beat; the dynamic program left the grid for them and came back a beat later.

## Triage was FIX_DIFFERENTLY

#196 guessed at the tempo estimator quantising to a comb-filter lag grid — "108 and 106 may simply be adjacent candidates". They are not: `TempoEstimator` already searches tempo in 0.25 BPM steps with interpolated correlation, so 106 and 108 are eight candidates apart. Interpolating harder would change nothing. Full verdict with evidence is on the issue.

### The three numbers this issue and PR #207 have been conflating

They are different quantities and only the third is #196:

| figure | what it is | |
|---|---|---|
| 106.058 BPM | the music | reproduced here independently: the onset envelope autocorrelates at 27.1697 s over twelve bars, giving 106.000 |
| 106.556 BPM | the tracker's **median** interval | 0.47% fast; a fact about a statistic |
| 108.067 BPM | the tracker's **end-to-end** rate | 1.9% fast; this is the drift, and it is the pulses |

The median resisted the drift because the detour and its catch-up are both off-median — it was never a summary of where the pulses went. PR #207's round-10 refutation was right about which figure to ship and wrong to infer the pulses were fine. #187's "cancels for ~24 bars then collapses" is the same mechanism: a run where the detours pair up, then one where they do not.

## What it does, measured

Both harnesses, all five benchmarks, run against the shipped CLI on this branch and on `origin/main`.

**`tools/score-samples.py` — bars cut on the tracker's own downbeats.** This is the headline the issue is about.

| recording | root before | root after | r+q before | r+q after |
|---|---|---|---|---|
| gmajorblues.mp3 | 50.2% | **84.1%** | 48.9% | **84.1%** |
| blues-a-90bpm.mp3 | 88.5% | 87.6% | 83.2% | 84.1% |
| blues-shuffle-a-106bpm.mp3 | 50.7% | **94.4%** | 3.3% | **16.8%** |
| blues-e-90bpm.mp3 | 48.7% | **99.1%** | 5.1% | 10.8% |
| bossa-cm.mp3 | 14.2% | 15.3% | 1.9% | 2.4% |

**`tools/score-chart.py` — the chart the tool prints.**

| recording | root before | root after |
|---|---|---|
| gmajorblues.mp3 | 63.9% | 67.1% |
| blues-a-90bpm.mp3 | 87.6% | **71.7%** |
| blues-shuffle-a-106bpm.mp3 | 66.2% | 80.4% |
| blues-e-90bpm.mp3 | 72.6% | 98.2% |
| bossa-cm.mp3 | 16.5% | 17.7% |

**The beat grid itself**, against a reference grid derived from each recording's own loop (onset-envelope autocorrelation for the period, envelope energy for the phase), scored as the standard F-measure at 70 ms:

| recording | reference | before | after | beats before/after/true |
|---|---|---|---|---|
| gmajorblues.mp3 | 106.000 | 0.611 | 0.917 | 1281 / 1257 / 1257 |
| blues-a-90bpm.mp3 | 89.998 | 0.970 | 0.977 | 453 / 453 / 456 |
| blues-shuffle-a-106bpm.mp3 | 105.000 | 0.378 | 0.371 | 614 / 577 / 599 |
| blues-e-90bpm.mp3 | 89.999 | 0.928 | 0.990 | 473 / 451 / 454 |
| bossa-cm.mp3 | 74.944 | 0.195 | 0.259 | 624 / 502 / 393 |

On `gmajorblues.mp3` there is now **no place in the recording where the grid steps by other than one beat of the music** — zero index slips over all 1257 beats, against 24 net insertions before.

## The one number that goes down, and why it is not this change

**`blues-a-90bpm.mp3` loses 16 points on `score-chart.py`.** It is the one benchmark whose grid was already right, and it is worth being precise about what moved.

The two grids differ in 27 of 453 beats. Four of those are in the first 2.7 s, which is a lead-in where neither grid is right: the recording's true grid starts at 0.081 s, `main` puts its first beat at 0.000 s and this branch at 0.325 s, and both converge by 2.75 s. `ChartLayout` anchors the entire bar axis on the **first** downbeat, so that one beat decides 113 bars.

Sweeping `--first-downbeat` across the four candidate phases on both grids:

| downbeat phase | before | after |
|---|---|---|
| beat 0 (what ships) | 87.6% | 71.7% |
| beat 1 | 93.0% | 91.2% |
| beat 2 | 93.0% | 96.5% |
| beat 3 | 96.5% | 97.3% |

**The harness reading swings 25 points on which of four beats the anchor lands, the shipped answer is the worst of the four on both grids, and this branch is at least as good as `main` at every phase but the one it happens to have landed on.** The measures that use every downbeat rather than one move by a bar: model score 88.5% → 87.6%, beat F-measure 0.970 → 0.977. Filed as #233; it is #187's neighbour and I have deliberately not fixed it here.

## Candidates rejected, measured

- **librosa's `__beat_local_score`** — convolving the envelope with a Gaussian of `period/32` before the DP, which is part of the algorithm this class cites. Swept against the tightness on all five: it is *worse everywhere*, and badly so on the recording that matters (gmajorblues 0.977 → 0.751 at the best tightness). `OnsetEnvelope`'s band low-pass already leaves attacks a few frames wide; smoothing them again destroys the peak the DP is trying to land on.
- **librosa's `__trim_beats`** — dropping leading and trailing beats whose smoothed onset strength is under half the RMS, which is the obvious answer to the lead-in problem above. It drops nothing on four of the five benchmarks and four beats on the fifth, and moves no score: those lead-in beats *do* sit on onsets, they are simply at the wrong phase. Recorded on #233.
- **Tuning the weight to the benchmarks.** The sweep is monotone-ish upward to 300 and beyond, which is what you would expect from five programmed loops with rigid timing — it rewards rigidity without bound and cannot choose a value. The published weight is used instead, and the sweep is only evidence that the failure is on the low side and that this is not a cliff edge. Two of the five have their best point a little below it and two a little above, by margins far smaller than the distance from the old weight.

## Tests

`BluesLoopIT` had two assertions that existed to pin this defect. Both are replaced, neither relaxed:

- `theTrackedTempoIsCloseToTheLoops` pinned 103–111 BPM. That band admits a rate 4.6% fast, which puts the grid a whole bar off the music inside two of the recording's twenty-six cycles. Now about 1% either side.
- `theBarGridFromTheTrackedBeatsIsWorseThanTheChords` asserted the tracked axis scored at least twenty points *worse* than the loop's, and said in its own comment that the day the gap closed it should fail and be replaced. It failed. The gap is 1.6 points, and it now asserts agreement — two-sided, because the tracked axis running *ahead* would mean the loop constant is wrong rather than that tracking improved.
- New `theTrackedBeatsAreOneBeatApart` pins the interval histogram directly (55.5% → 96.4% at one beat, 24.1% → 0.7% at two thirds). Its own test, because a tempo that is right on average is compatible with a grid that is wrong beat by beat, and that is exactly the state this recording was in.

New tier-0 test `BeatTrackingTest.aLouderOffbeatDoesNotBuyItselfABeat` reproduces the mechanism synthetically. The existing click-track fixtures could not: `SignalFactory.clickTrack` puts every event on a beat, so the spacing penalty is never asked to overrule the onset evidence and its weight is unobservable — which is how a 48x error shipped with tier 0 green. The new fixture puts a loud swung eighth between the beats and a loud backbeat. At the old weight 67 of 100 intervals are a beat long; at the published one, 96 of 99. **No tier-0 or tier-1 threshold has been moved.**

## Prose that rested on the old grid

Corrected where I could re-measure, filed where I could not:

- `tools/score-chart.py`'s docstring claimed the chart column beats the model column on four of five benchmarks. That has **inverted** — it is now lower on four of five, for the same reason read from the other side — so the paragraph now states the mechanism instead of the rule of thumb.
- `ChartLayout`'s three rates (0.5583 / 0.5552 / 0.5631 s) are re-measured to 0.5636 / 0.5658 / 0.5689. Its conclusion survives: the drift by chord 26 is unchanged at about a beat, so 26 still fails for the reason given, but the whole-recording figure falls from seventeen beats to seven, and what is left of it is #200 rather than #196. The chord indices themselves want re-taking a third time and have not been.
- `ChartLayout.atHarmonicRhythm`'s "12.0% to 32.9% of changes are faster than the chart's beat" is now 11.3% to 24.1%.
- **#232**: the chord stage's sweep tables in `ChordEstimator`, `NnlsChroma`, `NoteDictionary`, `LogFrequencySpectrum` and `AudioTranscriber` are anchored on a loop-cut score of 86.6% that is now 85.7%. Fourteen sites. None of their conclusions is in doubt — the differences they rest on are tens of points and the anchor moved by one — but every cell would have to be re-measured, and each cell is a full twelve-minute analysis with a constant patched. A note in `ChordEstimator`'s class javadoc points there, and says explicitly not to subtract the difference from each cell.
- `CLAUDE.md` and `.claude/agents/song-tester.md` named #196 as the live defect. Updated.

## A finding for #200 that I have deliberately **not** acted on

PR #207 proposed making `Score.estimatedTempo()` read the grid's end-to-end rate rather than its median, and was closed on the grounds that on this recording the end-to-end rate was 1.9% from the music while the median was 0.47% from it. **That premise is a consequence of this bug.** With the grid fixed, the median is 105.469 BPM and the end-to-end rate is 106.041 against the music's 106.000 — the sign has flipped.

Measured through the CLI with `--tempo` forcing each figure and scoring the emitted chart:

```
--tempo 105.4688 (the median, what ships)   67.1%
--tempo 106.0408 (the grid's own rate)      85.0%
```

Eighteen points of printed chart, on the same chords and the same beats. That is #200's change, in `mw-core`, and it does not belong in this PR — one change per PR, and `mw-core` goes through its own serialised review. I am posting the measurement on #200 and on #207.

## Where I am unsure

- **The value 100 is inherited, not derived.** It balances against an onset-strength scale that is ours, not librosa's — we do not apply their Gaussian local score (it is worse here, measured above), so the two are not in the same units even though the penalty now is. The benchmarks say the region is right and cannot say more, and every one of them is a rigid programmed loop. Somewhere above this value the tracker stops following a human rubato and nothing here measures where. The clean way to close that would be a fixture with a real tempo ramp; I have not built one.
- **`blues-shuffle-a-106bpm.mp3`'s beat F-measure does not improve** (0.378 → 0.371) even though its per-bar accuracy nearly doubles. The reason is a systematic ~100 ms offset between the tracked grid and my reference grid's phase, which fails a 70 ms tolerance uniformly; the tracker is locking between the beat and the swung eighth. I do not know whether that offset is the tracker, the onset envelope's known early bias (#55), or my reference phase, and I did not chase it because it does not move any product metric.
- **The file is named `blues-shuffle-a-106bpm.mp3` and its loop measures 105.000 BPM**, to five figures, over 27.4285 s. I have taken the measurement over the name but have not resolved the discrepancy.

## Verification

- `mvn -B verify` — 12 modules green.
- `mvn -B -Pintegration verify` — 12 modules green; `BluesLoopIT` 7/7, `EndToEndIT` 7/7.
- Both harnesses run end to end on all five benchmarks against a jar built from this branch and a jar built from `origin/main`, in one worktree with its own local Maven repository.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01N5nLMtZbBqTNtNTLygrHkq
