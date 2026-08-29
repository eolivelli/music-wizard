# How MW works: the pipeline

MP3 in; beats, tempo, key and chords out, engraved as a chord chart —
with lyrics under the chords when supplied or transcribed, and a lead sheet
when the melody stage is asked for. Bass and piano parts are the goal, not
yet the product. This page is the map; each stage has a page of its own:

- [Tempo, beats, meter and bars](tempo-detection.md)
- [Harmony: chords and key](harmony-detection.md)
- [Melody](melody-detection.md)
- [Lyrics](lyrics-detection.md)

## The stages, in order

```
decode ─ resample ─┬─ onset envelope ──┐
                   │                   ├─ beat tracking ─ meter ─ downbeats ─ TempoMap
                   └─ NNLS chroma ─────┘        │
                            │                   │
                            └─ chords ── key ───┴─ Score ─ quantize ─ engrave
```

1. **Decode** the recording and resample it to the analysis rate.
2. **Onset envelope** — where the attacks are, from spectral flux over mel
   bands.
3. **Chroma** — what pitch classes sound, frame by frame, through an
   approximate-transcription front end (NNLS) built for real mixes.
4. **Beat tracking** — dynamic programming over the onset envelope, with the
   recording's harmonic rhythm weighing the tempo candidates.
5. **Meter** — how many tracked pulses make a bar, from the period harmonic
   change repeats at, with 4/4 as the prior; a 6/8 counted in two rests on
   how the pulse divides, read from the onset envelope. 4/4, 3/4 and 6/8 are
   read; 12/8, 3/8 and irregular meters are typed.
6. **Downbeats** — which tracked beats begin bars, chosen from harmonic change.
7. **Chords** — template matching over beat-synchronous chroma, decoded with
   Viterbi, with the bass register as a prior over roots.
8. **Key** — read from the estimated chords, not from chroma.
9. **Score assembly** — everything lands in one `Score` on one time axis.
10. **Quantize and engrave** — grid choice per bar, then LilyPond source and
   PDF, emitted straight from the domain model (a MusicXML export exists in
   the notation layer but is not yet wired to the CLI).

A Standard MIDI File enters the same `Score` by a symbolic route instead:
tempo, meter and keys are *read* rather than estimated, and only the chords
are estimated (symbolically — exact pitches, no chroma).

## The two rules that shape everything

**Chords are estimated from the full mix, never from separated stems.**
Separation artifacts destroy the partial structure chroma estimation depends
on. Separation exists to feed melody, bass and lyrics only — and it does feed
melody: `--melody` tracks the separated vocal where there is a separator
(#559), which is why the stage sits outside the chain above.

**Nothing downstream of the beat grid works in seconds.** Once beats are
known, every time value is in quarter-note beats; `TempoMap` is the only
sanctioned conversion. This is what makes quantization, chord alignment,
lyric placement and arrangement mutually consistent for free. Two corollaries:
beats are always *quarter-note* beats whatever the meter (a 6/8 bar holds
three, not six), and pitch is carried twice on purpose — a MIDI number cannot
be engraved (61 is both C# and Db), so `PitchSpelling` rides beside the
sounding pitch all the way to the notation layer.

## What to trust

Beat tracking is the least reliable stage and everything depends on it, which
is why the highest-value user actions are `--tempo` and `--first-downbeat`.
Accuracy is measured on real recordings (`samples/`, plus local-only
benchmarks) by the harnesses in `tools/`, whose current readings live in
`tools/baselines/` — numbers belong there, not in prose, because they move
whenever the estimators do.
