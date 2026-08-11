# From a phone take to a corpus entry

The phone app (`android/README.md`) records; this note is what to do with a take
once it is off the phone. The loop is the point: the same recording read on the
phone and on the desktop, with what was played written down beside it.

## 1. Share the WAV out

Long-press the take in the library and choose **Share WAV** — that is the
corpus-export path. A take is a PCM 16-bit mono 44100 Hz WAV in app-private
storage, named `yyyy-MM-dd_HH-mm-ss.wav` until the library's **Rename** says
otherwise, and the app offers it under whatever that name is. Rename it first,
to something that says what it is.

## 2. Where it goes

A take of your own playing belongs in `samples/`, the corpus MW is measured on:
committed where the licensing allows it, and otherwise gitignored by name, as
several files there already are. `uncommitted/` is for commercial recordings,
and its `list.txt` header says why they are looked at rather than scored.

Being scored is a further step, and a later one: `tools/score-samples.py` looks
for every benchmark under `samples/` and reads its changes from the `BENCHMARKS`
table in that same script (`score-chart.py` imports it). A file's `list.txt`
entry does not put it there: `samples/list.txt` says changes are confirmed by
ear before a file is promoted.

## 3. Write the `list.txt` entry

Follow the entries already in whichever of the two files it is: file name, then
a paragraph. A phone take has no fetch command, so its provenance goes in that
place instead — who played, on what, when — and then what was played, marked as
known or as remembered.

## 4. Run the desktop CLI on the same file

```sh
./mw init <dir>/<slug>.wav
./mw analyze <dir>/<slug>.mwz
./mw render <dir>/<slug>.mwz
```

`render` prints the chart and writes `<slug>.mwz/out/chords.txt`. That file and
what the result screen's **Share chart** sends are both `ChordChart.toText`
unaltered, so the two compare line for line — as long as `init` is given no
`--title`/`--artist`, which add a header the phone's chart has nothing to match.

Append what came out to the `list.txt` entry, as the existing entries do. That
record is what lets a re-run after the next change say whether MW got better on
this recording.

## 5. Lyrics carry a second licence

A recording and its words are two copyrights, and a licence on the audio does
not reach the composition. They come apart constantly: a CC BY recording of a
cover carries words the uploader could not license, and a public-domain
recording usually wraps words still in term. A committed `.lrc` is a derivative
of the lyrics, so this gates the ground truth as hard as it gates the audio.

Lyric ground truth in `samples/` must be one of three things: the artist's own
under a licence that reaches the words, public domain on both sides, or
transcribed by ear from a recording we may redistribute. A lyric source that
states no licence is local-test-only and is never promoted (#347). LRCLIB is the
one to know about — it asserts no licence over its lyric database, and the CC0
and MIT that search results attach to it cover the file-format spec and the
server. Absence of a grant is not a grant.

Write the **language** down beside every sung entry, and do not promote dialect
material. `Hyphenator` has patterns for `it` and `en` and nothing else, and its
own javadoc says splitting on the wrong language's rules is worse than not
splitting at all; the ASR chosen under #314 covers the same two languages. A
Neapolitan entry would be scored against wrong syllable counts, so the number
would move for a reason that has nothing to do with transcription. This is what
keeps *Funiculì Funiculà* out: public domain on both sides, the best-shaped
audio the #307 survey found, and not Italian.
