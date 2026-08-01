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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChordChartTest {

    private static PitchSpelling root(NoteLetter letter) {
        return new PitchSpelling(letter, Accidental.NATURAL, 4);
    }

    /**
     * The lines of a chart's {@code \chordmode} block, trimmed and non-blank.
     *
     * <p>Everything this class decides is in that block; the rest of the file is
     * fixed text. Asserting on its lines rather than on a substring of the whole
     * is what makes a missing bar check, a stray extra bar or a lost {@code
     * \time} a failure instead of something a {@code contains} walks past --
     * which is how #64, #160 and #174 all survived a suite that read the
     * emitter's output.
     */
    private static List<String> chordModeOf(String source) {
        int open = source.indexOf("\\chordmode {");
        int close = source.indexOf("\n    }", open);
        return Arrays.stream(source.substring(source.indexOf('\n', open) + 1, close).split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /**
     * One chordmode duration in quarter beats, read back out of the emitted
     * token.
     *
     * <p>Deliberately a second implementation rather than a call into
     * {@link LilyPondDuration}: the question being asked is whether the bars the
     * emitter <em>wrote</em> add up, and asking the code that wrote them would
     * answer a different question. LilyPond answers this one for real in
     * {@code ChordChartEngravingIT}; this is the fast half of the same check.
     */
    private static double quarterBeatsOf(String token) {
        Matcher matcher = DURATION.matcher(token);
        assertThat(matcher.find()).as("a duration in %s", token).isTrue();
        double quarters = 4.0 / Integer.parseInt(matcher.group(1));
        if (!matcher.group(2).isEmpty()) {
            quarters *= 1.5;
        }
        if (matcher.group(3) != null) {
            quarters *= Integer.parseInt(matcher.group(3));
        }
        if (matcher.group(4) != null) {
            quarters /= Integer.parseInt(matcher.group(4));
        }
        return quarters;
    }

    /** A chordmode token's duration: a root or rest, then {@code 4.} or {@code 1*5/4}. */
    private static final Pattern DURATION =
            Pattern.compile("^(?:r|[a-g](?:es|is)*)(\\d+)(\\.?)(?:\\*(\\d+)(?:/(\\d+))?)?");

    /**
     * Asserts that every bar the emitter wrote holds exactly the meter it
     * declared, which is the arithmetic a bar check tests.
     *
     * @return the number of bars, so a caller can compare it with the text chart
     */
    private static int assertBarsFillTheirMeter(String source) {
        double barQuarters = 0;
        int bars = 0;
        for (String line : chordModeOf(source)) {
            Matcher meter = METER.matcher(line);
            if (meter.find()) {
                barQuarters = Integer.parseInt(meter.group(1)) * 4.0
                        / Integer.parseInt(meter.group(2));
                continue;
            }
            assertThat(line).as("every bar ends with a bar check").endsWith("|");
            double sum = 0;
            for (String token : line.substring(0, line.length() - 1).trim().split(" +")) {
                sum += quarterBeatsOf(token);
            }
            assertThat(sum).as("bar %d of %s", bars + 1, line).isEqualTo(barQuarters);
            bars++;
        }
        assertThat(barQuarters).as("the chart states a meter").isGreaterThan(0);
        return bars;
    }

    /** The declared meter, whether or not it carries a beat structure. */
    private static final Pattern METER =
            Pattern.compile("^\\\\time (?:#'\\([\\d ]+\\) )?(\\d+)/(\\d+)$");

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

        // One whole note each, one bar to a line, each bar closed by a bar check.
        // By duration in seconds the last two chords run to more than a bar and
        // a half of the averaged bar and are each written twice, giving a
        // six-bar score for four bars of music.
        assertThat(chordModeOf(source))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "c1 |", "g1 |", "a1:m |", "f1 |");
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
        // Four half notes and four whole notes: sixteen quarter beats, which is
        // the four bars the chart shows. Written as five whole notes -- which is
        // what a fixed duration gives -- LilyPond engraves six bars, and every
        // symbol from bar 2 on sits against the wrong bar of the recording. The
        // final chord is written once per bar it holds rather than as one
        // two-bar duration, so that every bar line carries a check; chordChanges
        // is what keeps its name from being printed twice.
        assertThat(chordModeOf(ChordChart.toLilyPond(score)))
                .containsExactly("\\time #'(1 1 1 1) 4/4",
                        "c2 a2:m |", "f2 g2 |", "c1 |", "c1 |");
    }

    /** One bar of one chord, quantized, so a quality can be engraved on its own. */
    private static Score oneChord(ChordQuality quality, PitchSpelling bass) {
        return oneChord(root(NoteLetter.C), quality, bass);
    }

    private static Score oneChord(PitchSpelling chordRoot, ChordQuality quality,
                                  PitchSpelling bass) {
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);
        Chord chord = new Chord(chordRoot, quality, Optional.ofNullable(bass),
                0, 2, Optional.of(0.0), Optional.of(4.0), Confidence.of(0.9));
        return Score.empty(map, 2)
                .withChords(new ChordProgression(List.of(chord), Confidence.of(0.9)));
    }

    /**
     * Every quality the model has, and what chordmode calls it.
     *
     * <p>The rows are listed rather than derived, because the whole point is to
     * state independently what each quality should engrave as. What stops the
     * list going stale is elsewhere and in two places: the emitter's switch has
     * no {@code default}, so a new quality fails to compile, and
     * {@code noQualityIsUnaccountedFor} fails if one is added without a row
     * here. The tokens are not guesses: each was engraved with LilyPond
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

    @ParameterizedTest(name = "a chord rooted on {0} is engraved as {1}")
    @CsvSource({"Eb4, ees1", "Bb4, bes1", "F#4, fis1", "C4, c1"})
    @DisplayName("engraves an accidental in a chord root, not just in a bass")
    void aRootKeepsItsAccidental(String spelling, String expected) {
        // Dropping the accidental from a root is what a hand-rolled LilyPond
        // note name does, and a chart in E flat then prints Eb and engraves E --
        // three different chords on a four-bar page. The root and the bass are
        // separate call sites, and until this test only the bass had one:
        // everything else in this file is rooted on a natural C.
        //
        // The roots come from the file's own key signature by way of
        // SymbolicChordEstimator, so any MIDI import in a flat or sharp key
        // reaches this.
        assertThat(ChordChart.toLilyPond(
                        oneChord(PitchSpelling.parse(spelling), ChordQuality.MAJOR, null)))
                .contains(expected + " ");
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
        assertThat(chordModeOf(ChordChart.toLilyPond(score)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "c1 |", "f1 |", "d1:m |");
    }

    @Test
    @DisplayName("prints a tempo the user can type back in, in any locale")
    void tempoLineIsLocaleIndependent() {
        // The chart's tempo is meant to be typed back into --tempo, which
        // picocli parses with Double.valueOf. ar_EG is the iteration that
        // discriminates: it prints the figure in Arabic-Indic digits, which
        // that rejects. The other two reproduce nothing today and are kept for
        // different reasons -- fr_FR would catch a later edit to %.1f, the
        // decimal comma this comment used to blame it for and which %.0f
        // cannot produce; hi_IN formats %.0f, %.1f and %d in ASCII on this
        // JDK's locale data, so it is a locale in the list rather than a case
        // under test. Round 3 of review measured all three.
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


    // ---------------------------------------------------------------- #160 --

    @Test
    @DisplayName("closes every bar with a bar check, so LilyPond can contradict the arithmetic")
    void everyBarCarriesABarCheck() {
        // The chart's bar arithmetic used to be the one thing LilyPond could not
        // argue with: a \chordmode sequence with no \time and no | performs no
        // bar check, so an emitter that made every bar half a bar engraved a
        // clean page. #164 measured exactly that -- the whole suite stayed green
        // against a deliberately halved emitter.
        String source = ChordChart.toLilyPond(fourChordSong(2));

        List<String> lines = chordModeOf(source);
        assertThat(lines.get(0)).as("the meter is stated before any bar").startsWith("\\time ");
        assertThat(lines.subList(1, lines.size()))
                .allSatisfy(line -> assertThat(line).endsWith(" |"));
        assertThat(assertBarsFillTheirMeter(source)).isEqualTo(8);
    }

    @Test
    @DisplayName("never begins a line with a bar check")
    void noEmittedLineBeginsWithABarCheck() {
        // Not a style rule. LilyPondComplaints tells a diagnostic apart from
        // LilyPond's echo of the offending source line partly by the echo's
        // column, and a source line that *starts* with a bar check is the case
        // it cannot resolve -- so its javadoc records that no emitter here
        // produces one. That was trivially true of a chart writing no bar checks
        // at all; #160 made it a property worth holding rather than a
        // consequence of not having the feature.
        for (Score score : List.of(fourChordSong(2), aWaltz(), aJig(),
                twoChordsInABar(), quantizedAcrossATempoChange(), clickTrackPhasedAt(2),
                eightChordsAtAForcedTempo())) {
            assertThat(ChordChart.toLilyPond(score).lines().toList())
                    .allSatisfy(line -> assertThat(line.trim()).doesNotStartWith("|"));
        }
    }

    @Test
    @DisplayName("writes as many engraved bars as the text chart prints")
    void theTextAndTheEngravingCountTheSameBars() {
        // The two outputs are two readings of one layout now. Before that they
        // were two derivations, and #174 caught them four bars apart on the same
        // score -- the text printing one bar and the page eight.
        for (Score score : List.of(fourChordSong(3), twoChordsInABar(),
                quantizedAcrossATempoChange(), aWaltz(), aJig(), eightChordsAtAForcedTempo())) {
            int printed = ChordChart.barLines(score).stream()
                    .mapToInt(line -> (int) line.chars().filter(c -> c == '|').count() - 1)
                    .sum();
            assertThat(assertBarsFillTheirMeter(ChordChart.toLilyPond(score)))
                    .as("engraved bars against printed bars for %s",
                            ChordChart.barLines(score))
                    .isEqualTo(printed);
        }
    }

    @Test
    @DisplayName("names a chord held over a bar line once, as the text chart does")
    void aHeldChordIsNamedOnce() {
        // \chordmode reprints a repeated chord name by default, so splitting a
        // held chord at every bar line -- which the bar checks require -- would
        // put "C C C" on a page whose text chart says "| C | % | % |".
        Score held = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 8.0)
                .withChords(new ChordProgression(List.of(
                        Chord.ofSeconds(root(NoteLetter.C), ChordQuality.MAJOR,
                                0, 8.0, Confidence.of(0.9))), Confidence.of(0.9)));

        assertThat(ChordChart.toLilyPond(held)).contains("chordChanges = ##t");
        assertThat(ChordChart.barLines(held))
                .containsExactly("| C           | %           | %           | %           |");
        assertThat(chordModeOf(ChordChart.toLilyPond(held)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "c1 |", "c1 |", "c1 |", "c1 |");
    }

    // ----------------------------------------------------------------- #64 --

    /** Four bars of 3/4 at 120 BPM, one chord each: a bar is 1.5 seconds. */
    private static Score aWaltz() {
        List<Chord> chords = new ArrayList<>();
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        for (int i = 0; i < 4; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                    i * 1.5, i * 1.5 + 1.5, Confidence.of(0.9)));
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.THREE_FOUR), 6.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /** Four bars of 6/8 at a dotted-quarter pulse of 0.5s: a bar is one second. */
    private static Score aJig() {
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
        return Score.empty(TempoMap.fromBeatTimes(pulses, TimeSignature.SIX_EIGHT), 4.0)
                .withBeatGrid(BeatGrid.ofTimes(pulses, TimeSignature.SIX_EIGHT, Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    @Test
    @DisplayName("engraves a 3/4 chart in 3/4, with bars a third shorter than a 4/4 one")
    void aWaltzIsEngravedInThreeFour() {
        // Every chart used to come out as whole notes with no \time at all, so a
        // 3/4 song engraved in 4/4 with bars a third too long. Invisible while
        // 4/4 was the hardcoded prior -- which is the one meter that was right.
        assertThat(chordModeOf(ChordChart.toLilyPond(aWaltz())))
                .containsExactly("\\time #'(1 1 1) 3/4", "c2. |", "g2. |", "a2. |", "f2. |");
    }

    @Test
    @DisplayName("engraves a 6/8 chart in 6/8, grouped as two dotted quarters")
    void aJigIsEngravedInSixEight() {
        // A 6/8 bar holds three quarter beats, so its whole-bar chord is a
        // dotted half and not a whole note. The grouping comes from the model's
        // own beatStructure(), which is why it reads #'(3 3) and not #'(1 1 1
        // 1 1 1): 6/8 is felt in two, and that is the entire difference from
        // 3/4, which this chart shares a bar length with.
        assertThat(chordModeOf(ChordChart.toLilyPond(aJig())))
                .containsExactly("\\time #'(3 3) 6/8", "c2. |", "g2. |", "a2. |", "f2. |");
    }

    @Test
    @DisplayName("heads the chart with the meter of its own first bar")
    void theHeaderNamesTheMeterTheChartOpensIn() {
        // The header used to read initialTimeSignature(), which is the piece's
        // meter and not the chart's. Where the harmony starts after a meter
        // change the two differ, and the header was then naming a meter no bar
        // of the chart is in -- and handing a reader a metronome mark 50% fast,
        // because tempoLine reads the same meter to decide whether the counted
        // beat is a quarter. That is the failure tempoLine exists to prevent,
        // reached by the other door.
        //
        // Not reachable through MidiTranscriber today, because
        // SymbolicChordEstimator spans from beat 0 and leading silence becomes
        // an N.C. chord, so a chart always opens on the piece's bar 0. It is one
        // estimator change away, and it is reachable through the public API now.
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR)
                .withMeterChange(1, TimeSignature.SIX_EIGHT);
        List<Chord> chords = List.of(
                Chord.ofSeconds(root(NoteLetter.C), ChordQuality.MAJOR,
                                map.beatsToSeconds(4), map.beatsToSeconds(7), Confidence.of(0.9))
                        .quantizedTo(4, 7),
                Chord.ofSeconds(root(NoteLetter.G), ChordQuality.MAJOR,
                                map.beatsToSeconds(7), map.beatsToSeconds(10), Confidence.of(0.9))
                        .quantizedTo(7, 10));
        Score score = Score.empty(map, map.beatsToSeconds(10))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        // Every bar of this chart is 6/8, so the header says 6/8 and qualifies
        // the tempo: 120 quarter notes a minute is 80 dotted quarters. Off
        // initialTimeSignature() it said 4/4 and printed an unqualified
        // "Tempo  120 BPM" over bars a musician counts at 80 -- a metronome
        // marking half again too fast.
        assertThat(ChordChart.toText(score))
                .contains("Meter  6/8")
                .contains("Tempo  80 BPM (120 quarter notes/min)")
                .doesNotContain("Meter  4/4");
        assertThat(chordModeOf(ChordChart.toLilyPond(score)))
                .containsExactly("\\time #'(3 3) 6/8", "c2. |", "g2. |");
        // The engraved mark is counted in the chart's meter for the same
        // reason and by the same rule: off the piece's it would read "4 = 120"
        // over bars counted at 80.
        assertThat(ChordChart.toLilyPond(score))
                .contains("\\tempo \\markup { \\italic \"ca.\" } 4. = 80");
    }

    @Test
    @DisplayName("states a meter change where it happens, and nowhere else")
    void aMeterChangeIsStatedOnceWhereItFalls() {
        // MIDI import emits meter changes (#66), and the quantized route bars by
        // the tempo map, so the engraving has to restate the meter mid-chart or
        // the bar checks it now carries would fail on the first bar of the new
        // meter.
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR).withMeterChange(2,
                TimeSignature.THREE_FOUR);
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        double[] starts = {0, 4, 8, 11};
        double[] ends = {4, 8, 11, 14};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < starts.length; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                            map.beatsToSeconds(starts[i]), map.beatsToSeconds(ends[i]),
                            Confidence.of(0.9))
                    .quantizedTo(starts[i], ends[i]));
        }
        Score score = Score.empty(map, map.beatsToSeconds(14))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(chordModeOf(ChordChart.toLilyPond(score)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "c1 |", "g1 |",
                        "\\time #'(1 1 1) 3/4", "a2. |", "f2. |");
        assertBarsFillTheirMeter(ChordChart.toLilyPond(score));
    }

    // ----------------------------------------------------------------- #83 --

    /** A 4/4 click track whose downbeats are forced onto one of the four phases. */
    private static Score clickTrackPhasedAt(int phase) {
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            int position = Math.floorMod(i - phase, 4);
            beats.add(new BeatGrid.Beat(i * 0.5, position == 0, position));
        }
        List<Chord> chords = new ArrayList<>();
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        for (int i = 0; i < 4; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                    i * 2.0, i * 2.0 + 2.0, Confidence.of(0.9)));
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 12.0)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    @Test
    @DisplayName("draws the bar lines on the grid's downbeats, so --first-downbeat is visible")
    void theBarLinesFollowTheGridsDownbeats() {
        // Measured on a click track before this change, forcing each of the four
        // 4/4 phases in turn: the text chart, the LilyPond and the PDF were
        // byte-identical whichever beat the user nominated, and only score.json
        // moved. --first-downbeat was honest in the model and invisible in every
        // artefact -- and CLAUDE.md calls correcting the downbeat by hand the
        // highest-value action a user has.
        //
        // The engraving is where the phase becomes visible, and it is asserted
        // separately from the text for a reason round 1 of review measured: the
        // text chart prints chord *names* and not cell lengths, so it shows that
        // the harmony starts inside the first bar and cannot show by how much.
        // Phases 2 and 3 give the same text and different pages. Asserting the
        // pair jointly would pass on the engraving alone and read as more than
        // it proves. See #186.
        List<String> engraved = new ArrayList<>();
        for (int phase = 0; phase < 4; phase++) {
            engraved.add(ChordChart.toLilyPond(clickTrackPhasedAt(phase)));
        }
        assertThat(engraved).doesNotHaveDuplicates();

        // In phase, the harmony fills its bars exactly; out of phase it does not,
        // and the chart says so rather than quietly re-phasing itself onto the
        // chords -- which is the only way a reader learns to reach for the flag.
        assertThat(ChordChart.barLines(clickTrackPhasedAt(0)))
                .containsExactly("| C           | G           | A           | F           |");
        assertThat(chordModeOf(engraved.get(0)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "c1 |", "g1 |", "a1 |", "f1 |");
        assertThat(ChordChart.barLines(clickTrackPhasedAt(2)).get(0))
                .startsWith("| N.C. C");
        // Half a bar out: every chord straddles a bar line, which is what a
        // reader has to see to know the phase is wrong. Four bars still, because
        // the last half-bar of F does not fill more than half of a fifth one.
        assertThat(chordModeOf(engraved.get(2)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "r2 c2 |", "c2 g2 |", "g2 a2 |",
                        "a2 f2 |");
        for (String source : engraved) {
            assertBarsFillTheirMeter(source);
        }
    }

    @Test
    @DisplayName("falls back to the first chord when the grid marks no downbeat")
    void anUnphasedGridLeavesTheChartOnTheHarmony() {
        // Beat tracking can produce pulses without deciding which begins a bar,
        // and Beat.unphased is how the model says so. There is no phase to
        // honour then, so the chart anchors on the harmony as it always did.
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            beats.add(BeatGrid.Beat.unphased(0.25 + i * 0.5));
        }
        List<Chord> chords = new ArrayList<>();
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        for (int i = 0; i < 4; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                    1.0 + i * 2.0, 3.0 + i * 2.0, Confidence.of(0.9)));
        }
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 12.0)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(score.beatGrid().orElseThrow().downbeatTimes()).isEmpty();
        assertThat(ChordChart.barLines(score))
                .containsExactly("| C           | G           | A           | F           |");
    }

    @Test
    @DisplayName("charts a score with no beat grid at all, which is the MIDI path")
    void aScoreWithNoGridStillCharts() {
        // MIDI import leaves beatGrid() empty (#98) and quantizes instead, so the
        // downbeat anchor must not be the only route to a bar line.
        Score score = quantizedAcrossATempoChange();

        assertThat(score.beatGrid()).isEmpty();
        assertThat(ChordChart.barLines(score))
                .containsExactly("| C           | G           | Am          | F           |");
    }

    @Test
    @DisplayName("steps the anchor back when every downbeat follows the first chord")
    void aGridStartingAfterTheHarmonyStillAnchorsOnItsPhase() {
        // A grid whose first tracked downbeat is a bar and a half into the piece
        // still states a phase, and the chart has to start at or before the first
        // chord or that chord has no bar to sit in.
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int position = i % 4;
            beats.add(new BeatGrid.Beat(3.0 + i * 0.5, position == 0, position));
        }
        List<Chord> chords = List.of(
                Chord.ofSeconds(root(NoteLetter.C), ChordQuality.MAJOR, 0, 2, Confidence.of(0.9)),
                Chord.ofSeconds(root(NoteLetter.G), ChordQuality.MAJOR, 2, 4, Confidence.of(0.9)));
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 8.0)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        // The anchor is 3.0 stepped back two bars to -1.0, so the harmony starts
        // one beat into bar 1 and keeps the grid's phase.
        assertThat(ChordChart.barLines(score))
                .containsExactly("| N.C. C      | G           |");
        assertBarsFillTheirMeter(ChordChart.toLilyPond(score));
    }

    // ---------------------------------------------------------------- #174 --

    /** Eight chords a half-beat apart under a corrected {@code --tempo 60}. */
    private static Score eightChordsAtAForcedTempo() {
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            chords.add(Chord.ofSeconds(root(roots[i % 4]), ChordQuality.MAJOR,
                    i * 0.5, i * 0.5 + 0.5, Confidence.of(0.9)));
        }
        return Score.empty(TempoMap.constantPulse(60, TimeSignature.FOUR_FOUR), 4.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    @Test
    @DisplayName("prints a final chord that stops before the bar line")
    void aFinalChordShorterThanItsBarIsStillPrinted() {
        // Two roundings, taken independently: which bar each chord started in,
        // and how many bars the chart had. G rounded into bar 1 while the bar
        // count rounded to 1, and the guard that was there to keep the index in
        // range deleted it -- silently, from an ordinary final chord.
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 2.0)
                .withChords(new ChordProgression(List.of(
                        Chord.ofSeconds(root(NoteLetter.C), ChordQuality.MAJOR,
                                0.0, 1.5, Confidence.of(0.9)),
                        Chord.ofSeconds(root(NoteLetter.G), ChordQuality.MAJOR,
                                1.5, 2.0, Confidence.of(0.9))), Confidence.of(0.9)));

        assertThat(ChordChart.barLines(score)).containsExactly("| C G         |");
        assertThat(chordModeOf(ChordChart.toLilyPond(score)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "c2. g4 |");
    }

    @Test
    @DisplayName("keeps all eight chords when a forced tempo puts them on half beats")
    void everyChordSurvivesAForcedTempo() {
        // Reachable today: at --tempo 60 on 120 BPM material the model holds all
        // eight chords and the chart printed four, crammed into one bar, while
        // the engraving wrote eight whole notes -- eight bars of music for four
        // seconds of it, and the two outputs disagreeing on their face.
        Score score = eightChordsAtAForcedTempo();

        assertThat(score.chords().chords()).hasSize(8);
        assertThat(ChordChart.barLines(score)).containsExactly("| C G A F C G A F|");
        assertThat(chordModeOf(ChordChart.toLilyPond(score)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "c8 g8 a8 f8 c8 g8 a8 f8 |");
    }

    @Test
    @DisplayName("an ornamental sub-beat chord does not push the others out of their bars")
    void anOrnamentalChordKeepsTheRestInPlace() {
        // C 0..1, G 1..1.1, A 1.1..2, F 2..3, C 3..4 at 120 BPM. The final C --
        // a full-beat chord, perfectly placeable -- used to be dropped, and A
        // and F printed in a bar they do not sound in.
        double[][] spans = {{0, 1}, {1, 1.1}, {1.1, 2}, {2, 3}, {3, 4}};
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A,
                NoteLetter.F, NoteLetter.C};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < spans.length; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                    spans[i][0], spans[i][1], Confidence.of(0.9)));
        }
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 4.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(ChordChart.barLines(score))
                .containsExactly("| C G A       | F C         |");
        assertBarsFillTheirMeter(ChordChart.toLilyPond(score));
    }

    @Test
    @DisplayName("prints every chord the model holds, in order, whatever they land on")
    void noChordIsEverDropped() {
        // The property the guard at barLines used to break. Stated over the
        // awkward cases rather than one of them, because each of the three
        // reproductions in #174 lost a different chord for a different reason.
        for (Score score : List.of(eightChordsAtAForcedTempo(), aWaltz(), aJig(),
                twoChordsInABar(), quantizedAcrossATempoChange(), clickTrackPhasedAt(2),
                fourChordSong(3))) {
            String printed = String.join(" ", ChordChart.barLines(score));
            int at = 0;
            for (Chord chord : score.chords().chords()) {
                int found = printed.indexOf(chord.symbol(), at);
                assertThat(found).as("%s in %s", chord.symbol(), printed).isNotNegative();
                at = found;
            }
        }
    }

    @Test
    @DisplayName("draws a chart on the beat when the chords only ever change on one")
    void aChartChangingOnceABarIsDrawnOnTheBeat() {
        // Which grid the seconds route snaps to is a claim about how precisely
        // an estimate is worth believing, and a fixed sixteenth claimed too
        // much. Measured on samples/gmajorblues.mp3, where one downbeat -- the
        // fourth -- was detected 0.18s early against a 2.25s bar. Past tense
        // since #3: the beat times are unchanged but the downbeat phase moved a
        // beat and that recording's worst deviation fell to 0.017s, so it no
        // longer demonstrates the case. See ChartLayout, which carries the
        // measurement and says the same thing at more length. At that
        // recording's tempo a sixteenth grid moves a chord by at most 0.070s, so
        // that chord printed in the previous bar, where a beat grid moves it by
        // up to 0.282s and it does not. Both tolerances are that recording's;
        // quoting one of them at 120 BPM and the other at 106.6 is how this
        // comment read until round 8.
        //
        // This fixture is that shape in miniature: four chords a bar apart at
        // 120 BPM, the third detected 0.16s early -- 0.32 of a beat, which a
        // sixteenth grid rounds to 0.25 of a beat before its bar line and a
        // beat grid puts on the bar line where it belongs.
        double[] starts = {0.0, 2.0, 3.84, 6.0};
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < starts.length; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR, starts[i],
                    i + 1 < starts.length ? starts[i + 1] : 8.0, Confidence.of(0.9)));
        }
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 8.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(ChordChart.barLines(score))
                .containsExactly("| C           | G           | A           | F           |");
        assertThat(chordModeOf(ChordChart.toLilyPond(score)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "c1 |", "g1 |", "a1 |", "f1 |");
    }

    @Test
    @DisplayName("keeps a chord heard just before the first downbeat out of the way of the rest")
    void aChordHeardBeforeTheFirstDownbeatDoesNotShuntTheOthers() {
        // The anchor may not move a chord further than the snapping will, and
        // for one round it could. The chart anchored within half a counted beat
        // of the first chord while snapping on a grid that can be much finer, so
        // a first chord heard just before its downbeat anchored on that
        // downbeat, snapped to a negative position, was clamped to zero, and
        // pushed every chord behind it one grid step along -- into the next bar.
        //
        // Nine chords, the first heard 0.2s before the downbeat at 2.0s and the
        // other eight inside the bar it opens. They changed on half beats, so
        // the chart is drawn on half beats and the anchor may overshoot by only
        // a quarter beat: too little to reach 2.0, so the chart opens at 0.0 and
        // all nine keep their places. Under the mismatched tolerance the ninth
        // was printed alone in a bar it does not sound in.
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            beats.add(new BeatGrid.Beat(i * 0.5, i % 4 == 0, i % 4));
        }
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.D, NoteLetter.E, NoteLetter.F,
                NoteLetter.G, NoteLetter.A, NoteLetter.B, NoteLetter.C, NoteLetter.D};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < roots.length; i++) {
            double from = 1.8 + i * 0.25;
            chords.add(Chord.ofSeconds(root(roots[i]),
                    i % 2 == 0 ? ChordQuality.MAJOR : ChordQuality.MINOR,
                    from, from + 0.25, Confidence.of(0.9)));
        }
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 8.0)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        // The ninth chord is heard at 3.8s, inside the bar that runs 2.0..4.0.
        assertThat(ChordChart.barLines(score))
                .containsExactly("| N.C. C      | Dm E Fm G Am B Cm D|");
        assertThat(chordModeOf(ChordChart.toLilyPond(score)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "r1*7/8 c8 |",
                        "d8:m e8 f8:m g8 a8:m b8 c8:m d8 |");
    }

    @Test
    @DisplayName("draws a chart on eighths when the chords change on eighths")
    void aChartChangingOnEighthsIsDrawnOnEighths() {
        // The other half of the same rule, and the reason it cannot simply be
        // "snap to the beat": here the off-beat positions are the evidence, not
        // noise, and rounding them to the beat would collapse the eight chords
        // #174 exists to keep onto four positions.
        Score score = eightChordsAtAForcedTempo();

        assertThat(chordModeOf(ChordChart.toLilyPond(score)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "c8 g8 a8 f8 c8 g8 a8 f8 |");
    }

    @Test
    @DisplayName("resolves two chords a hair apart rather than putting them in one place")
    void twoChordsAHairApartGetACellEach() {
        // C, then G 20ms later, then Am. On the beat those are one position, so
        // the grid drops until they are three -- here to the shortest value a
        // duration can name, which resolves 31ms at 120 BPM.
        List<Chord> chords = List.of(
                Chord.ofSeconds(root(NoteLetter.C), ChordQuality.MAJOR, 0, 0.98,
                        Confidence.of(0.9)),
                Chord.ofSeconds(root(NoteLetter.G), ChordQuality.MAJOR, 0.98, 1.0,
                        Confidence.of(0.9)),
                Chord.ofSeconds(root(NoteLetter.A), ChordQuality.MINOR, 1.0, 2.0,
                        Confidence.of(0.9)));
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 2.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(ChordChart.barLines(score)).containsExactly("| C G Am      |");
        assertThat(chordModeOf(ChordChart.toLilyPond(score)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "c1*31/64 g64 a2:m |");
    }

    @Test
    @DisplayName("gives two chords closer than any duration a cell each anyway")
    void twoChordsCloserThanTheShortestValueBothSurvive() {
        // Below the shortest value a duration can name there is no grid left to
        // drop to, and the choice is between nudging the second along and
        // printing a zero-length chord no duration can name. Dropping it is not
        // one of the choices: that is the defect #174 is, and a chart that
        // silently loses a chord is worse than one that prints a 64th where a
        // 128th was heard.
        List<Chord> chords = List.of(
                Chord.ofSeconds(root(NoteLetter.C), ChordQuality.MAJOR, 0, 1.99,
                        Confidence.of(0.9)),
                Chord.ofSeconds(root(NoteLetter.G), ChordQuality.MAJOR, 1.99, 2.0,
                        Confidence.of(0.9)),
                Chord.ofSeconds(root(NoteLetter.A), ChordQuality.MINOR, 2.0, 4.0,
                        Confidence.of(0.9)));
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 4.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        // G and Am both land on quarter beat 4 even at a 64th; Am is nudged to
        // the next one. All three are printed, which is the whole property.
        assertThat(ChordChart.barLines(score))
                .containsExactly("| C           | G Am        |");
        assertBarsFillTheirMeter(ChordChart.toLilyPond(score));
    }

    @ParameterizedTest(name = "a quarter note lasting {0} seconds")
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY})
    @DisplayName("charts one chord to a bar when no tempo can be had")
    void withoutATempoEachChordGetsABar(double quarterSeconds) {
        // No model can produce this today -- every route through estimatedTempo()
        // ends at a validated positive tempo -- which is exactly why the length
        // is passed in rather than read. Without the guard an infinite position
        // sends the bar walk into a loop of some 10^17 iterations; with it, one
        // chord to a bar states no rhythm the model does not have, and still
        // gives the engraving a meter and a bar check, which a bare list of
        // chord names would not.
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 4.0)
                .withChords(new ChordProgression(List.of(
                        Chord.ofSeconds(root(NoteLetter.C), ChordQuality.MAJOR, 0, 2,
                                Confidence.of(0.9)),
                        Chord.ofSeconds(root(NoteLetter.G), ChordQuality.MAJOR, 2, 4,
                                Confidence.of(0.9))), Confidence.of(0.9)));

        List<ChartLayout.Bar> bars = ChartLayout.fromSeconds(score, quarterSeconds);

        assertThat(bars).hasSize(2);
        assertThat(bars).allSatisfy(bar -> assertThat(bar.cells()).hasSize(1));
        assertThat(bars.get(0).cells().get(0).symbol()).isEqualTo("C");
        assertThat(bars.get(1).cells().get(0).symbol()).isEqualTo("G");
        assertThat(bars.get(0).cells().get(0).lengthQuarters())
                .isEqualTo(TimeSignature.FOUR_FOUR.quarterBeatsPerBar());
    }

    @Test
    @DisplayName("escapes quotes in the title rather than producing broken source")
    void escapesTitle() {
        Score score = fourChordSong(1).withMetadata("A \"Quoted\" Title", null);

        assertThat(ChordChart.toLilyPond(score)).contains("\\\"Quoted\\\"");
    }

    // ---------------------------------------------------------------- #216 --

    /** The mark the engraving carries at a given counted tempo. */
    private static String markOf(String unit, int perMinute) {
        return "\\tempo \\markup { \\italic \"ca.\" } " + unit + " = " + perMinute;
    }

    @Test
    @DisplayName("the engraving states the tempo the text chart states")
    void theEngravingStatesTheTempo() {
        // The two charts are the same chart in two media, and the engraved one
        // used to state no tempo at all: a page a musician was handed with the
        // one number the pipeline is most confident about left in the .txt file
        // beside it.
        Score score = fourChordSong(1);

        assertThat(ChordChart.toText(score)).contains("Tempo  120 BPM");
        assertThat(ChordChart.toLilyPond(score)).contains(markOf("4", 120));
    }

    @Test
    @DisplayName("the engraved mark counts the beat the reader counts, not the stored quarter")
    void theEngravedMarkCountsTheCountedBeat() {
        // 180 quarter notes a minute is 120 dotted quarters, and a 6/8 bar is
        // counted in dotted quarters. A mark reading "4 = 180" over these bars
        // is a metronome setting 50% fast -- the trap the text chart's tempo
        // line has carried a comment about since round 2, now reachable through
        // a second emitter.
        Score jig = aJig();

        assertThat(ChordChart.toText(jig)).contains("Tempo  120 BPM (180 quarter notes/min)");
        assertThat(ChordChart.toLilyPond(jig))
                .contains(markOf("4.", 120))
                .doesNotContain("= 180");
    }

    @Test
    @DisplayName("the mark says the figure is an estimate")
    void theMarkIsQualified() {
        // On a chart whose tempo came from beat tracking -- the least reliable
        // stage in the pipeline -- an unqualified metronome mark states a
        // precision nothing in the score has.
        assertThat(ChordChart.toLilyPond(fourChordSong(1)))
                .contains("\\italic \"ca.\"")
                .doesNotContain("\\tempo 4 =");
    }

    @Test
    @DisplayName("the mark sits outside \\chordmode, where no bar has to account for it")
    void theMarkIsNotInAnyBar() {
        // Every line of the chordmode block is a bar whose durations must sum to
        // the meter, and a bar check follows each. A zero-duration mark written
        // among them would fail LilyPond's own check on the bar it landed in.
        String source = ChordChart.toLilyPond(fourChordSong(1));

        assertThat(source).contains("\\tempo");
        assertThat(chordModeOf(source)).noneMatch(line -> line.contains("\\tempo"));
        assertBarsFillTheirMeter(source);
    }

    @Test
    @DisplayName("heads the engraving with the title and the artist it was given")
    void theEngravingCarriesTitleAndArtist() {
        Score named = fourChordSong(1).withMetadata("Hanno ucciso l'uomo ragno", "883");

        assertThat(ChordChart.toLilyPond(named))
                .contains("title = \"Hanno ucciso l'uomo ragno\"")
                .contains("composer = \"883\"")
                .doesNotContain("Untitled");
    }

    @Test
    @DisplayName("says Untitled rather than inventing a title, and names no artist")
    void anUnnamedScoreIsNotGivenAName() {
        assertThat(ChordChart.toLilyPond(fourChordSong(1)))
                .contains("title = \"Untitled\"")
                .doesNotContain("composer");
    }
}
