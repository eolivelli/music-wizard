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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SectionLayoutTest {

    /** One bar holding a single major-triad cell, named, filling the bar. */
    private static ChartLayout.Bar bar(NoteLetter letter, ChordQuality quality) {
        Chord chord = Chord.ofSeconds(new PitchSpelling(letter, Accidental.NATURAL, 4),
                quality, 0, 4, Confidence.of(0.9));
        return new ChartLayout.Bar(TimeSignature.FOUR_FOUR, false,
                List.of(new ChartLayout.Cell(Optional.of(chord), 4.0, true)));
    }

    private static ChartLayout.Bar bar(NoteLetter letter) {
        return bar(letter, ChordQuality.MAJOR);
    }

    @Test
    @DisplayName("labels nothing when no line's content ever recurs")
    void noLabelsWithoutRepetition() {
        List<ChartLayout.Bar> bars = List.of(bar(NoteLetter.C), bar(NoteLetter.G),
                bar(NoteLetter.A), bar(NoteLetter.F));

        List<Optional<String>> labels = SectionLayout.labelsPerLine(bars, 1);

        assertThat(labels).allSatisfy(label -> assertThat(label).isEmpty());
    }

    @Test
    @DisplayName("labels a line whose content recurs later in the chart")
    void labelsARepeatedLine() {
        // Two identical four-bar lines, one chord to a bar: fourChordSong(2)'s
        // shape, at the unit level.
        List<ChartLayout.Bar> bars = List.of(
                bar(NoteLetter.C), bar(NoteLetter.G), bar(NoteLetter.A), bar(NoteLetter.F),
                bar(NoteLetter.C), bar(NoteLetter.G), bar(NoteLetter.A), bar(NoteLetter.F));

        List<Optional<String>> labels = SectionLayout.labelsPerLine(bars, 4);

        assertThat(labels).hasSize(2);
        assertThat(labels.get(0)).contains("Section A");
        // The second line is the same content immediately continuing, not a
        // second occurrence a reader needs pointed out.
        assertThat(labels.get(1)).isEmpty();
    }

    @Test
    @DisplayName("gives an A-B-A-B chart the same label on both returns of A")
    void differentRepeatsGetDifferentLabels() {
        List<ChartLayout.Bar> bars = List.of(
                bar(NoteLetter.C),  // A
                bar(NoteLetter.G),  // B
                bar(NoteLetter.C),  // A again
                bar(NoteLetter.G),  // B again
                bar(NoteLetter.D)); // never repeats

        List<Optional<String>> labels = SectionLayout.labelsPerLine(bars, 1);

        assertThat(labels).containsExactly(
                Optional.of("Section A"),
                Optional.of("Section B"),
                Optional.of("Section A"),
                Optional.of("Section B"),
                Optional.empty());
    }

    @Test
    @DisplayName("stays quiet through a run of more than two identical lines")
    void continuationLinesStayQuiet() {
        List<ChartLayout.Bar> bars = List.of(
                bar(NoteLetter.C), bar(NoteLetter.C), bar(NoteLetter.C));

        List<Optional<String>> labels = SectionLayout.labelsPerLine(bars, 1);

        assertThat(labels).containsExactly(
                Optional.of("Section A"), Optional.empty(), Optional.empty());
    }

    @Test
    @DisplayName("tells two bars with the same root but a different quality apart")
    void qualityIsPartOfTheSignature() {
        List<ChartLayout.Bar> bars = List.of(
                bar(NoteLetter.C, ChordQuality.MAJOR),
                bar(NoteLetter.C, ChordQuality.MINOR),
                bar(NoteLetter.C, ChordQuality.MAJOR),
                bar(NoteLetter.C, ChordQuality.MINOR));

        List<Optional<String>> labels = SectionLayout.labelsPerLine(bars, 1);

        // Cm never equals C, so this is an A-B-A-B chart like the one above,
        // not four lines of one repeated section.
        assertThat(labels).containsExactly(
                Optional.of("Section A"), Optional.of("Section B"),
                Optional.of("Section A"), Optional.of("Section B"));
    }

    @Test
    @DisplayName("names its labels the way a spreadsheet names columns past Z")
    void wrapsPastTwentySixLikeASpreadsheet() {
        ChordQuality[] qualities = {ChordQuality.MAJOR, ChordQuality.MINOR,
                ChordQuality.DOMINANT_SEVENTH, ChordQuality.MAJOR_SEVENTH};
        NoteLetter[] letters = NoteLetter.values();
        // 27 distinct one-bar sections -- more than the alphabet holds -- each
        // heard once, then all heard again in the same order, so every one of
        // them is a detected repeat.
        List<ChartLayout.Bar> firstPass = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            firstPass.add(bar(letters[i % letters.length], qualities[i / letters.length]));
        }
        List<ChartLayout.Bar> bars = new ArrayList<>(firstPass);
        bars.addAll(firstPass);

        List<Optional<String>> labels = SectionLayout.labelsPerLine(bars, 1);

        assertThat(labels.get(0)).contains("Section A");
        assertThat(labels.get(25)).contains("Section Z");
        assertThat(labels.get(26)).contains("Section AA");
    }

    @Test
    @DisplayName("rejects a non-positive line length")
    void rejectsNonPositiveBarsPerLine() {
        List<ChartLayout.Bar> bars = List.of(bar(NoteLetter.C));

        assertThatThrownBy(() -> SectionLayout.labelsPerLine(bars, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
