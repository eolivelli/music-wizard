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
 * trusted export. English has one; Italian does not, so its checkpoint
 * carries no {@link ModelRef} and runs only from a directory the user
 * produced themselves (#388, the #396 pattern) — the published Italian
 * uploads carry no licence, no declared base model and no conversion
 * script, which is not provenance this project accepts.
 *
 * <p>A vocabulary is the model's own, fixed by the digest or the export
 * beside it: characters plus a word separator, no BPE and no tokenizer
 * dependency — which is what makes forced alignment tractable in Java at all
 * (#313). <b>The vocabulary is also the authority on normalisation.</b> Its
 * own case decides whether text is folded up or down, and whether it spells
 * accented vowels decides whether they are folded away: the English
 * checkpoint has neither {@code è} nor {@code e} with a mark, so the accent
 * goes; the Italian one spells them, and folding there would delete the
 * nucleus of the syllable the aligner is trying to place.
 */
final class Wav2Vec2Models {

    /**
     * One model and the alphabet it emits. {@code published} is null for a
     * checkpoint no trusted export exists for: it runs only when the user
     * points {@code ml.alignmentModelDirectory} at one they produced.
     */
    record Checkpoint(String name, ModelRef published, String[] vocabulary) {

        int blank() {
            return indexOf("<pad>");
        }

        int separator() {
            return indexOf("|");
        }

        /** True when the alphabet spells lower-case letters. */
        boolean isLowerCase() {
            return indexOf("a") >= 0;
        }

        /** True when the alphabet spells at least one accented vowel. */
        boolean spellsAccents() {
            for (String entry : vocabulary) {
                if (entry.length() == 1 && entry.charAt(0) > 127) {
                    return true;
                }
            }
            return false;
        }

        int indexOf(String entry) {
            for (int i = 0; i < vocabulary.length; i++) {
                if (vocabulary[i].equals(entry)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * facebook/wav2vec2-base-960h. Entries 1-3 are sequence tokens the aligner
     * never emits; they hold their slots so the indices match the logits.
     */
    private static final String[] ENGLISH_VOCABULARY = {
        "<pad>", "<s>", "</s>", "<unk>", "|",
        "E", "T", "A", "O", "N", "I", "H", "S", "R", "D", "L", "U",
        "M", "W", "C", "F", "G", "Y", "P", "B", "V", "K", "'", "X", "J", "Q", "Z"
    };

    /**
     * jonatasgrosman/wav2vec2-large-xlsr-53-italian, in the order its
     * vocab.json gives — lower case, and the accented vowels Italian needs.
     * Transcribed here rather than read from the export, so a directory
     * holding a different model produces nonsense the aligner's confidences
     * show rather than silently re-indexing the alphabet.
     */
    private static final String[] ITALIAN_VOCABULARY = {
        "<pad>", "<s>", "</s>", "<unk>", "|", "'", "-",
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
        "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
        "à", "á", "è", "é", "ì", "í", "ò", "ó", "ù", "ú", "š"
    };

    static final Checkpoint ENGLISH = new Checkpoint("en", new ModelRef(
            "wav2vec2-base-960h",
            "model.onnx",
            URI.create("https://huggingface.co/Xenova/wav2vec2-base-960h"
                    + "/resolve/main/onnx/model.onnx"),
            "e46614273f03ff4b87923a965e417fa72004825522cb007c9c25633b8475490d",
            377_887_594,
            "Apache-2.0 (facebook/wav2vec2-base-960h; ONNX export by Xenova,"
                    + " base_model declared, no licence tag of its own)"),
            ENGLISH_VOCABULARY);

    /**
     * Italian, with no published export to fetch: the checkpoint is
     * Apache-2.0 (jonatasgrosman/wav2vec2-large-xlsr-53-italian, fine-tuned
     * from facebook/wav2vec2-large-xlsr-53 on Common Voice), and
     * docs/italian-alignment-model.md is the recipe that turns it into the
     * directory this runs from.
     */
    static final Checkpoint ITALIAN = new Checkpoint("it", null, ITALIAN_VOCABULARY);

    /** The checkpoints this provider knows, by BCP 47 language subtag. */
    static final java.util.Map<String, Checkpoint> BY_LANGUAGE =
            java.util.Map.of("en", ENGLISH, "it", ITALIAN);

    /** Samples per emitted frame: 20 ms at the model's 16 kHz. */
    static final int HOP_SAMPLES = 320;

    /** The rate the checkpoints were trained at; the provider resamples to it. */
    static final int MODEL_RATE = 16_000;

    private Wav2Vec2Models() {
    }
}
