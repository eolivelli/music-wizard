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
        lines = List.copyOf(lines);
    }

    public static Lyrics empty() {
        return new Lyrics(List.of(), "und", Confidence.UNKNOWN);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** Every word across every line, in time order. */
    public List<LyricWord> allWords() {
        return lines.stream().flatMap(line -> line.words().stream()).toList();
    }

    /** The full text, one line per line. */
    public String text() {
        return lines.stream().map(LyricLine::text).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
