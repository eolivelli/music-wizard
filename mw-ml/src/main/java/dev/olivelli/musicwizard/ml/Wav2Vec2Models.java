/*
 * Copyright 2026 Music Wizard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.olivelli.musicwizard.ml;

import java.net.URI;

/**
 * The wav2vec2 CTC checkpoint the aligner runs, and its fixed properties.
 *
 * <p>English first: {@code facebook/wav2vec2-base-960h}, Apache-2.0 by the
 * base model's own licence tag. The ONNX export is Xenova's, which declares
 * its base model on the card but carries no licence tag of its own — the same
 * shape as #312's sherpa-onnx case, with weaker paperwork: no conversion
 * script in a repository, only optimum's documented export path. Recorded
 * here so nobody mistakes the chain for stronger than it is. Italian needs a
 * trusted export that does not exist yet; that is a follow-up issue, not a
 * different design — the vocabulary below simply grows a second table.
 *
 * <p>The vocabulary is the model's, fixed by the digest beside it: 32 entries,
 * characters plus a word separator, no BPE and no tokenizer dependency —
 * which is what makes forced alignment tractable in Java at all (#313).
 */
final class Wav2Vec2Models {

    static final ModelRef ENGLISH = new ModelRef(
            "wav2vec2-base-960h",
            "model.onnx",
            URI.create("https://huggingface.co/Xenova/wav2vec2-base-960h"
                    + "/resolve/main/onnx/model.onnx"),
            "e46614273f03ff4b87923a965e417fa72004825522cb007c9c25633b8475490d",
            377_887_594,
            "Apache-2.0 (facebook/wav2vec2-base-960h; ONNX export by Xenova,"
                    + " base_model declared, no licence tag of its own)");

    /** Vocabulary index of the CTC blank ({@code <pad>}). */
    static final int BLANK = 0;

    /** Vocabulary index of the word separator ({@code |}). */
    static final int SEPARATOR = 4;

    /**
     * The model's emission vocabulary, index-ordered. Entries 1–3 are sequence
     * tokens the aligner never emits; they hold their slots so the indices
     * match the logits.
     */
    static final String[] VOCABULARY = {
        "<pad>", "<s>", "</s>", "<unk>", "|",
        "E", "T", "A", "O", "N", "I", "H", "S", "R", "D", "L", "U",
        "M", "W", "C", "F", "G", "Y", "P", "B", "V", "K", "'", "X", "J", "Q", "Z"
    };

    /** Samples per emitted frame: 20 ms at the model's 16 kHz. */
    static final int HOP_SAMPLES = 320;

    /** The rate the checkpoint was trained at; the provider resamples to it. */
    static final int MODEL_RATE = 16_000;

    private Wav2Vec2Models() {
    }
}
