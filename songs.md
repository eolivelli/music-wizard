## What this does to five real commercial recordings

The five benchmarks are programmed loops, which is the largest hole in the evidence above — I said in the description that they cannot choose the constant and that nothing here measures where the tracker stops following a human rubato. So I ran the five commercial recordings in `uncommitted/` through both jars. **There is no ground truth for any of them**, so nothing below is scored; these are the two things that can be measured without one.

### The grid is more regular on all five

Share of tracked intervals within a tenth of the median, and the share that are the two-thirds detour this bug is made of:

| recording | on grid before | after | two-thirds before | after |
|---|---|---|---|---|
| la-canzone-del-sole | 55.2% | 88.9% | 8.2% | 2.1% |
| gli-anni | 14.8% | 70.6% | 36.0% | 0.0% |
| islanda | 22.4% | 46.2% | 6.0% | 5.3% |
| karma-chameleon | 53.9% | 93.9% | 3.1% | 1.8% |
| hanno-ucciso-luomo-ragno-karaoke | 27.5% | 92.4% | 37.6% | 0.0% |

`islanda` is much the weakest of the five afterwards and was the weakest before.

### The reported tempo now agrees with the tempo estimator, and it did not

| recording | `TempoEstimator` says | tracked before | tracked after |
|---|---|---|---|
| la-canzone-del-sole | 87.00 | 89.10 | 86.86 |
| gli-anni | 73.00 | 99.38 | 73.83 |
| islanda | 87.75 | 159.01 | 90.27 |
| karma-chameleon | 92.25 | 95.70 | 92.29 |
| hanno-ucciso-luomo-ragno-karaoke | 120.25 | 159.01 | 120.19 |

**The tracked tempo is now within about a percent of the seed on all five; before, it disagreed with it by up to 81%.** That is the other face of the octave finding two comments up, and it is the honest way to describe what changed: `BeatTracker` has stopped overriding the period it was handed, so the number a user sees is `TempoEstimator`'s answer rather than a hybrid nobody chose.

**Whether those readings are better I cannot say, and I am not going to claim it.** `uncommitted/list.txt` records that a human judged `hanno-ucciso`'s chart good at 159 BPM, and this branch reports 120 there. Two things push toward the new figures — the estimator gives 120.25 in 21 of that recording's 22 windows, and the old grid produced four beats for every three the new one does, which is what counting some swung eighths as beats looks like — but neither is evidence, and the honest statement is that the number moved and nothing here measures which way. Where there *is* ground truth, on the five benchmarks, the reported tempo is right on four of five before and after, and the fifth is #231.

### One 7.9 s hole, and it is a fade-out

`la-canzone-del-sole` gains a single gap longer than 1.5 s, at 342.1 s, and it is the only one across all five. I checked the audio rather than assuming: over 342–350 s the recording's RMS falls from 0.108 to 0.006 and reaches digital silence at 350 s, and the onset envelope's peak over the same span falls from about 3 to under 1.4. So the tracker declines to place beats where the recording has stopped, and `list.txt`'s complaint about that song is that its final ten seconds "degrade into low-confidence chromatic noise". Placing beats on a fade is what produced that.

### A note I have deliberately not acted on

`uncommitted/list.txt` attributes three observations to #196 — the chromatic fade-out tails on two recordings, and `la-canzone-del-sole`'s wobbling grid. The third is this bug and is measurably better above; the first two are fades. I have not edited that file: it is a dated running log and a parallel session appends to it, so a new dated entry is the right way to supersede those lines rather than a diff from me. The measurement is here and on #196 so it is not lost.
