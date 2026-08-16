# The music-teacher agent and the synthetic corpus

`synthetic_samples/` is the corpus's synthetic sibling: full-band packages —
spec, MIDI, rendered MP3 — generated to teach MW the common harmonic patterns
of mainstream genres. The
[music-teacher agent](../.claude/agents/music-teacher.md) writes them; the
`mw-teacher` module compiles them; `tools/score-synthetic.py` scores MW
against them.

## Ground truth by construction

Each package is three files sharing a base name:

- `<name>.spec.txt` — tempo, meter, key, seed, style and the bar-by-bar
  chord grid. Everything musical is stated here, so the truth is exact by
  construction rather than confirmed by ear.
- `<name>.mid` — compiled deterministically from the spec (`mw-teacher`'s
  `SpecParser`; same spec + same seed = same bytes). Drums, bass, comping,
  and usually a simple melody.
- `<name>.mp3` — the MIDI rendered with FluidSynth and a cached, checksummed
  GM soundbank that is never committed (`tools/music-teacher/`), loudness-
  normalized.

Some packages exist for the melody stage instead: a `melody-level` on a
1–4 difficulty ramp with the accompaniment thinned to a `pad` or removed —
a monophonic pitch tracker pointed at a full mix measures the separation
that did not happen in front of it, so these take the band away. A package
with no accompaniment carries no evidence for its own chord grid and is
skipped by the chord harness.

## What the agent does

The agent is a harmony-and-genre expert with a deliberate ceiling: easy pop,
hip-hop and R&B loops, rock, rock and roll, folk-pop — never advanced jazz,
classical or metal, because material MW cannot in principle name teaches
nothing yet (#287 tracks the vocabulary). It writes **archetypes** — the
progressions and cadences that define a genre — never a specific song's
chart or melody. For each package it runs MW on the rendered MP3 and files
one `synthetic-sample` issue recording what MW read against the truth; those
issues are the corpus's to-do list.

## Where it sits in the testing tiers

Between tiers 1 and 2: more realistic than the JDK-synth fixtures (real
soundfont, full band, loudness-normalized), still systematically easier than
a real recording — so these figures are regression signal, never quoted as
product accuracy. Every package is committed, so CI can run this harness in
full (unlike the sample harness, whose local-only benchmarks never leave the
machine); both premerge and CI diff `tools/score-synthetic.py` against
`tools/baselines/score-synthetic.txt`, and any movement fails until the
baseline is regenerated in the same PR.
