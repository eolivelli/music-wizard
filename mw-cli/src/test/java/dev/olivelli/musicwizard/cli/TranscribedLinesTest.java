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
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("transcribed words into lines")
class TranscribedLinesTest {

    private static LyricWord word(String text, double start, double end, double confidence) {
        return LyricWord.ofSeconds(text, start, end, Confidence.of(confidence));
    }

    @Test
    @DisplayName("every sung stretch is its own line, however small the gap")
    void stretchesBreakLines() {
        // 0.15 s apart across the boundary -- far below the gap rule; the
        // break is the stretch boundary itself. On a real recording the
        // stretch padding shrinks pauses this way, and a gap rule alone
        // merged whole verses into one line.
        Lyrics lyrics = TranscribedLines.grouped(List.of(
                List.of(word("la", 0.0, 0.4, 0.8), word("sol", 0.8, 1.2, 0.8)),
                List.of(word("mi", 1.35, 1.7, 0.8))), "it");

        assertThat(lyrics.lines()).hasSize(2);
        assertThat(lyrics.lines().get(0).words()).hasSize(2);
        assertThat(lyrics.language()).isEqualTo("it");
    }

    @Test
    @DisplayName("a pause inside a stretch breaks again; a beat between words does not")
    void pausesInsideAStretchBreakLines() {
        Lyrics lyrics = TranscribedLines.grouped(List.of(List.of(
                word("la", 0.0, 0.4, 0.8),
                word("sol", 0.8, 1.2, 0.8),      // 0.4 s after: same line
                word("mi", 2.5, 2.9, 0.8),       // 1.3 s after: new line
                word("do", 2.95, 3.3, 0.8))), "it");

        assertThat(lyrics.lines()).hasSize(2);
        assertThat(lyrics.lines().get(0).words()).hasSize(2);
        assertThat(lyrics.lines().get(1).words()).hasSize(2);
    }

    @Test
    @DisplayName("confidence is the floor, not the average")
    void confidenceIsTheMinimum() {
        Lyrics lyrics = TranscribedLines.grouped(List.of(
                List.of(word("la", 0.0, 0.4, 0.9), word("sol", 0.5, 0.9, 0.3)),
                List.of(word("mi", 3.0, 3.4, 0.7))), "en");

        assertThat(lyrics.lines().get(0).confidence().value()).isEqualTo(0.3);
        assertThat(lyrics.lines().get(1).confidence().value()).isEqualTo(0.7);
        assertThat(lyrics.confidence().value()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("stretches cut from one run cannot overlap by an ulp")
    void tiledStretchesStayMonotone() {
        // The two float routes to a shared cut differ in the last bit; the
        // sheet's chord cursor walks line ends and depends on monotone lines.
        double boundary = 2.0;
        double hair = Math.nextUp(boundary);
        Lyrics lyrics = TranscribedLines.grouped(List.of(
                List.of(word("la", 0.0, hair, 0.8)),
                List.of(word("sol", boundary, 3.0, 0.8))), "en");

        assertThat(lyrics.lines().get(1).words().get(0).startSeconds())
                .isGreaterThanOrEqualTo(lyrics.lines().get(0).words().get(0).endSeconds());
    }

    @Test
    @DisplayName("no words means empty lyrics, which the caller reports")
    void noWords() {
        assertThat(TranscribedLines.grouped(List.of(), "en").isEmpty()).isTrue();
        assertThat(TranscribedLines.grouped(List.of(List.of()), "en").isEmpty())
                .isTrue();
    }

    @Test
    @DisplayName("the gap is measured from the previous word's end, not its start")
    void gapMeasuredFromEnd() {
        // A held word ending 0.2 s before the next: one line, although the
        // starts are far apart.
        Lyrics lyrics = TranscribedLines.grouped(List.of(List.of(
                word("laaa", 0.0, 2.0, 0.8),
                word("sol", 2.2, 2.5, 0.8))), "en");

        assertThat(lyrics.lines()).hasSize(1);
    }
}
