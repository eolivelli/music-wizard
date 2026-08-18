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

import dev.olivelli.musicwizard.arrange.BarGrid;
import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.arrange.Quantizer;
import dev.olivelli.musicwizard.arrange.SwingFeel;
import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lead sheet, whose one job of its own is placing three blocks in a score
 * that already agree about the music.
 *
 * <p>So these tests are about the seams and not about the engraving: that the
 * chord names and the melody staff open on the same bar, that the words attach
 * to the staff rather than to the chords, and that nothing is printed twice.
 * What the notes look like is {@code StaffNotationTest}'s, and what the chord
 * names look like is {@code ChordChartTest}'s.
 */
class LeadSheetTest {

    private static final double QUARTER_BPM = 120;

    private static PitchSpelling pitch(String name) {
        NoteLetter letter = NoteLetter.valueOf(name.substring(0, 1));
        Accidental accidental = name.contains("#") ? Accidental.SHARP
                : name.contains("b") ? Accidental.FLAT : Accidental.NATURAL;
        return new PitchSpelling(letter, accidental,
                Integer.parseInt(name.substring(name.length() - 1)));
    }

    private static Note note(TempoMap map, double onsetBeat, double beats, String spelling) {
        PitchSpelling written = pitch(spelling);
        return Note.ofSeconds(map.beatsToSeconds(onsetBeat),
                        map.beatsToSeconds(onsetBeat + beats) - map.beatsToSeconds(onsetBeat),
                        written.midiPitch(), Confidence.CERTAIN)
                .quantizedTo(onsetBeat, beats)
                .spelledAs(written);
    }

    private static Chord chord(TempoMap map, String root, ChordQuality quality,
                               double fromBeat, double toBeat) {
        return Chord.ofSeconds(pitch(root), quality,
                        map.beatsToSeconds(fromBeat), map.beatsToSeconds(toBeat),
                        Confidence.of(0.9))
                .quantizedTo(fromBeat, toBeat);
    }

    /** A score whose melody enters late, so the staff opens with a pickup. */
    private static QuantizedScore withPickup() {
        TempoMap map = TempoMap.constant(QUARTER_BPM, TimeSignature.FOUR_FOUR);
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(map, 3, 1, "G4"),
                note(map, 4, 2, "C5"), note(map, 6, 2, "E5"),
                note(map, 8, 4, "D5"),
                note(map, 12, 4, "C5")), Confidence.CERTAIN);
        Score score = Score.empty(map, 16 / (QUARTER_BPM / 60))
                .withTrack(voice)
                .withChords(new ChordProgression(List.of(
                        chord(map, "C4", ChordQuality.MAJOR, 0, 4),
                        chord(map, "F4", ChordQuality.MAJOR, 4, 8),
                        chord(map, "G4", ChordQuality.DOMINANT_SEVENTH, 8, 12),
                        chord(map, "C4", ChordQuality.MAJOR, 12, 16)),
                        Confidence.of(0.9)));
        return Quantizer.quantize(score);
    }

    /** The same, starting on beat one, and with words under it. */
    private static QuantizedScore withLyrics() {
        TempoMap map = TempoMap.constant(QUARTER_BPM, TimeSignature.FOUR_FOUR);
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(map, 0, 2, "E4"), note(map, 2, 2, "G4"),
                note(map, 4, 4, "C5"),
                note(map, 8, 8, "G4")), Confidence.CERTAIN);
        List<LyricWord> words = new ArrayList<>();
        String[] sung = {"one", "two", "three", "four"};
        double[] at = {0, 2, 4, 8};
        double[] length = {2, 2, 4, 8};
        for (int i = 0; i < sung.length; i++) {
            words.add(new LyricWord(sung[i], map.beatsToSeconds(at[i]),
                    map.beatsToSeconds(at[i] + length[i]),
                    java.util.Optional.of(at[i]), java.util.Optional.of(at[i] + length[i]),
                    false, false, Confidence.CERTAIN));
        }
        Score score = Score.empty(map, 16 / (QUARTER_BPM / 60))
                .withTrack(voice)
                .withChords(new ChordProgression(List.of(
                        chord(map, "C4", ChordQuality.MAJOR, 0, 4),
                        chord(map, "A4", ChordQuality.MINOR, 4, 8),
                        chord(map, "F4", ChordQuality.MAJOR, 8, 16)),
                        Confidence.of(0.9)))
                .withLyrics(new Lyrics(List.of(new LyricLine(words, Confidence.CERTAIN)),
                        "en", Confidence.CERTAIN));
        return Quantizer.quantize(score);
    }

    /**
     * A melody entering on beat four, with words under it, the first of them
     * sung at {@code firstWordBeat}.
     *
     * <p>The pair the defect needed and no fixture had (#601): a staff that
     * opens with a pickup and a lyric lane that has to open with it. The first
     * word's beat is the parameter because a word sung inside the pickup and a
     * word sung before the staff enters are the two cases the opening decides
     * between.
     */
    private static QuantizedScore withPickupAndLyrics(double firstWordBeat) {
        TempoMap map = TempoMap.constant(QUARTER_BPM, TimeSignature.FOUR_FOUR);
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(map, 3, 1, "G4"),
                note(map, 4, 4, "C5"),
                note(map, 8, 4, "E5"),
                note(map, 12, 4, "D5")), Confidence.CERTAIN);
        String[] sung = {"one", "two", "three", "four"};
        double[] at = {firstWordBeat, 4, 8, 12};
        double[] until = {4, 8, 12, 16};
        List<LyricWord> words = new ArrayList<>();
        for (int i = 0; i < sung.length; i++) {
            words.add(LyricWord.ofSeconds(sung[i], map.beatsToSeconds(at[i]),
                    map.beatsToSeconds(until[i]), Confidence.CERTAIN));
        }
        Score score = Score.empty(map, 16 / (QUARTER_BPM / 60))
                .withTrack(voice)
                .withChords(new ChordProgression(List.of(
                        chord(map, "C4", ChordQuality.MAJOR, 0, 4),
                        chord(map, "F4", ChordQuality.MAJOR, 4, 8),
                        chord(map, "G4", ChordQuality.DOMINANT_SEVENTH, 8, 12),
                        chord(map, "C4", ChordQuality.MAJOR, 12, 16)),
                        Confidence.of(0.9)))
                .withLyrics(new Lyrics(List.of(new LyricLine(words, Confidence.CERTAIN)),
                        "en", Confidence.CERTAIN));
        return Quantizer.quantize(score);
    }

    /** A position or length of {@code steps} triplet eighths, in quarter beats. */
    private static double thirds(double steps) {
        return steps / 3.0;
    }

    /**
     * A score whose melody enters inside a triplet, so the pickup is a sixth of
     * a whole note — a length no number of 64ths can write.
     *
     * <p>The grids are stated rather than quantized, so that this says what the
     * emitter does with a triplet bar rather than what the quantizer decides
     * this week; {@code StaffNotationTest} states its own the same way.
     */
    private static QuantizedScore withTripletPickup() {
        TempoMap map = TempoMap.constant(QUARTER_BPM, TimeSignature.FOUR_FOUR);
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(map, 3 + thirds(1), thirds(1), "G4"),
                note(map, 3 + thirds(2), thirds(1), "A4"),
                note(map, 4, 4, "C5"),
                note(map, 8, 4, "E5")), Confidence.CERTAIN);
        Score score = Score.empty(map, 12 / (QUARTER_BPM / 60))
                .withTrack(voice)
                .withChords(new ChordProgression(List.of(
                        chord(map, "C4", ChordQuality.MAJOR, 0, 4),
                        chord(map, "F4", ChordQuality.MAJOR, 4, 8),
                        chord(map, "G4", ChordQuality.DOMINANT_SEVENTH, 8, 12)),
                        Confidence.of(0.9)));
        GridResolution[] perBar = {
            GridResolution.THIRD_BEAT, GridResolution.BEAT, GridResolution.BEAT};
        List<BarGrid> grids = new ArrayList<>();
        for (int bar = 0; bar < perBar.length; bar++) {
            grids.add(new BarGrid(bar, bar * 4.0, perBar[bar], TimeSignature.FOUR_FOUR));
        }
        return new QuantizedScore(score, grids, SwingFeel.STRAIGHT);
    }

    /** The triplet pickup, with words, the first sung on the note that opens it. */
    private static QuantizedScore withTripletPickupAndLyrics() {
        TempoMap map = TempoMap.constant(QUARTER_BPM, TimeSignature.FOUR_FOUR);
        String[] sung = {"sung", "on", "time"};
        double[] at = {3 + thirds(1), 4, 8};
        double[] until = {4, 8, 12};
        List<LyricWord> words = new ArrayList<>();
        for (int i = 0; i < sung.length; i++) {
            words.add(LyricWord.ofSeconds(sung[i], map.beatsToSeconds(at[i]),
                    map.beatsToSeconds(until[i]), Confidence.CERTAIN));
        }
        QuantizedScore quantized = withTripletPickup();
        return new QuantizedScore(quantized.score().withLyrics(
                new Lyrics(List.of(new LyricLine(words, Confidence.CERTAIN)),
                        "en", Confidence.CERTAIN)),
                quantized.grids(), quantized.swing());
    }

    private static NoteTrack melodyOf(QuantizedScore quantized) {
        return quantized.score().track(PartRole.LEAD_VOCAL).orElseThrow();
    }

    /** The bars of a context, as the bar checks delimit them. */
    private static List<String> barsOf(String block) {
        List<String> bars = new ArrayList<>();
        for (String rawLine : block.split("\n")) {
            String line = rawLine.trim();
            if (line.endsWith("|") && !line.startsWith("\\bar")) {
                bars.add(line.substring(0, line.length() - 1).trim());
            }
        }
        return bars;
    }

    /** One context of the score, from its opening line to the next one's. */
    private static String context(String source, String opening) {
        int from = source.indexOf(opening);
        assertThat(from).as("no %s in the source", opening).isNotNegative();
        int next = source.indexOf("  \\new ", from + opening.length());
        int end = next >= 0 ? next : source.indexOf("  >>", from);
        return source.substring(from, end < 0 ? source.length() : end);
    }

    @Test
    @DisplayName("opens the chord names on the same bar the staff's pickup opens")
    void chordNamesHonourThePickup() {
        QuantizedScore quantized = withPickup();

        String source = LeadSheet.toLilyPond(quantized, melodyOf(quantized));

        // The staff shortens bar one, and a \partial moves the score's shared
        // timing, so the chord names have to be one quarter shorter too. Written
        // as the durations rather than as a bar count, because a bar count is
        // exactly what stays right while the bars slide apart.
        assertThat(context(source, "\\new Staff")).contains("\\partial 4");
        assertThat(barsOf(context(source, "\\new ChordNames")))
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .as("the chord names' first bar must fill the pickup and no more")
                .isEqualTo("c4");
    }

    @Test
    @DisplayName("cuts the chord names to a pickup no number of 64ths can write")
    void chordNamesFollowATripletPickup() {
        QuantizedScore quantized = withTripletPickup();

        // Two triplet eighths is a sixth of a whole note. Carried to the chord
        // names as a double and subtracted from the bar, every remaining chord
        // became a length LilyPond has no duration for -- and LilyPondDuration
        // throws rather than writing one, so `render` exited non-zero on three
        // of the nine committed packages.
        String source = LeadSheet.toLilyPond(quantized, melodyOf(quantized));

        assertThat(context(source, "\\new Staff")).contains("\\partial 1*1/6");
        assertThat(barsOf(context(source, "\\new ChordNames")))
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .isEqualTo("c1*1/6");
    }

    @Test
    @DisplayName("drops a lead-in cell that lies wholly before the pickup")
    void dropsACellEntirelyOutsideThePickup() {
        TempoMap map = TempoMap.constant(QUARTER_BPM, TimeSignature.FOUR_FOUR);
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(map, 3, 1, "G4"),
                note(map, 4, 4, "C5"),
                note(map, 8, 4, "E5")), Confidence.CERTAIN);
        // The harmony starts a beat into the bar, so the chart's first bar holds
        // two cells: a lead-in rest and the chord. With a one-beat pickup the
        // rest lies wholly outside it and the chord straddles its edge, which is
        // the pair of branches -- drop, and shorten -- that cutting a bar down
        // to a pickup has.
        Score score = Score.empty(map, 12 / (QUARTER_BPM / 60))
                .withTrack(voice)
                .withChords(new ChordProgression(List.of(
                        chord(map, "C4", ChordQuality.MAJOR, 1, 4),
                        chord(map, "F4", ChordQuality.MAJOR, 4, 8),
                        chord(map, "G4", ChordQuality.DOMINANT_SEVENTH, 8, 12)),
                        Confidence.of(0.9)));
        QuantizedScore quantized = Quantizer.quantize(score);

        assertThat(barsOf(context(ChordChart.toLilyPond(quantized.score()), "\\new ChordNames")))
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .as("the chart prints the lead-in as a rest, which is the cell being dropped")
                .isEqualTo("r4 c2.");

        String source = LeadSheet.toLilyPond(quantized, melodyOf(quantized));

        assertThat(context(source, "\\new Staff")).contains("\\partial 4");
        assertThat(barsOf(context(source, "\\new ChordNames")))
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .as("the rest is gone entirely and the chord keeps only the pickup")
                .isEqualTo("c4");
    }

    @Test
    @DisplayName("gives the chord names as many bars as the staff")
    void theTwoContextsAgreeOnTheBarCount() {
        QuantizedScore quantized = withPickup();

        String source = LeadSheet.toLilyPond(quantized, melodyOf(quantized));

        assertThat(barsOf(context(source, "\\new ChordNames")))
                .hasSameSizeAs(barsOf(context(source, "\\new Staff")));
    }

    @Test
    @DisplayName("prints the tempo once, on the staff")
    void theTempoIsNotPrintedTwice() {
        QuantizedScore quantized = withPickup();

        String source = LeadSheet.toLilyPond(quantized, melodyOf(quantized));

        assertThat(source.split("\\\\tempo", -1).length - 1)
                .as("one tempo mark, in one context")
                .isEqualTo(1);
        assertThat(context(source, "\\new Staff")).contains("\\tempo");
    }

    @Test
    @DisplayName("attaches the words to the staff above them, not to the chords")
    void lyricsLeanOnTheStaff() {
        QuantizedScore quantized = withLyrics();

        String source = LeadSheet.toLilyPond(quantized, melodyOf(quantized));

        // #UP, because the Lyrics context sits below a real staff here. In the
        // chart it is #DOWN and both are right; getting it wrong is a LilyPond
        // complaint on every run, into output this tool reads to decide whether
        // engraving went well.
        assertThat(context(source, "\\new Lyrics"))
                .contains("\\override VerticalAxisGroup.staff-affinity = #UP");
        assertThat(source).contains("\\lyricmode");
    }

    @Test
    @DisplayName("opens the words on the same bar the staff's pickup opens")
    void lyricsHonourThePickup() {
        QuantizedScore quantized = withPickupAndLyrics(3);

        String source = LeadSheet.toLilyPond(quantized, melodyOf(quantized));

        assertThat(context(source, "\\new Staff")).contains("\\partial 4");
        assertThat(barsOf(context(source, "\\new Lyrics")))
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .as("the words' first bar must fill the pickup and no more")
                .isEqualTo("\"one\"4");
        assertThat(barsOf(context(source, "\\new Lyrics")))
                .hasSameSizeAs(barsOf(context(source, "\\new Staff")));
    }

    @Test
    @DisplayName("keeps a word sung before the staff enters, on the pickup")
    void aWordBeforeThePickupIsPushedIntoIt() {
        QuantizedScore quantized = withPickupAndLyrics(1);

        String source = LeadSheet.toLilyPond(quantized, melodyOf(quantized));

        // A syllable is an event, so it cannot be cut the way the chord names
        // cut a chord that was already sounding: the page is sung from.
        assertThat(barsOf(context(source, "\\new Lyrics")))
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .isEqualTo("\"one\"4");
    }

    @Test
    @DisplayName("pays a pickup no number of 64ths can write with a leading rest")
    void lyricsFollowATripletPickup() {
        QuantizedScore quantized = withTripletPickupAndLyrics();

        String source = LeadSheet.toLilyPond(quantized, melodyOf(quantized));

        // The lane opens on the nearest grid unit inside the pickup and a
        // leading rest pays the rest of it, the two summing to the \partial.
        assertThat(context(source, "\\new Staff")).contains("\\partial 1*1/6");
        assertThat(barsOf(context(source, "\\new Lyrics")))
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .isEqualTo("\\skip 1*1/96 \"sung\"1*5/32");
    }

    @Test
    @DisplayName("leaves a lead sheet with no pickup alone")
    void lyricsWithoutAPickupOpenOnBarOne() {
        QuantizedScore quantized = withLyrics();

        String source = LeadSheet.toLilyPond(quantized, melodyOf(quantized));

        assertThat(context(source, "\\new Staff")).doesNotContain("\\partial");
        assertThat(barsOf(context(source, "\\new Lyrics")))
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .isEqualTo("\"one\"2 \"two\"2");
    }

    @Test
    @DisplayName("the whole page, so the three lanes can be read against each other")
    void theWholePageWithAPickupAndWords() {
        QuantizedScore quantized = withPickupAndLyrics(3);

        Goldens.assertGolden("lead-sheet-pickup-lyrics",
                LeadSheet.toLilyPond(quantized, melodyOf(quantized)));
    }

    @Test
    @DisplayName("keeps the chart's own lanes pointing down")
    void theChartIsUnchanged() {
        QuantizedScore quantized = withLyrics();

        String chart = LyricSheet.toLilyPond(quantized.score(), ChartOptions.defaults());

        assertThat(context(chart, "\\new Lyrics"))
                .contains("\\override VerticalAxisGroup.staff-affinity = #DOWN");
    }

    @Test
    @DisplayName("engraves melody, chords and words in one score")
    void holdsAllThreeBlocks() {
        QuantizedScore quantized = withLyrics();

        String source = LeadSheet.toLilyPond(quantized, melodyOf(quantized));

        assertThat(source)
                .contains("\\new ChordNames")
                .contains("\\new Staff")
                .contains("\\new Lyrics")
                .contains("\\score {")
                .contains("  <<\n")
                .contains("  >>\n");
        assertThat(source.indexOf("\\new ChordNames"))
                .as("chord names above the staff")
                .isLessThan(source.indexOf("\\new Staff"));
        assertThat(source.indexOf("\\new Staff"))
                .as("words below the staff")
                .isLessThan(source.indexOf("\\new Lyrics"));
    }
}
