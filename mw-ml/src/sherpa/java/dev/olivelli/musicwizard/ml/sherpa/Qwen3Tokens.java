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

package dev.olivelli.musicwizard.ml.sherpa;

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LyricWord;
import java.util.ArrayList;
import java.util.List;

/**
 * Recognizer tokens into words: pure text-and-time arithmetic, no natives.
 *
 * <p>Qwen3's tokenizer marks a word boundary with a leading space on the
 * token (BPE-style; the metaspace variants {@code ▁} and {@code Ġ}
 * are folded to the same meaning). A word runs from its first token's
 * timestamp to its last token's timestamp plus duration; a token with no
 * duration still advances the word's end to its own start, so a word is never
 * cut short by one missing measurement.
 *
 * <p>Confidence is {@link Confidence#of} 0.6 for every transcribed word — a
 * deliberate constant, unlike the aligner's measured number, because
 * sherpa-onnx exposes no per-token posterior; the honest statement is "this
 * source is a transcription", not a per-word certainty the API cannot supply.
 * #386 owns reconciling the scales before anything branches on them.
 */
final class Qwen3Tokens {

    static final Confidence TRANSCRIBED = Confidence.of(0.6);

    private Qwen3Tokens() {
    }

    static List<LyricWord> words(String[] tokens, float[] timestamps,
                                 float[] durations) {
        List<LyricWord> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        double start = 0;
        double end = 0;
        for (int i = 0; i < tokens.length; i++) {
            String piece = tokens[i] == null ? "" : tokens[i];
            boolean boundary = startsNewWord(piece);
            String cleaned = strip(piece);
            double at = i < timestamps.length ? timestamps[i] : end;
            double duration = i < durations.length ? Math.max(0, durations[i]) : 0;
            if (boundary && text.length() > 0) {
                out.add(word(text.toString(), start, end));
                text.setLength(0);
            }
            if (cleaned.isEmpty()) {
                continue;
            }
            if (text.length() == 0) {
                start = at;
                end = at;
            }
            text.append(cleaned);
            end = Math.max(end, at + duration);
        }
        if (text.length() > 0) {
            out.add(word(text.toString(), start, end));
        }
        return List.copyOf(out);
    }

    private static LyricWord word(String text, double start, double end) {
        return LyricWord.ofSeconds(text, start, Math.max(start, end), TRANSCRIBED);
    }

    private static boolean startsNewWord(String token) {
        if (token.isEmpty()) {
            return false;
        }
        char first = token.charAt(0);
        return first == ' ' || first == '▁' || first == 'Ġ';
    }

    private static String strip(String token) {
        int from = 0;
        while (from < token.length() && (token.charAt(from) == ' '
                || token.charAt(from) == '▁' || token.charAt(from) == 'Ġ')) {
            from++;
        }
        return token.substring(from).strip();
    }
}
