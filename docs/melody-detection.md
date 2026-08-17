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

## What comes out

`render --parts lead` engraves a lead sheet (melody, chord symbols, words)
and `--parts voice` a bare melody staff. Honest expectations: pitch mostly
right, rhythm approximate — the quantizer's job is a plausible reading, not
a literal one.
