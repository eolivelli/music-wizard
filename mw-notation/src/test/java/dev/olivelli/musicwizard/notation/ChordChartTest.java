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
import java.util.Locale;
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
    @DisplayName("bars a 6/8 chart every two pulses, not every six")
    void barsCompoundTimeOnTheCountedBeat() {
        // A 6/8 tune at a dotted-quarter pulse of 0.5s: one bar is a second, and
        // one chord per bar for four bars. Reading the meter's numerator instead
        // of its counted beats made a bar three times too long, so all four
        // chords collapsed into the first two bars.
        List<Double> pulses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            pulses.add(i * 0.5);
        }
        List<Chord> chords = new ArrayList<>();
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        for (int i = 0; i < 4; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                    i * 1.0, i * 1.0 + 1.0, Confidence.of(0.9)));
        }
        Score score = Score.empty(
                        TempoMap.fromBeatTimes(pulses, TimeSignature.SIX_EIGHT), 4.0)
                .withBeatGrid(BeatGrid.ofTimes(pulses, TimeSignature.SIX_EIGHT, Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        List<String> lines = ChordChart.barLines(score);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("C").contains("G").contains("A").contains("F");
        assertThat(lines.get(0)).doesNotContain("%");
    }

    @Test
    @DisplayName("heads a 6/8 chart with the tempo the reader counts")
    void headsCompoundChartWithTheCountedTempo() {
        // The chart is the artefact a musician actually holds, and this line sits
        // directly above "Meter  6/8", which makes it look authoritative. The map
        // stores 180 quarter notes a minute; a reader setting a metronome from an
        // unqualified "180 BPM" in 6/8 is 50% fast.
        List<Double> pulses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            pulses.add(i * 0.5);
        }
        Score compound = Score.empty(
                        TempoMap.fromBeatTimes(pulses, TimeSignature.SIX_EIGHT), 4.0)
                .withChords(new ChordProgression(List.of(
                        Chord.ofSeconds(root(NoteLetter.C), ChordQuality.MAJOR,
                                0, 4.0, Confidence.of(0.9))), Confidence.of(0.9)));

        assertThat(ChordChart.toText(compound))
                .contains("Tempo  120 BPM (180 quarter notes/min)")
                .contains("Meter  6/8");

        // Unchanged in common time, where the two figures coincide.
        assertThat(ChordChart.toText(fourChordSong(1)))
                .contains("Tempo  120 BPM\n")
                .doesNotContain("quarter notes/min");
    }

    @Test
    @DisplayName("bars a chart at the tempo it prints, even when the user forced one")
    void headerAndBarsCannotDisagree() {
        // The chart used to read its header off the tempo map and its bar lines
        // off the beat grid, and --tempo moves only the map. So a chart could be
        // headed 60 BPM above bars a musician would count at 120, contradicting
        // itself on its own face -- and the correction the README calls the
        // highest-value thing a user can do reached the header and nothing else.
        List<Double> pulses = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            pulses.add(i * 0.5);
        }
        // One chord every four seconds, which at the corrected 60 BPM is exactly
        // one 4/4 bar each and at the tracked 120 BPM is every other bar.
        List<Chord> chords = new ArrayList<>();
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        for (int i = 0; i < 4; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                    i * 4.0, i * 4.0 + 4.0, Confidence.of(0.9)));
        }
        // Tracked at 120, but the user says the tracker doubled it and it is 60.
        Score corrected = Score.empty(TempoMap.constantPulse(60, TimeSignature.FOUR_FOUR), 16.0)
                .withBeatGrid(BeatGrid.ofTimes(pulses, TimeSignature.FOUR_FOUR,
                        Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(ChordChart.toText(corrected)).contains("Tempo  60 BPM");
        // Four bars, one chord each. Off the grid the bars would be half as long,
        // so there would be eight of them and every other one a "%" continuation
        // -- bars a musician counts at 120 under a header saying 60.
        assertThat(ChordChart.barLines(corrected)).hasSize(1);
        assertThat(ChordChart.barLines(corrected).get(0))
                .contains("C").contains("G").contains("A").contains("F")
                .doesNotContain("%");
    }

    @Test
    @DisplayName("reads its tempo from the tracked beats, for the header and the bars alike")
    void headerAndBarsBothUseTheTrackedBeats() {
        // Every other fixture here uses a constant map or a grid starting at
        // t=0.0, and in both cases every source of a tempo agrees, so none of
        // them can tell which one was read. This one has a lead-in: a whole pulse
        // crammed into the 0.05s before the first tracked beat pulls the map's
        // average to 124.3, against the 120 a musician would count.
        //
        // Both assertions matter and neither implies the other. The header and
        // the bar lines are separate readers of the same answer, and this PR has
        // twice shipped a fix that reached one reader and not the other.
        List<Double> pulses = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            pulses.add(0.05 + i * 0.5);
        }
        List<Chord> chords = new ArrayList<>();
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A,
                NoteLetter.F, NoteLetter.D, NoteLetter.E};
        for (int i = 0; i < 6; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                    0.05 + i * 2.1, 0.05 + (i + 1) * 2.1, Confidence.of(0.9)));
        }
        Score tracked = Score.empty(
                        TempoMap.fromBeatTimes(pulses, TimeSignature.FOUR_FOUR), 12.65)
                .withBeatGrid(BeatGrid.ofTimes(pulses, TimeSignature.FOUR_FOUR,
                        Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(tracked.tempoMap().averageTempo(12.65))
                .as("the map is inflated, so this fixture discriminates")
                .isGreaterThan(124.0);

        assertThat(ChordChart.toText(tracked)).contains("Tempo  120 BPM");
        // At the tracked 120 a 4/4 bar is two seconds, so the harmony is six bars
        // of one chord. Off the map's inflated average the bars are 1.93s, which
        // rounds to a seventh bar with nothing in it -- a "%" continuation under
        // a header that still says 120.
        assertThat(ChordChart.barLines(tracked))
                .allSatisfy(line -> assertThat(line).doesNotContain("%"));
        assertThat(String.join("", ChordChart.barLines(tracked)))
                .contains("C").contains("G").contains("A")
                .contains("F").contains("D").contains("E");
    }

    @Test
    @DisplayName("prints a tempo the user can type back in, in any locale")
    void tempoLineIsLocaleIndependent() {
        // picocli parses --tempo with Double.valueOf, which rejects "120,0". A
        // chart printed under fr_FR used to hand the user a number their own
        // tool would not accept, and under ar_EG one in Arabic-Indic digits.
        Locale original = Locale.getDefault();
        try {
            for (Locale locale : List.of(Locale.forLanguageTag("fr-FR"),
                    Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN"))) {
                Locale.setDefault(locale);
                assertThat(ChordChart.toText(fourChordSong(1)))
                        .as("chart under %s", locale)
                        .contains("Tempo  120 BPM");
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("escapes quotes in the title rather than producing broken source")
    void escapesTitle() {
        Score score = fourChordSong(1).withMetadata("A \"Quoted\" Title", null);

        assertThat(ChordChart.toLilyPond(score)).contains("\\\"Quoted\\\"");
    }
}
