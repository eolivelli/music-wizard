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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A beat grid says what its tracked pulse is worth, instead of leaving every
 * reader to assume the meter's counted beat (#139).
 *
 * <p><b>No grid here starts at t = 0.</b> A map built from pulses starting at
 * the origin needs no lead-in, and the lead-in is where the pulse unit does its
 * other work -- it is measured in whole pulses, not whole quarter notes -- so a
 * fixture at the origin would agree with the wrong arithmetic as readily as
 * with the right one.
 */
@DisplayName("a beat grid says what one pulse is worth")
class PulseUnitTest {

    /** {@code count} pulses, {@code interval} apart, the first at {@code first}. */
    private static List<Double> pulses(double first, double interval, int count) {
        List<Double> times = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            times.add(first + i * interval);
        }
        return times;
    }

    @Nested
    @DisplayName("a grid tracked at half tempo")
    class HalfTempo {

        // One pulse per two quarter notes, one pulse a second: 60 pulses a
        // minute is 120 quarter notes a minute, and a 4/4 bar is two pulses.
        private static final double PULSE_QUARTERS = 2.0;
        private final List<Double> times = pulses(0.7, 1.0, 6);
        private final BeatGrid grid = BeatGrid.ofTimes(
                times, TimeSignature.FOUR_FOUR, PULSE_QUARTERS, Confidence.of(0.9));
        private final TempoMap map =
                TempoMap.fromBeatTimes(times, TimeSignature.FOUR_FOUR, PULSE_QUARTERS);

        @Test
        @DisplayName("reports the tempo the map built from the same pulses describes")
        void agreesWithTheMap() {
            // The defect, in the issue's own terms: both were internally
            // consistent and they answered figures a factor of two apart,
            // because only the map was told what a pulse was.
            assertThat(grid.medianPulseRate()).isCloseTo(60.0, within(1e-9));
            assertThat(grid.medianTempo(TimeSignature.FOUR_FOUR))
                    .isCloseTo(120.0, within(1e-9))
                    .isCloseTo(map.tempoAtBeat(map.secondsToBeats(0.7)), within(1e-9));
        }

        @Test
        @DisplayName("is what Score.estimatedTempo answers, since it prefers the grid")
        void scoreAnswersFromTheGrid() {
            Score score = Score.empty(map, 12.0).withBeatGrid(grid);

            // 60.0 before #139 -- half the tempo of the map in the same score.
            assertThat(score.estimatedTempo()).isCloseTo(120.0, within(1e-9));
            // Not by accident of the fallback path: the grid is what answered.
            assertThat(score.estimatedTempo())
                    .isEqualTo(grid.medianTempo(map.initialTimeSignature()));
        }

        @Test
        @DisplayName("bars every two pulses, because two of them fill a 4/4 bar")
        void barsByThePulseNotByTheBeatCount() {
            // The meter counts four beats to a bar and this grid holds two
            // pulses to a bar. Taking beatsPerBar() would mark a downbeat every
            // four pulses, which is every other bar.
            assertThat(grid.downbeatTimes()).containsExactly(0.7, 2.7, 4.7);
        }

        @Test
        @DisplayName("puts every tracked pulse on a whole pulse of the map")
        void tracksAndMapAgreeOnPositionsToo() {
            // The tempo agreeing is not enough: a grid and a map that agree on
            // the rate but not on where the pulses fall still misplace every
            // chord. The lead-in is a whole pulse, so pulse i sits at
            // (1 + i) * 2.0 quarter beats.
            for (int i = 0; i < times.size(); i++) {
                assertThat(map.secondsToBeats(times.get(i)))
                        .as("pulse %d at %ss", i, times.get(i))
                        .isCloseTo((1 + i) * PULSE_QUARTERS, within(1e-9));
            }
        }
    }

    @Nested
    @DisplayName("a grid that records nothing")
    class Unrecorded {

        @Test
        @DisplayName("still answers through the meter, which is what it was tracked at")
        void fallsBackToTheCountedBeat() {
            // Every grid built before #139 was tracked at the counted beat, so
            // this is not a guess about them -- it is the fact, and changing the
            // answer would move the tempo printed on charts already generated.
            List<Double> times = pulses(0.35, 0.5, 7);
            BeatGrid grid = BeatGrid.ofTimes(times, 2, Confidence.of(0.8));

            assertThat(grid.pulseQuarters()).isEmpty();
            assertThat(grid.medianTempo(TimeSignature.SIX_EIGHT)).isCloseTo(180.0, within(1e-9));
            assertThat(grid.medianTempo(TimeSignature.FOUR_FOUR)).isCloseTo(120.0, within(1e-9));
        }

        @Test
        @DisplayName("is what the three-argument constructor builds")
        void theShortConstructorRecordsNothing() {
            BeatGrid grid = new BeatGrid(
                    List.of(BeatGrid.Beat.unphased(0.4), BeatGrid.Beat.unphased(0.9)),
                    Confidence.of(0.5), Confidence.of(0.5));

            assertThat(grid.pulseQuarters()).isEmpty();
            assertThat(grid.withPulseQuarters(1.5).pulseQuarters()).hasValue(1.5);
            // And the copy changes nothing else.
            assertThat(grid.withPulseQuarters(1.5).beats()).isEqualTo(grid.beats());
        }

        @Test
        @DisplayName("cannot have its pulse recovered from its own bar cycle")
        void theBarCycleIsNotTheAnswer() {
            // Why this is a stored fact rather than a derived one. The arithmetic
            // looks sound -- pulses per bar times quarter notes per pulse is the
            // meter's quarter beats per bar, always -- and it fails on grids the
            // pipeline really produces.
            //
            // A grid shorter than one bar: three pulses of a 4/4 bar reach a
            // maximum positionInBar of 2, which would imply three pulses to the
            // bar and a pulse of 4/3 quarter notes.
            BeatGrid shorterThanABar =
                    BeatGrid.ofTimes(pulses(0.25, 0.5, 3), 4, Confidence.of(0.7));
            assertThat(shorterThanABar.beats().stream()
                    .mapToInt(BeatGrid.Beat::positionInBar).max().orElseThrow())
                    .isEqualTo(2);

            // And a grid whose pulses were never phased has no cycle at all.
            BeatGrid unphased = new BeatGrid(
                    List.of(BeatGrid.Beat.unphased(0.25), BeatGrid.Beat.unphased(0.75),
                            BeatGrid.Beat.unphased(1.25)),
                    Confidence.of(0.7), Confidence.of(0.0));
            assertThat(unphased.beats()).allMatch(beat -> beat.positionInBar() == -1);
            assertThat(unphased.downbeatTimes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the meter's counted beat, recorded rather than assumed")
    class CountedBeat {

        @Test
        @DisplayName("is what the meter-aware factory writes down")
        void ofTimesWithAMeterRecordsIt() {
            assertThat(BeatGrid.ofTimes(pulses(0.35, 0.5, 6), TimeSignature.SIX_EIGHT,
                            Confidence.of(0.8)).pulseQuarters())
                    .hasValue(1.5);
            assertThat(BeatGrid.ofTimes(pulses(0.35, 0.5, 6), TimeSignature.FOUR_FOUR,
                            Confidence.of(0.8)).pulseQuarters())
                    .hasValue(1.0);
            assertThat(BeatGrid.ofTimes(pulses(0.35, 0.5, 6), new TimeSignature(7, 8),
                            Confidence.of(0.8)).pulseQuarters())
                    .hasValue(0.5);
        }

        @Test
        @DisplayName("reports the tempo it always did, since it is the same figure")
        void recordingItChangesNoAnswer() {
            // Recording the assumption must not change the answer the assumption
            // produced, or #139 would be a silent tempo change for every score in
            // existence rather than a fix for one that cannot happen yet.
            for (TimeSignature meter : List.of(TimeSignature.FOUR_FOUR, TimeSignature.THREE_FOUR,
                    TimeSignature.SIX_EIGHT, new TimeSignature(9, 8), new TimeSignature(12, 8),
                    new TimeSignature(5, 4), new TimeSignature(7, 8))) {
                List<Double> times = pulses(0.35, 0.5, 8);
                BeatGrid recorded = BeatGrid.ofTimes(times, meter, Confidence.of(0.8));
                BeatGrid unrecorded = BeatGrid.ofTimes(times, meter.beatsPerBar(), Confidence.of(0.8));

                assertThat(recorded.medianTempo(meter))
                        .as("%s", meter)
                        .isEqualTo(unrecorded.medianPulseRate() * meter.beatUnitQuarters())
                        .isEqualTo(unrecorded.medianTempo(meter));
                // Right down to the bar phase: only the recorded pulse differs.
                assertThat(recorded.beats()).isEqualTo(unrecorded.beats());
            }
        }
    }

    @Nested
    @DisplayName("pulses per bar")
    class PulsesPerBar {

        @Test
        @DisplayName("is the counted beat count exactly, when the pulse is the counted beat")
        void agreesWithBeatsPerBarBitForBit() {
            // The whole change rests on this: every producer today tracks at the
            // counted beat, so deriving the bar length from the pulse instead of
            // asking the meter must be the same number, for every legal meter and
            // not merely for the ones a test happened to name.
            int checked = 0;
            for (int numerator = 1; numerator <= 64; numerator++) {
                for (int denominator : new int[] {1, 2, 4, 8, 16, 32, 64}) {
                    TimeSignature meter = new TimeSignature(numerator, denominator);
                    assertThat(meter.pulsesPerBar(meter.beatUnitQuarters()))
                            .as("%s", meter)
                            .isEqualTo(meter.beatsPerBar());
                    checked++;
                }
            }
            // Counted, so a loop that filtered everything out cannot pass quietly.
            assertThat(checked).isEqualTo(448);
        }

        @Test
        @DisplayName("counts by the pulse, which is the point")
        void countsByThePulse() {
            assertThat(TimeSignature.FOUR_FOUR.pulsesPerBar(2.0)).isEqualTo(2);
            assertThat(TimeSignature.FOUR_FOUR.pulsesPerBar(0.5)).isEqualTo(8);
            assertThat(TimeSignature.SIX_EIGHT.pulsesPerBar(0.5)).isEqualTo(6);
            assertThat(TimeSignature.SIX_EIGHT.pulsesPerBar(3.0)).isEqualTo(1);
            // The tolerance earns its place here rather than in prose: a pulse a
            // caller computed as 1.0/49 divides a 4/4 bar into 196.00000000000003.
            assertThat(TimeSignature.FOUR_FOUR.pulsesPerBar(1.0 / 49)).isEqualTo(196);
            assertThat(4.0 / (1.0 / 49)).isNotEqualTo(196.0);
        }

        @Test
        @DisplayName("refuses a pulse a bar cannot be built from")
        void refusesAPulseThatDoesNotDivide() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TimeSignature.FOUR_FOUR.pulsesPerBar(1.5))
                    .withMessageContaining("does not divide");
            // Longer than the bar: there is no bar left to bar.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TimeSignature.FOUR_FOUR.pulsesPerBar(8.0))
                    .withMessageContaining("does not fit");
            // And short enough to imply more pulses than an int can count, which
            // must be refused rather than wrapped into a negative bar length.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TimeSignature.FOUR_FOUR.pulsesPerBar(1e-9))
                    .withMessageContaining("does not fit");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TimeSignature.FOUR_FOUR.pulsesPerBar(Double.MIN_VALUE))
                    .withMessageContaining("does not fit");
            for (double bad : new double[] {Double.NaN, 0.0, -1.0, Double.POSITIVE_INFINITY}) {
                assertThatIllegalArgumentException()
                        .as("pulse %s", bad)
                        .isThrownBy(() -> TimeSignature.FOUR_FOUR.pulsesPerBar(bad))
                        .withMessageContaining("pulseQuarters");
            }
        }
    }

    @Nested
    @DisplayName("a pulse that is not a note value")
    class Validation {

        @Test
        @DisplayName("is rejected on the same bounds the tempo map rejects it on")
        void boundsMatchTheTempoMap() {
            // The two are given the same figure by the same caller, so a pulse
            // one accepts and the other rejects sends that caller looking for a
            // difference between them that is not there.
            for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.5,
                    1.0 / 2048, 2048.0}) {
                List<Double> times = pulses(0.4, 0.5, 4);
                assertThatIllegalArgumentException()
                        .as("pulse %s on the grid", bad)
                        .isThrownBy(() -> BeatGrid.ofTimes(
                                times, TimeSignature.FOUR_FOUR, bad, Confidence.of(0.8)))
                        .withMessageContaining("pulseQuarters");
                assertThatIllegalArgumentException()
                        .as("pulse %s on the map", bad)
                        .isThrownBy(() -> TempoMap.fromBeatTimes(
                                times, TimeSignature.FOUR_FOUR, bad))
                        .withMessageContaining("pulseQuarters");
            }
        }

        @Test
        @DisplayName("is rejected by the constructor too, not only by the factory")
        void theConstructorValidatesAsWell() {
            List<BeatGrid.Beat> beats = List.of(BeatGrid.Beat.unphased(0.4));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BeatGrid(beats, Confidence.of(0.5), Confidence.of(0.5),
                            OptionalDouble.of(Double.NaN)))
                    .withMessageContaining("pulseQuarters");
        }

        @Test
        @DisplayName("must divide the bar, or the grid could not say where one begins")
        void aPulseMustDivideTheBar() {
            // 4/4 is four quarter notes and a dotted quarter does not tile them.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BeatGrid.ofTimes(pulses(0.4, 0.5, 4),
                            TimeSignature.FOUR_FOUR, 1.5, Confidence.of(0.8)))
                    .withMessageContaining("does not divide");
            // But it tiles 6/8, 9/8 and 12/8 exactly, and 4/4 takes a half note.
            assertThatCode(() -> {
                BeatGrid.ofTimes(pulses(0.4, 0.5, 4), TimeSignature.SIX_EIGHT, 1.5,
                        Confidence.of(0.8));
                BeatGrid.ofTimes(pulses(0.4, 0.5, 4), new TimeSignature(9, 8), 1.5,
                        Confidence.of(0.8));
                BeatGrid.ofTimes(pulses(0.4, 0.5, 4), new TimeSignature(12, 8), 1.5,
                        Confidence.of(0.8));
                BeatGrid.ofTimes(pulses(0.4, 0.5, 4), TimeSignature.FOUR_FOUR, 2.0,
                        Confidence.of(0.8));
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("a pulse that contradicts the bars the grid marks")
    class ContradictsTheBars {

        /** A grid barred every {@code pulsesPerBar} pulses, recording {@code pulse}. */
        private static BeatGrid barredEvery(int pulsesPerBar, double pulse, int count) {
            return BeatGrid.ofTimes(pulses(0.4, 0.5, count), pulsesPerBar, Confidence.of(0.9))
                    .withPulseQuarters(pulse);
        }

        @Test
        @DisplayName("is rejected where the grid meets the meter, since nowhere lower can")
        void aPulseThatContradictsTheBarsIsRejected() {
            // The route round the factory: a grid is barred for one pulse and
            // then handed another, which withPulseQuarters cannot refuse because
            // a grid holds no meter. Left alone this is #139's symptom again --
            // the grid would report 180 where the map says 120.
            BeatGrid lying = barredEvery(4, 1.5, 8);
            assertThat(lying.medianTempo(TimeSignature.FOUR_FOUR)).isEqualTo(180.0);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Score.empty(
                            TempoMap.constant(120.0, TimeSignature.FOUR_FOUR), 12.0)
                            .withBeatGrid(lying))
                    .withMessageContaining("6.0 quarter notes to the bar")
                    .withMessageContaining("4/4 bar is 4.0");
        }

        @Test
        @DisplayName("is rejected when it arrives through a file rather than a setter")
        void deserializationIsCheckedToo() {
            String json = ScoreJson.toJson(Score.empty(
                            TempoMap.constant(120.0, TimeSignature.FOUR_FOUR), 12.0)
                    .withBeatGrid(barredEvery(4, 1.0, 8)))
                    .replace("\"pulseQuarters\" : 1.0", "\"pulseQuarters\" : 1.5");

            assertThatThrownBy(() -> ScoreJson.fromJson(json))
                    .isInstanceOf(UncheckedIOException.class)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quarter notes to the bar");
        }

        @Test
        @DisplayName("is not guessed at where the bar cycle was never observed")
        void whatTheCheckDeclinesToJudge() {
            TempoMap fourFour = TempoMap.constant(120.0, TimeSignature.FOUR_FOUR);

            // A clip shorter than a bar shows a cycle that is observably wrong --
            // the reason the pulse is stored rather than derived -- so a single
            // downbeat is not evidence of anything and must not reject a score.
            assertThatCode(() -> Score.empty(fourFour, 12.0)
                    .withBeatGrid(barredEvery(4, 1.5, 3)))
                    .doesNotThrowAnyException();

            // A grid that records nothing is every grid written before #139.
            assertThatCode(() -> Score.empty(fourFour, 12.0)
                    .withBeatGrid(BeatGrid.ofTimes(pulses(0.4, 0.5, 8), 4, Confidence.of(0.9))))
                    .doesNotThrowAnyException();

            // Bars are not one length either side of a meter change, so the first
            // cycle says nothing about the rest and the check stands down.
            assertThatCode(() -> Score.empty(
                    fourFour.withMeterChange(2, TimeSignature.THREE_FOUR), 12.0)
                    .withBeatGrid(barredEvery(4, 1.5, 8)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts every grid the pulse-aware factory builds")
        void theFactoryAndTheCheckAgree() {
            // The two must not be able to disagree: a pulse TimeSignature.
            // pulsesPerBar accepts for a meter has to survive meeting that meter
            // in a Score, or a legitimate producer would be building scores that
            // cannot be constructed.
            for (TimeSignature meter : List.of(TimeSignature.FOUR_FOUR, TimeSignature.THREE_FOUR,
                    TimeSignature.SIX_EIGHT, new TimeSignature(9, 8), new TimeSignature(7, 8),
                    new TimeSignature(5, 4), new TimeSignature(12, 8))) {
                for (double pulse : new double[] {0.25, 0.5, meter.beatUnitQuarters()}) {
                    int perBar;
                    try {
                        perBar = meter.pulsesPerBar(pulse);
                    } catch (IllegalArgumentException notThisMeter) {
                        continue;
                    }
                    BeatGrid grid = BeatGrid.ofTimes(
                            pulses(0.4, 0.5, 2 * perBar + 1), meter, pulse, Confidence.of(0.9));
                    assertThatCode(() -> Score.empty(
                            TempoMap.constant(120.0, meter), 12.0).withBeatGrid(grid))
                            .as("%s at %s quarter notes a pulse", meter, pulse)
                            .doesNotThrowAnyException();
                }
            }
        }
    }

    @Nested
    @DisplayName("score files")
    class Files {

        /**
         * A {@code score.json} produced by the build before this change, byte for
         * byte: generated by checking out {@code origin/main} at {@code 3f6b766}
         * and running the construction {@code AudioTranscriber} used there for a
         * 6/8 analysis -- {@code fromBeatTimes(times, meter)} and
         * {@code ofTimes(times, meter, confidence)} -- then writing it through
         * {@code ScoreJson}. Its grid therefore carries no pulse at all, which is
         * the point.
         */
        private String scoreBeforeThePulseUnit() throws Exception {
            try (var in = getClass().getResourceAsStream("/score-before-the-pulse-unit.json")) {
                assertThat(in).as("fixture on the test classpath").isNotNull();
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        @Test
        @DisplayName("a file written before the pulse existed still opens, and says the same")
        void readsAPreChangeScoreFile() throws Exception {
            String json = scoreBeforeThePulseUnit();
            // The property is genuinely absent rather than present and null, so
            // this is the case Jackson answers with a missing creator argument.
            assertThat(json).doesNotContain("pulseQuarters");
            assertThatCode(() -> ScoreJson.fromJson(json)).doesNotThrowAnyException();

            Score score = ScoreJson.fromJson(json);
            assertThat(score.tempoMap().initialTimeSignature()).isEqualTo(TimeSignature.SIX_EIGHT);
            assertThat(score.beatGrid()).isPresent();
            assertThat(score.beatGrid().get().pulseQuarters()).isEmpty();
            // 180.0 is what the pre-change build printed for this very file,
            // captured when it was generated.
            assertThat(score.beatGrid().get().medianPulseRate()).isEqualTo(120.0);
            assertThat(score.estimatedTempo()).isEqualTo(180.0);
            // And it survives a write-read cycle on the new build.
            assertThat(ScoreJson.fromJson(ScoreJson.toJson(score))).isEqualTo(score);
        }

        @Test
        @DisplayName("an explicit null pulse normalises rather than failing")
        void anExplicitNullIsAccepted() throws Exception {
            // Not the same case as the property being absent: Jackson reaches the
            // creator by a different route, and #142 is open about a newer build
            // writing something an older one chokes on.
            String json = scoreBeforeThePulseUnit()
                    .replace("\"beatConfidence\"", "\"pulseQuarters\" : null,\n \"beatConfidence\"");
            assertThat(json).contains("\"pulseQuarters\" : null");

            Score score = ScoreJson.fromJson(json);
            assertThat(score.beatGrid().orElseThrow().pulseQuarters()).isEmpty();
            assertThat(score.estimatedTempo()).isEqualTo(180.0);
        }

        @Test
        @DisplayName("a pulse that is not a note value is rejected on the way in")
        void deserializationCannotSmuggleABadPulse() {
            // Deserialization runs the compact constructor, so the bounds the
            // factories enforce hold for a hand-edited file too -- a zero pulse
            // is the one that would otherwise report an infinite tempo. The
            // constructor cannot check the pulse against the bar the grid marks,
            // because a grid holds no meter; that is Score's, and
            // aPulseThatContradictsTheBarsIsRejected covers it.
            String json = ScoreJson.toJson(Score.empty(
                            TempoMap.fromBeatTimes(pulses(0.7, 1.0, 3), TimeSignature.FOUR_FOUR, 2.0),
                            12.0)
                    .withBeatGrid(BeatGrid.ofTimes(
                            pulses(0.7, 1.0, 3), TimeSignature.FOUR_FOUR, 2.0, Confidence.of(0.9))))
                    .replace("\"pulseQuarters\" : 2.0", "\"pulseQuarters\" : 0.0");

            assertThatThrownBy(() -> ScoreJson.fromJson(json))
                    .isInstanceOf(UncheckedIOException.class)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pulseQuarters");
        }

        @Test
        @DisplayName("a recorded pulse round-trips")
        void aRecordedPulseSurvivesJson() {
            List<Double> times = pulses(0.7, 1.0, 6);
            Score score = Score.empty(
                            TempoMap.fromBeatTimes(times, TimeSignature.FOUR_FOUR, 2.0), 12.0)
                    .withBeatGrid(BeatGrid.ofTimes(
                            times, TimeSignature.FOUR_FOUR, 2.0, Confidence.of(0.9)));

            String json = ScoreJson.toJson(score);
            assertThat(json).contains("\"pulseQuarters\" : 2.0");

            Score back = ScoreJson.fromJson(json);
            assertThat(back).isEqualTo(score);
            assertThat(back.beatGrid().orElseThrow().pulseQuarters()).hasValue(2.0);
            assertThat(back.estimatedTempo()).isCloseTo(120.0, within(1e-9));
        }
    }
}
