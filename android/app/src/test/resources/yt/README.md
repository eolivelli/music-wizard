# Player-response fixtures

Real replies from YouTube's `youtubei/v1/player` endpoint, captured with the
`ANDROID_VR` client that `InnerTube` uses, then **trimmed and scrubbed**. Both
words matter.

**Trimmed** to the fields the parser reads, plus one video format in
`player-ok.json` so the audio-only filter is tested against something to reject.
A raw reply is about 75 KB; a fixture nobody can read is a fixture nobody
checks.

**Scrubbed**, because a raw reply is not safe to commit. Every media URL carries
the capturing machine's public IP in `ip=`, a per-session `ei=` and `bui=`, the
serving host's own name, and the `sig`/`lsig`/`spc` signatures. The host is
rewritten to `media.example.invalid`, `ip=` to `0.0.0.0`, and each of the rest
to `SCRUBBED`. `responseContext.visitorData` is replaced with an obvious fake.

So the URLs here are the right *shape* and resolve nowhere, which is what a test
wants: `StreamDownloadTest` drives a fake transport and never opens a socket.

## What each one is

| File | Captured from |
|---|---|
| `player-ok.json` | An ordinary music video. Four audio formats, all with URLs, plus one video format. |
| `player-login-required.json` | A call carrying no session. Note it still carries a `visitorData` — that is the whole bootstrap. |
| `player-sabr-only.json` | `player-ok.json` with every audio `url` removed, which is what an enforced client is served. Note `serverAbrStreamingUrl` is present in the OK reply too, so its presence is not the signal; the missing `url` is. |
| `player-made-for-kids.json` | A video marked as made for children. `UNPLAYABLE`, and YouTube's own wording is the unhelpfully generic "This video is not available". |
| `player-live.json` | A 24/7 live stream. |
| `player-error.json` | An id that does not exist. |

## Re-capturing

These will go stale, and the day they do is the day the app stops working —
which is what `InnerTubeLiveTest` exists to notice, since every test here will
keep passing regardless. If you re-capture, scrub to at least the standard
above and check the result before committing: `grep` the directory for
`googlevideo`, for an `ip=` that is not `0.0.0.0`, and for a `sig=` that is not
`SCRUBBED`.
