# The Android app

A field-recording instrument for the corpus, not a product: record a take,
run the same MW harmony analysis on the device, read the chart as text, and
get the take off the phone so it can join `samples/` or `uncommitted/` on the
desktop. [android/README.md](../android/README.md) is the app's own
documentation; this page is where it fits in the system.

## Why it exists

MW is judged on real recordings, and the app closes the loop from *music
happening in a room* to *a benchmark with ground truth*: the player records
the take, types what was actually played while it is fresh, and the note
travels with the audio. That written account — confirmed by ear on import —
is what turns a recording into ground truth rather than description.

## How it shares code

The app links the shared Maven modules directly (`mw-core`, `mw-audio`,
`mw-dsp`, `mw-transcribe`, `mw-arrange`, `mw-notation`) — which is why the
reactor compiles to Java 21 bytecode (Android's D8 reads no newer) and why
the dependency rule matters: `mw-cli` is the only module that depends on
`mw-ml`, so ONNX Runtime's desktop natives never enter the app's compile
closure. A test (`DesktopOnlyCodeStaysOffThePhoneTest`) holds the seam:
the phone path must not reach `javax.sound`, desktop natives or LilyPond.

On the device the pipeline runs decode → beats → chords → chart text — the
same estimators as the desktop, no PDF and no LilyPond.

## Getting takes off the phone

Nothing is uploaded and the app holds no credential. Sharing goes through
the system share sheet as one zip, `<take>.mwz.zip`: the WAV, the player's
note, an info file, and — when the take was analysed on the phone — its
chart and analysis cache. A YouTube link shared *into* the app can be
fetched as a take too; its bundle carries `source: youtube`, which marks it
commercial audio whatever it sounds like — such takes never reach the
committed corpus.

## Importing on the desktop

The `take-importer` agent (`.claude/agents/take-importer.md`) sweeps a cloud
drive for bundles, verifies each, runs the full desktop pipeline on the WAV,
and reports the desktop chart against the phone's and against the player's
own note. From there,
[phone-to-corpus.md](phone-to-corpus.md) is the human step: what to confirm
by ear and write down before a take is promoted into `samples/`.
