# How MW detects melody

Two stages: a frame-by-frame pitch tracker, then a segmenter that turns the
track into notes. Both are classical DSP — no neural model on this path yet.

## Pitch tracking (`PitchTracker`)

Monophonic fundamental-frequency tracking after pYIN (Mauch & Dixon, 2014):
YIN's difference function read at many thresholds at once, each candidate
weighted by a Beta prior over thresholds, and the per-frame distribution
decoded by Viterbi with a voiced/unvoiced switch.

The two decisions that carry the quality are pYIN's rather than YIN's:

- **No single threshold is chosen.** YIN's one constant decides every octave
  error it will ever make; reading every threshold lets an ambiguous frame
  stay ambiguous until its neighbours settle it.
- **The sequence is decoded, not the frame.** Pitch moves slowly within a
  note and voicing persists, so a frame whose strongest candidate is an
  octave down still loses if its neighbours are an octave up. The price is
  that the same continuity smears note boundaries — an onset read from the
  track is late, and a leap is later than a step.

**Monophonic means monophonic.** Pointed at a mix it does not fail — it
confidently returns one fundamental for whatever is loudest and most
periodic, usually the bass. That is why `analyze --melody` is off by default,
and why it does not point the tracker at the mix.

## Which signal the tracker reads (#559)

`analyze --melody` reads the **separated vocal** wherever a separation
provider can be had, and says which signal it read in its own output. The mix
is the fallback: no provider, `--skip-separation`, or a separator that could
not run. A run asking for both a melody and transcribed lyrics separates once.

Two measurements decide that default, and they point opposite ways —
`tools/baselines/score-melody*.txt` holds all four, each corpus read both ways.

- Where a band plays under the melody, the mix melody is the band. Separating
  lifts the pitch column off the floor on some of those packages and not on
  others; `score-melody-separated.txt` beside `score-melody.txt` is the
  reading.
- Where the melody is **sung** alone, separation costs about nothing: clips
  move both ways and no column's mean moves by as much as a point, which is
  #503's finding on this corpus.
- Where the melody is **played** alone — a solo instrument, or the synthetic
  packages' rendered melody lines — a vocal separator has no voice to keep and
  the melody largely does not survive it. **Pass `--skip-separation` for a
  recording whose melody is not a voice.** Choosing between the two signals by
  evidence rather than by a flag is #560.

## Notes from the track (`MelodyEstimator`)

A run of voiced frames is cut where the pitch moves away from the note's
own running mean — deliberately *not* by rounding frames to semitones and
grouping, which would make a slightly-flat note vanish into its neighbours.
Pieces too short to be notes are removed (the decoded path *travels
through* the pitches between two notes, and those transit frames would
otherwise become a note of their own on every interval wider than a
semitone); what is left absorbs the gaps.

The onset envelope of **the signal being tracked** splits
**re-articulations**: two notes of the same pitch with no gap are invisible
in a pitch track, because a re-articulation is an amplitude event. A strong
envelope peak inside a note cuts it in two, but only where the voice itself
restarts (#495). Its strength is measured in standard deviations of that
signal's own envelope, so on a stem run this is the stem's envelope and not
the mix's — a mix envelope would rate the drums against the band's spread and
cut vocal notes on them.

What the envelope deliberately does *not* do is move the boundaries: against
exact MIDI truth that would close the onset lateness completely, but against
real singing it moves boundaries *away* from human annotations — the
envelope marks the attack where a human marks the sung vowel (#497). One
more instance of the project's standing rule: what synthetic truth rewards,
real truth can punish.

Last, notes the tracker read **an octave or two out** are folded back (#596).
A tracker that locks onto a harmonic reports a multiple of the fundamental,
so the pitch class survives and the note is recovered by moving it, never by
dropping it. The octave the melody is in comes from the recording's own notes
rather than from a fixed compass, so a stretch where the separator left no
voice cannot state the singer's tessitura and a line that ranges wide is not
held to a voice's range.

**How far the fold may move a note is bounded**, and that bound is what stops
it relocating a phrase it should not touch. On a recording where the tracker
sits in one register for most of the length and in another for the rest — the
accompaniment and then the melody (#560) — the band's centre belongs to
whichever sounds longer, and without a bound the other is folded into it a
correct note at a time. The bound is on the correction rather than on how far
out the note was, and the bench sweeps it alongside the band.

**The fold decides a gesture, not a note** (#614). Notes following one another
within a whole tone are one line moving, and which side of the band's edge each
of them falls on is a semitone of tracker noise — so a gesture with any note
inside the band keeps its octave whole, and one entirely outside moves as a unit
and keeps its own intervals. A gesture is grouped by pitch alone and so bridges
a silence of any length (#664).

A line whose wide notes are too rare to widen its own band is held to a narrow
one (#615). A lone leap is its own gesture and is folded with the rest, and a
register lying wholly outside the band is folded
gesture by gesture — which is why a recording the tracker reads in two registers
can still lose the shorter one.

`tools/OctaveSweep.java` is the bench. Its `octaves` mode says what the corpora
can and cannot show — almost none of what the stage gets wrong on clean solo
singing is wrong by whole octaves, so this is a defect of mixes and separation
and the field recordings are what witness it — and its `splits` mode says what
the fold itself did: what it moved, whether truth called those notes right
before and after, and how many pairs of notes within a width the caller names it
left an octave or more apart. That width has to be wider than the setting, and
the mode refuses it otherwise rather than printing the zero the setting
guarantees. `--separated`
reads every recording the way `analyze --melody` does, which is what the two
`--separated` baselines score.

## What comes out

`render --parts lead` engraves a lead sheet (melody, chord symbols, words)
and `--parts voice` a bare melody staff. Honest expectations: pitch mostly
right, rhythm approximate — the quantizer's job is a plausible reading, not
a literal one.

`--parts playable` engraves the lead sheet a second time from a **reduced**
melody (#592): a sung syllable carries one note-head, and the pitch printed
is the one its group settles on rather than an average of the ones it passed
through. A syllable **the melody moves under** keeps its notes instead
(#597): the aligner marks it a melisma, and the reduction then prints the run
rather than collapsing it. Movement is the evidence, not length — a syllable
merely held, and one re-articulated on its own pitch, stay collapsed, and so
does one whose neighbouring notes leap an octave, which is the octave fold
rather than a voice — the leap, not the syllable's whole reach, since a real
run can cover an octave a step at a time (#624). The decision is taken only on
the lines the aligner actually
measured, because a line left at its parsed times has its words apportioned
across it by a syllable count and such a span says nothing about what is sung
over it; `tools/PlayablePartCheck.java` sweeps both intervals and prints what
each setting marks. That is synthesis rather than transcription,
so it is written only when asked for and the estimate is untouched — every
melody baseline scores the estimate, and a page a player reads and a page
that answers what the singer did are two different correctness conditions.
Where there are no words the grouping falls back to absorbing an ornament
into the note it leads into, which is much weaker: what says where a sung
gesture begins is the onset envelope, and that is audio, which the
arrangement layer cannot see.

The reduced page is also written on a **narrower set of divisions** than the
estimate's (#594), which `QuantizationSettings.READING` defines. A bar of the
reduction holds a handful of note-heads, few enough that some division always
fits them, so the one that wins where all are offered is fitting the
segmenter's spread. `tools/PlayablePartCheck.java` prints the sweep it was
chosen from, against an arranger's own reading of the same recording, and its
two page columns are why the restriction has two halves: withdrawing a division
takes brackets off the page and puts depth on.
