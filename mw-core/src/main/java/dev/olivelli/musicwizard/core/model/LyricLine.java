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

import java.util.List;
import java.util.Objects;

/**
 * A line of lyrics, which is the unit a chord chart prints.
 *
 * @param words      the words in the line, ordered in time
 * @param confidence trust in the line as a whole
 */
public record LyricLine(List<LyricWord> words, Confidence confidence) {

    public LyricLine {
        Objects.requireNonNull(words, "words");
        Objects.requireNonNull(confidence, "confidence");
        if (words.isEmpty()) {
            throw new IllegalArgumentException("a lyric line needs at least one word");
        }
        words = List.copyOf(words);
    }

    public double startSeconds() {
        return words.get(0).startSeconds();
    }

    public double endSeconds() {
        return words.get(words.size() - 1).endSeconds();
    }

    /** The line as plain text. */
    public String text() {
        return words.stream().map(LyricWord::text).reduce((a, b) -> a + " " + b).orElse("");
    }
}
