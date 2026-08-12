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
 * <p>Qwen3-ASR decodes text the way a language model does, and sherpa-onnx's
 * implementation carries no per-token times — {@code getTimestamps()} comes
 * back empty. So the words' times are inferred, not recognised: spread across
 * the transcribed window in proportion to a syllable estimate, the same
 * apportioning the LRC parser uses for an un-timed line. The window is one
 * sung stretch, not the whole song, which is what keeps the inference honest
 * enough to use — and the aligner measures real onsets afterwards where it
 * speaks the language.
 *
 * <p>Tokens arrive already byte-level-decoded to UTF-8, with a word boundary
 * as leading whitespace (the metaspace variants {@code ▁} and {@code Ġ} are
 * folded in case an export surfaces them raw). A dangling-byte token ends in
 * U+FFFD, which is noise, not text.
 *
 * <p>{@link #TRANSCRIBED} is one deliberate constant for every word — text
 * from a modest recognizer, timing inferred — because sherpa-onnx exposes no
 * per-token posterior to be honest with. #386 owns reconciling the scales
 * before anything branches on them.
 */
final class Qwen3Tokens {

    static final Confidence TRANSCRIBED = Confidence.of(0.6);

    private Qwen3Tokens() {
    }

    /**
     * Whether a decode is a repetition loop rather than lyrics: it ran to the
     * decoder's token cap with almost no distinct tokens. The larger locally
     * exported model does this on some sung stretches (#396), and 128 copies
     * of one syllable engraved under a verse is strictly worse than a line
     * missing. The threshold is deliberately far from anything singing
     * produces -- real choruses repeat words, not nine-tenths of a window's
     * tokens.
     */
    static boolean looksLikeALoop(String[] tokens) {
        if (tokens.length < 64) {
            return false;
        }
        long distinct = java.util.Arrays.stream(tokens).distinct().count();
        return distinct * 10 <= tokens.length;
    }

    /** The tokens as words, spread across {@code windowSeconds} of singing. */
    static List<LyricWord> words(String[] tokens, double windowSeconds) {
        List<String> texts = wordTexts(tokens);
        if (texts.isEmpty() || windowSeconds <= 0) {
            return List.of();
        }
        List<LyricWord> unplaced = new ArrayList<>(texts.size());
        int totalSyllables = 0;
        for (String text : texts) {
            LyricWord word = LyricWord.ofSeconds(text, 0, 0, TRANSCRIBED);
            unplaced.add(word);
            totalSyllables += word.syllableEstimate();
        }
        // Boundaries as cumulative fractions, not a running sum of durations:
        // accumulated rounding once pushed a stretch's last word a hair past
        // the next stretch's exact start. This way the last end IS the window.
        List<LyricWord> out = new ArrayList<>(unplaced.size());
        int syllablesBefore = 0;
        for (LyricWord word : unplaced) {
            double from = windowSeconds * syllablesBefore / totalSyllables;
            syllablesBefore += word.syllableEstimate();
            double to = windowSeconds * syllablesBefore / totalSyllables;
            out.add(LyricWord.ofSeconds(word.text(), from, to, TRANSCRIBED));
        }
        return List.copyOf(out);
    }

    private static List<String> wordTexts(String[] tokens) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String token : tokens) {
            String piece = token == null ? "" : token;
            if (startsNewWord(piece) && current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
            }
            current.append(cleaned(piece));
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    private static boolean startsNewWord(String token) {
        if (token.isEmpty()) {
            return false;
        }
        char first = token.charAt(0);
        return Character.isWhitespace(first) || first == '▁' || first == 'Ġ';
    }

    private static String cleaned(String token) {
        return token.replace('▁', ' ').replace('Ġ', ' ')
                .replace("�", "").strip();
    }
}
