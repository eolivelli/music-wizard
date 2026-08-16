# How MW detects harmony: chords and key

Two estimators produce chords — one for audio, one for MIDI — and one key
estimator reads whichever's output. The audio chain is where the project's
hardest lessons live.

## Why plain chroma fails on records

Folding the spectrum onto twelve pitch classes is correct for a synthesised
triad and wrong for a record: a real mix's bass partials, overtones, drums
and reverb sum to nearly flat, and against a flat no-chord template "no
chord" becomes the maximum-likelihood answer for the whole song. That was
#185 — an actual track came back as one `N.C.` span — and it taught the
project its central methodological lesson: **the sign of a measurement can
flip between synthetic and real audio**, so synthetic figures are never
quoted as product accuracy.

## The NNLS front end (`NnlsChroma`)

A transcription step in the way of the fold. The spectrum is resampled onto
a pitch-linear grid (a long window, because separating adjacent bass
semitones needs bins a few hertz apart), whitened, and explained as a
non-negative combination of idealised note spectra — so a low C's fifth
partial is attributed to the low C rather than counted as an E. Only the
note activations are folded to chroma.

It produces **two registers**, treble and bass, that must stay in step. The
combined fold is what chord labelling uses — the two registers fail on
different chords, and the sum beats either alone by a wide margin — and the
fold must happen *before* beat-synchronisation, whose per-register
normalisation would give every beat half treble and half bass whatever they
held (the wrong order is refused at runtime, not documented).

The front end alone did not fix #185: through the old emission model the
answer did not change at all. The cure needed both halves.

## The chord estimator (`ChordEstimator`)

Binary templates matched by cosine over beat-synchronous chroma, decoded by
Viterbi with a preference for staying put. On top of that skeleton, the
decisions that real recordings forced:

- **No-chord is a level to clear, not a template to match.** Cosine against a
  flat profile grows *stronger* the less a frame looks like music — the #185
  mechanism. A no-chord model that reads energy and flatness is future work
  (#195).
- **The decoder's vocabulary and the quality decision's are not the same.**
  A quality the decoder may choose competes across *roots*, and a four-note
  template contains a triad on another root (`Am7` is `C` with an A), so the
  minor seventh lives in a quality-only vocabulary that can relabel a placed
  chord but never move it.
- **Root and quality are decided separately** (#208): the root beat by beat
  from both registers (the bass is where roots are played), the quality once
  per run of beats sharing a root, from the treble alone — the bass scales up
  the root's share of the chroma by however loud it was mixed, and the
  chord's colour is what gets scaled down.
- **The bass register is a prior over roots** (#448). Both registers added is
  still a fold, and a fold cannot say which of a chord's own notes is its
  root — the whole difference between a chord and its relative minor, since
  `A6` and `F#m7` are the same four notes. The prior is read over about a bar,
  not beat by beat: a walking bass passes through the third and the sixth,
  and asserting a root at every passing note splits a chord's run in two.
- **Minor-third qualities are scored with a partial-aware correction**: the
  root's own fifth partial *is* the major third, so a minor candidate is
  charged only for the major-third mass the root cannot account for.
  Subtract all of it and a blues third turns minor chords major.
- **The seventh is a property of the chord, not of the bar**: the minor
  seventh is settled per root across the whole recording — believed where
  most of that root's beats carry it, withdrawn where a minority do — so one
  beat of melody bleed cannot relabel one bar of a vamp.

The vocabulary today: major and minor triads, dominant and minor sevenths.
Major sevenths, sixths and half-diminished were each measured to cost more
than they buy until four-note candidates can be ranked on something better
than which extra note is louder (#287, #274). The constants' sweeps are
re-derivable with `tools/ChordSweep.java`; current readings live in
`tools/baselines/`.

## The symbolic estimator (`SymbolicChordEstimator`)

The MIDI path, and deliberately not a reuse of the audio matcher: notes carry
what no chroma can — exact durations, exact pitches, and which part each note
came from. One decision per counted beat over duration-weighted pitch-class
histograms, a Viterbi with a change cost halved at bar lines, and a wider
vocabulary (sevenths, suspensions, diminished/augmented) because exact
pitches support distinctions a chroma bin cannot. "No chord" is a legitimate
answer: a span whose winning chord never sounds three of its own notes is
demoted, and a file with nothing but drums or a drone returns no progression
at all.

## The chart is not the progression

What is printed is deliberately less than what was estimated: each bar is
written at the harmonic rhythm its own evidence supports (#212), because the
estimator disagrees with itself between beats and mostly-right harmony
printed span-by-span reads as noise. Nothing is lost from the model — the
`Score` keeps every span.

## Key detection (`KeyEstimator`)

Reads the estimated chords, not chroma, and reports **two confidences**
because it makes two decisions of very different reliability: the key
signature (reliable), and which of a relative pair is home (the half that
fails). A loop that neither begins nor ends on its tonic gives that second
decision nothing to work with, and it answers at the coin-flip floor rather
than pretending. With no evidence at all, major wins the tie — a prior, never
an override.
