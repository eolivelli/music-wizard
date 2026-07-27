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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The seconds-to-beats mapping is the hinge of the pipeline, so it is tested
 * against values that can be checked by hand rather than only for self-consistency.
 */
class TempoMapTest {

    @Nested
    @DisplayName("constant tempo")
    class ConstantTempo {

        @Test
        @DisplayName("120 BPM puts one beat every half second")
        void oneBeatPerHalfSecond() {
            TempoMap map = TempoMap.constant(120);

            assertThat(map.beatsToSeconds(0)).isEqualTo(0.0);
            assertThat(map.beatsToSeconds(1)).isEqualTo(0.5);
            assertThat(map.beatsToSeconds(4)).isEqualTo(2.0);
            assertThat(map.secondsToBeats(2.0)).isEqualTo(4.0);
        }

        @Test
        @DisplayName("60 BPM puts one beat every second")
        void oneBeatPerSecond() {
            TempoMap map = TempoMap.constant(60);

            assertThat(map.beatsToSeconds(7)).isEqualTo(7.0);
            assertThat(map.secondsToBeats(7.0)).isEqualTo(7.0);
        }

        @Test
        @DisplayName("converting to seconds and back is lossless")
        void roundTrips() {
            TempoMap map = TempoMap.constant(137.5);

            for (double beat = 0; beat < 64; beat += 0.125) {
                assertThat(map.secondsToBeats(map.beatsToSeconds(beat)))
                        .isCloseTo(beat, within(1e-9));
            }
        }
    }

    @Nested
    @DisplayName("tempo changes")
    class TempoChanges {

        /** 120 BPM for the first 4 beats (2s), then 60 BPM. */
        private TempoMap twoSegments() {
            return new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0, 120),
                            new TempoMap.TempoSegment(4, 2.0, 60)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        }

        @Test
        @DisplayName("times after the change use the new tempo")
        void appliesLaterTempo() {
            TempoMap map = twoSegments();

            assertThat(map.beatsToSeconds(4)).isEqualTo(2.0);
            // 2 further beats at 60 BPM take 2 seconds.
            assertThat(map.beatsToSeconds(6)).isEqualTo(4.0);
            assertThat(map.secondsToBeats(4.0)).isEqualTo(6.0);
        }

        @Test
        @DisplayName("round-trips across the segment boundary")
        void roundTripsAcrossBoundary() {
            TempoMap map = twoSegments();

            for (double beat = 0; beat < 12; beat += 0.25) {
                assertThat(map.secondsToBeats(map.beatsToSeconds(beat)))
                        .isCloseTo(beat, within(1e-9));
            }
        }

        @Test
        @DisplayName("reports the tempo in force at a beat")
        void reportsTempoAtBeat() {
            TempoMap map = twoSegments();

            assertThat(map.tempoAtBeat(0)).isEqualTo(120);
            assertThat(map.tempoAtBeat(3.9)).isEqualTo(120);
            assertThat(map.tempoAtBeat(4)).isEqualTo(60);
            assertThat(map.tempoAtBeat(100)).isEqualTo(60);
        }
    }

    @Nested
    @DisplayName("musical time")
    class MusicalTimeConversion {

        @Test
        @DisplayName("maps beats onto bars in 4/4")
        void barsInCommonTime() {
            TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);

            assertThat(map.toMusicalTime(0)).isEqualTo(
                    new MusicalTime(0, 0, TimeSignature.FOUR_FOUR));
            assertThat(map.toMusicalTime(3)).isEqualTo(
                    new MusicalTime(0, 3, TimeSignature.FOUR_FOUR));
            assertThat(map.toMusicalTime(4)).isEqualTo(
                    new MusicalTime(1, 0, TimeSignature.FOUR_FOUR));
            assertThat(map.toMusicalTime(9.5)).isEqualTo(
                    new MusicalTime(2, 1.5, TimeSignature.FOUR_FOUR));
        }

        @Test
        @DisplayName("counts 3/4 bars as three quarter beats")
        void barsInThreeFour() {
            TempoMap map = TempoMap.constant(120, TimeSignature.THREE_FOUR);

            assertThat(map.toMusicalTime(3).bar()).isEqualTo(1);
            assertThat(map.toMusicalTime(6).bar()).isEqualTo(2);
        }

        @Test
        @DisplayName("counts a 6/8 bar as three quarter beats, not six")
        void compoundTimeCountsQuarterBeats() {
            // This is the trap the model exists to avoid: 6/8 has six eighths
            // but only three quarter-note beats.
            assertThat(TimeSignature.SIX_EIGHT.quarterBeatsPerBar()).isEqualTo(3.0);

            TempoMap map = TempoMap.constant(120, TimeSignature.SIX_EIGHT);
            assertThat(map.toMusicalTime(3).bar()).isEqualTo(1);
        }

        @Test
        @DisplayName("round-trips bar positions back to beats")
        void roundTripsMusicalTime() {
            TempoMap map = TempoMap.constant(100, TimeSignature.THREE_FOUR);

            for (double beat = 0; beat < 30; beat += 0.5) {
                assertThat(map.toBeat(map.toMusicalTime(beat))).isCloseTo(beat, within(1e-9));
            }
        }

        @Test
        @DisplayName("honours a mid-piece meter change")
        void honoursMeterChange() {
            // 4/4 for two bars (8 beats), then 3/4.
            TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR)
                    .withMeterChange(2, TimeSignature.THREE_FOUR);

            assertThat(map.timeSignatureAtBar(1)).isEqualTo(TimeSignature.FOUR_FOUR);
            assertThat(map.timeSignatureAtBar(2)).isEqualTo(TimeSignature.THREE_FOUR);

            // Beat 8 starts bar 2, the first 3/4 bar.
            assertThat(map.toMusicalTime(8)).isEqualTo(
                    new MusicalTime(2, 0, TimeSignature.THREE_FOUR));
            // Bar 3 therefore starts three beats later, at beat 11.
            assertThat(map.toMusicalTime(11)).isEqualTo(
                    new MusicalTime(3, 0, TimeSignature.THREE_FOUR));
        }
    }

    @Nested
    @DisplayName("construction from tracked beats")
    class FromBeatTimes {

        @Test
        @DisplayName("preserves an uneven performance exactly")
        void preservesUnevenTiming() {
            // A performance that slows down: intervals 0.5, 0.5, 0.6, 0.7.
            List<Double> beats = List.of(0.0, 0.5, 1.0, 1.6, 2.3);
            TempoMap map = TempoMap.fromBeatTimes(beats, TimeSignature.FOUR_FOUR);

            for (int i = 0; i < beats.size(); i++) {
                assertThat(map.beatsToSeconds(i)).isCloseTo(beats.get(i), within(1e-9));
            }
        }

        @Test
        @DisplayName("computes a duration-weighted average tempo")
        void averagesTempo() {
            List<Double> beats = List.of(0.0, 0.5, 1.0, 1.5, 2.0);
            TempoMap map = TempoMap.fromBeatTimes(beats, TimeSignature.FOUR_FOUR);

            assertThat(map.averageTempo()).isCloseTo(120.0, within(1e-6));
        }

        @Test
        @DisplayName("rejects non-increasing beat times")
        void rejectsNonIncreasingBeats() {
            assertThatThrownBy(() ->
                    TempoMap.fromBeatTimes(List.of(0.0, 0.5, 0.4), TimeSignature.FOUR_FOUR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly increase");
        }

        @Test
        @DisplayName("rejects fewer than two beats")
        void rejectsTooFewBeats() {
            assertThatThrownBy(() ->
                    TempoMap.fromBeatTimes(List.of(0.0), TimeSignature.FOUR_FOUR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least two beats");
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects out-of-order tempo segments")
        void rejectsOutOfOrderSegments() {
            assertThatThrownBy(() -> new TempoMap(
                    List.of(new TempoMap.TempoSegment(4, 2.0, 120),
                            new TempoMap.TempoSegment(0, 0.0, 60)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ordered by start beat");
        }

        @Test
        @DisplayName("rejects a meter list that does not start at bar 0")
        void requiresMeterAtBarZero() {
            assertThatThrownBy(() -> new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0, 120)),
                    List.of(new TempoMap.MeterChange(4, TimeSignature.FOUR_FOUR))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be at bar 0");
        }

        @Test
        @DisplayName("rejects a non-positive tempo")
        void rejectsNonPositiveTempo() {
            assertThatThrownBy(() -> TempoMap.constant(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }
    }
}
