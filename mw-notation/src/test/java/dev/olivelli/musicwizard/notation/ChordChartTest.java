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

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChordChartTest {

    private static PitchSpelling root(NoteLetter letter) {
        return new PitchSpelling(letter, Accidental.NATURAL, 4);
    }

    /** Four bars per chord cycle at 120 BPM: one bar is exactly 2 seconds. */
    private static Score fourChordSong(int cycles) {
        List<Chord> chords = new ArrayList<>();
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        ChordQuality[] qualities = {ChordQuality.MAJOR, ChordQuality.MAJOR,
                ChordQuality.MINOR, ChordQuality.MAJOR};

        double time = 0;
        for (int cycle = 0; cycle < cycles; cycle++) {
            for (int i = 0; i < 4; i++) {
                chords.add(Chord.ofSeconds(root(roots[i]), qualities[i],
                        time, time + 2.0, Confidence.of(0.9)));
                time += 2.0;
            }
        }

        List<Double> beats = new ArrayList<>();
        for (double t = 0; t < time; t += 0.5) {
            beats.add(t);
        }

        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), time)
                .withBeatGrid(BeatGrid.ofTimes(beats, 4, Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    @Test
    @DisplayName("prints one chord per bar, four bars to a line")
    void printsOneChordPerBar() {
        List<String> lines = ChordChart.barLines(fourChordSong(4));

        assertThat(lines).hasSize(4);
        for (String line : lines) {
            assertThat(line).contains("C").contains("G").contains("Am").contains("F");
        }
    }

    @Test
    @DisplayName("does not merge two chords into the opening bar")
    void doesNotMergeTheOpeningBar() {
        // The failure this guards: anchoring the bar grid on a detected downbeat
        // that sits half a bar out of phase with the harmony puts the first two
        // chords in bar one together.
        String first = ChordChart.barLines(fourChordSong(2)).get(0);

        assertThat(first).doesNotContain("C G");
    }

    @Test
    @DisplayName("marks a bar with no chord change as a continuation")
    void marksContinuations() {
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 8.0)
                .withChords(new ChordProgression(List.of(
                        Chord.ofSeconds(root(NoteLetter.C), ChordQuality.MAJOR,
                                0, 8.0, Confidence.of(0.9))), Confidence.of(0.9)));

        assertThat(ChordChart.barLines(score).get(0)).contains("C").contains("%");
    }

    @Test
    @DisplayName("says so plainly when nothing was found")
    void reportsNoChords() {
        Score empty = Score.empty(TempoMap.constant(120), 10);

        assertThat(ChordChart.toText(empty)).contains("no chords");
    }

    @Test
    @DisplayName("emits LilyPond that names the chords in chordmode")
    void emitsLilyPond() {
        String source = ChordChart.toLilyPond(fourChordSong(1));

        assertThat(source)
                .contains("\\version")
                .contains("\\chordmode")
                .contains("c1")
                .contains("g1")
                .contains("a1:m")
                .contains("f1");
    }

    @Test
    @DisplayName("escapes quotes in the title rather than producing broken source")
    void escapesTitle() {
        Score score = fourChordSong(1).withMetadata("A \"Quoted\" Title", null);

        assertThat(ChordChart.toLilyPond(score)).contains("\\\"Quoted\\\"");
    }
}
