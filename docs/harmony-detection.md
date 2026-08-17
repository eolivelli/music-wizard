# How MW detects harmony: chords and key

Two estimators produce chords — one for audio, one for MIDI. The key is
estimated on the audio path only, from those chords; a MIDI file's declared
key signatures are read, never estimated. The audio chain is where the
project's hardest lessons live.

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
- **A third the fit does not need is not a third** (#537). The same partial
  is why the fit will activate a note that is not playing to cover it, and a
  chroma reports that note exactly as it reports a played one — which is a
  minor tonic read major for most of a record (#527). So the quality decision
  refits the chord's own span with the candidate third's pitch class deleted
  and reads what the deletion costs (`NnlsAblation`): a major third that
  removes less residual than the minor third over the same root, *and* only a
  small share of what the root itself removes, is not counted — neither as a
  major candidate's evidence nor against a minor one. Both conditions: the
  ranking alone also fires on a blues third over a dominant, where both
  thirds are played. The minor third is asked its own, simpler question —
  does the fit need it at all — because otherwise a run holding neither
  third goes minor on the noise left on its pitch class (#546).
- **The seventh is a property of the chord, not of the bar**: the minor
  seventh is settled per root across the whole recording — believed where
  most of that root's beats carry it, withdrawn where a minority do — so one
  beat of melody bleed cannot relabel one bar of a vamp.

The vocabulary today: major and minor triads, dominant and minor sevenths,
and — since the quality decision can ask the NNLS fit whether a candidate's
distinguishing note is really there (#537, #543) — minor sixths and
half-diminished sevenths (#547). The two exclusions have two different
reasons (#287 carries the tables): the *seventh degree* is not
residual-gated at all, because a flat seventh really played on a real mix
removes less residual as a share of its root's than a manufactured one does
on a rendered package, so major sevenths cannot be admitted the way the
sixth and the diminished fifth were; and the plain sixth's problem is the
opposite — a boogie shuffle really plays its sixth, so no test of whether
the note is sounding can help, and telling `A6` from `F#m7` needs evidence
about which sounding note the chord is built on. The
constants' sweeps are re-derivable with `tools/ChordSweep.java`; current
readings live in `tools/baselines/`.

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
