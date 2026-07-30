Closes #139. Triage verdict `FIX_AS_DESCRIBED`, with two refinements the issue
does not mention — [verdict on the issue](https://github.com/eolivelli/music-wizard/issues/139#issuecomment-5129724375).

## The bug

Reproduced on `3f6b766` before touching anything, with the issue's own figures.
First pulse at 0.05 s, so nothing is masked by the origin:

```
times = [0.05, 1.05, 2.05], meter 4/4, pulseQuarters = 2.0
  map tempo at the first tracked pulse  120.0
  grid.medianPulseRate()                 60.00000000000001
  grid.medianTempo(FOUR_FOUR)            60.00000000000001
  score.estimatedTempo()                 60.00000000000001
```

A grid holds *pulses*, and nothing in a list of times says what one pulse is
worth. `medianTempo(meter)` multiplied the pulse rate by the meter's counted
beat and answered as if every grid had been tracked at it.
`TempoMap.fromBeatTimes(times, meter, pulseQuarters)` has always known better, so
the two, built from one set of pulses, disagreed by exactly the ratio the caller
passed. `Score.estimatedTempo()` prefers the grid (#69), so a score whose map was
built to describe 120 reported 60 — and would have printed it on the chart whose
bar lines that map drew.

Latent, as the issue says and as I confirmed: no main-source caller of the
three-argument `fromBeatTimes` exists, so every grid in the reactor is tracked at
the counted beat and the two agree. It becomes a wrong number in somebody's chart
the day a half-tempo tracker or a tempo-doubling correction lands.

## The design, and why it is what it is

`BeatGrid` gains `OptionalDouble pulseQuarters`.

**On the grid, not on the map.** The map already knows the meter, which is why
the issue leaves the choice open. But the map is the summary and the grid is the
evidence: `mw analyze --tempo` replaces the map wholesale and keeps the tracked
beats, precisely because "the beats are measured evidence, whereas the tempo is a
summary of them" (`AudioTranscriber`). A pulse stored on the map is thrown away
by the one command most likely to be correcting a half-or-double reading.

**Stored, not derived — and I checked rather than assumed.** The grid's own bar
cycle looks like it knows: pulses per bar times quarter notes per pulse is the
meter's quarter beats per bar, *always*, so
`meter.quarterBeatsPerBar() / pulsesPerBar` gives 2.0 for the half-tempo grid
above. It is not usable, and `theBarCycleIsNotTheAnswer` executes both reasons: a
grid of `Beat.unphased` pulses has no cycle at all, and a grid shorter than one
bar has one that is observably wrong — three tracked pulses of a 4/4 bar reach a
maximum `positionInBar` of 2 and would imply a pulse of 4/3 quarter notes. That
is inferring a fact from an artifact, which is the guess #141 removed from
`estimatedTempo`, and it fails silently on exactly the short clips this project
already has fixtures for.

**Recorded, not required.** A missing *or explicitly null* `pulseQuarters`
normalises to `OptionalDouble.empty()` in the compact constructor, exactly as
`Provenance` does since #141, and `medianTempo(meter)` falls back to
`meter.beatUnitQuarters()` when the grid records none. A new required property is
#22, and #142 is the same shape still open. The three-argument constructor is
kept for a caller that genuinely does not know — `BeatTracker` phases bars from
onset energy and is never told what a bar is worth — so it says so by omission
rather than by picking a plausible-looking pulse.

## One behaviour change, named because a reviewer will find it

**A grid that records a pulse now ignores the meter passed to `medianTempo`.**
That is the fix rather than a side effect: the meter is the fallback for grids
that say nothing. `BeatUnitTest.gridRateAndTempoAreDifferentNumbers` asserted the
old behaviour — a 6/8 grid asked for its tempo in 4/4 returned its raw pulse rate
— and is updated to assert the new one, with the pulse-agnostic grid kept
alongside so the fallback is still covered.

No production path can reach the difference: nothing outside tests calls
`TempoMap.withTimeSignature` or `withMeterChange`, so `estimatedTempo` always
passes the meter the grid was built with.

## What lands outside mw-core

`AudioTranscriber` only. `double pulseQuarters = meter.beatUnitQuarters()` named
once and given to both `fromBeatTimes` and the grid, replacing two independent
expressions of one figure forty lines apart — the caller the issue names. The
map's arithmetic is bit-for-bit unchanged, because the two-argument
`fromBeatTimes` already delegated to exactly that. Plus `TrackedPulseUnitTest`,
new, which reads the pulse back off the map's own beat axis and asserts the grid
records the same number, so a transcriber that passed the two different figures
fails there.

Nothing in `mw-notation`, `mw-cli` or `mw-arrange` is touched.

## Every reader of the value that changed

`medianTempo` is the only place in the reactor that converts pulses to quarter
notes. Its only main-source caller is `Score.estimatedTempo()`. The other
accessors — `beatTimes`, `downbeatTimes`, `nearestBeatIndex`, `size`,
`medianPulseRate` — are pulse-agnostic and unaffected; `PitchSpeller` and
`Quantizer` pass the grid through without reading it. Grids are constructed in
`BeatTracker.toBeatGrid` (records nothing, by design) and by the two `ofTimes`
factories.

## What I verified, and how

- **`mvn -B verify` green across all 12 modules**: 343 tests in `mw-core`
  (16 new), 188 in `mw-transcribe` (2 new), and the rest unchanged.
- **`mvn -B verify -Pintegration` green**, `EndToEndIT` 6/6 including the one
  that asserts the chart starts `C G Am F`.
- **Old files open and say the same thing.**
  `mw-core/src/test/resources/score-before-the-pulse-unit.json` was generated by
  running, on `3f6b766`, the construction `AudioTranscriber` used there for a 6/8
  analysis, and committed byte for byte; the pre-change build printed
  `estimatedTempo = 180.0` for it, which is what `readsAPreChangeScoreFile`
  asserts on the new build. The property is genuinely *absent* from that file,
  asserted, so it exercises the missing-creator-argument path; a separate test
  covers an explicit `null`, which reaches the constructor by a different route.
- **The fix is end to end, not an accessor.** `tracksAndMapAgreeOnPositionsToo`
  checks every tracked pulse of a half-tempo grid lands on a whole pulse of the
  map, because a grid and a map that agree on the rate but not on where the
  pulses fall still misplace every chord.
- **Recording the assumption changes no answer**, asserted over seven meters
  including 6/8, 9/8, 12/8, 5/4 and 7/8, down to bar phase.
- No fixture in the new tests starts at t = 0: the lead-in is rounded to whole
  *pulses*, so a grid at the origin agrees with the wrong arithmetic as readily
  as with the right one.

## What I am unsure about

- Nothing stops a producer from building a grid through the three-argument
  constructor and leaving the pulse unrecorded when it does know — the same gap
  #143 records for `TempoMap.constant`. `BeatTracker` is the one such producer
  and it genuinely does not know; `AudioTranscriber` fills it in. Only review
  enforces that.
- `ofTimes(times, meter, pulseQuarters, confidence)` requires the pulse to divide
  the bar into a whole number of pulses, since otherwise the grid cannot say
  where a bar begins. That rejects a 1.5-quarter pulse in 4/4, which is right,
  but I have not thought of a real tracker that would want one.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01N5nLMtZbBqTNtNTLygrHkq
