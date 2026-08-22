Closes #614. Leaves #615 open, with what I measured for it on the issue.

## Why

`foldOctaves` decided each note against the band alone. A run of notes a step
or two apart that the band's edge fell inside was therefore decided note by
note, on a semitone of tracker noise, and came out in two octaves.

I measured it before fixing it. `tools/OctaveSweep.java` gains a `splits` mode
that counts pairs of notes next to each other in time and within a whole tone
that the fold leaves an octave or more apart, alongside what it moved and
whether truth called those notes right before and after. At the shipped
constants:

- two such pairs on `pop-axis` read as given, one on `pop-borrowed-iv` read
  through the stem;
- none on vocadito, and none on four field recordings (`per-sempre-si`,
  `la-canzone-del-sole`, `generale`, `bellissimissima`);
- where it happens it is expensive: on `pop-axis` the fold moves thirteen notes
  that truth calls right and none that it calls wrong — the edge lands inside a
  phrase the tracker read correctly in another register.

So this is a defect of the mixes where the tracker is not on the melody, and it
is worth what it costs there.

## The rule

Notes following one another within a whole tone are one gesture, decided once.
A gesture with any note inside the band keeps its octave whole; one entirely
outside moves as a unit, by the shift its own longest-held pitch takes, so its
own intervals survive. A whole tone is the widest step a scale takes; the sweep
holds flat above it and keeps the split below it.

**Every recovery the fold makes today survives.** The moves it now declines are
the ones truth calls right — that is the whole of the movement, and it is why
three baseline rows rise and none falls.

## The bench reaches `--separated` now

#613 flagged that `OctaveSweep` could not reach the configuration
`analyze --melody` actually uses. It can now: it separates with the default
provider, caches the stem's front end like any other, takes the tuning from the
mix and the envelope from the stem. **Its rows reproduce all 49 rows of both
committed `--separated` baselines exactly**, which is the only reason to trust
what it says about a setting.

## What moved

Two melody baselines are regenerated; every other harness is byte-identical.

| row | |
|---|---|
| `pop-axis` pinned | `F1@100ms` and `pitch` rise; five of the thirteen right notes it was losing are kept |
| `pop-axis` separated | all three columns rise |
| `melody-level4` separated | all three columns rise — the one melody target that moves |

**Real singing does not move at all**: both vocadito baselines are byte-identical,
pinned and separated, and no clip's fold changes. The annotator ceiling is
therefore exactly as far away as it was.

The four field recordings keep their fold: 32, 15 and 27 notes moved on
`la-canzone-del-sole`, `generale` and `bellissimissima` before and after, and
12 of 13 on `per-sempre-si` — the one that no longer moves belongs to a gesture
that reaches into the band.

## Tests

`MelodyEstimationTest` gains two and changes one. `aRunAcrossTheEdgeIsNotCut`
is the fail-before executed in the suite rather than by reverting: at a gesture
of zero — every note decided alone, which is what this branch replaces — the
run comes out spread over three octaves, and at the shipped setting it is one
gesture. `aNoteWithNothingNearItIsStillFolded` guards the other side, which is
what the fold does for vocadito: a note alone past the edge is still moved.
`anEmptyStretchIsNotEvidence` asserted the split as a known limit and now
asserts the leakage keeping its octave whole.

## Local gate

`tools/premerge.sh` output is posted in a comment below.
