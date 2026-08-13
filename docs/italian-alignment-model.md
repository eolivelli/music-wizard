# The Italian alignment model

Forced alignment needs a CTC model for the language being sung. English has a
trusted ONNX export; Italian has none, and the uploads that exist carry no
licence, no declared base model and no conversion script — not provenance this
project runs (the rule `CONTRIBUTING.md` states, applied before to a Qwen
checkpoint in #314). So the Italian model is produced locally, from official
Apache-2.0 weights, and pointed at by config.

## What it is

[jonatasgrosman/wav2vec2-large-xlsr-53-italian][model], Apache-2.0, fine-tuned
from `facebook/wav2vec2-large-xlsr-53` on Common Voice Italian. Its alphabet is
lower case and spells the accented vowels — which is why `Wav2Vec2Models`
carries the vocabulary per checkpoint rather than one table: folding `è` to `e`
for this model would delete the nucleus of the syllable being placed.

## Producing it

```sh
python3 -m venv .venv
.venv/bin/pip install "optimum[exporters]" optimum-onnx onnx onnxruntime
.venv/bin/python -m optimum.exporters.onnx \
    --model jonatasgrosman/wav2vec2-large-xlsr-53-italian \
    --task automatic-speech-recognition \
    ~/.cache/music-wizard/alignment/it
```

That writes `model.onnx` (about 1.2 GB, fp32) beside the tokenizer files the
export includes and MW does not read — the vocabulary lives in
`Wav2Vec2Models` so a directory holding some other model produces nonsense the
confidences show, rather than silently re-indexing the alphabet.

## Using it

```yaml
# ~/.config/music-wizard/config.yaml
ml:
  alignmentModelDirectory: /home/you/.cache/music-wizard/alignment
```

The provider looks for `<that directory>/<language>/model.onnx`, so a third
language is a subdirectory rather than another setting. `mw doctor` prints the
languages this machine can align under the alignment line; a language with no
model is reported by `analyze` and its lyrics keep their parsed times, exactly
as before.

The key is read from the **global** config file only — providers configure
themselves from the environment (#383).

[model]: https://huggingface.co/jonatasgrosman/wav2vec2-large-xlsr-53-italian
