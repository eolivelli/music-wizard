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
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    /**
     * A quantized progression over a tempo that halves half-way through.
     *
     * <p>One chord to the bar throughout, which is what a reader should see. In
     * seconds those bars are 2, 2, 8 and 8 seconds long against an average bar
     * of 5, so a grid built from a single averaged bar length can neither place
     * them nor count them.
     */
    private static Score quantizedAcrossATempoChange() {
        TempoMap map = new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120),
                        new TempoMap.TempoSegment(8, 4, 30)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        ChordQuality[] qualities = {ChordQuality.MAJOR, ChordQuality.MAJOR,
                ChordQuality.MINOR, ChordQuality.MAJOR};
        List<Chord> chords = new ArrayList<>();
        for (int bar = 0; bar < 4; bar++) {
            double from = bar * 4.0;
            double to = from + 4.0;
            chords.add(Chord.ofSeconds(root(roots[bar]), qualities[bar],
                            map.beatsToSeconds(from), map.beatsToSeconds(to), Confidence.of(0.9))
                    .quantizedTo(from, to));
        }
        return Score.empty(map, map.beatsToSeconds(16))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    @Test
    @DisplayName("bars a quantized chart by its beats, so a tempo change does not crush it")
    void barsAQuantizedChartByItsBeats() {
        Score score = quantizedAcrossATempoChange();
        assertThat(score.chords().isQuantized()).isTrue();

        // Four bars, one chord each. Placing them by seconds against a single
        // averaged bar length put C and G in bar 1, left bar 2 empty and pushed
        // Am into bar 3 -- a chart that cannot be played against the recording.
        assertThat(ChordChart.barLines(score))
                .containsExactly("| C           | G           | Am          | F           |");
    }

    @Test
    @DisplayName("gives each chord of a quantized chart the bars it actually holds")
    void quantizedLilyPondSpansTheRightBars() {
        String source = ChordChart.toLilyPond(quantizedAcrossATempoChange());

        // One whole note each. By duration in seconds the last two chords run to
        // more than a bar and a half of the averaged bar and are each written
        // twice, giving a six-bar score for four bars of music.
        assertThat(source).contains("c1 g1 a1:m f1 ");
    }

    /** Five chords over four bars of 4/4, so two of them share a bar. */
    private static Score twoChordsInABar() {
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.A, NoteLetter.F, NoteLetter.G, NoteLetter.C};
        ChordQuality[] qualities = {ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.MAJOR,
                ChordQuality.MAJOR, ChordQuality.MAJOR};
        double[] starts = {0, 2, 4, 6, 8};
        double[] ends = {2, 4, 6, 8, 16};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < starts.length; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), qualities[i],
                            map.beatsToSeconds(starts[i]), map.beatsToSeconds(ends[i]),
                            Confidence.of(0.9))
                    .quantizedTo(starts[i], ends[i]));
        }
        return Score.empty(map, map.beatsToSeconds(16))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    @Test
    @DisplayName("engraves a chord that is half a bar as half a bar, not as a whole one")
    void aChordShorterThanABarKeepsItsLength() {
        Score score = twoChordsInABar();

        // Two chords to the first two bars, one to the last two.
        assertThat(ChordChart.barLines(score))
                .containsExactly("| C Am        | F G         | C           | %           |");
        // Four half notes and two whole notes: sixteen quarter beats, which is
        // the four bars the chart shows. Written as five whole notes -- which is
        // what a fixed duration gives -- LilyPond engraves six bars, and every
        // symbol from bar 2 on sits against the wrong bar of the recording.
        assertThat(ChordChart.toLilyPond(score)).contains("c2 a2:m f2 g2 c1*2 ");
    }

    /** One bar of one chord, quantized, so a quality can be engraved on its own. */
    private static Score oneChord(ChordQuality quality, PitchSpelling bass) {
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);
        Chord chord = new Chord(root(NoteLetter.C), quality, Optional.ofNullable(bass),
                0, 2, Optional.of(0.0), Optional.of(4.0), Confidence.of(0.9));
        return Score.empty(map, 2)
                .withChords(new ChordProgression(List.of(chord), Confidence.of(0.9)));
    }

    /**
     * Every quality the model has, and what chordmode calls it.
     *
     * <p>Enumerated from {@link ChordQuality} rather than listed, so a quality
     * added to the model without a mapping fails here as well as failing to
     * compile. The tokens are not guesses: each was engraved with LilyPond
     * 2.26.0 and the sounding pitches read back out of its MIDI, and every one
     * matches {@code ChordQuality.intervals()}. {@code :m7.5-} prints as Bø and
     * {@code :m7+} as a minor triad with the conventional major-seventh
     * triangle.
     *
     * <p>This is what the switch used to get wrong. Four qualities collapsed
     * onto {@code :m} and three onto {@code :dim}, which was harmless while only
     * major, minor and no-chord could reach the emitter and became a chart that
     * engraved a different chord than it printed as soon as #115 widened the
     * vocabulary.
     */
    @ParameterizedTest(name = "{0} is engraved as c1{1}")
    @CsvSource(nullValues = "-", value = {
        "MAJOR,                    ''",
        "MINOR,                    :m",
        "DIMINISHED,               :dim",
        "AUGMENTED,                :aug",
        "SUSPENDED_SECOND,         :sus2",
        "SUSPENDED_FOURTH,         :sus4",
        "DOMINANT_SEVENTH,         :7",
        "MAJOR_SEVENTH,            :maj7",
        "MINOR_SEVENTH,            :m7",
        "MINOR_MAJOR_SEVENTH,      :m7+",
        "HALF_DIMINISHED_SEVENTH,  :m7.5-",
        "DIMINISHED_SEVENTH,       :dim7",
        "SIXTH,                    :6",
        "MINOR_SIXTH,              :m6",
    })
    @DisplayName("engraves each chord quality as the chord it is")
    void everyQualityHasItsOwnChordModeToken(ChordQuality quality, String modifier) {
        assertThat(ChordChart.toLilyPond(oneChord(quality, null)))
                .contains("c1" + (modifier == null ? "" : modifier) + " ");
    }

    @Test
    @DisplayName("the quality table covers every quality the model can hold")
    void noQualityIsUnaccountedFor() {
        // The parameterized rows above minus N.C., which is engraved as a rest
        // and has no root to modify. If this count moves, a quality was added
        // and the table was not.
        assertThat(ChordQuality.values()).hasSize(15);
        assertThat(ChordChart.toLilyPond(oneChord(ChordQuality.NONE, null))).contains("r1 ");
    }

    @Test
    @DisplayName("engraves a slash chord over its bass, not in root position")
    void aSlashChordKeepsItsBass() {
        String source = ChordChart.toLilyPond(
                oneChord(ChordQuality.MAJOR, PitchSpelling.parse("E4")));
        // Dropping the bass printed a root-position C where the chart said C/E,
        // which is a different instruction to whoever is playing the bass line.
        assertThat(source).contains("c1/e ");

        String flat = ChordChart.toLilyPond(
                oneChord(ChordQuality.MINOR_SEVENTH, PitchSpelling.parse("Eb4")));
        assertThat(flat).contains("c1:m7/ees ");
    }

    @Test
    @DisplayName("a chord ending a few ticks past a bar line does not add a bar to the text")
    void theChartAndTheEngravingRoundTheSameWay() {
        // A piece whose last note-off is two MIDI ticks past bar 3. The
        // engraving rounds that away, and the text used to count the bar it
        // touched, so a chart claimed four bars where the page had three.
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);
        double raggedEnd = 12 + 2 / 480.0;
        List<Chord> chords = List.of(
                Chord.ofSeconds(root(NoteLetter.C), ChordQuality.MAJOR, 0, 2, Confidence.of(0.9))
                        .quantizedTo(0, 4),
                Chord.ofSeconds(root(NoteLetter.F), ChordQuality.MAJOR, 2, 4, Confidence.of(0.9))
                        .quantizedTo(4, 8),
                Chord.ofSeconds(root(NoteLetter.D), ChordQuality.MINOR, 4,
                                map.beatsToSeconds(raggedEnd), Confidence.of(0.9))
                        .quantizedTo(8, raggedEnd));
        Score score = Score.empty(map, map.beatsToSeconds(raggedEnd))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(ChordChart.barLines(score))
                .containsExactly("| C           | F           | Dm          |");
        assertThat(ChordChart.toLilyPond(score)).contains("c1 f1 d1:m ");
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
