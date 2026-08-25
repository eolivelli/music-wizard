# From a phone take to a corpus entry

## 0. Fetching a commercial song directly (no phone involved)

To test MW against a commercial recording from a YouTube link, keep only the
video id from the URL — strip `list=`, `start_radio=`, `index=` and every other
playlist parameter, or yt-dlp may fetch a whole radio playlist — then:

```sh
yt-dlp -x --audio-format mp3 --audio-quality 0 \
  -o uncommitted/<slug>.mp3 'https://www.youtube.com/watch?v=<id>'
```

Record it in `uncommitted/list.txt` in the style of the entries there (title,
artist, duration, the exact fetch command, any remembered chords labelled as
remembered by ear) before running the pipeline on it. These recordings have no
ground truth: the chart is looked at against the recollection, never scored —
the `list.txt` header says why. Never commit or `git add` anything under
`uncommitted/` but `list.txt` itself.

The phone app (`android/README.md`) records; this note is what to do with a take
once it is off the phone. The loop is the point: the same recording read on the
phone and on the desktop, with what was played written down beside it.

## 1. Share the take out

Long-press the take in the library and choose **Share bundle** — that is the
corpus-export path. It is one zip holding the WAV, the chart the phone made of
it, the cached `score.json` where one could be written, the note typed on the
result screen when there is one ("what was played", in the player's words), and
an info file with the duration, the recorded date, the tempo and meter, and the
app version. The file is `<take>.mwz.zip`, so a Drive search for `mwz` answers
with exactly the bundles; sharing it to a cloud
drive is how someone who is not holding the phone fetches it, and the WAV
inside is what step 4 runs on. **Share WAV** sends the audio alone.

A take is a mono 16-bit PCM WAV in app-private storage, named
`yyyy-MM-dd_HH-mm-ss.wav` until the library's **Rename** says otherwise, and
both shares name their files after whatever that name is. Rename it first, to
something that says what it is. The rate is 44100 when the phone recorded it,
and whatever the decoder produced when the take was imported rather than played
— usually 44100 there too, 48000 for an Opus-only video. Nothing resamples at
import and nothing downstream needs it to.

## 2. Where it goes

**First, read `<take>.info.txt` from the bundle and find its `source:` line.** A
take whose line says `source: youtube` was fetched from a link, not played into
a microphone: it is a commercial recording, it goes to `uncommitted/`
unconditionally, and no amount of it sounding like someone's kitchen changes
that. `source: microphone` is a take of your own playing. A bundle with no
`source:` line at all was made by an app older than #415 — those predate the
import feature, so they are field recordings, but check the app version in the
same file rather than assuming.

The app marks this at the source: an imported take carries a `.source.txt`
beside it, the marking survives a rename, and its note is seeded with the link
it came from. That is deliberate — a take that arrives with the marking lost is
indistinguishable from one you played, and the two do not go to the same place.

A take of your own playing belongs in `samples/`, the corpus MW is measured on:
committed where the licensing allows it, and otherwise gitignored by name, as
several files there already are. `uncommitted/` is for commercial recordings,
and its `list.txt` header says why they are looked at rather than scored.

Being scored is a further step, and a later one: `tools/score-samples.py` looks
for every benchmark under `samples/` and reads its changes from the `BENCHMARKS`
table in that same script (`score-chart.py` imports it). A file's `list.txt`
entry does not put it there: `samples/list.txt` says changes are confirmed by
ear before a file is promoted. A sung recording is registered the same way and
in one more place — its recording and its `.lrc` go in the `LYRICS` table of
`tools/score-lyrics.py`.

## 3. Write the `list.txt` entry

Follow the entries already in whichever of the two files it is: file name, then
a paragraph. A phone take has no fetch command, so its provenance goes in that
place instead — who played, on what, when — and then what was played, marked as
known or as remembered. An imported take does have a source, and it is the one
kind of phone take that gets a URL there: take it from the `.source.txt` or
the note, and write it where a fetch command would go in
`uncommitted/list.txt`. A bundled take whose player typed a note arrives with
its `.notes.txt`, the account written at the time; start from that rather than
from memory.

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

## 5. Lyrics are not gated; committing them is

A recording and its words are two copyrights, and a licence on the audio does
not reach the composition. That still decides where a lyric file may *live*, but
it no longer decides whether it may be used.

**Any lyric file may be fetched and used**, whatever licence it states or does
not state, as analysis input and as ground truth, and scored by
`tools/score-lyrics.py`. Enrico's decision and his responsibility: MW measures
how well a program reads a recording, and does not perform the music, publish
the words, or make anything from them. LRCLIB asserts no licence over its lyric
database — the CC0 and MIT that search results attach to it cover the file
format and the server — and that is no longer a bar to using it.

**The full text stays in `uncommitted/`**, which is gitignored. Using a work to
measure a program is not publishing it; putting it in a public repository is.
So a `.lrc` is never committed and never listed in `NOTICE`, and a committed
test or baseline quotes a line or two only where it needs one to fail for the
right reason.

Which means a sung recording can be promoted into `samples/` on its **audio**
licence alone, with its words held locally beside it. What used to block a
promotion — words whose rights were unclear — now only blocks committing those
words. CLAUDE.md carries the rule.

Write the **language** down beside every sung entry, and do not promote dialect
material. `Hyphenator` has patterns for `it` and `en` and nothing else, and its
own javadoc says splitting on the wrong language's rules is worse than not
splitting at all; the ASR chosen under #314 covers the same two languages. A
Neapolitan entry would be scored against wrong syllable counts, so the number
would move for a reason that has nothing to do with transcription. This is what
keeps *Funiculì Funiculà* out: public domain on both sides, acoustically well
suited to what MW does, and not Italian.
