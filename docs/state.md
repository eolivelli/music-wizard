# Where the pipeline stands

The narrative record CLAUDE.md points at: how each analysis stage got to where
it is, what each fix was worth on real recordings, and the defects each one
left standing. Read this before touching `ChordEstimator`, the beat grid or the
melody chain. A figure quoted here is what a change was worth when it landed;
the current readings live in `tools/baselines/`.

## The chord and beat line

The first tier-2 run — an actual 3:43 track — produced seven chord spans, five
of them `N.C.`, one covering 169 consecutive seconds (#185). Measured over
every frame, the flat no-chord template scores 0.859 against the best possible
triad's 0.713; on the synthetic fixture the same measurement gives +0.356 the
other way. **The sign flips between synthetic and real.**

That was read as meaning no constant in `ChordEstimator` could fix it, and #3
showed otherwise. On the G blues that was the reference recording then — a
different recording, with exactly known changes — the estimator's three changes
together take *plain* chroma from 0.0% to 58.9% of bars correct, before any
front end. The flat no-chord template scores highest exactly when a frame looks
least like music, so it wins on a real mix whatever the chroma is.

No single constant does it, though, and the first draft of this paragraph
claimed one did — three changes reach 58.9% and the largest of them alone
reaches 17.5%. `tools/ChordSweep.java` is what re-derives the decomposition;
do not restate it anywhere, because this figure has already gone stale in four
separate files. The lesson is not "a constant can fix it" but "the emission
model was wrong in a way the front end could not compensate for".

So: work that makes real audio work outranks work that polishes what already
works on synthetic audio. NNLS chroma (#3) was the top item and has landed —
every benchmark with known ground truth went from 0% of bars correct to between
14% and 89%, and from one `N.C.` span per recording to none. The beat drift that
exposed is fixed too (#196): the tracker's spacing penalty was a forty-eighth of
the published one, so it left the grid for any loud offbeat, and the benchmarks
now score between 15% and 99% of bars correct on the tracker's own downbeats.

The chart's bar *rate* is fixed too (#200): it was the median tracked interval,
which is not a rate and is quantised to the analysis hop, and it is now the mean
of the intervals the tracker held steadily. Each of the five benchmarks that
existed then improved or held **on the root column**, the reference recording by
fifteen points and one other by twelve. Not on `root+quality`, which fell a point
or two on two of them — at the time that column was dominated by #208, whose
small movements did not mean much either way, which is exactly why the two are
quoted separately.

The chart's bar axis no longer hangs on one downbeat and one constant rate.
Where the grid's downbeats are every one of them a plausible bar, they *are* the
bar lines (#187); where they are not, the chart is one bar length hung on the
offset the downbeats agree on, keeping the first downbeat where they agree on no
offset within the beat (#233). So what the chart's bar lines are wrong by is now
what the grid is wrong by, and nothing else — on the benchmarks whose tracked
downbeats sit further from the music than one constant bar length did, the chart
sits further too, which is #424 rather than the chart's.
`tools/baselines/score-chart.txt` carries the readings.

What is now top of the bar axis is which grids to believe: the veto that decides
it catches a tracker that lost the beat and says nothing about one that is
merely jittery (#429).

Then: the residual gate of #543 admitted the minor sixth and the
half-diminished (#547), and the major seventh (#588) — which had to go into the
*decoder* rather than beside them, because on the recordings whose truth holds
one those bars were not decoded onto their own root at all, and a quality that
cannot move a root cannot reach a bar it never got. It is the one decoded
template admitted on the fit's residual instead of the chroma, and the price is
the same relative-pair confusion one layer up: `Abmaj7` is `Fm7` with a G for
its F, so a minor-seventh vamp gives up roots to it and the bass prior, measured,
does not carry the difference. The plain sixth is still out, and for the other
reason: it really is sounding, so it needs root evidence rather than presence
evidence (#287, #274).

**A third is settled across every run on a root, not run by run** (#558). That
gate leaves the third weakest exactly where a run holds no third at all: the
major one is discounted for not being in the fit and the minor one need only
clear the noise, so the minor candidate takes the run on whatever the mix left
on its pitch class — and once the gate fires it cannot lose. Over one run that
is a coin toss; over a root it is a count. The recordings a musician confirms
hold no minor chord lost most of their false minors to it and no scored
benchmark moved a bar; `tools/baselines/score-samples.txt` carries both rows.
Its cost is a chord where the seventh's is a colour — a minor chord stated
once on a root otherwise played major goes with the false ones (#583) — and
what it cannot reach is a minor label on a root the song does not have (#448).

Dominant sevenths are found now (#208) — they were found on two benchmarks and
called plain triads on three others whose roots were read nearly perfectly. The
root is still decided from both registers and the quality now from the treble,
once per chord rather than per beat, which is two changes rather than one
because different benchmarks needed different halves. A large net gain that
closed nothing and cost a couple of points on the two benchmarks whose sevenths
were already being found: `ChordEstimator` carries the mechanism and
`tools/baselines/score-samples.txt` the current reading.

Minor sevenths are found too (#272), and it took two things. **The decoder's
vocabulary and the quality decision's are not the same one**: a quality the
decoder may choose competes across roots, and `Am7` is a `C` triad with an A in
it, so in the decoder it moves roots wherever the sixth degree sounds. And
`C7` and `Cm7` differ in nothing but the third, so a minor-third candidate is
scored on its notes' mass less whatever major third the root's own fifth
partial cannot account for — subtract all of the major third instead and a
blues third or a strongly voiced root turns minor chords major, which is how a
B minor blues came to be named B major.

**The corpus has a plain-triad benchmark now**, `pop-c-g-am-f-120.mp3`, every
root right on the uploader's stated grid. It is what decided the size of that
correction, and before it nothing in the scored set could tell a quality that is
found from one that is reported because nothing said not to (#273).

**The decoder reads the bass register too, as a prior over roots** (#448). Both
registers added is still a fold to pitch classes, and a fold cannot say which of
a chord's own notes is its root — which is the whole difference between a chord
and its relative minor, since a sixth added to the one gives exactly the other's
four notes. So a boogie shuffle's root-and-sixth comping reads as the relative
minor, and goes on reading that way however wide the span it is decided over;
nothing about the window fixes it. The bass says it instead. It has to be
read over about a bar rather than beat by beat, because a walking bass passes
through the third and the sixth and asserting a root at every passing note
splits a chord's run in two — and a run split in two has its quality decided
twice from half the evidence each time, which is a different defect wearing the
same clothes. `ChordEstimator` carries both constants, `tools/ChordSweep.java`
re-derives the sweeps;
`tools/baselines/` carries what it was worth, which was most of the corpus and
not only the shuffle.

## State

**The pipeline runs end to end.** A real MP3 goes in; beats, tempo and chords
come out; a chord chart is engraved to PDF via LilyPond. Verified on a
synthesised I-V-vi-IV signal and on an actual MP3 encoded from it.

Done: M0 (reactor, domain model, workspace with content-addressed caching,
layered config, CLI) and the harmony half of M1b (decode, onsets, Ellis beat
tracking, tuning-corrected chroma, chord recognition, key naming, chord chart,
LilyPond). Four review rounds on `mw-core`.

Key detection (#275) reads the estimated chords, not chroma, and reports two
confidences because it makes two decisions of very different reliability: the
key signature, and which of a relative pair is home. The second is what fails —
a loop that neither begins nor ends on its tonic gives it nothing to work with,
and it answers at the coin-flip floor rather than pretending. `KeyEstimator`
carries the rules and `tools/baselines/score-samples.txt` carries the scores.

The lyrics chain (#9) runs end to end: vocal separation (#312), forced
alignment of supplied LRC lyrics (#313), and transcription from the audio
itself (#314, Qwen3-ASR through a sherpa-onnx source submodule, built by
`tools/build-sherpa-native.sh` and present only when that has run). The
transcriber knows words but not their times — sherpa's Qwen3 emits none — so
words are spread across their sung stretch and the aligner measures onsets
where it speaks the language, which today is English only.

**What the melody stage is worth is known now, on real singing.** `vocadito`
— 40 clips of solo voice annotated note by note by two trained musicians, CC BY
4.0, fetched into `uncommitted/` — is scored by `tools/score-melody.py --source
vocadito`, and it carries its own ceiling: each row prints one annotator scored
against the other by the same rule. That ceiling is nowhere near 100%, because
where a sung note begins is genuinely ambiguous — the two annotators do not
even agree how many notes a clip holds. MW sits close under it. Read the
baseline rather than a figure quoted here.

Two things that measurement overturned, both of which had been believed on the
strength of how a page looked:

- **Real sung notes are short**, most of them under a quarter of a second. A
  melody stage returning notes that length is not fragmenting, and a rule that
  absorbed short notes would destroy real music.
- **On a mix the melody stage's accuracy is a statement about separation, not
  about the melody stage.** `tools/measure-separation-cost.py` scores the same
  annotated voices three ways — clean, through the separator with no band, and
  through it with a band mixed in. The middle row costs almost nothing, so the
  separator does not spoil a voice by itself; the whole loss appears once a
  band is there. **What that gap is made of is known now (#503): voice the
  mask removed dominates it, and band the mask left costs the tracker
  something too**, and `tools/apportion-separation-loss.py` is what says so —
  it takes the stem apart against the two sources the measurement mixed, and
  scores each part.
  The mask is softer for it, and the voice is still lost where the band is
  loud, which is #575. How far the voice sits above the band is the
  variable the tool now states rather than inherits — but the bed is added to a
  voice at its own recorded level, so a clip with no headroom left rails before
  the band is anywhere near loud, and what is lost is the side where the band
  is loud, which is the side the loss lives on (#518). Absolute level is a
  second axis and is not controlled either, since
  the separator is not level-invariant even at a fixed ratio (#515). None of it
  is baselined.

**Melody is read from the separated vocal where a separator can be had
(#559), and from the raw signal otherwise (#494).** pYIN in `mw-dsp`,
segmented into notes, engraved as a lead sheet — melody staff, chord symbols,
lyrics. The stage is off unless `analyze --melody` asks for it. The tracker
itself is monophonic: on whatever signal it is given it confidently returns
the loudest periodic line, so without a separator a band reads as its bass —
and a *played* melody largely does not survive a vocal separator, which is
what `--skip-separation` is for (#560 is choosing by evidence). The
melody baselines under `tools/baselines/` carry both signals; only one of
their rows runs in CI — the rest need this machine's vocadito audio or its
separation model — so read premerge's output rather than CI for melody
movement. *When* a sung note starts is
genuinely ambiguous and #497 records the limit.

Still missing: melody accuracy on a real mix, which is separation's quality
problem rather than the tracker's (#575), piano
(#10), advisor (#11). The symbolic track (#1) is four-fifths landed and parked.
NNLS chroma (#3) and the Ellis-penalty correction (#196) have landed;
`tools/score-samples.py` and `tools/score-chart.py` are the standing
measurement of what they are worth, with baselines under `tools/baselines/`.

`mw-core` passed round 4 once its three blockers landed, but see the open
`design-gap` issues before treating it as frozen — especially #4 (no beat unit,
so compound meters mis-bar) and #5 (notation-facing gaps).
