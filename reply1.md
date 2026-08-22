Round 1 — all six findings taken, in `8fa34ea7`. Thank you for the two that
matter; finding 2 is the exact shape of defect this repo has a note about.

**1 and 2 — the blind count.** Confirmed and fixed as a code change, not a
comment. The width the count is asked at is now the caller's first argument,
and a width the setting has already answered is refused with its reason rather
than printed:

```
$ ... OctaveSweep.java splits 2 14 0.9 2 2
a width of 2 is answered by a gesture of 2: nothing that near can be split,
whatever the fold does                                          # exit 2
```

The mode can now fail at the shipped setting, and does — asked at a fifth,
`pop-threechord` through the stem leaves two such pairs an octave apart:

```
$ ... --separated splits 7 14 0.9 2 2
synthetic ... pop-threechord-c-108: notes=58 moved=7  right before 0 after 2
                                    pairs within 7 cut 2
$ ... --separated splits 4 14 0.9 2 2      # and none within a major third
```

The defect this branch fixes is still counted the same way, now with the width
named: `splits 2 14 0.9 2 0` gives two pairs on `pop-axis` as given and one on
`pop-borrowed-iv` through the stem. The bench's own copy of the width, and the
comment claiming the stage had no such notion, are gone.

**3 — the gesture has no time condition.** Confirmed, and I am keeping the rule
while dropping the temporal justification, because I measured the repair.
Requiring the notes to touch, with a silence no longer than the shortest thing
that can be a note, puts `pop-axis` read as given straight back to its pre-#614
row: the notes of a phrase in a mix are parted by unvoiced stretches longer than
any note. A longer tolerance is a new constant with a shallow plateau — half a
second holds one of that recording's two phrases together and breaks the other.
So the javadoc now says nearness in pitch and nothing else, names the residual,
and #664 carries it with both measurements, yours and mine.

**4** — clause cut, and the paragraph now says what the shorter register really
loses to: the band's width, gesture by gesture, which is #615 from the other
side. **5** — rewritten with no number in it. **6** — replaced by the one
sentence that is worth a javadoc there, the why for #664.

Also fixed, my own: `theBandIsSweepable` still described a zero setting in the
words of a band edge rather than a gesture.

No behaviour changed in this pass — the estimator's diff since `9bfcc48c` is
javadoc only — so the baselines and the premerge run above stand.
