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

package dev.olivelli.musicwizard.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Objects;

/**
 * All lyrics for a piece, grouped into printable lines.
 *
 * @param lines      the lines, ordered in time
 * @param language   BCP 47 language tag as detected, e.g. "en"
 * @param confidence overall trust, which for sung speech recognition is modest
 */
public record Lyrics(List<LyricLine> lines, String language, Confidence confidence) {

    public Lyrics {
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(confidence, "confidence");
        // copyOf first, then sort: copyOf is what rejects a null line, and the
        // sort alone would not, because a one-element list never invokes the
        // comparator that would have dereferenced it.
        lines = List.copyOf(lines).stream()
                .sorted(java.util.Comparator.comparingDouble(LyricLine::startSeconds))
                .toList();
    }

    public static Lyrics empty() {
        return new Lyrics(List.of(), "und", Confidence.UNKNOWN);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** True once every word carries beat-snapped timing. */
    @JsonIgnore
    public boolean isQuantized() {
        return lines.stream().allMatch(LyricLine::isQuantized);
    }

    /**
     * Every word across every line, in time order.
     *
     * <p>Sorted here rather than relying on the line order, because recognition
     * spans on sung speech overlap: a line that starts later can hold a word
     * that starts before the end of the line before it, so concatenating
     * already-ordered lines is not enough to make the words ordered.
     */
    public List<LyricWord> allWords() {
        return lines.stream()
                .flatMap(line -> line.words().stream())
                .sorted(java.util.Comparator.comparingDouble(LyricWord::startSeconds))
                .toList();
    }

    /** The full text, one line per line. */
    public String text() {
        return lines.stream().map(LyricLine::text).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
