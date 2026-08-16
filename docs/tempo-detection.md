# How MW detects tempo, beats and bars

The chain is: onset envelope → tempo estimate → beat tracking → downbeats →
`TempoMap`. Each link exists to fix a way the previous one fails on real
recordings.

## The onset envelope (`OnsetEnvelope`)

Spectral flux over mel bands: the first difference of each band's decibel
series, half-wave rectified (a note *ending* is not an onset) and summed.
Three details matter more than the textbook picture:

- **Each band is low-passed along time before differencing.** A held note's
  partials beat inside FFT bins at rates far above the frame rate's Nyquist;
  sampled unfiltered, that ripple folds into the tempo range and a sustained
  tone reads as rhythmic. The filter is zero-phase (forwards then backwards)
  because beat tracking reads onset *times* off this signal.
- **The silence floor is relative to the recording.** A decibel scale is
  unbounded below, so the step out of digital silence would otherwise be the
  largest "attack" in the envelope — lead-ins and fade-outs used to read as
  tempo events.
- The envelope is normalised to zero mean and unit variance, so downstream
  constants are in a scale the recording sets.

## Tempo estimation (`TempoEstimator`)

Autocorrelation of the envelope, with a log-normal perceptual prior centred
near ordinary tempos to resolve the octave (is it 60 or 120 BPM?). Two
corrections earn their place on real music:

- **Accents are ceilinged before correlating.** Metre *is* accent
  alternation, and autocorrelation reads a strong-weak pattern as evidence
  for half the tempo — the more clearly a recording states its metre, the
  harder its own autocorrelation argues for the half-bar. Holding the loudest
  frames level removes that bias without touching the quiet frames where
  subdivisions live (a compression curve would raise those too).
- **Harmonic rhythm weighs the candidates.** A comping pattern can put the
  strongest correlation peak at a rate that is no subdivision of the beat at
  all; what that pulse cannot do is *bar the harmony*. `HarmonicRhythm`
  measures which pulses can, from frame-level chroma, and candidates the
  harmony cannot be barred by keep only a floor of their score.

The confidence (`periodicity × peakiness`) is deliberately weak: it is zero
for silence and comparable between two readings of the same recording, and
supports no absolute threshold — see the javadoc before gating anything on it.

## Beat tracking (`BeatTracker`)

Ellis (2007) dynamic programming: maximise onset strength at the beats plus a
spacing penalty on deviating from the period, decoded exactly by
backtracking. One tempo is assumed per window of tens of seconds; windows half-overlap
and each contributes its first half.

- **The penalty weight is the published one, and the base of the logarithm is
  part of the constant.** MW once shipped the penalty in log2 at the natural-
  log form's weight — a factor of (ln 2)² under the published algorithm — and
  a loud swung eighth could buy itself a beat (#196). No downstream constant
  could compensate.
- **Window seeds are corrected against the recording's pulse.** Each window
  estimates its own tempo (that is what follows drift), but which
  *subdivision* of the beat it landed on is a property of the recording:
  every window's seed is read against the median of the windows' seeds,
  and a seed that is a musical subdivision (½, ⅓, 2, 3…) of that reference
  is divided out before tracking. The dynamic program itself will not fix an octave
  error — at the published weight it follows its seed — so the seed is the
  only place it can be fixed.

## Downbeats (`DownbeatEstimator`)

The beats say where the pulse is, not where the bar starts. The phase is
chosen from **harmonic change**: chords change preferentially at bar lines,
measured as cosine distance between beat-synchronous chroma either side of
each beat — no chord labels involved, so downbeat detection does not depend
on chord estimation (which depends on the beats). Onset energy survives only
as a bounded tie-breaker: on real drum material the loudest phase is the
backbeat, so an accent may decide only where harmony cannot distinguish the
phases, and it is never asked to vouch for the phase it chose.

The known limit: this measures agreement with harmonic change, not with bar
lines. A style that consistently *anticipates* the chord moves the harmony a
beat early, and nothing in the chroma separates "pushed bar" from "bar
starting a beat later" — which is why the confidence is ceilinged below
certainty, and why the bass evidence that would settle it is future work
(#42).

## From beats to bars: `TempoMap` and the chart's bar axis

`TempoMap.fromBeatTimes` fits one tempo segment per beat interval, preserving
the measured timing exactly, and anchors a lead-in of whole pulses so the
first downbeat lands on a bar line — the map and the grid stored beside it in
one file must not disagree about where beat one is (#84, #501).

On the chart, where the grid's downbeats are every one of them a plausible
bar, they *are* the bar lines (#187); a veto (`evenThroughout`) refuses a
sequence with any bar that is not one, because repairs measured worse than
the constant rate (#421). Where the sequence is refused, the chart is one bar
length hung on the phase the downbeats agree on (#233).

## The corrections

- `--tempo` corrects the *rate* and keeps the tracked beats: a user
  correcting tempo is usually correcting a half-or-double reading, and the
  beats are measured evidence. The corrected ratio also tells MW how many
  tracked pulses fill a bar (#139), which nothing can measure from audio.
- `--first-downbeat` chooses the bar phase outright, as a time in
  *seconds* snapped to the nearest tracked beat; the estimator is not run,
  because a human who counted the bars outranks harmonic novelty.
- `--time-signature` states the meter, which is **assumed 4/4 and never
  inferred** — nothing on the audio path estimates a meter.

`--tempo` is read in the meter's *counted* beats per minute — what a
metronome shows — which differs from quarter notes in compound time.
