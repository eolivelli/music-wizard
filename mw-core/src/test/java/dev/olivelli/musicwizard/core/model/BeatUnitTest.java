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

package dev.olivelli.musicwizard.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The seam between "how much music is in a bar" and "how that bar divides into
 * beats".
 *
 * <p>The pipeline measures every duration in quarter notes, whatever the meter,
 * and that is not negotiable -- it is what makes quantization, chord alignment
 * and lyric placement mutually consistent. What was missing is a way to say that
 * a <em>counted</em> beat can be longer than a quarter. Without it 3/4 and 6/8
 * are indistinguishable to everything that bars, beams or converts tracked
 * pulses, because they hold exactly the same three quarter notes per bar, and a
 * 6/8 piece comes out barred as though it were 3/4.
 */
class BeatUnitTest {

    /** Every signature the model accepts. */
    private static List<TimeSignature> allSignatures() {
        List<TimeSignature> all = new ArrayList<>();
        for (int denominator = 1; denominator <= 64; denominator *= 2) {
            for (int numerator = 1; numerator <= 64; numerator++) {
                all.add(new TimeSignature(numerator, denominator));
            }
        }
        return all;
    }

    @Nested
    @DisplayName("the counted beat")
    class CountedBeat {

        @Test
        @DisplayName("names the note value that gets the beat")
        void namesTheBeatUnit() {
            // Simple meters: the denominator gets the beat, exactly as written.
            assertThat(TimeSignature.FOUR_FOUR.beatUnitQuarters()).isEqualTo(1.0);
            assertThat(TimeSignature.FOUR_FOUR.beatsPerBar()).isEqualTo(4);
            assertThat(TimeSignature.THREE_FOUR.beatUnitQuarters()).isEqualTo(1.0);
            assertThat(TimeSignature.THREE_FOUR.beatsPerBar()).isEqualTo(3);
            assertThat(new TimeSignature(2, 2).beatUnitQuarters()).isEqualTo(2.0);
            assertThat(new TimeSignature(2, 2).beatsPerBar()).isEqualTo(2);

            // Compound meters: three denominator units make one dotted beat.
            assertThat(TimeSignature.SIX_EIGHT.beatUnitQuarters()).isEqualTo(1.5);
            assertThat(TimeSignature.SIX_EIGHT.beatsPerBar()).isEqualTo(2);
            assertThat(new TimeSignature(9, 8).beatUnitQuarters()).isEqualTo(1.5);
            assertThat(new TimeSignature(9, 8).beatsPerBar()).isEqualTo(3);
            assertThat(new TimeSignature(12, 8).beatUnitQuarters()).isEqualTo(1.5);
            assertThat(new TimeSignature(12, 8).beatsPerBar()).isEqualTo(4);
            // A dotted eighth, not a dotted quarter -- the dot is on whatever the
            // denominator names, so this cannot be hardcoded to 1.5.
            assertThat(new TimeSignature(6, 16).beatUnitQuarters()).isEqualTo(0.75);
            assertThat(new TimeSignature(6, 16).beatsPerBar()).isEqualTo(2);
        }

        @Test
        @DisplayName("leaves the irregular meters regular rather than guessing a grouping")
        void irregularMetersAreNotGuessed() {
            // 5/4 and 7/8 have conventional asymmetric groupings, but which one is
            // genuinely ambiguous (2+2+3 and 3+2+2 both occur), so the model
            // declines to invent one. It still bars them at the right length --
            // that is the part this issue is about. See #62.
            assertThat(new TimeSignature(5, 4).beatsPerBar()).isEqualTo(5);
            assertThat(new TimeSignature(5, 4).beatUnitQuarters()).isEqualTo(1.0);
            assertThat(new TimeSignature(5, 4).quarterBeatsPerBar()).isEqualTo(5.0);

            assertThat(new TimeSignature(7, 8).beatsPerBar()).isEqualTo(7);
            assertThat(new TimeSignature(7, 8).beatUnitQuarters()).isEqualTo(0.5);
            assertThat(new TimeSignature(7, 8).quarterBeatsPerBar()).isEqualTo(3.5);

            // 3/8 is simple triple, not compound: three eighth beats, no dot.
            assertThat(new TimeSignature(3, 8).beatsPerBar()).isEqualTo(3);
            assertThat(new TimeSignature(3, 8).beatUnitQuarters()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("beats fill the bar exactly, for every signature the model accepts")
        void beatsFillTheBarExactly() {
            // Exactly, not approximately. A beat unit that is a hair short leaves
            // the last beat of a bar sitting a rounding error before the bar line;
            // a hair long puts it in the next bar. Both are silent.
            for (TimeSignature meter : allSignatures()) {
                assertThat(meter.beatsPerBar() * meter.beatUnitQuarters())
                        .as("beats fill a %s bar", meter)
                        .isEqualTo(meter.quarterBeatsPerBar());
                assertThat(meter.beatStructure().stream().mapToInt(Integer::intValue).sum())
                        .as("beat structure of %s sums to its numerator", meter)
                        .isEqualTo(meter.numerator());
                assertThat(meter.beatStructure())
                        .as("one entry per counted beat in %s", meter)
                        .hasSize(meter.beatsPerBar());
            }
        }

        @Test
        @DisplayName("tells 3/4 and 6/8 apart, which quarter-beat content alone cannot")
        void distinguishesThreeFourFromSixEight() {
            TimeSignature three = TimeSignature.THREE_FOUR;
            TimeSignature six = TimeSignature.SIX_EIGHT;

            // Identical by the measure the rest of the pipeline uses...
            assertThat(three.quarterBeatsPerBar()).isEqualTo(six.quarterBeatsPerBar());
            // ...and different in every way a bar line or a beam cares about.
            assertThat(three.beatsPerBar()).isNotEqualTo(six.beatsPerBar());
            assertThat(three.beatUnitQuarters()).isNotEqualTo(six.beatUnitQuarters());
            assertThat(three.beatStructure()).containsExactly(1, 1, 1);
            assertThat(six.beatStructure()).containsExactly(3, 3);
            assertThat(three).isNotEqualTo(six);
        }
    }

    @Nested
    @DisplayName("tracked pulses to musical time")
    class TrackedPulses {

        /** Where each tracked pulse lands, read back the way notation reads it. */
        private List<String> positionsOf(List<Double> pulses, TimeSignature meter) {
            TempoMap map = TempoMap.fromBeatTimes(pulses, meter);
            return pulses.stream()
                    .map(seconds -> map.toMusicalTime(map.secondsToBeats(seconds)).toString())
                    .toList();
        }

        @Test
        @DisplayName("bars 6/8 every two pulses, because the pulse is a dotted quarter")
        void barsCompoundTimeOnTheDottedQuarter() {
            // The reported defect, end to end. A beat tracker in 6/8 emits a
            // dotted-quarter pulse, so these six pulses are three bars of two, not
            // two bars of three. Before the beat unit existed this produced
            // "bar 1 beat 1/2/3, bar 2 beat 1/2/3".
            List<Double> pulses = List.of(0.0, 0.5, 1.0, 1.5, 2.0, 2.5);

            assertThat(positionsOf(pulses, TimeSignature.SIX_EIGHT)).containsExactly(
                    "bar 1 beat 1.0", "bar 1 beat 2.0",
                    "bar 2 beat 1.0", "bar 2 beat 2.0",
                    "bar 3 beat 1.0", "bar 3 beat 2.0");
        }

        @Test
        @DisplayName("bars the same pulses every three in 3/4")
        void barsSimpleTimeOnTheQuarter() {
            // The same six pulses under the meter that holds the same three
            // quarter notes per bar. The two meters must not agree here, or the
            // model still cannot tell them apart.
            List<Double> pulses = List.of(0.0, 0.5, 1.0, 1.5, 2.0, 2.5);

            assertThat(positionsOf(pulses, TimeSignature.THREE_FOUR)).containsExactly(
                    "bar 1 beat 1.0", "bar 1 beat 2.0", "bar 1 beat 3.0",
                    "bar 2 beat 1.0", "bar 2 beat 2.0", "bar 2 beat 3.0");
        }

        @Test
        @DisplayName("bars 9/8 in three and 12/8 in four")
        void barsTheOtherCompoundMeters() {
            List<Double> pulses = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                pulses.add(i * 0.5);
            }

            assertThat(positionsOf(pulses, new TimeSignature(9, 8)))
                    .startsWith("bar 1 beat 1.0", "bar 1 beat 2.0", "bar 1 beat 3.0",
                            "bar 2 beat 1.0")
                    .endsWith("bar 4 beat 3.0");
            assertThat(positionsOf(pulses, new TimeSignature(12, 8)))
                    .startsWith("bar 1 beat 1.0", "bar 1 beat 2.0", "bar 1 beat 3.0",
                            "bar 1 beat 4.0", "bar 2 beat 1.0")
                    .endsWith("bar 3 beat 4.0");
        }

        @Test
        @DisplayName("bars 5/4 in five and 7/8 in seven")
        void barsTheIrregularMeters() {
            List<Double> pulses = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                pulses.add(i * 0.5);
            }

            assertThat(positionsOf(pulses, new TimeSignature(5, 4)))
                    .startsWith("bar 1 beat 1.0")
                    .contains("bar 1 beat 5.0", "bar 2 beat 1.0")
                    .endsWith("bar 2 beat 5.0");
            // 3.5 quarter notes to the bar, so the bar line does not fall on a
            // quarter -- it still has to fall between pulse 7 and pulse 8.
            assertThat(positionsOf(pulses, new TimeSignature(7, 8)))
                    .startsWith("bar 1 beat 1.0")
                    .contains("bar 1 beat 7.0", "bar 2 beat 1.0")
                    .endsWith("bar 2 beat 3.0");
        }

        @Test
        @DisplayName("still reproduces the measured pulse times exactly in compound time")
        void preservesMeasuredTiming() {
            // The map may not buy correct barring with drift: a pulse must still
            // convert back to the second it was tracked at.
            List<Double> pulses = List.of(0.0, 0.51, 0.99, 1.62, 2.30);
            TempoMap map = TempoMap.fromBeatTimes(pulses, TimeSignature.SIX_EIGHT);

            for (int i = 0; i < pulses.size(); i++) {
                assertThat(map.beatsToSeconds(i * 1.5))
                        .as("pulse %d", i)
                        .isCloseTo(pulses.get(i), within(1e-9));
                assertThat(map.secondsToBeats(pulses.get(i)))
                        .isCloseTo(i * 1.5, within(1e-9));
            }
            // And the tempo is reported in quarter notes, not in pulses: the
            // opening dotted-quarter pulse lasts 0.51s, which is 1.5 quarter
            // notes in 0.51s and so 176.5 quarter-note BPM, not 117.6.
            assertThat(map.initialTempo()).isCloseTo(60.0 * 1.5 / 0.51, within(1e-9));
        }

        @Test
        @DisplayName("rounds a compound lead-in to whole pulses, not whole quarters")
        void leadInIsCountedInPulses() {
            // A whole number of quarters is not a whole number of dotted quarters,
            // so rounding the lead-in in the wrong unit leaves every tracked pulse
            // a third of a beat off the grid and every bar line with it.
            for (double firstPulse : new double[] {0.001, 0.2, 0.5, 0.75, 1.0, 1.6, 7.3}) {
                List<Double> pulses = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    pulses.add(firstPulse + i * 0.5);
                }
                TempoMap map = TempoMap.fromBeatTimes(pulses, TimeSignature.SIX_EIGHT);

                assertThat(map.beatsToSeconds(0.0))
                        .as("anchored at second 0 for firstPulse=%s", firstPulse)
                        .isCloseTo(0.0, within(1e-9));
                for (int i = 0; i < pulses.size(); i++) {
                    double beat = map.secondsToBeats(pulses.get(i)) / 1.5;
                    assertThat(beat - Math.rint(beat))
                            .as("pulse %d on a whole pulse for firstPulse=%s", i, firstPulse)
                            .isCloseTo(0.0, within(1e-6));
                }
            }
        }

        @Test
        @DisplayName("takes an explicit pulse for a grid tracked off the counted beat")
        void honoursAnExplicitPulse() {
            // A tracker that locked onto half tempo in 4/4: each pulse is a half
            // note. The meter cannot say this, so the caller does.
            List<Double> pulses = List.of(0.0, 1.0, 2.0, 3.0, 4.0);
            TempoMap map = TempoMap.fromBeatTimes(pulses, TimeSignature.FOUR_FOUR, 2.0);

            assertThat(map.secondsToBeats(1.0)).isCloseTo(2.0, within(1e-9));
            assertThat(map.initialTempo()).isCloseTo(120.0, within(1e-9));
            // Two pulses to a 4/4 bar at this pulse.
            assertThat(map.toMusicalTime(map.secondsToBeats(2.0)))
                    .isEqualTo(new MusicalTime(1, 0, TimeSignature.FOUR_FOUR));
        }

        @Test
        @DisplayName("a typed tempo and a tracked one agree, given a grid starting at t=0")
        void aTypedTempoAgreesWithATrackedOne() {
            // The regression this guards was introduced by the fix above, not by
            // the original bug: teaching fromBeatTimes about the counted beat and
            // leaving the tempo-override path reading quarter notes put the two
            // 1.5x apart in 6/8, so correcting a tempo silently moved every bar
            // line. Before the beat unit existed the two agreed, and they have to
            // go on agreeing.
            for (TimeSignature meter : List.of(TimeSignature.FOUR_FOUR,
                    TimeSignature.THREE_FOUR, TimeSignature.SIX_EIGHT,
                    new TimeSignature(9, 8), new TimeSignature(12, 8),
                    new TimeSignature(2, 2), new TimeSignature(7, 8))) {
                // A grid tracked at exactly 120 pulses a minute, and the same 120
                // typed in by hand.
                List<Double> pulses = new ArrayList<>();
                for (int i = 0; i < 24; i++) {
                    pulses.add(i * 0.5);
                }
                TempoMap tracked = TempoMap.fromBeatTimes(pulses, meter);
                TempoMap typed = TempoMap.constantPulse(120, meter);

                assertThat(typed.initialTempo())
                        .as("tempo in %s", meter)
                        .isCloseTo(tracked.initialTempo(), within(1e-9));
                assertThat(typed.beatsToSeconds(meter.quarterBeatsPerBar()))
                        .as("bar length in %s", meter)
                        .isCloseTo(tracked.beatsToSeconds(meter.quarterBeatsPerBar()), within(1e-9));
                for (int i = 0; i < pulses.size(); i++) {
                    assertThat(typed.secondsToBeats(pulses.get(i)))
                            .as("pulse %d of %s", i, meter)
                            .isCloseTo(tracked.secondsToBeats(pulses.get(i)), within(1e-9));
                    assertThat(typed.toMusicalTime(typed.secondsToBeats(pulses.get(i))))
                            .as("bar of pulse %d of %s", i, meter)
                            .isEqualTo(tracked.toMusicalTime(
                                    tracked.secondsToBeats(pulses.get(i))));
                }
            }
        }

        @Test
        @DisplayName("a typed tempo corrects the rate but not the phase, in any meter")
        void aTypedTempoDoesNotCarryTheLeadIn() {
            // The limit of the claim above, pinned so nobody reads more into it.
            // A constant map is anchored at (beat 0, second 0) and has no lead-in,
            // so a grid that starts part-way through a pulse keeps its rate and
            // loses its phase. Not a compound-meter problem -- 4/4 does it too --
            // and it is still true of this factory, which is why the transcriber
            // no longer builds a --tempo map from it alone: it anchors the
            // constant tempo on the first tracked pulse instead, so that a
            // Score's map and its own beat grid agree about where beat one is.
            // See AudioTranscriber.constantPulseFrom and #65.
            List<Double> pulses = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                pulses.add(0.3 + i * 0.5);
            }
            for (TimeSignature meter : List.of(TimeSignature.FOUR_FOUR, TimeSignature.SIX_EIGHT)) {
                TempoMap tracked = TempoMap.fromBeatTimes(pulses, meter);
                TempoMap typed = TempoMap.constantPulse(120, meter);

                // The rate is right...
                assertThat(typed.tempoAtBeat(20))
                        .as("rate in %s", meter)
                        .isCloseTo(tracked.tempoAtBeat(20), within(1e-9));
                // ...and the first tracked pulse is on a whole pulse under the
                // tracked map and is not under the typed one.
                double trackedPulse = tracked.secondsToBeats(pulses.get(0)) / meter.beatUnitQuarters();
                double typedPulse = typed.secondsToBeats(pulses.get(0)) / meter.beatUnitQuarters();
                assertThat(trackedPulse - Math.rint(trackedPulse))
                        .as("tracked phase in %s", meter)
                        .isCloseTo(0.0, within(1e-9));
                assertThat(typedPulse - Math.rint(typedPulse))
                        .as("typed phase in %s", meter)
                        .isNotCloseTo(0.0, within(1e-6));
            }
        }

        @Test
        @DisplayName("reads a typed tempo as quarter notes only where the two coincide")
        void constantPulseIsConstantOnlyInQuarterBeatMeters() {
            assertThat(TempoMap.constantPulse(120, TimeSignature.FOUR_FOUR))
                    .isEqualTo(TempoMap.constant(120, TimeSignature.FOUR_FOUR));
            // 120 dotted quarters a minute is 180 quarter notes a minute, and the
            // map stores quarter notes.
            assertThat(TempoMap.constantPulse(120, TimeSignature.SIX_EIGHT).initialTempo())
                    .isEqualTo(180.0);
            // Cut time counts half notes, so 120 of them is 240 quarter notes.
            assertThat(TempoMap.constantPulse(120, new TimeSignature(2, 2)).initialTempo())
                    .isEqualTo(240.0);
            // And back again, which is what a chart header and the CLI both print.
            assertThat(TimeSignature.SIX_EIGHT.countedTempo(180.0)).isEqualTo(120.0);
            assertThat(TimeSignature.FOUR_FOUR.countedTempo(180.0)).isEqualTo(180.0);
            assertThat(new TimeSignature(2, 2).countedTempo(240.0)).isEqualTo(120.0);
        }

        @Test
        @DisplayName("rejects a typed tempo in the unit it was typed in")
        void rejectsATypedTempoInTheTypedUnit() {
            // --tempo feeds this directly, so the message has to name the number
            // the user gave. Converting first reported a 6/8 tempo of -1 as -1.5.
            for (double bad : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
                assertThatThrownBy(() -> TempoMap.constantPulse(bad, TimeSignature.SIX_EIGHT))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("pulsesPerMinute")
                        .hasMessageContaining(String.valueOf(bad));
            }
            // Large enough that the conversion overflows, which used to surface as
            // "beatsPerMinute must be finite ... got: Infinity".
            assertThatThrownBy(() ->
                    TempoMap.constantPulse(Double.MAX_VALUE, new TimeSignature(1, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("too large to express as a tempo");
        }

        @Test
        @DisplayName("rejects a pulse that is not a positive number of quarters")
        void rejectsBadPulse() {
            List<Double> pulses = List.of(0.0, 0.5, 1.0);
            // The extremes are rejected here rather than several frames later,
            // where they surfaced as "inconsistent segment" or "beatsPerMinute
            // must be finite" -- messages about the symptom, not the mistake.
            for (double bad : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY,
                    Double.MIN_VALUE, 1e-320, Double.MAX_VALUE, 1e6}) {
                assertThatThrownBy(() ->
                        TempoMap.fromBeatTimes(pulses, TimeSignature.FOUR_FOUR, bad))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("pulseQuarters");
            }
        }
    }

    @Nested
    @DisplayName("simple meters are untouched")
    class SimpleMetersUnchanged {

        /**
         * {@code fromBeatTimes} exactly as it was before the beat unit existed,
         * kept as the oracle. Every meter whose counted beat is a quarter has to
         * agree with it to the last bit, because the whole pipeline's stored beat
         * values were produced by it.
         */
        private TempoMap asBefore(List<Double> beatSeconds, TimeSignature timeSignature) {
            double firstBeat = beatSeconds.get(0);
            double firstInterval = beatSeconds.get(1) - firstBeat;
            int leadInBeats = 0;
            if (firstBeat > 0) {
                double ratio = firstBeat / firstInterval;
                long rounded = Double.isFinite(ratio) ? Math.round(Math.min(ratio, 1e6)) : 1;
                leadInBeats = (int) Math.max(1, rounded);
            }
            List<TempoMap.TempoSegment> built = new ArrayList<>(beatSeconds.size() + 1);
            if (leadInBeats > 0) {
                built.add(new TempoMap.TempoSegment(0, 0.0, 60.0 * leadInBeats / firstBeat));
            }
            for (int i = 0; i < beatSeconds.size() - 1; i++) {
                double start = beatSeconds.get(i);
                double interval = beatSeconds.get(i + 1) - start;
                built.add(new TempoMap.TempoSegment(leadInBeats + i, start, 60.0 / interval));
            }
            return new TempoMap(built, List.of(new TempoMap.MeterChange(0, timeSignature)));
        }

        @Test
        @DisplayName("every quarter-beat meter builds the map it built before")
        void quarterBeatMetersAreBitForBitIdentical() {
            List<List<Double>> grids = List.of(
                    List.of(0.0, 0.5, 1.0, 1.5, 2.0),
                    List.of(0.37, 0.86, 1.34, 1.88, 2.41, 2.9),
                    List.of(2.0, 2.5, 3.0, 3.5, 4.0),
                    List.of(0.001, 0.501, 1.002, 1.499));

            int checked = 0;
            for (TimeSignature meter : allSignatures()) {
                if (meter.beatUnitQuarters() != 1.0) {
                    continue;
                }
                for (List<Double> grid : grids) {
                    // Record equality on doubles is bitwise, so this is the strict
                    // comparison it needs to be and not a tolerance.
                    assertThat(TempoMap.fromBeatTimes(grid, meter))
                            .as("meter %s on grid %s", meter, grid)
                            .isEqualTo(asBefore(grid, meter));
                    checked++;
                }
            }
            // Guards the guard: a filter that matched nothing would pass silently.
            assertThat(checked).isEqualTo(64 * grids.size());
        }

        @Test
        @DisplayName("an explicit quarter pulse is the same as no pulse at all")
        void explicitQuarterPulseMatchesTheDefault() {
            List<Double> grid = List.of(0.37, 0.86, 1.34, 1.88, 2.41);

            assertThat(TempoMap.fromBeatTimes(grid, TimeSignature.FOUR_FOUR, 1.0))
                    .isEqualTo(TempoMap.fromBeatTimes(grid, TimeSignature.FOUR_FOUR));
        }

        @Test
        @DisplayName("a stored map is read back exactly as written, whatever its meter")
        void storedMapsAreNotReinterpreted() {
            // The change is in how a map is built from tracked pulses, not in what
            // a map means. A 6/8 map already on disk keeps the beat positions it
            // was written with, or a score saved yesterday moves its own bar lines.
            TempoMap stored = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0, 120),
                            new TempoMap.TempoSegment(1, 0.5, 120)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.SIX_EIGHT)));

            assertThat(stored.secondsToBeats(0.5)).isEqualTo(1.0);
            assertThat(stored.toMusicalTime(1.0))
                    .isEqualTo(new MusicalTime(0, 1.0, TimeSignature.SIX_EIGHT));
        }
    }

    @Nested
    @DisplayName("reading a position back")
    class ReadingPositions {

        @Test
        @DisplayName("counts a 6/8 bar in two, while still measuring it in quarters")
        void countsInTheMetersOwnBeats() {
            MusicalTime second = new MusicalTime(0, 1.5, TimeSignature.SIX_EIGHT);

            assertThat(second.beatInBar()).isEqualTo(1.5);
            assertThat(second.beatNumber()).isEqualTo(2.0);
            assertThat(second).hasToString("bar 1 beat 2.0");
        }

        @Test
        @DisplayName("counts a quarter-beat meter exactly as it always did")
        void quarterBeatMetersReadTheSame() {
            for (TimeSignature meter : List.of(TimeSignature.FOUR_FOUR, TimeSignature.THREE_FOUR,
                    new TimeSignature(5, 4), new TimeSignature(2, 4), new TimeSignature(64, 4))) {
                for (double beatInBar = 0; beatInBar < meter.quarterBeatsPerBar(); beatInBar += 0.25) {
                    MusicalTime at = new MusicalTime(3, beatInBar, meter);
                    assertThat(at.beatNumber())
                            .as("%s at %s", meter, beatInBar)
                            .isEqualTo(beatInBar + 1);
                }
            }
        }
    }

    @Nested
    @DisplayName("grids and files")
    class GridsAndFiles {

        @Test
        @DisplayName("phases downbeats on the counted beat, not the numerator")
        void gridPhasesOnCountedBeats() {
            List<Double> pulses = List.of(0.0, 0.5, 1.0, 1.5, 2.0, 2.5);

            assertThat(BeatGrid.ofTimes(pulses, TimeSignature.SIX_EIGHT, Confidence.of(0.9))
                    .downbeatTimes()).containsExactly(0.0, 1.0, 2.0);
            assertThat(BeatGrid.ofTimes(pulses, TimeSignature.THREE_FOUR, Confidence.of(0.9))
                    .downbeatTimes()).containsExactly(0.0, 1.5);
            assertThat(BeatGrid.ofTimes(pulses, TimeSignature.FOUR_FOUR, Confidence.of(0.9))
                    .downbeatTimes()).containsExactly(0.0, 2.0);
        }

        @Test
        @DisplayName("answers the tempo question once, from the best evidence there is")
        void oneTempoAnswerNotTwo() {
            List<Double> pulses = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                pulses.add(0.05 + i * 0.5);
            }

            // Tracked: the grid wins over the map, because fromBeatTimes crams a
            // whole pulse into the 0.05s before the first tracked beat and the
            // map's average is measurably high for it.
            Score tracked = Score.empty(TempoMap.fromBeatTimes(pulses, TimeSignature.SIX_EIGHT),
                            12.0)
                    .withBeatGrid(BeatGrid.ofTimes(pulses, TimeSignature.SIX_EIGHT,
                            Confidence.of(0.9)));
            assertThat(tracked.estimatedTempo()).isCloseTo(180.0, within(1e-6));
            assertThat(tracked.tempoMap().averageTempo(12.0)).isGreaterThan(181.0);

            // Forced: a single-segment map did not come from tracked beats, so it
            // is a correction and it wins over the grid it is correcting.
            Score corrected = tracked.withTempoMap(
                    TempoMap.constantPulse(60, TimeSignature.SIX_EIGHT));
            assertThat(corrected.estimatedTempo()).isEqualTo(90.0);
            assertThat(TimeSignature.SIX_EIGHT.countedTempo(corrected.estimatedTempo()))
                    .isEqualTo(60.0);

            // Neither: all that is left is the map. It has to be a multi-segment
            // map, or the first rule answers and this tests nothing -- every
            // constant map is single-segment, which is the whole basis of rule 1.
            TempoMap measured = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0, 120),
                            new TempoMap.TempoSegment(4, 2.0, 60)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
            Score bare = Score.empty(measured, 10.0);
            assertThat(bare.tempoMap().segments()).hasSizeGreaterThan(1);
            assertThat(bare.beatGrid()).isEmpty();
            assertThat(bare.estimatedTempo()).isEqualTo(measured.averageTempo(10.0));
            assertThat(bare.estimatedTempo()).isCloseTo(72.0, within(1e-9));

            // A grid too short to have a median falls through to the map rather
            // than asking it for one: BeatGrid.medianPulseRate throws below two
            // beats, so the size check is load-bearing, not defensive.
            Score oneBeat = Score.empty(measured, 10.0)
                    .withBeatGrid(BeatGrid.ofTimes(List.of(0.05),
                            TimeSignature.FOUR_FOUR, Confidence.of(0.9)));
            assertThat(oneBeat.estimatedTempo()).isCloseTo(72.0, within(1e-9));
        }

        @Test
        @DisplayName("hears a tempo stated through a lead-in, and only through a lead-in")
        void aStatedTempoSurvivesItsLeadIn() {
            // A map that states one constant tempo is a correction, and has to win
            // over the grid it corrects. It used to be recognised by having a
            // single segment, which stopped being true when the transcriber began
            // anchoring a forced tempo on the first tracked pulse: the anchor adds
            // a lead-in segment, and --tempo 60 on a 120 BPM recording would have
            // gone back to reporting 120.
            List<Double> pulses = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                pulses.add(0.2 + i * 0.5);
            }
            BeatGrid grid = BeatGrid.ofTimes(pulses, TimeSignature.FOUR_FOUR, Confidence.of(0.9));
            // 60 counted beats a minute over a grid tracked at 120, anchored so
            // that the first tracked pulse lands on whole pulse 1: one second per
            // beat, so beat 1 is at 1.0s and the lead-in has to cover 0.2s.
            TempoMap anchored = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 60.0 / 0.2),
                            new TempoMap.TempoSegment(1.0, 0.2, 60.0)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
            Score corrected = Score.empty(anchored, 12.0).withBeatGrid(grid);

            assertThat(corrected.tempoMap().segments()).hasSizeGreaterThan(1);
            assertThat(grid.medianTempo(TimeSignature.FOUR_FOUR)).isCloseTo(120.0, within(1e-6));
            assertThat(corrected.estimatedTempo()).isEqualTo(60.0);
            // The lead-in's own 300 BPM is an artefact of where the pulse fell and
            // must never be the answer.
            assertThat(corrected.estimatedTempo()).isNotEqualTo(anchored.initialTempo());

            // The exemption is for a segment that really is a lead-in, identified
            // as the one before the segment starting at the grid's first beat.
            // Skipping segment zero unconditionally would report 60 for this map,
            // which states two tempi and no lead-in.
            TempoMap twoTempi = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 120.0),
                            new TempoMap.TempoSegment(4, 2.0, 60.0)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
            Score changing = Score.empty(twoTempi, 12.0).withBeatGrid(
                    BeatGrid.ofTimes(List.of(0.0, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0),
                            TimeSignature.FOUR_FOUR, Confidence.of(0.9)));
            assertThat(changing.estimatedTempo())
                    .isEqualTo(changing.beatGrid().orElseThrow()
                            .medianTempo(TimeSignature.FOUR_FOUR));

            // And with no grid there is nothing to identify the lead-in against,
            // so the same anchored map falls back rather than guessing.
            assertThat(Score.empty(anchored, 12.0).estimatedTempo())
                    .isEqualTo(anchored.averageTempo(12.0));
        }

        @Test
        @DisplayName("reports a grid's rate in pulses, and its tempo in quarter notes")
        void gridRateAndTempoAreDifferentNumbers() {
            // These used to be one method called medianTempo returning pulses per
            // minute, which is the same conflation this issue is about.
            BeatGrid grid = BeatGrid.ofTimes(List.of(0.0, 0.5, 1.0, 1.5, 2.0, 2.5),
                    TimeSignature.SIX_EIGHT, Confidence.of(0.9));

            assertThat(grid.medianPulseRate()).isCloseTo(120.0, within(1e-9));
            assertThat(grid.medianTempo(TimeSignature.SIX_EIGHT)).isCloseTo(180.0, within(1e-9));
            // And it agrees with the map built from the same pulses.
            assertThat(grid.medianTempo(TimeSignature.SIX_EIGHT)).isCloseTo(
                    TempoMap.fromBeatTimes(grid.beatTimes(), TimeSignature.SIX_EIGHT)
                            .initialTempo(), within(1e-9));
            assertThat(grid.medianTempo(TimeSignature.FOUR_FOUR))
                    .isEqualTo(grid.medianPulseRate());
        }

        /**
         * A {@code score.json} produced by the build before this change, byte for
         * byte: generated by checking out {@code origin/main}, running the
         * construction {@code AudioTranscriber} used there for a 6/8 analysis --
         * {@code fromBeatTimes(times, meter)} and
         * {@code ofTimes(times, meter.numerator(), ...)} -- and writing the file
         * through {@code ScoreJson}. Its seven pulses therefore sit on quarter
         * beats 0 through 6 and its grid bars every six pulses: both the old,
         * wrong answers, which is the point.
         */
        private String scoreBeforeTheBeatUnit() throws Exception {
            try (var in = getClass().getResourceAsStream("/score-before-the-beat-unit.json")) {
                assertThat(in).as("fixture on the test classpath").isNotNull();
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }

        @Test
        @DisplayName("still opens a score written before the beat unit existed")
        void readsAPreChangeScoreFile() throws Exception {
            // Nothing gained a serialized field, so this must load unchanged --
            // and keep the beat positions it was written with rather than being
            // reinterpreted under the new pulse. #22 records what happens when a
            // core change does silently invalidate old files.
            String json = scoreBeforeTheBeatUnit();
            assertThatCode(() -> ScoreJson.fromJson(json)).doesNotThrowAnyException();
            Score score = ScoreJson.fromJson(json);

            assertThat(score.tempoMap().initialTimeSignature()).isEqualTo(TimeSignature.SIX_EIGHT);
            assertThat(score.tempoMap().segments()).hasSize(7);
            // The stored -- and by today's rules wrong -- mapping, unchanged. A
            // file already on disk must not move its own bar lines.
            assertThat(score.tempoMap().secondsToBeats(1.5)).isEqualTo(3.0);
            assertThat(score.tempoMap().toMusicalTime(3.0))
                    .isEqualTo(new MusicalTime(1, 0.0, TimeSignature.SIX_EIGHT));
            assertThat(score.beatGrid()).isPresent();
            assertThat(score.beatGrid().get().downbeatTimes()).containsExactly(0.5, 3.5);
            assertThat(score.chords().size()).isEqualTo(2);
            // And it survives a write-read cycle on the new build.
            assertThat(ScoreJson.fromJson(ScoreJson.toJson(score))).isEqualTo(score);
        }

        @Test
        @DisplayName("round-trips a compound-meter score through JSON")
        void roundTripsCompoundMeter() {
            List<Double> pulses = List.of(0.5, 1.0, 1.5, 2.0);
            Score score = Score.empty(
                            TempoMap.fromBeatTimes(pulses, TimeSignature.SIX_EIGHT), 3.0)
                    .withBeatGrid(BeatGrid.ofTimes(pulses, TimeSignature.SIX_EIGHT,
                            Confidence.of(0.8)));

            Score back = ScoreJson.fromJson(ScoreJson.toJson(score));

            assertThat(back).isEqualTo(score);
            assertThat(back.tempoMap().initialTimeSignature().beatUnitQuarters()).isEqualTo(1.5);
        }
    }
}
