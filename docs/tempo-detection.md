# How MW detects tempo, beats, meter and bars

The chain is: onset envelope → tempo estimate → beat tracking → meter →
downbeats → `TempoMap`. Each link exists to fix a way the previous one fails on real
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
- **The bass register decides one octave the other two cannot.** Levelling
  the accents is also what stops a marked quarter outscoring an eighth-note
  hi-hat, and the prior then takes the faster grid (#509). Which instruments
  play on the beats in question is the evidence the summed envelope throws
  away, so `MarkedPulse` reads the lowest mel bands alone
  (`OnsetEnvelope.pulseRegister`) on the beats the tracker lays down, and
  halves a grid whose every second beat is unstated there — but only where
  the envelope's own ranking had the half above the grid anyway, since read
  alone the register cannot tell a doubled grid from a correct one whose bass
  plays half as often. Only that direction, too: doubling would have to force
  the tracker to twice the rate, where it lands beats on any energy at all.

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

## Meter (`MeterEstimator`)

The tracker says where the pulses are and the downbeat estimator which of
them begin bars — given a bar length. The bar length is read here, from the
same harmonic change: chords change preferentially at bar lines, so the bar
is the period that change repeats at. Each candidate is scored by one Fourier
coefficient of the per-beat novelty over the novelty's own energy, a
statistic with the same expectation at every period under a null of
independent beats. That is what makes bar lengths comparable at all; the
per-phase means the downbeat estimator uses are not, since the best of four
phases beats the best of three by chance.

**4/4 is the prior and nothing leaves it cheaply.** A wrong meter moves every
bar line, so the gates are asymmetric: a bar of three or six tracked pulses
has to be supported on its own *and* clear the four-beat bar by a margin, and
a reading that does not is reported as 4/4 at the confidence the evidence
gave it rather than hidden — unless the two-pulse evidence below admits a
shorter bar. The statistic cannot prefer a period over its own
divisors — novelty repeating every six beats scores the same at three — so
where six is comparable to three, six is believed: the shorter reading is
implied by the longer, never the reverse.

What is read, and from what:

- **3/4** — three tracked pulses to a bar, from harmony.
- **6/8 with the tracker on the eighth** — six pulses to a bar, from harmony;
  the estimate carries the pulse count beside the signature, and the tempo is
  printed on the dotted quarter. Not where the pulse divides in three: both
  signatures six pulses can name hold three quarter notes, so six pulses that
  are dotted quarters are no bar, and the two-pulse reading below takes it
  (#727).
- **A bar of two pulses, named 6/8** — the dotted quarter is the pulse,
  which is where a listener taps, and the bar is two of them. Harmony cannot
  choose this one: comping that moves every two beats of a 4/4 bar scores at
  period two exactly as a compound bar counted in two does. So the two-pulse
  bar is admitted only where the pulse also **divides in three**, read from
  the onset envelope's own periodicity at a third and two thirds of the pulse
  — the one thing the envelope is asked. Harmony can refuse it only on a
  length two does not divide, or by saying nothing at any length two
  divides: a two-bar chord loop is periodic at four pulses whether the bar
  is two of them or four, so
  a supported four is a length the shorter bar tiles, not a rival account of
  it (#712), and a three that a supported six accounts for is that six seen
  again rather than a refusal (#727). What is read there is the length; the
  signature is given, since
  a bar of two dotted-quarter pulses is 3/4 as much as it is 6/8 and nothing
  measured separates them (#728).

What is not read, and why:

- **12/8.** A swung 4/4 divides its pulse in three exactly as a compound bar
  does, and every shuffle in the corpus is barred in four by its own confirmed
  cycle; the position a compound sounds and a shuffle leaves out does not
  separate them either (`tools/MeterSweep.java`'s `mid` column, #701). So the
  division may admit a two-pulse bar and may not promote a four-pulse one.
  `--time-signature 12/8` works end to end.
- **3/8.** One pulse to a bar leaves the downbeat estimator no phase to
  choose (#701).
- **2/4**; **5/4, 7/8 and the other irregular meters** (#62); and **a meter
  that changes** within a recording: one meter per run.
- **Accent** is not evidence for the bar length. On ordinary drum material
  its strongest periodicity is the backbeat (#70), which argues for a two-beat
  bar across the corpus and for four over three on its waltz.

The reading is printed on `analyze`'s meter line with its confidence, and on
the chart header; the run log's beats stage carries the same fact. The score
file records the meter but not yet whether it was read, typed or assumed
(#703). `tools/MeterSweep.java` prints the readings behind every constant,
for the committed benchmarks and the local-only recordings alike, and the
chord harnesses (`tools/score-samples.py`, `tools/score-synthetic.py`) carry a
meter column against each recording's stated meter — read `tools/baselines/`
for what it is worth today rather than any figure in prose.

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
  tracked pulses fill a bar (#139).
- `--first-downbeat` chooses the bar phase outright, as a time in
  *seconds* snapped to the nearest tracked beat; the estimator is not run,
  because a human who counted the bars outranks harmonic novelty.
- `--time-signature` states the meter and wins outright on the *signature*.
  The reading is still taken, for its other half: how many tracked pulses fill
  a bar is a measurement, and where the typed bar holds as much music as the
  read one it is kept, so the run bars exactly as it would have with nothing
  typed and only the name on the page changes (#736). A typed bar of another
  length takes its own counted beat for the pulse, as before; where that drops
  a reading that had put the tracker below the counted beat, the run says so.
  `--tempo` is what states the pulse where the reading has it wrong.
  `--time-signature` is also the only route to 12/8, 3/8 and the irregular
  meters.

`--tempo` is read in the meter's *counted* beats per minute — what a
metronome shows — which differs from quarter notes in compound time, and which
meter that is may be one MW read rather than one anyone typed (#705).
