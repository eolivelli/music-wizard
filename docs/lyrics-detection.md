# How MW hears and places lyrics

Two routes to words under the chords: supply them, or let MW transcribe the
singing. Both end at the same aligner, and every failure on the way degrades
to something rather than nothing — lyrics are a decoration on an analysis
that has already succeeded, and no lyric stage may take it down.

## The supplied route: LRC

`analyze --lyrics song.lrc` parses an [LRC] file: line timestamps from the
tags, word timings where the file carries them and estimated within each
line where it does not. Supplied lyrics survive later `analyze` runs, so
correcting the tempo does not throw them away.

## The transcribed route

`analyze --lyrics-language it` (no file) asks MW to hear the words:

1. **Separation** — the vocal stem is pulled out of the mix with Spleeter
   (ONNX; MIT for code *and* weights). This is what separation exists for;
   chords never read stems.
2. **Sung stretches** (`VocalSegments`) — the recognizer is fed only where
   the singing is. A song is mostly not singing, and a recognizer fed an
   intro or a solo is free to hallucinate words into it. The threshold is
   relative to the stem's own loud frames; breaths stay inside a segment;
   an over-long segment is split at its quietest interior frame.
3. **Recognition** — Qwen3-ASR through [sherpa-onnx], built from a source
   submodule with TTS off (the default build links a GPL espeak fork;
   `tools/check-sherpa-native.sh` asserts the built library is clean, in CI
   too). The submodule currently points at [Enrico's fork][sherpa-fork],
   which carries the Qwen3-ASR feature-alignment fix
   ([k2-fsa/sherpa-onnx#3873][sherpa-pr]) ahead of upstream review, and
   points back at upstream once it lands. Another Qwen3-ASR export can be
   substituted via `ml.asrModelDirectory` in the global config. The language
   is *stated, never detected*: a recognizer told to guess produces fluent
   wrong words when it guesses wrong, which nothing downstream can notice.
4. The recognizer knows **words but not times** — each line's words arrive
   spread across its sung stretch — which is why the aligner exists.

## Alignment

A wav2vec2 forced aligner measures word onsets where it speaks the language
(English has a published export; Italian aligns when you produce one —
[italian-alignment-model.md](italian-alignment-model.md)). Each line is
aligned inside a window around its own timestamps, so a whole song is many
small alignments; a line ends no later than the next line's start, so an
aligned line cannot take its neighbour's chords. Lines that share a moment —
a second voice, a two-line display — keep their shared span untouched.
Failures degrade per line to the parsed times; a model that cannot be
fetched degrades the whole run, with the reason.

Alignment is done over **syllables**, not words, where the language has
patterns (#414): the engraved sheet prints a syllable per note, and sung
Italian holds the stressed syllable, so dividing a word's span evenly puts a
downbeat inside the wrong syllable.

## Syllables and languages

`--lyrics-language` also splits words into the syllables they are sung on —
*a-mo-re*, not *amore* — with hyphenation patterns for Italian and English.
Any other language deliberately stays whole: splitting on the wrong
language's rules is worse than not splitting, which is also why lyric ground
truth in the corpus must name its language.

## Two things kept deliberately separate

- **Confidences are never merged across scales**: the words carry whoever
  produced them's rating (parser or recognizer), and the aligner's rating of
  its path through the audio is reported beside it, not folded in.
- **Lyrics live outside the transcription cache**: correcting a typo in a
  lyric file must not recompute minutes of DSP.

Honest expectations: sung speech recognition is modest — expect to correct
the transcription, not to trust it. The chord chart with lyrics is MW's
strongest output all the same.

[LRC]: https://en.wikipedia.org/wiki/LRC_(file_format)
[sherpa-onnx]: https://github.com/k2-fsa/sherpa-onnx
[sherpa-fork]: https://github.com/eolivelli/sherpa-onnx/tree/qwen3-asr-stft-center-alignment
[sherpa-pr]: https://github.com/k2-fsa/sherpa-onnx/pull/3873
