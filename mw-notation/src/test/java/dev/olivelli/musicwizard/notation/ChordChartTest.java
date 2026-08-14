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
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChordChartTest {

    /** With the repeat tags asked for, which is not the default (#417). */
    private static final ChartOptions TAGGED = new ChartOptions(false, true);

    private static PitchSpelling root(NoteLetter letter) {
        return new PitchSpelling(letter, Accidental.NATURAL, 4);
    }

    /**
     * The chart before {@link ChartLayout#atHarmonicRhythm} reduces it, as text.
     *
     * <p>Where #174's property is held. That property -- every chord in the model
     * begins exactly one cell -- is a promise about the bar arithmetic, and it
     * survives #212 unchanged; what #212 added is a second stage that then drops
     * chords on purpose. Asserting it on the finished chart would assert the two
     * jointly and fail the moment either moved, which is how a deliberate
     * reduction and a rounding bug come to look alike.
     */
    private static List<String> unreducedBarLines(Score score) {
        return ChordChart.linesOf(ChartLayout.unreduced(score));
    }

    /** The same chart as LilyPond. */
    private static String unreducedLilyPond(Score score) {
        return ChordChart.lilyPondOf(score, ChartLayout.unreduced(score));
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
        return aChordPerBar(4 * cycles);
    }

    /** The same cycle, cut to a bar count that need not be a whole number of lines. */
    private static Score aChordPerBar(int bars) {
        List<Chord> chords = new ArrayList<>();
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        ChordQuality[] qualities = {ChordQuality.MAJOR, ChordQuality.MAJOR,
                ChordQuality.MINOR, ChordQuality.MAJOR};

        double time = 0;
        for (int bar = 0; bar < bars; bar++) {
            chords.add(Chord.ofSeconds(root(roots[bar % 4]), qualities[bar % 4],
                    time, time + 2.0, Confidence.of(0.9)));
            time += 2.0;
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
    @DisplayName("heads the chart with the key and how much it is trusted")
    void headsTheChartWithTheKey() {
        // The confidence is on the line because the key's failure mode is
        // invisible: a wrong relative reads exactly as well as a right one.
        Score score = fourChordSong(1).withKeys(List.of(Key.ofSeconds(
                new PitchSpelling(NoteLetter.A, Accidental.NATURAL, 4), Mode.MINOR,
                0, 8.0, Confidence.of(0.25))));

        assertThat(ChordChart.toText(score)).contains("Key    A minor (25% confidence)");
    }

    @Test
    @DisplayName("leaves the key line out when no key was estimated")
    void omitsTheKeyLineWithoutAKey() {
        assertThat(ChordChart.toText(fourChordSong(1))).doesNotContain("Key");
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

    /**
     * A quantized chart over a file stating 120 BPM in 4/4 and then 60 in 6/8.
     *
     * <p>Four bars, one chord each, and the second half of them is in the second
     * meter at the second tempo -- which the bar grid has honoured since #121
     * and the header did not.
     */
    private static Score statingTwoTemposAndTwoMeters() {
        TempoMap map = new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120, Provenance.DECLARED),
                        new TempoMap.TempoSegment(8, 4, 60, Provenance.DECLARED)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR),
                        new TempoMap.MeterChange(2, TimeSignature.SIX_EIGHT)));
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
        return Score.empty(map, map.beatsToSeconds(14))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    @Test
    @DisplayName("heads a chart whose file states two tempos with the one it opens on (#66)")
    void aStatedTempoChangeIsNotAveragedAway() {
        // Score.estimatedTempo() answers a map like this with a duration-weighted
        // average, which is neither of the two tempos the file states and is
        // played nowhere in it -- and the header printed that over a bar grid
        // honouring both. (No figure for the average here: it is a property of
        // the fixture's lengths and not of anything being tested.)
        assertThat(statingTwoTemposAndTwoMeters().estimatedTempo())
                .isNotEqualTo(120.0).isNotEqualTo(60.0);

        assertThat(ChordChart.toText(statingTwoTemposAndTwoMeters()))
                .contains("Tempo  120 BPM at the start, changed 1 time later");
    }

    @Test
    @DisplayName("engraves the mark the text header states, on a file stating two tempos")
    void theTextAndTheEngravingStateOneTempo() {
        // The two artefacts of one score, and the reader holds them together.
        // Heading the text with the tempo the file opens on while the page keeps
        // the average is the disagreement TempoMark exists to prevent, arriving
        // through the header.
        assertThat(ChordChart.toText(statingTwoTemposAndTwoMeters())).contains("Tempo  120 BPM");
        assertThat(ChordChart.toLilyPond(statingTwoTemposAndTwoMeters()))
                .contains("\\tempo \\markup { \\italic \"ca.\" } 4 = 120");
    }

    @Test
    @DisplayName("says so where the chart holds more than one meter (#191)")
    void aChartHoldingTwoMetersSaysSo() {
        // The engraving restates \time wherever a change falls (#64/#160), so a
        // header naming one meter contradicted the page of the same score.
        assertThat(ChordChart.toText(statingTwoTemposAndTwoMeters()))
                .contains("Meter  4/4 at the start, changed 1 time later");
        assertThat(chordModeOf(ChordChart.toLilyPond(statingTwoTemposAndTwoMeters())))
                .contains("\\time #'(3 3) 6/8");
    }

    @Test
    @DisplayName("heads a corrected --tempo with the correction, not with the lead-in's rate")
    void aSuppliedTempoIsNotReadAsATempoChange() {
        // The map --tempo builds: a DERIVED lead-in stretched to land on the
        // first tracked beat, then the correction. The lead-in's rate is an
        // artefact of where that beat fell -- a whole quarter beat crammed into
        // 0.05s reads as 1200 BPM -- and Provenance carries DERIVED so that it
        // is never reported as what the user asked for.
        //
        // The chart's opening bar line can precede the first tracked beat, since
        // it steps back by whole bars from the grid's phase, and a grid whose
        // downbeats do not fall on the first beat puts it there. Read as "the
        // segment in force at the opening", that is the lead-in.
        List<Double> pulses = new ArrayList<>();
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            pulses.add(0.05 + i * 0.5);
            int position = Math.floorMod(i - 3, 4);
            beats.add(new BeatGrid.Beat(0.05 + i * 0.5, position == 0, position));
        }
        List<Chord> chords = new ArrayList<>();
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A};
        for (int i = 0; i < 3; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                    0.05 + i * 4.0, 0.05 + (i + 1) * 4.0, Confidence.of(0.9)));
        }
        Score corrected = Score.empty(new TempoMap(
                        List.of(new TempoMap.TempoSegment(0, 0, 1200, Provenance.DERIVED),
                                new TempoMap.TempoSegment(1, 0.05, 60, Provenance.SUPPLIED)),
                        List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR))), 12.05)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(corrected.estimatedTempo()).isEqualTo(60.0);
        assertThat(ChartLayout.unreduced(corrected).get(0).startSeconds())
                .as("the opening bar line precedes the first tracked beat")
                .isLessThan(0.05);
        assertThat(ChordChart.toText(corrected))
                .contains("Tempo  60 BPM\n")
                .doesNotContain("changed");
    }

    @Test
    @DisplayName("reports no tempo change where the chart ends before one")
    void aTempoChangeAfterTheChartIsNotReported() {
        // The same rule the meter row applies by reading the chart's own bars.
        // The chart is two bars long and the file's second tempo begins long
        // after them, so a reader is told the tempo of the two bars in front of
        // them and nothing about music the page does not carry.
        TempoMap map = new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120, Provenance.DECLARED),
                        new TempoMap.TempoSegment(40, 20.0, 60, Provenance.DECLARED)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        List<Chord> chords = new ArrayList<>();
        for (int bar = 0; bar < 2; bar++) {
            chords.add(Chord.ofSeconds(root(bar == 0 ? NoteLetter.C : NoteLetter.G),
                            ChordQuality.MAJOR, bar * 2.0, bar * 2.0 + 2.0, Confidence.of(0.9))
                    .quantizedTo(bar * 4.0, bar * 4.0 + 4.0));
        }
        Score score = Score.empty(map, map.beatsToSeconds(48))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(ChordChart.barLines(score)).hasSize(1);
        assertThat(ChordChart.toText(score))
                .contains("Tempo  120 BPM\n")
                .doesNotContain("changed");
    }

    @Test
    @DisplayName("spaces a stated tempo change's bars at the tempo the header names")
    void theHeaderAndTheBarsAgreeOnASecondsRouteScore() {
        // The seconds route has one bar length for the whole chart, so the
        // header names a tempo the bars are actually at or it names nothing.
        // Read off estimatedTempo() the bars came out at the duration-weighted
        // average instead, wider than the header's own tempo asks for. Not
        // reachable from the CLI, since the estimator that reads a stated tempo
        // also quantizes; a hand-built score is.
        TempoMap map = new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120, Provenance.DECLARED),
                        new TempoMap.TempoSegment(8, 4, 60, Provenance.DECLARED)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int bar = 0; bar < 4; bar++) {
            chords.add(Chord.ofSeconds(root(roots[bar]), ChordQuality.MAJOR,
                    bar * 2.0, bar * 2.0 + 2.0, Confidence.of(0.9)));
        }
        Score score = Score.empty(map, 12.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
        assertThat(score.chords().isQuantized()).isFalse();
        assertThat(score.estimatedTempo()).isNotEqualTo(120.0);

        assertThat(ChordChart.toText(score)).contains("Tempo  120 BPM at the start");
        List<ChartLayout.Bar> bars = ChartLayout.unreduced(score);
        for (ChartLayout.Bar bar : bars) {
            assertThat(bar.endSeconds() - bar.startSeconds())
                    .as("a bar of the chart the header says is at 120")
                    .isEqualTo(2.0, within(1e-9));
        }
    }

    @Test
    @DisplayName("reports a tempo change the chart's first bar holds, before the harmony starts")
    void aTempoChangeInsideTheOpeningBarIsReported() {
        // The chart opens on a bar line and the harmony starts inside that bar,
        // so a tempo event between the two is on the page. An ordinary MIDI
        // file: MidiTranscriber makes a DECLARED segment of every tempo event at
        // whatever tick it sits on, and nothing says a file may not change tempo
        // mid-bar before its first chord.
        //
        // Counted from the harmony the change fell outside the window and the
        // row read "60 BPM" flat -- a chart whose first bar holds two tempos,
        // saying there is nothing more to know.
        TempoMap map = new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120, Provenance.DECLARED),
                        new TempoMap.TempoSegment(2, 1.0, 60, Provenance.DECLARED)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double from = 3 + i * 4.0;
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                            map.beatsToSeconds(from), map.beatsToSeconds(from + 4.0),
                            Confidence.of(0.9))
                    .quantizedTo(from, from + 4.0));
        }
        Score score = Score.empty(map, map.beatsToSeconds(16))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        List<ChartLayout.Bar> bars = ChartLayout.of(score);
        assertThat(bars.get(0).startSeconds())
                .as("the chart opens a bar before the harmony does")
                .isLessThan(ChartLayout.harmonyStarts(score));
        assertThat(ChordChart.toText(score))
                .contains("Tempo  120 BPM at the start, changed 1 time later");
        // The engraved mark reads the same moment through a second call site,
        // and every other fixture that reaches the engraving starts its harmony
        // where the chart opens -- so the two moments coincide there and only
        // this one can tell them apart.
        assertThat(ChordChart.toLilyPond(score)).contains("4 = 120");
    }

    @Test
    @DisplayName("heads a chart lying wholly after a stated change with the tempo it is drawn at")
    void aChartAfterAStatedChangeIsSpacedAtTheTempoItNames() {
        // The header reads the tempo where the harmony starts and so does the
        // spacing, or the chart contradicts itself the other way round: read at
        // the start of the piece the bars came out 2.0s wide under a header
        // saying 60 BPM, which in 4/4 wants 4.0s -- each chord printed over two
        // bars, and no "changed" qualifier either, since the change is before
        // the chart rather than in it.
        TempoMap map = new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120, Provenance.DECLARED),
                        new TempoMap.TempoSegment(8, 4, 60, Provenance.DECLARED)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int bar = 0; bar < 4; bar++) {
            chords.add(Chord.ofSeconds(root(roots[bar]), ChordQuality.MAJOR,
                    9.0 + bar * 4.0, 13.0 + bar * 4.0, Confidence.of(0.9)));
        }
        Score score = Score.empty(map, 26.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
        assertThat(score.chords().isQuantized()).isFalse();

        assertThat(ChordChart.toText(score))
                .contains("Tempo  60 BPM\n")
                .doesNotContain("changed");
        for (ChartLayout.Bar bar : ChartLayout.unreduced(score)) {
            assertThat(bar.endSeconds() - bar.startSeconds())
                    .as("a bar of the chart the header says is at 60")
                    .isEqualTo(4.0, within(1e-9));
        }
        // One chord to a bar, which is what the two agreeing buys the reader.
        assertThat(ChordChart.barLines(score))
                .containsExactly("| C           | G           | A           | F           |");
    }

    @Test
    @DisplayName("counts only stated tempos, so a tracked map heads one chart with one number")
    void aTrackedMapIsNotReadAsHundredsOfTempoChanges() {
        // TempoMap.fromBeatTimes emits a segment per tracked beat, each at its
        // own rate and none of them anybody's statement. Counting those would
        // head every chart taken from audio with several hundred changes.
        List<Double> pulses = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            pulses.add(0.5 * i + (i % 3) * 0.01);
        }
        Score tracked = Score.empty(
                        TempoMap.fromBeatTimes(pulses, TimeSignature.FOUR_FOUR), 12.0)
                .withBeatGrid(BeatGrid.ofTimes(pulses, TimeSignature.FOUR_FOUR,
                        Confidence.of(0.9)))
                .withChords(new ChordProgression(List.of(
                        Chord.ofSeconds(root(NoteLetter.C), ChordQuality.MAJOR,
                                0, 8.0, Confidence.of(0.9))), Confidence.of(0.9)));

        assertThat(tracked.tempoMap().segments().stream()
                .map(TempoMap.TempoSegment::beatsPerMinute)
                .distinct()).hasSizeGreaterThan(2);
        assertThat(ChordChart.toText(tracked)).doesNotContain("changed");
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

    /**
     * A 4/4 grid at a nominal 120 BPM whose bars 6 and 12 are one detour short.
     *
     * <p>The shape #233 is about, in miniature. Every tracked interval that
     * counts towards the rate is 0.5s, so the chart spaces its bars at exactly
     * 2.0s; two bars are closed by a 0.3s interval instead, which is outside
     * {@code BeatGrid}'s steady band and so does not reach the rate. The
     * recording's downbeats therefore walk 0.4s ahead of any 2.0s grid over
     * sixteen bars -- one-sidedly, since bar zero starts them in agreement.
     *
     * <p>A chord sits on each downbeat, so a chord in the wrong printed bar is a
     * bar line in the wrong place.
     *
     * @param extraBar      mark one more beat a downbeat, in this bar, or -1 for
     *                      the grid as the tracker would emit it
     * @param extraPosition which beat of that bar to mark
     */
    private static Score aGridDriftingAgainstItsOwnRate(int extraBar, int extraPosition) {
        double[] downbeats = new double[16];
        List<BeatGrid.Beat> beats = new ArrayList<>();
        double at = 0;
        for (int bar = 0; bar < 16; bar++) {
            downbeats[bar] = at;
            for (int position = 0; position < 4; position++) {
                boolean extra = bar == extraBar && position == extraPosition;
                beats.add(new BeatGrid.Beat(at, position == 0 || extra, extra ? 0 : position));
                at += position == 3 && (bar == 6 || bar == 12) ? 0.3 : 0.5;
            }
        }
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int bar = 0; bar < 16; bar++) {
            chords.add(Chord.ofSeconds(root(roots[bar % 4]), ChordQuality.MAJOR, downbeats[bar],
                    bar + 1 < 16 ? downbeats[bar + 1] : at, Confidence.of(0.9)));
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), at)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    @Test
    @DisplayName("phases the bar lines on every downbeat, not on the first one (#233)")
    void theBarLinesTakeTheirPhaseFromTheWholeGrid() {
        // On a grid whose downbeats are taken as the bar lines there is no
        // phase left to choose, so the question #233 answers is only live where
        // the sequence is refused -- here by one downbeat two beats out, which
        // is no bar. What the chart then draws is one bar length hung on the
        // offset the downbeats agree on.
        Score score = aGridDriftingAgainstItsOwnRate(3, 2);

        // Sixteen chords, sixteen bars, each chord alone in its own -- which is
        // where each of them sounds.
        assertThat(unreducedBarLines(score)).containsExactly(
                "| C           | G           | A           | F           |",
                "| C           | G           | A           | F           |",
                "| C           | G           | A           | F           |",
                "| C           | G           | A           | F           |");
        assertBarsFillTheirMeter(ChordChart.toLilyPond(score));

        // One bar length, hung on the offset the whole grid agrees on: a fifth
        // of a second before the downbeat the tracker nominated first, which
        // this grid's later downbeats outvote. Anchoring on that first downbeat
        // instead moves every line on the page by that fifth.
        List<Double> drawn = ChartLayout.unreduced(score).stream()
                .map(ChartLayout.Bar::startSeconds)
                .toList();
        List<Double> downbeats = score.beatGrid().orElseThrow().downbeatTimes();
        assertThat(drawn.get(0)).isEqualTo(downbeats.get(0) - 0.2, within(1e-9));
        for (int bar = 1; bar < drawn.size(); bar++) {
            assertThat(drawn.get(bar) - drawn.get(bar - 1))
                    .as("bar %d", bar)
                    .isEqualTo(2.0, within(1e-9));
        }
    }

    /**
     * A 4/4 grid at a nominal 120 BPM that loses a third of a beat every third
     * bar, so its downbeats walk most of a bar away from any 2.0s grid over
     * twenty-four of them.
     *
     * <p>The same shape as {@link #aGridDriftingAgainstItsOwnRate} and further
     * along it: the detour intervals stay outside {@code BeatGrid}'s steady
     * band, so the rate the chart is spaced at is still exactly 2.0s and the
     * whole of the difference is in the spacing. A chord sits on each downbeat,
     * so a chord in the wrong printed bar is a bar line in the wrong place.
     */
    private static Score aGridWanderingAwayFromItsOwnRate() {
        double[] downbeats = new double[24];
        List<BeatGrid.Beat> beats = new ArrayList<>();
        double at = 0;
        for (int bar = 0; bar < 24; bar++) {
            downbeats[bar] = at;
            for (int position = 0; position < 4; position++) {
                beats.add(new BeatGrid.Beat(at, position == 0, position));
                at += position == 3 && bar % 3 == 2 ? 0.3 : 0.5;
            }
        }
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int bar = 0; bar < 24; bar++) {
            chords.add(Chord.ofSeconds(root(roots[bar % 4]), ChordQuality.MAJOR, downbeats[bar],
                    bar + 1 < 24 ? downbeats[bar + 1] : at, Confidence.of(0.9)));
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), at)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    @Test
    @DisplayName("hangs each bar on its own downbeat, not on one downbeat and a rate (#187)")
    void theBarLinesFollowTheGridsWander() {
        Score score = aGridWanderingAwayFromItsOwnRate();

        // Twenty-four chords, twenty-four bars, one chord in each -- which is
        // where each of them sounds. Stepped by a constant bar from the same
        // opening line the chart drew twenty-three bars reading
        //     | C | G | A | F | C | G A | F | C | G | A | F | C | ...
        // -- the sixth bar doubled, every chord after it a bar early, and the
        // last chord with no bar of its own.
        assertThat(unreducedBarLines(score)).containsOnly(
                "| C           | G           | A           | F           |");
        assertThat(unreducedBarLines(score)).hasSize(6);
        assertBarsFillTheirMeter(ChordChart.toLilyPond(score));

        // The mechanism: every bar line is one of the grid's own downbeats.
        assertThat(ChartLayout.unreduced(score).stream()
                .map(ChartLayout.Bar::startSeconds)
                .toList())
                .containsExactlyElementsOf(score.beatGrid().orElseThrow().downbeatTimes());
    }

    @Test
    @DisplayName("refuses a whole downbeat sequence holding a bar no bar could be (#421)")
    void aSequenceWithABarNoBarCouldBeIsRefusedWhole() {
        // The case #187's own first comment raises: a detector that emits a gap
        // of three quarters of a bar is wrong about that downbeat, and barring
        // straight off it would trade a misplaced chord for a short bar. A
        // chart cannot tell from the downbeats alone where the tracker lost the
        // beat, so the whole sequence goes and the constant rate carries the
        // chart -- which is the chart there was before any of this.
        Set<Integer> marked = Set.of(0, 4, 8, 12, 16, 20, 23, 28, 32, 36, 40, 44);
        List<BeatGrid.Beat> beats = new ArrayList<>();
        int since = 0;
        for (int beat = 0; beat < 48; beat++) {
            since = marked.contains(beat) ? beat : since;
            beats.add(new BeatGrid.Beat(beat * 0.5, beat == since, beat - since));
        }
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int bar = 0; bar < 12; bar++) {
            chords.add(Chord.ofSeconds(root(roots[bar % 4]), ChordQuality.MAJOR,
                    bar * 2.0, bar * 2.0 + 2.0, Confidence.of(0.9)));
        }
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 24.0)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(ChartLayout.unreduced(score).stream()
                .map(ChartLayout.Bar::startSeconds)
                .toList())
                .containsExactly(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 20.0,
                        22.0);
        assertThat(unreducedBarLines(score)).containsOnly(
                "| C           | G           | A           | F           |");
    }

    /**
     * A grid whose bar lengths are stated one per bar, with a chord on each
     * downbeat and four beats to a bar, at a nominal 120 BPM.
     *
     * <p>The bar's own length falls in the interval that closes it, so the
     * three inner beats keep the 0.5s pulse. That keeps the steady rate at 120
     * -- and so keeps the grid on the followed path, where the veto is the only
     * thing that can refuse it -- only while that closing interval stays
     * outside {@code BeatGrid.STEADY_BAND}, which wants a bar under 1.9s or
     * over 2.1s. A bar within about a twentieth of nominal reaches the rate
     * instead, moves it off 120, and is refused before the veto is asked: use
     * something else to write a fixture for the jitter case (#429).
     */
    private static Score aGridWithBars(double... lengths) {
        List<BeatGrid.Beat> beats = new ArrayList<>();
        List<Double> downbeats = new ArrayList<>();
        double at = 0;
        for (double length : lengths) {
            downbeats.add(at);
            for (int position = 0; position < 4; position++) {
                beats.add(new BeatGrid.Beat(at + position * 0.5, position == 0, position));
            }
            at += length;
        }
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int bar = 0; bar < downbeats.size(); bar++) {
            chords.add(Chord.ofSeconds(root(roots[bar % 4]), ChordQuality.MAJOR,
                    downbeats.get(bar),
                    bar + 1 < downbeats.size() ? downbeats.get(bar + 1) : at,
                    Confidence.of(0.9)));
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), at + 2.0)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /** Where the chart draws its bar lines, in seconds. */
    private static List<Double> drawnBarLines(Score score) {
        return ChartLayout.unreduced(score).stream()
                .map(ChartLayout.Bar::startSeconds)
                .toList();
    }

    @Test
    @DisplayName("refuses a sequence whose bars do not agree with each other")
    void aSequenceWithNoRateOfItsOwnIsRefused() {
        // The clause the veto's argument rests on and the one #421's sweep is
        // about: a sequence that states no rate states no bars. Every gap here
        // is strictly inside what the stated tempo admits -- 1.75s and 2.375s
        // against a 2.0s bar, so neither the short nor the long band refuses it,
        // and neither sits on a boundary where one comparison's strictness
        // would decide -- and they do not agree with each other, the long one
        // being a third longer than the rest.
        Score score = aGridWithBars(1.75, 1.75, 1.75, 2.375, 1.75, 1.75, 1.75, 1.75);

        assertThat(drawnBarLines(score).get(1) - drawnBarLines(score).get(0))
                .as("the constant rate carries the chart")
                .isEqualTo(2.0, within(1e-9));
    }

    @Test
    @DisplayName("refuses a sequence whose bars are all longer than the stated tempo admits")
    void aSequenceOfBarsTooLongIsRefused() {
        // The doubled-downbeat half of what the veto claims to catch, which the
        // short band cannot: every one of these bars agrees with every other,
        // so the sequence has a perfectly good rate of its own -- and it is a
        // rate no chart headed at this tempo can draw.
        Score score = aGridWithBars(2.75, 2.75, 2.75, 2.75, 2.75, 2.75, 2.75, 2.75);

        assertThat(drawnBarLines(score).get(1) - drawnBarLines(score).get(0))
                .as("the constant rate carries the chart")
                .isEqualTo(2.0, within(1e-9));
    }

    @Test
    @DisplayName("draws a short bar the sequence admits as the short bar it is")
    void aShortBarTheSequenceAdmitsIsFollowed() {
        // A bar an eighth short is a bar -- the recording that motivated #187
        // has one -- so the sequence stands and that bar is printed where it
        // sounds. There is nothing to absorb it into and nothing that has to
        // catch up afterwards: the line after it is a downbeat too.
        double[] downbeats = {0, 2.0, 4.0, 6.0, 7.65, 9.65, 11.65, 13.65, 15.65};
        // Four beats at the pulse from each downbeat, so the bar's own length
        // falls in the interval that closes it -- 0.15s on the short bar, which
        // is outside BeatGrid's steady band and so does not reach the rate.
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (double downbeat : downbeats) {
            for (int position = 0; position < 4; position++) {
                beats.add(new BeatGrid.Beat(downbeat + position * 0.5, position == 0, position));
            }
        }
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int bar = 0; bar < downbeats.length; bar++) {
            chords.add(Chord.ofSeconds(root(roots[bar % 4]), ChordQuality.MAJOR, downbeats[bar],
                    bar + 1 < downbeats.length ? downbeats[bar + 1] : 17.65, Confidence.of(0.9)));
        }
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 17.65)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(ChartLayout.unreduced(score).stream()
                .map(ChartLayout.Bar::startSeconds)
                .toList())
                .containsExactly(0.0, 2.0, 4.0, 6.0, 7.65, 9.65, 11.65, 13.65, 15.65);
        // Nine chords, nine bars, one chord in each.
        assertThat(unreducedBarLines(score)).containsExactly(
                "| C           | G           | A           | F           |",
                "| C           | G           | A           | F           |",
                "| C           |");
    }

    @Test
    @DisplayName("keeps a corrected tempo's own bars where the grid would pass the veto (#421)")
    void aCorrectedTempoKeepsItsOwnBars() {
        // --tempo says how long a bar is, and the grid is what the user is
        // disagreeing with, so its downbeats cannot define the bars however
        // even they are. The guard for that is its own: this grid wanders and
        // every one of its bars is well inside what evenThroughout admits, so
        // nothing else here refuses it.
        //
        // headerAndBarsCannotDisagree does not reach this guard. Its corrected
        // tempo doubles the bar, so the grid's downbeats are half of one and
        // the veto refuses them first -- delete the guard and that test still
        // passes.
        List<BeatGrid.Beat> beats = new ArrayList<>();
        double at = 0;
        List<Double> downbeats = new ArrayList<>();
        for (int bar = 0; bar < 16; bar++) {
            downbeats.add(at);
            for (int position = 0; position < 4; position++) {
                beats.add(new BeatGrid.Beat(at, position == 0, position));
                at += position == 3 && bar % 3 == 2 ? 0.3 : 0.5;
            }
        }
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int bar = 0; bar < 16; bar++) {
            chords.add(Chord.ofSeconds(root(roots[bar % 4]), ChordQuality.MAJOR,
                    downbeats.get(bar), bar + 1 < 16 ? downbeats.get(bar + 1) : at,
                    Confidence.of(0.9)));
        }
        // A tenth longer than the grid's own bar: the user counting a little
        // slower than the tracker did, which every gap here is well within.
        Score corrected = Score.empty(new TempoMap(
                        List.of(new TempoMap.TempoSegment(0, 0, 4 * 60 / 2.2,
                                Provenance.SUPPLIED)),
                        List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR))), at)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        List<Double> drawn = ChartLayout.unreduced(corrected).stream()
                .map(ChartLayout.Bar::startSeconds)
                .toList();
        for (int bar = 1; bar < drawn.size(); bar++) {
            assertThat(drawn.get(bar) - drawn.get(bar - 1))
                    .as("bar %d is the bar the user asked for", bar)
                    .isEqualTo(2.2, within(1e-9));
        }
    }

    @Test
    @DisplayName("follows a wandering grid in 3/4 as well as in 4/4")
    void aWaltzGridWandersOntoItsOwnDownbeats() {
        // Every benchmark is barred 4/4, so no baseline can tell whether the fit
        // is running in any other meter. It is switched on by comparing the
        // chart's quarter with the grid's own, and comparing bars instead
        // multiplies each side by the quarters in one: exact where that factor
        // is a power of two, and not in 3/4, where it is three. A 0.32s pulse is
        // one of the tempi it disagrees at.
        //
        // The same shape as the 4/4 fixture: three beats to the bar, every third
        // bar a detour short, and the detour outside BeatGrid's steady band so
        // the rate stays the pulse.
        List<Double> times = new ArrayList<>();
        double[] downbeats = new double[24];
        double at = 0;
        for (int bar = 0; bar < 24; bar++) {
            downbeats[bar] = at;
            for (int position = 0; position < 3; position++) {
                times.add(at);
                at += position == 2 && bar % 3 == 2 ? 0.2 : 0.32;
            }
        }
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            beats.add(new BeatGrid.Beat(times.get(i), i % 3 == 0, i % 3));
        }
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int bar = 0; bar < 24; bar++) {
            chords.add(Chord.ofSeconds(root(roots[bar % 4]), ChordQuality.MAJOR, downbeats[bar],
                    bar + 1 < 24 ? downbeats[bar + 1] : at, Confidence.of(0.9)));
        }
        Score score = Score.empty(
                        TempoMap.fromBeatTimes(times, TimeSignature.THREE_FOUR), at)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(ChartLayout.unreduced(score).stream()
                .map(ChartLayout.Bar::startSeconds)
                .toList())
                .containsExactlyElementsOf(score.beatGrid().orElseThrow().downbeatTimes());
        assertThat(unreducedBarLines(score)).containsOnly(
                "| C           | G           | A           | F           |");
        assertBarsFillTheirMeter(ChordChart.toLilyPond(score));
    }

    @Test
    @DisplayName("spaces at the tempo past the last downbeat the tracker marked")
    void barsPastTheGridAreSpacedAtTheTempo() {
        // The tail of every chart, since the harmony reaches the end of the
        // recording and the last downbeat does not. There is no measurement out
        // there, so those bars are the stated length -- and they have to be
        // whole ones, or the chart's last bars would each be a fraction of a
        // bar wide.
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int beat = 0; beat < 24; beat++) {
            beats.add(new BeatGrid.Beat(beat * 0.5, beat % 4 == 0, beat % 4));
        }
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int bar = 0; bar < 12; bar++) {
            chords.add(Chord.ofSeconds(root(roots[bar % 4]), ChordQuality.MAJOR,
                    bar * 2.0, bar * 2.0 + 2.0, Confidence.of(0.9)));
        }
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 24.0)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(ChartLayout.unreduced(score).stream()
                .map(ChartLayout.Bar::startSeconds)
                .toList())
                .containsExactly(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 20.0,
                        22.0);
    }

    @Test
    @DisplayName("measures how far it may overshoot the first chord on the bar that chord is in")
    void aChordJustShortOfALongBarKeepsItsLeadIn() {
        // How far the opening bar line may be drawn past the first chord is half
        // a grid step, and a fitted bar is not the nominal length -- so which
        // bar's length that is decides. Here the bar the chord sounds in is a
        // beat and a half short of the one after it, and the chord sits between
        // the two answers: half a step of its own bar refuses to move the line,
        // half a step of the next one takes it. Taking it back-dates the chord
        // onto a bar line a quarter of a second after it sounds and swallows the
        // lead-in gap that says the phase is out, which is #83's signal.
        double[] downbeats = {0, 2.0, 3.75, 6.0, 8.0, 10.0};
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int bar = 0; bar < downbeats.length; bar++) {
            for (int position = 0; position < 4; position++) {
                beats.add(new BeatGrid.Beat(downbeats[bar] + position * 0.5, position == 0,
                        position));
            }
        }
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        double[] starts = {3.5, 6.0, 8.0, 10.0};
        for (int i = 0; i < starts.length; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR, starts[i],
                    i + 1 < starts.length ? starts[i + 1] : 12.0, Confidence.of(0.9)));
        }
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 12.0)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(ChartLayout.unreduced(score).get(0).startSeconds()).isEqualTo(2.0);
        assertThat(unreducedBarLines(score).get(0)).startsWith("| N.C. C");
    }

    @Test
    @DisplayName("measures a chord gap without reference to where the chart starts")
    void aGapIsTheSameGapWhereverTheChartOpens() {
        // How finely the chart resolves is read off the closest two chord
        // changes, and that is a fact about the progression: measured as two
        // positions on the axis and subtracted, it rounds twice where the gap
        // rounds once, and the comparison against a candidate grid is exact. The
        // pair below is exactly one counted beat apart and reads as a hair under
        // one through the subtraction, which halves the grid for the whole chart
        // and writes the bar the two chords share as an eighth and seven eighths
        // rather than a beat and three.
        //
        // A grid running at a rate the chart is not counted at, so the bar lines
        // are spaced uniformly and the axis has an origin at 0.51s to subtract.
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            beats.add(new BeatGrid.Beat(0.51 + i * 0.55, i % 4 == 0, i % 4));
        }
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.D, NoteLetter.G, NoteLetter.A,
                NoteLetter.F};
        double[] starts = {0.51, 4.81, 8.51, 16.01, 16.51};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < starts.length; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR, starts[i],
                    i + 1 < starts.length ? starts[i + 1] : 18.51, Confidence.of(0.9)));
        }
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 22.0)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        assertThat(chordModeOf(unreducedLilyPond(score))).contains("c4 d2. |");
    }

    @Test
    @DisplayName("draws the same chart wherever one more downbeat is marked in the grid")
    void anExtraDownbeatAnywhereLeavesTheChartAlone() {
        // One more downbeat is a bar no bar could be, so the sequence goes and
        // the chart is one bar length on the phase the downbeats agree on. That
        // phase has to be the same phase wherever the extra one falls: it adds
        // one candidate and one term to every total, and a median on a circle
        // must not flip for that. Every placement rather than one, because a
        // placement that survives says nothing about the ones that do not, and
        // which of them a single-placement test picked would be arbitrary.
        //
        // Asserted against each other and not against the unperturbed grid,
        // which is followed rather than refused and so is not drawn by the
        // decision under test.
        List<String> first = unreducedBarLines(aGridDriftingAgainstItsOwnRate(0, 1));
        List<Double> firstLines = drawnBarLines(aGridDriftingAgainstItsOwnRate(0, 1));
        for (int bar = 0; bar < 16; bar++) {
            for (int position = 1; position < 4; position++) {
                assertThat(unreducedBarLines(aGridDriftingAgainstItsOwnRate(bar, position)))
                        .as("one more downbeat at bar %d, beat %d", bar, position)
                        .isEqualTo(first);
                assertThat(drawnBarLines(aGridDriftingAgainstItsOwnRate(bar, position)))
                        .as("the phase, with one more downbeat at bar %d, beat %d", bar, position)
                        .isEqualTo(firstLines);
            }
        }
    }

    @Test
    @DisplayName("refuses a phase more than half a beat off the downbeat the grid nominates")
    void aDownbeatSequenceThatWandersOffTheBeatKeepsTheNominatedOne() {
        // Drifting for three bars and then holding, so thirteen of the
        // seventeen downbeats agree on a phase 0.75s -- more than one counted
        // beat -- from where the first one sits. The grid is stating that its
        // first downbeat is the odd one out, and the chart still declines to
        // act on it: moving the bar lines that far puts them on a different
        // beat of the bar, and which beat begins a bar is the grid's decision
        // and --first-downbeat's rather than the chart's (#83).
        //
        // One late bar drops a beat, which is what puts this grid on the path
        // where a phase is chosen at all. Without it the sequence is followed,
        // every bar line is a downbeat, and the assertion below would hold for
        // a reason that has nothing to do with the phase.
        //
        // Every time here is a whole number of quarters of a second, so the
        // grid's steady rate is exactly the 120 the map states; one bit between
        // them and the grid is refused before the veto or the phase is reached.
        // The grid starts at 1.25s and not at zero, so the assertion names the
        // nominated downbeat instead of agreeing with zero.
        double first = 1.25;
        List<BeatGrid.Beat> beats = new ArrayList<>();
        double at = first;
        double second = 0;
        for (int bar = 0; bar < 17; bar++) {
            second = bar == 1 ? at : second;
            int pulses = bar == 15 ? 3 : 4;
            for (int position = 0; position < pulses; position++) {
                beats.add(new BeatGrid.Beat(at, position == 0, position));
                at += position == 3 && bar < 3 ? 0.25 : 0.5;
            }
        }
        List<Chord> chords = List.of(
                Chord.ofSeconds(root(NoteLetter.C), ChordQuality.MAJOR, first, second,
                        Confidence.of(0.9)),
                Chord.ofSeconds(root(NoteLetter.G), ChordQuality.MAJOR, second, second + 1.75,
                        Confidence.of(0.9)));
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), at)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        List<Double> drawn = drawnBarLines(score);
        assertThat(drawn.get(0)).isEqualTo(first, within(1e-9));
        // And the whole chart is one bar length, which a followed sequence
        // could not draw: three of its bars are a beat and three quarters.
        for (int bar = 1; bar < drawn.size(); bar++) {
            assertThat(drawn.get(bar) - drawn.get(bar - 1))
                    .as("bar %d", bar)
                    .isEqualTo(2.0, within(1e-9));
        }
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

        assertThat(unreducedBarLines(score)).containsExactly("| C G         |");
        assertThat(chordModeOf(unreducedLilyPond(score)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "c2. g4 |");

        // The written chart absorbs the G, which is #212 and not a return of
        // #174: it is in the layout above, and it is dropped because one beat of
        // four is not enough of a bar to earn a second symbol. The distinction
        // is the whole reason the two stages are asserted separately.
        assertThat(ChordChart.barLines(score)).containsExactly("| C           |");
        assertThat(chordModeOf(ChordChart.toLilyPond(score)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "c1 |");
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
        assertThat(unreducedBarLines(score)).containsExactly("| C G A F C G A F|");
        assertThat(chordModeOf(unreducedLilyPond(score)))
                .containsExactly("\\time #'(1 1 1 1) 4/4", "c8 g8 a8 f8 c8 g8 a8 f8 |");

        // What the reader gets, and it is the sharpest edge of #212: a tempo
        // wrong by a factor packs the whole progression into one bar, and the
        // reduction then writes that bar as the chord holding most of it. Seven
        // of the eight go.
        //
        // Declining to reduce a bar whose harmony moves faster than the counted
        // beat would save this one, and it is not a clean rule: on some sample
        // recordings a substantial minority of changes is that fast against the
        // beat their charts are spaced at, while none of them is against the
        // tracked beat grid the estimator used. See ChartLayout.atHarmonicRhythm,
        // and tools/baselines/score-chart.txt, which carries both columns per
        // recording.
        //
        // So this is a stated cost rather than a case to special-case. The model
        // still holds all eight, which the assertion above is on.
        assertThat(ChordChart.barLines(score)).containsExactly("| C           |");
    }

    /** One sub-beat ornament among four beat-aligned chords, at 120 BPM. */
    private static Score anOrnamentalChord() {
        double[][] spans = {{0, 1}, {1, 1.1}, {1.1, 2}, {2, 3}, {3, 4}};
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A,
                NoteLetter.F, NoteLetter.C};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < spans.length; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                    spans[i][0], spans[i][1], Confidence.of(0.9)));
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 4.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    @Test
    @DisplayName("an ornamental sub-beat chord does not push the others out of their bars")
    void anOrnamentalChordKeepsTheRestInPlace() {
        // C 0..1, G 1..1.1, A 1.1..2, F 2..3, C 3..4 at 120 BPM. The final C --
        // a full-beat chord, perfectly placeable -- used to be dropped, and A
        // and F printed in a bar they do not sound in.
        Score score = anOrnamentalChord();

        assertThat(unreducedBarLines(score))
                .containsExactly("| C G A       | F C         |");
        assertBarsFillTheirMeter(unreducedLilyPond(score));

        // Written, the ornament goes and the two full-beat chords of the second
        // bar stay: it holds F and C for two beats each, which is a split rather
        // than chatter. So the reduction is not simply "one chord a bar".
        assertThat(ChordChart.barLines(score))
                .containsExactly("| C A         | F C         |");
        assertBarsFillTheirMeter(ChordChart.toLilyPond(score));
    }

    @Test
    @DisplayName("lays out every chord the model holds, in order, whatever they land on")
    void noChordIsEverDropped() {
        // The property the guard at barLines used to break. Stated over the
        // awkward cases rather than one of them, because each of the three
        // reproductions in #174 lost a different chord for a different reason.
        //
        // On the layout rather than on the finished chart, because #212 makes the
        // chart drop chords deliberately. Moving the assertion is not weakening
        // it: the layout is where a chord can be lost to arithmetic, and it is
        // still every chord, still in order, over the same seven fixtures.
        for (Score score : List.of(eightChordsAtAForcedTempo(), aWaltz(), aJig(),
                twoChordsInABar(), quantizedAcrossATempoChange(), clickTrackPhasedAt(2),
                fourChordSong(3))) {
            String printed = String.join(" ", unreducedBarLines(score));
            int at = 0;
            for (Chord chord : score.chords().chords()) {
                int found = printed.indexOf(chord.symbol(), at);
                assertThat(found).as("%s in %s", chord.symbol(), printed).isNotNegative();
                at = found;
            }
        }
    }

    @Test
    @DisplayName("what the written chart drops, it drops for holding no more than half its bar")
    void aDroppedChordNeverHeldMoreThanHalfItsBar() {
        // The other half of the property above, and the one that makes the move
        // to the layout honest: a chord missing from the page has to have been
        // outvoted, not mislaid. Every chord the layout holds and the chart does
        // not is one whose own bar gave it no more than half.
        //
        // Half and not less than half, and the difference is not pedantry --
        // round 1 of review swept every bar of up to nine equal cells over three
        // symbols in eleven meters and found the bound attained, twice over. The
        // last two fixtures below are those cases: a bar of I-V-I whose V holds a
        // contiguous half, and two chords alternating on the beat where the loser
        // holds an aggregate half. Both are ordinary shapes rather than corners,
        // both lose a chord, and the eight fixtures that were here before happen
        // to contain neither. The bound is tight: nothing holding *more* than
        // half was dropped anywhere in that sweep.
        for (Score score : List.of(eightChordsAtAForcedTempo(), aWaltz(), aJig(),
                twoChordsInABar(), quantizedAcrossATempoChange(), clickTrackPhasedAt(2),
                fourChordSong(3), anOrnamentalChord(),
                aBarHoldingAContiguousHalf(), aBarAlternatingOnTheBeat())) {
            List<ChartLayout.Bar> laid = ChartLayout.unreduced(score);
            List<ChartLayout.Bar> written = ChartLayout.of(score);
            for (int i = 0; i < laid.size(); i++) {
                double bar = laid.get(i).meter().quarterBeatsPerBar();
                List<String> kept = laid.get(i).cells().stream()
                        .map(ChartLayout.Cell::symbol).toList();
                for (ChartLayout.Cell cell : laid.get(i).cells()) {
                    boolean survives = written.get(i).cells().stream()
                            .anyMatch(c -> c.symbol().equals(cell.symbol()));
                    if (!survives) {
                        double held = laid.get(i).cells().stream()
                                .filter(c -> c.symbol().equals(cell.symbol()))
                                .mapToDouble(ChartLayout.Cell::lengthQuarters)
                                .sum();
                        assertThat(held)
                                .as("%s dropped from bar %d of %s", cell.symbol(), i, kept)
                                .isLessThanOrEqualTo(bar / 2);
                    }
                }
            }
        }
    }

    /** One 4/4 bar of C G G C, where the G holds a contiguous half and still goes. */
    private static Score aBarHoldingAContiguousHalf() {
        return oneBarOf(new NoteLetter[] {NoteLetter.C, NoteLetter.G, NoteLetter.C},
                0.5, 1.0, 0.5);
    }

    /** One 4/4 bar of G C G C, where the C holds an aggregate half and still goes. */
    private static Score aBarAlternatingOnTheBeat() {
        return oneBarOf(new NoteLetter[] {NoteLetter.G, NoteLetter.C, NoteLetter.G, NoteLetter.C},
                0.5, 0.5, 0.5, 0.5);
    }

    /** Back-to-back chords of the stated lengths in seconds, 4/4 at 120 BPM. */
    private static Score oneBarOf(NoteLetter[] roots, double... seconds) {
        List<Chord> chords = new ArrayList<>();
        double at = 0;
        for (int i = 0; i < roots.length; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                    at, at + seconds[i], Confidence.of(0.9)));
            at += seconds[i];
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), at)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
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
        // On the layout: where a chord is placed is this fixture's question, and
        // the reduction that follows would hide a chord shunted a bar along by
        // absorbing it, which is exactly the failure being guarded against.
        assertThat(unreducedBarLines(score))
                .containsExactly("| N.C. C      | Dm E Fm G Am B Cm D|");
        assertThat(chordModeOf(unreducedLilyPond(score)))
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

        // Which grid the layout draws on is what this asks, so it asks the
        // layout. The reduction downstream writes a coarser rhythm than the grid
        // it was drawn on, which is a different decision and has its own test.
        assertThat(chordModeOf(unreducedLilyPond(score)))
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

        assertThat(unreducedBarLines(score)).containsExactly("| C G Am      |");
        assertThat(chordModeOf(unreducedLilyPond(score)))
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
        // the next one. All three are laid out, which is the whole property.
        assertThat(unreducedBarLines(score))
                .containsExactly("| C           | G Am        |");
        assertBarsFillTheirMeter(unreducedLilyPond(score));
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

    // ---------------------------------------------------------------- #217 --

    @Test
    @DisplayName("the ChordNames context is given the engraver that draws bar lines")
    void theEngravingDrawsItsBarLines() {
        // ChordNames has no Bar_engraver of its own, so the | closing every bar
        // was only a check: the page was one uninterrupted row of chord names,
        // on which nothing distinguishes "| C G | Am |" from "| C | G | Am |".
        // The two are not the same page -- a half-note bar is spaced more
        // tightly than two whole-note bars, so the names land differently --
        // but nothing on either says where a bar ends, which is the reading a
        // chart exists to give.
        assertThat(ChordChart.toLilyPond(fourChordSong(2)))
                .contains("\\consists \"Bar_engraver\"");
    }

    @Test
    @DisplayName("the bar lines are given a height, which the engraver alone does not")
    void theBarLinesAreGivenAHeight() {
        // A bar line is drawn the height of its staff and ChordNames has no
        // staff, so the engraver on its own emits lines of empty vertical
        // extent -- in the score, invisible on the page, which reads exactly
        // like not having asked for them. ChordChartEngravingIT reads the
        // heights back out of LilyPond; this only says the request is made.
        assertThat(ChordChart.toLilyPond(fourChordSong(2)))
                .contains("\\override BarLine.bar-extent = #'(-2 . 2)");
    }

    @Test
    @DisplayName("the chart ends with a final bar line, as the staff parts do")
    void theChartClosesWithAFinalBarLine() {
        String source = ChordChart.toLilyPond(fourChordSong(1));

        assertThat(source).contains("\\bar \"|.\"");
        // After the last bar and outside \chordmode: inside it, a mark with no
        // duration is a bar whose contents do not sum to the meter, which is
        // the check the tempo mark is kept out of that block for.
        assertThat(chordModeOf(source)).noneMatch(line -> line.contains("\\bar"));
        assertThat(source.indexOf("\\bar \"|.\""))
                .as("the final bar line follows the last bar")
                .isGreaterThan(source.lastIndexOf("|\n"));
        assertBarsFillTheirMeter(source);
    }

    @Test
    @DisplayName("a chart with no bars is not closed with a bar line")
    void aChartWithNothingInItIsNotGivenAnEnding() {
        // Nothing to end. The text chart says "(no chords were found)"; the
        // engraving of a score with none should not print the one mark that
        // claims a piece just finished.
        Score empty = Score.empty(TempoMap.constant(120), 10);

        assertThat(ChordChart.toLilyPond(empty)).doesNotContain("\\bar");
    }

    // -------------------------------------------------------------- #218 ----

    /** One repeat bracket the emitter wrote: its tag, and the bars it covers. */
    private record Bracket(String tag, int firstBar, int lastBar) {
    }

    private static final Pattern BRACKET_TAG = Pattern.compile(
            "\\\\once \\\\override TextSpanner\\.bound-details\\.left\\.text = "
                    + "\\\\markup \\{ \\\\bold \"([A-Z]+)\" .*");

    /**
     * The brackets, read back out of the annotation context bar by bar.
     *
     * <p>Read structurally rather than by looking for a substring of the whole
     * source. #223's tempo mark is a {@code \markup}, and {@code \markup}
     * contains {@code \mark}: an earlier draft of this feature probed the file
     * for {@code \mark}, which made one test that was named for "no repeat, no
     * annotation" pass on a source that always carries a markup. Counting the
     * ends of each span against the bars they fall on cannot be satisfied that
     * way.
     */
    private static List<Bracket> bracketsOf(String source) {
        int open = source.indexOf("\\new Dynamics");
        if (open < 0) {
            return List.of();
        }
        int body = source.indexOf("  } {\n", open) + "  } {\n".length();
        List<Bracket> brackets = new ArrayList<>();
        String pending = null;
        String tag = null;
        int firstBar = 0;
        int bar = -1;
        for (String line : source.substring(body, source.indexOf("\n  }\n", body)).split("\n")) {
            String stripped = line.strip();
            Matcher label = BRACKET_TAG.matcher(stripped);
            if (label.matches()) {
                pending = label.group(1);
                continue;
            }
            bar++;
            if (stripped.contains("\\startTextSpan")) {
                tag = pending;
                firstBar = bar;
                pending = null;
            }
            if (stripped.contains("\\stopTextSpan")) {
                brackets.add(new Bracket(tag, firstBar, bar));
                tag = null;
            }
        }
        assertThat(tag).as("every bracket the emitter opened, it closed").isNull();
        assertThat(pending).as("every label the emitter wrote belongs to a bracket").isNull();
        return brackets;
    }

    /** The text chart's tag for each printed line, {@code "."} where it has none. */
    private static String textTags(Score score) {
        return textTagsOf(ChordChart.toText(score, TAGGED));
    }

    /** The same, over a chart the caller has already rendered. */
    private static String textTagsOf(String chart) {
        StringBuilder tags = new StringBuilder();
        for (String line : chart.lines().toList()) {
            if (line.startsWith("|")) {
                Matcher tag = Pattern.compile("\\[([A-Z]+)]$").matcher(line);
                tags.append(tag.find() ? tag.group(1) : ".");
            }
        }
        return tags.toString();
    }

    @Test
    @DisplayName("no tags at all unless the caller asks for them")
    void tagsAreOffByDefault() {
        // Held on a chart that does repeat, so what is asserted is the gate
        // rather than nothing having been found.
        Score repeats = fourChordSong(3);

        assertThat(textTagsOf(ChordChart.toText(repeats))).isEqualTo("...");
        assertThat(ChordChart.toText(repeats)).doesNotContain("Tags");
        assertThat(bracketsOf(ChordChart.toLilyPond(repeats))).isEmpty();
        assertThat(ChordChart.toLilyPond(repeats)).doesNotContain("\\new Dynamics");
    }

    @Test
    @DisplayName("says nothing at all about a chart that never repeats a line")
    void aChartThatDoesNotRepeatIsNotAnnotated() {
        Score once = fourChordSong(1);

        assertThat(textTags(once)).isEqualTo(".");
        assertThat(ChordChart.toText(once, TAGGED)).doesNotContain("Tags");
        assertThat(bracketsOf(ChordChart.toLilyPond(once, TAGGED))).isEmpty();
        assertThat(ChordChart.toLilyPond(once, TAGGED))
                .as("no annotation, no context to carry it")
                .doesNotContain("\\new Dynamics");
    }

    @Test
    @DisplayName("tags every printing of a repeated line, not only the first")
    void everyPrintingOfARepeatedLineIsTagged() {
        // The bounded reading, and the point of #218's rework: a tag on the
        // first occurrence alone is a heading, and a heading runs to the next
        // one -- which on a real recording meant a section announced over
        // scores of bars nothing had looked at.
        assertThat(textTags(fourChordSong(3))).isEqualTo("AAA");
        // With the one line that says what a tag is. Its absence is asserted on
        // a chart that does not repeat; without this, deleting it outright would
        // leave the suite green.
        assertThat(ChordChart.toText(fourChordSong(3), TAGGED)).contains("Tags   [A]");
    }

    @Test
    @DisplayName("brackets exactly the bars of the line it tags")
    void aBracketCoversItsOwnLine() {
        assertThat(bracketsOf(ChordChart.toLilyPond(fourChordSong(3), TAGGED)))
                .containsExactly(new Bracket("A", 0, 3),
                        new Bracket("A", 4, 7),
                        new Bracket("A", 8, 11));
    }

    @Test
    @DisplayName("the page and the text chart tag the same lines")
    void bothOutputsReadOneAnswer() {
        // #174's failure mode, at the level of the annotation: two outputs of
        // one score deriving the same thing separately and disagreeing. Both
        // read LineRepeats over the same printed lines, so the tags and the
        // lines they fall on have to match.
        Score score = fourChordSong(3);
        String tags = textTags(score);

        List<Bracket> brackets = bracketsOf(ChordChart.toLilyPond(score, TAGGED));
        assertThat(brackets).hasSize((int) tags.chars().filter(c -> c != '.').count());
        for (Bracket bracket : brackets) {
            assertThat(bracket.tag())
                    .isEqualTo(String.valueOf(tags.charAt(bracket.firstBar() / 4)));
        }
    }

    @Test
    @DisplayName("writes the brackets on the same durations as the chords under them")
    void theBracketsRideOnTheChordTimeline() {
        // What keeps a bracket's ends where its line's ends are: the spacers it
        // is spelled against are the chord cells' own durations, written by the
        // same call. If the two timelines could come apart, a bracket would
        // still be emitted and would simply cover the wrong bars.
        String source = ChordChart.toLilyPond(aSplitBarLineTwice(), TAGGED);

        assertThat(spacerDurations(source)).isEqualTo(chordDurations(source));
    }

    /** {@link #twoChordsInABar}'s line printed twice, so a bracket covers a split bar. */
    private static Score aSplitBarLineTwice() {
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.A, NoteLetter.F, NoteLetter.G};
        ChordQuality[] qualities = {ChordQuality.MAJOR, ChordQuality.MINOR,
                ChordQuality.MAJOR, ChordQuality.MAJOR};
        List<Chord> chords = new ArrayList<>();
        for (int cycle = 0; cycle < 2; cycle++) {
            double offset = 16 * cycle;
            for (int i = 0; i < roots.length; i++) {
                chords.add(quantized(map, root(roots[i]), qualities[i],
                        offset + 2 * i, offset + 2 * i + 2));
            }
            // Not C, so the next cycle's opening C is a change and is named:
            // a cell is printed only where the chord differs from the one
            // before it, which is exactly what makes two lines of one harmony
            // able to print differently.
            chords.add(quantized(map, root(NoteLetter.D), ChordQuality.MAJOR,
                    offset + 8, offset + 16));
        }
        return Score.empty(map, map.beatsToSeconds(32))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    private static Chord quantized(TempoMap map, PitchSpelling chordRoot, ChordQuality quality,
            double fromBeat, double toBeat) {
        return Chord.ofSeconds(chordRoot, quality, map.beatsToSeconds(fromBeat),
                        map.beatsToSeconds(toBeat), Confidence.of(0.9))
                .quantizedTo(fromBeat, toBeat);
    }

    private static final Pattern CHORD_DURATION = Pattern.compile(
            "^(?:r|[a-g](?:is|es)*)(\\d+\\.?(?:\\*\\d+(?:/\\d+)?)?)");

    private static final Pattern SPACER_DURATION = Pattern.compile(
            "^s(\\d+\\.?(?:\\*\\d+(?:/\\d+)?)?)(?:\\\\\\w+)*$");

    /** Every chord cell's written duration, in order. */
    private static List<String> chordDurations(String source) {
        List<String> durations = new ArrayList<>();
        for (String line : chordModeOf(source)) {
            if (line.startsWith("\\")) {
                continue;
            }
            for (String token : line.substring(0, line.length() - 1).trim().split(" +")) {
                Matcher duration = CHORD_DURATION.matcher(token);
                assertThat(duration.find()).as("chordmode token %s", token).isTrue();
                durations.add(duration.group(1));
            }
        }
        return durations;
    }

    /** Every spacer's written duration in the annotation context, in order. */
    private static List<String> spacerDurations(String source) {
        int open = source.indexOf("\\new Dynamics");
        assertThat(open).as("the chart carries an annotation context").isNotNegative();
        int body = source.indexOf("  } {\n", open) + "  } {\n".length();
        List<String> durations = new ArrayList<>();
        for (String line : source.substring(body, source.indexOf("\n  }\n", body)).split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("\\")) {
                continue;
            }
            for (String token : stripped.split(" +")) {
                Matcher duration = SPACER_DURATION.matcher(token);
                assertThat(duration.matches()).as("annotation token %s", token).isTrue();
                durations.add(duration.group(1));
            }
        }
        return durations;
    }

    @Test
    @DisplayName("keeps the annotation off the lines a bar check closes")
    void nothingButChordsShareALineWithABarCheck() {
        // Those lines are read back as the chart's bars -- by
        // tools/score-chart.py, which scores what the chart prints, and by
        // ChordChartEngravingIT, which counts them against the bar lines
        // LilyPond drew. Both take a line ending in a bar check and not opening
        // with a backslash, and both would break on a chord carrying a
        // post-event. It is why the brackets ride in a context of their own.
        for (String line : ChordChart.toLilyPond(fourChordSong(3), TAGGED).lines().toList()) {
            String stripped = line.strip();
            if (!stripped.endsWith("|") || stripped.startsWith("\\")) {
                continue;
            }
            assertThat(stripped.substring(0, stripped.length() - 1).trim().split(" +"))
                    .as("%s", stripped)
                    .allSatisfy(token -> assertThat(token).doesNotContain("\\"));
        }
    }

    @Test
    @DisplayName("asks for a broken bracket to be drawn as one bracket, not two")
    void aBrokenBracketIsAskedToKeepOneOfEachEnd() {
        // LilyPond may break a system anywhere, so a bracket is routinely drawn
        // in pieces; each piece takes the label and the closing hook unless it
        // is told not to, and then reads as a whole bracket over part of a line.
        // Like the bar-extent request above, this only says the request is made.
        // ChordChartEngravingIT engraves a bracket across a break and counts the
        // labels and the hooks LilyPond drew.
        String source = ChordChart.toLilyPond(fourChordSong(3), TAGGED);

        assertThat(source)
                .contains("\\override TextSpanner.bound-details.left-broken.text = ##f")
                .contains("\\override TextSpanner.bound-details.right-broken.text = ##f");
    }

    @Test
    @DisplayName("never tags a line short of a full one, however much the chart repeats")
    void aShortLastLineIsNeverTagged() {
        // A short line prints fewer bar lines, so it is never character-equal to
        // a full one and never carries a tag. The emitter depends on that: a
        // tagged line always holds at least two cells, so no bracket is ever
        // asked to open and close on one moment, which LilyPond refuses.
        Score score = aChordPerBar(9);

        assertThat(textTags(score)).isEqualTo("AA.");
        assertThat(bracketsOf(ChordChart.toLilyPond(score, TAGGED)))
                .allSatisfy(bracket -> assertThat(bracket.lastBar())
                        .isGreaterThan(bracket.firstBar()));
    }
}
