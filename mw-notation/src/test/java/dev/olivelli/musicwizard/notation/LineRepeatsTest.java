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

package dev.olivelli.musicwizard.notation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LineRepeatsTest {

    /** The tags as plain strings, with a dot where a line carries none. */
    private static String tags(String... lines) {
        StringBuilder read = new StringBuilder();
        for (Optional<String> tag : LineRepeats.tagsOf(List.of(lines))) {
            read.append(tag.orElse("."));
        }
        return read.toString();
    }

    @Test
    @DisplayName("says nothing about a line that is printed only once")
    void aLineSeenOnceIsUntagged() {
        assertThat(tags("| C |", "| G |", "| F |")).isEqualTo("...");
    }

    @Test
    @DisplayName("tags every occurrence, not only the one that opens the repeat")
    void everyOccurrenceCarriesTheTag() {
        // The property that keeps a tag a claim about its own line. Tagging only
        // the first occurrence is a heading, and a heading scopes forward to the
        // next one -- which on the recording #218 was filed from meant one label
        // standing over the rest of the song.
        assertThat(tags("| C |", "| G |", "| C |")).isEqualTo("A.A");
    }

    @Test
    @DisplayName("gives distinct repeated lines distinct tags, in first-seen order")
    void tagsCountUpInFirstSeenOrder() {
        assertThat(tags("| C |", "| G |", "| F |", "| G |", "| C |")).isEqualTo("AB.BA");
    }

    @Test
    @DisplayName("keeps a tag through an interruption, however far apart the repeats are")
    void aTagSurvivesAnInterruption() {
        assertThat(tags("| C |", "| x |", "| y |", "| z |", "| C |")).isEqualTo("A...A");
    }

    @Test
    @DisplayName("runs past Z the way a spreadsheet names its columns")
    void tagsRunPastZ() {
        // 27 distinct repeated lines, so the 27th has to be spelled with two
        // letters rather than colliding with the first or running out.
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            lines.add("| " + i + " |");
            lines.add("| " + i + " |");
        }
        List<Optional<String>> tags = LineRepeats.tagsOf(lines);

        assertThat(tags.get(0)).contains("A");
        assertThat(tags.get(50)).contains("Z");
        assertThat(tags.get(52)).contains("AA");
        assertThat(tags).allMatch(Optional::isPresent);
        assertThat(tags.stream().map(Optional::orElseThrow).distinct()).hasSize(27);
    }

    @Test
    @DisplayName("charts nothing for an empty chart")
    void anEmptyChartHasNoTags() {
        assertThat(LineRepeats.tagsOf(List.of())).isEmpty();
    }
}
