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

package dev.olivelli.musicwizard.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Directly, because on every healthy path the sequential window head and the
 * tail bound make it a no-op — which is exactly why no end-to-end fixture can
 * discriminate it, and why it must be pinned here.
 */
@DisplayName("the assembly-point shift")
class ShiftedAfterTest {

    private static LyricLine line(double... startsAndEnds) {
        var words = new java.util.ArrayList<LyricWord>();
        for (int i = 0; i < startsAndEnds.length; i += 2) {
            words.add(LyricWord.ofSeconds("w" + i, startsAndEnds[i],
                    startsAndEnds[i + 1], Confidence.of(0.8)));
        }
        return new LyricLine(List.copyOf(words), Confidence.of(0.8));
    }

    @Test
    @DisplayName("a line starting at or after the previous end is untouched")
    void noShiftWhenOrdered() {
        LyricLine before = line(2.0, 2.5, 2.5, 3.0);
        assertThat(AnalyzeCommand.shiftedAfter(before, 2.0)).isSameAs(before);
        assertThat(AnalyzeCommand.shiftedAfter(before, 1.0)).isSameAs(before);
    }

    @Test
    @DisplayName("a line starting earlier is shifted forward, intervals intact")
    void shiftPreservesShape() {
        LyricLine shifted = AnalyzeCommand.shiftedAfter(line(1.0, 1.4, 1.6, 2.0), 3.0);

        assertThat(shifted.startSeconds()).isEqualTo(3.0);
        assertThat(shifted.words().get(0).endSeconds()).isEqualTo(3.4);
        assertThat(shifted.words().get(1).startSeconds()).isEqualTo(3.6);
        assertThat(shifted.words().get(1).endSeconds()).isEqualTo(4.0);
    }
}
