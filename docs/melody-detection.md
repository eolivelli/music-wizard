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
periodic, usually the bass. That is why `analyze --melody` is off by
default: it is for a recording whose melody is the only thing sounding, or a
separated vocal stem. Reading the melody out of a full mix through
separation is the roadmap item (#8), and the measured gap between clean solo
singing and a mix is separation's, not the melody stage's (#503).

## Notes from the track (`MelodyEstimator`)

A run of voiced frames is cut wherever the rounded pitch changes; pieces too
short to be notes are removed (the decoded path *travels through* the
pitches between two notes, and those transit frames would otherwise become a
note of their own on every interval wider than a semitone); what is left
absorbs the gaps.

The onset envelope — the same one the beat tracker reads — splits
**re-articulations**: two notes of the same pitch with no gap are invisible
in a pitch track, because a re-articulation is an amplitude event. A strong
envelope peak inside a note cuts it in two, but only where the voice itself
restarts (#495).

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
