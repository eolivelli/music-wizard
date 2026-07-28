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
        @DisplayName("rejects a pulse that is not a positive number of quarters")
        void rejectsBadPulse() {
            List<Double> pulses = List.of(0.0, 0.5, 1.0);
            for (double bad : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
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

        /**
         * A {@code score.json} produced by the build before this change, captured
         * verbatim. It is a 6/8 score whose tempo segments sit on quarter-beats
         * 0, 1, 2, 3 -- the old, wrong pulse mapping.
         */
        private static final String SCORE_BEFORE_THE_BEAT_UNIT = """
                {
                  "title" : null,
                  "artist" : null,
                  "tempoMap" : {
                    "segments" : [ {
                      "startBeat" : 0.0, "startSeconds" : 0.0, "beatsPerMinute" : 120.0
                    }, {
                      "startBeat" : 1.0, "startSeconds" : 0.5, "beatsPerMinute" : 120.0
                    }, {
                      "startBeat" : 2.0, "startSeconds" : 1.0, "beatsPerMinute" : 120.0
                    }, {
                      "startBeat" : 3.0, "startSeconds" : 1.5, "beatsPerMinute" : 120.0
                    } ],
                    "meterChanges" : [ {
                      "startBar" : 0,
                      "timeSignature" : { "numerator" : 6, "denominator" : 8 }
                    } ]
                  },
                  "beatGrid" : {
                    "beats" : [
                      { "seconds" : 0.5, "downbeat" : true,  "positionInBar" : 0 },
                      { "seconds" : 1.0, "downbeat" : false, "positionInBar" : 1 },
                      { "seconds" : 1.5, "downbeat" : false, "positionInBar" : 2 },
                      { "seconds" : 2.0, "downbeat" : true,  "positionInBar" : 0 } ],
                    "beatConfidence" : { "value" : 0.8 },
                    "downbeatConfidence" : { "value" : 0.8 }
                  },
                  "keys" : [ ],
                  "sections" : [ ],
                  "tracks" : [ ],
                  "chords" : {
                    "chords" : [ {
                      "root" : { "letter" : "C", "accidental" : "NATURAL", "octave" : 4 },
                      "quality" : "MAJOR",
                      "bass" : null,
                      "startSeconds" : 0.5,
                      "endSeconds" : 2.0,
                      "startBeat" : null,
                      "endBeat" : null,
                      "confidence" : { "value" : 0.9 }
                    } ],
                    "confidence" : { "value" : 0.9 }
                  },
                  "lyrics" : {
                    "lines" : [ ], "language" : "und", "confidence" : { "value" : 0.0 }
                  },
                  "durationSeconds" : 3.0
                }
                """;

        @Test
        @DisplayName("still opens a score written before the beat unit existed")
        void readsAPreChangeScoreFile() {
            // Nothing gained a serialized field, so this must load unchanged --
            // and keep the beat positions it was written with rather than being
            // reinterpreted under the new pulse. #22 records what happens when a
            // core change does silently invalidate old files.
            assertThatCode(() -> ScoreJson.fromJson(SCORE_BEFORE_THE_BEAT_UNIT))
                    .doesNotThrowAnyException();
            Score score = ScoreJson.fromJson(SCORE_BEFORE_THE_BEAT_UNIT);

            assertThat(score.tempoMap().initialTimeSignature()).isEqualTo(TimeSignature.SIX_EIGHT);
            assertThat(score.tempoMap().segments()).hasSize(4);
            assertThat(score.tempoMap().secondsToBeats(1.5)).isEqualTo(3.0);
            assertThat(score.beatGrid()).isPresent();
            assertThat(score.beatGrid().get().downbeatTimes()).containsExactly(0.5, 2.0);
            assertThat(score.chords().size()).isEqualTo(1);
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
