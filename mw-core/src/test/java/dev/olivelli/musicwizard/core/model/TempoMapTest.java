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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;
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
            // Anchored at beat 0 so this exercises the ordering check specifically
            // rather than tripping the anchor check first.
            assertThatThrownBy(() -> new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 120),
                            new TempoMap.TempoSegment(8, 4.0, 120),
                            new TempoMap.TempoSegment(4, 6.0, 120)),
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

    /**
     * Segment lookup is a binary search rather than a scan, because a map built
     * from tracked beats has one segment per beat and every stage converts in a
     * loop. The search is only allowed to be faster: it must pick the same
     * segment as the scan it replaced for every key, on both axes, or the
     * seconds/beats boundary stops being the single consistent thing the whole
     * pipeline leans on.
     */
    @Nested
    @DisplayName("segment lookup")
    class SegmentLookup {

        /**
         * The lookup exactly as it was written before the binary search, kept
         * here as the oracle. The {@code break} is load-bearing: it is what makes
         * a key before the map starts -- and {@code NaN}, against which every
         * comparison is false -- fall back to the first segment.
         */
        private TempoMap.TempoSegment scan(
                List<TempoMap.TempoSegment> segments,
                double key,
                ToDoubleFunction<TempoMap.TempoSegment> axis) {
            TempoMap.TempoSegment found = segments.get(0);
            for (TempoMap.TempoSegment candidate : segments) {
                if (axis.applyAsDouble(candidate) <= key) {
                    found = candidate;
                } else {
                    break;
                }
            }
            return found;
        }

        /** Asserts binary search and scan agree on every interesting key. */
        private void assertAgreesWithScan(TempoMap map, Random random) {
            List<TempoMap.TempoSegment> segments = map.segments();
            for (double beat : probeKeys(segments, TempoMap.TempoSegment::startBeat, random)) {
                TempoMap.TempoSegment expected =
                        scan(segments, beat, TempoMap.TempoSegment::startBeat);
                assertThat(map.segmentAtBeat(beat))
                        .describedAs("segmentAtBeat(%s) over %d segments", beat, segments.size())
                        .isSameAs(expected);
                // The conversion is the point of the lookup, so check it too, and
                // bit-exactly: the arithmetic is unchanged, so an identical
                // segment must give an identical double, not merely a close one.
                assertThat(Double.doubleToLongBits(map.beatsToSeconds(beat)))
                        .describedAs("beatsToSeconds(%s)", beat)
                        .isEqualTo(Double.doubleToLongBits(expected.startSeconds()
                                + (beat - expected.startBeat()) * expected.secondsPerBeat()));
            }
            for (double seconds : probeKeys(segments, TempoMap.TempoSegment::startSeconds, random)) {
                TempoMap.TempoSegment expected =
                        scan(segments, seconds, TempoMap.TempoSegment::startSeconds);
                assertThat(map.segmentAtSeconds(seconds))
                        .describedAs("segmentAtSeconds(%s) over %d segments", seconds, segments.size())
                        .isSameAs(expected);
                assertThat(Double.doubleToLongBits(map.secondsToBeats(seconds)))
                        .describedAs("secondsToBeats(%s)", seconds)
                        .isEqualTo(Double.doubleToLongBits(expected.startBeat()
                                + (seconds - expected.startSeconds()) / expected.secondsPerBeat()));
            }
        }

        /**
         * Every segment boundary and its two neighbouring representable doubles,
         * because an off-by-one in a binary search shows up nowhere else, plus
         * the values that break naive comparisons.
         */
        private double[] probeKeys(
                List<TempoMap.TempoSegment> segments,
                ToDoubleFunction<TempoMap.TempoSegment> axis,
                Random random) {
            List<Double> keys = new ArrayList<>();
            for (TempoMap.TempoSegment segment : segments) {
                double at = axis.applyAsDouble(segment);
                keys.add(at);
                keys.add(Math.nextDown(at));
                keys.add(Math.nextUp(at));
            }
            double last = axis.applyAsDouble(segments.get(segments.size() - 1));
            keys.add(0.0);
            keys.add(-0.0);
            keys.add(-Double.MIN_VALUE);
            keys.add(Double.MIN_VALUE);
            keys.add(-1.0);
            keys.add(-1e300);
            keys.add(Double.MAX_VALUE);
            keys.add(Double.NEGATIVE_INFINITY);
            keys.add(Double.POSITIVE_INFINITY);
            keys.add(Double.NaN);
            for (int i = 0; i < 16; i++) {
                keys.add(-1.0 + random.nextDouble() * (last + 2.0));
            }
            double[] flattened = new double[keys.size()];
            for (int i = 0; i < flattened.length; i++) {
                flattened[i] = keys.get(i);
            }
            return flattened;
        }

        /** A map whose boundary seconds follow exactly from the stored tempi. */
        private TempoMap exactMap(int segmentCount, Random random) {
            List<TempoMap.TempoSegment> segments = new ArrayList<>(segmentCount);
            double beat = 0;
            double seconds = 0;
            double bpm = 40 + random.nextDouble() * 200;
            for (int i = 0; i < segmentCount; i++) {
                segments.add(new TempoMap.TempoSegment(beat, seconds, bpm));
                double beatDelta = 0.25 + random.nextDouble() * 8;
                beat += beatDelta;
                seconds += beatDelta * 60.0 / bpm;
                bpm = 40 + random.nextDouble() * 200;
            }
            return new TempoMap(segments, List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        }

        /**
         * A map whose boundary seconds were rounded to milliseconds, which the
         * constructor tolerates. Its axes are then very slightly out of step with
         * each other, which is exactly when a lookup that guesses one axis from
         * the other would go wrong.
         */
        private TempoMap millisecondRoundedMap(int segmentCount, Random random) {
            List<TempoMap.TempoSegment> segments = new ArrayList<>(segmentCount);
            double beat = 0;
            double seconds = 0;
            double bpm = 40 + random.nextDouble() * 200;
            for (int i = 0; i < segmentCount; i++) {
                // Rounding moves a boundary by at most half a millisecond, well
                // inside the 1.5 ms consistency tolerance, and the one-beat
                // minimum keeps the rounded times strictly increasing.
                double rounded = i == 0 ? 0.0 : Math.round(seconds * 1000.0) / 1000.0;
                segments.add(new TempoMap.TempoSegment(beat, rounded, bpm));
                double beatDelta = 1 + random.nextInt(8);
                beat += beatDelta;
                seconds = rounded + beatDelta * 60.0 / bpm;
                bpm = 40 + random.nextDouble() * 200;
            }
            return new TempoMap(segments, List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        }

        /** A map built the way the beat tracker builds one, lead-in included. */
        private TempoMap trackedMap(int beatCount, Random random) {
            List<Double> beatTimes = new ArrayList<>(beatCount);
            double t = random.nextInt(3) == 0 ? 0.0 : random.nextDouble() * 2.0;
            for (int i = 0; i < beatCount; i++) {
                beatTimes.add(t);
                t += 0.2 + random.nextDouble();
            }
            return TempoMap.fromBeatTimes(beatTimes, TimeSignature.FOUR_FOUR);
        }

        @Test
        @DisplayName("agrees with the linear scan on every generated map")
        void agreesWithLinearScanEverywhere() {
            Random random = new Random(20250727L);

            // Single-segment maps: the search must never move off index 0.
            assertAgreesWithScan(TempoMap.constant(120), random);
            assertAgreesWithScan(TempoMap.constant(37.4), random);

            for (int trial = 0; trial < 150; trial++) {
                // Sizes 1..48 so both parities and both loop shapes are covered;
                // an odd/even midpoint bug survives a single size.
                int size = 1 + random.nextInt(48);
                assertAgreesWithScan(exactMap(size, random), random);
                assertAgreesWithScan(millisecondRoundedMap(size, random), random);
                assertAgreesWithScan(trackedMap(1 + size, random), random);
            }

            // And one map large enough that the search does real work.
            assertAgreesWithScan(exactMap(4096, random), random);
            assertAgreesWithScan(trackedMap(3000, random), random);
        }

        @Test
        @DisplayName("a key before the map, and NaN, fall back to the first segment")
        void fallsBackToFirstSegment() {
            TempoMap map = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0, 120),
                            new TempoMap.TempoSegment(4, 2.0, 60),
                            new TempoMap.TempoSegment(8, 6.0, 90)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
            TempoMap.TempoSegment first = map.segments().get(0);

            assertThat(map.segmentAtBeat(-1)).isSameAs(first);
            assertThat(map.segmentAtSeconds(-1)).isSameAs(first);
            assertThat(map.segmentAtBeat(Double.NEGATIVE_INFINITY)).isSameAs(first);
            assertThat(map.segmentAtSeconds(Double.NEGATIVE_INFINITY)).isSameAs(first);
            assertThat(map.segmentAtBeat(Double.NaN)).isSameAs(first);
            assertThat(map.segmentAtSeconds(Double.NaN)).isSameAs(first);

            // -0.0 is at the boundary, not before it: 0.0 <= -0.0 numerically.
            assertThat(map.segmentAtBeat(-0.0)).isSameAs(first);
            assertThat(map.segmentAtSeconds(-0.0)).isSameAs(first);
        }

        @Test
        @DisplayName("boundaries belong to the segment they start")
        void boundariesBelongToTheSegmentTheyStart() {
            TempoMap map = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0, 120),
                            new TempoMap.TempoSegment(4, 2.0, 60),
                            new TempoMap.TempoSegment(8, 6.0, 90)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));

            assertThat(map.segmentAtBeat(4).beatsPerMinute()).isEqualTo(60);
            assertThat(map.segmentAtBeat(Math.nextDown(4.0)).beatsPerMinute()).isEqualTo(120);
            assertThat(map.segmentAtBeat(8).beatsPerMinute()).isEqualTo(90);
            assertThat(map.segmentAtBeat(Math.nextDown(8.0)).beatsPerMinute()).isEqualTo(60);
            assertThat(map.segmentAtSeconds(6.0).beatsPerMinute()).isEqualTo(90);
            assertThat(map.segmentAtSeconds(Math.nextDown(6.0)).beatsPerMinute()).isEqualTo(60);
            assertThat(map.segmentAtSeconds(Double.POSITIVE_INFINITY).beatsPerMinute()).isEqualTo(90);
        }

        @Test
        @DisplayName("lookup cost barely grows with the number of segments")
        void lookupCostIsSublinear() {
            // The regression this guards is a scan reappearing, which costs 128x
            // here; binary search costs about 1.6x plus cache effects. Asserting
            // a ratio rather than a wall-clock budget keeps it meaningful on a
            // slow or a loaded machine, and the margin either side is ~6x.
            int small = 2_000;
            int large = 256_000;
            TempoMap smallMap = constantIntervalMap(small);
            TempoMap largeMap = constantIntervalMap(large);

            double[] probes = new double[2_000];
            for (int i = 0; i < probes.length; i++) {
                probes[i] = (double) i / probes.length;
            }

            timeLookups(smallMap, probes, small);   // warm up and let the JIT settle
            timeLookups(largeMap, probes, large);

            long smallNanos = Long.MAX_VALUE;
            long largeNanos = Long.MAX_VALUE;
            for (int trial = 0; trial < 5; trial++) {
                smallNanos = Math.min(smallNanos, timeLookups(smallMap, probes, small));
                largeNanos = Math.min(largeNanos, timeLookups(largeMap, probes, large));
            }

            double ratio = (double) largeNanos / smallNanos;
            assertThat(ratio)
                    .describedAs("%dx more segments cost %.1fx more time (%d ns vs %d ns);"
                            + " lookup has gone linear again", large / small, ratio,
                            largeNanos, smallNanos)
                    .isLessThan(20.0);
        }

        /** Beat i at second i, so a probe in [0,1] of the span is easy to place. */
        private TempoMap constantIntervalMap(int segmentCount) {
            List<TempoMap.TempoSegment> segments = new ArrayList<>(segmentCount);
            for (int i = 0; i < segmentCount; i++) {
                segments.add(new TempoMap.TempoSegment(i, i, 60));
            }
            return new TempoMap(segments, List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        }

        private long timeLookups(TempoMap map, double[] fractions, int span) {
            long start = System.nanoTime();
            double sink = 0;
            for (double fraction : fractions) {
                sink += map.secondsToBeats(fraction * span);
            }
            long elapsed = System.nanoTime() - start;
            // Keep the loop from being optimised away entirely.
            assertThat(sink).isGreaterThan(0.0);
            return elapsed;
        }
    }
}
