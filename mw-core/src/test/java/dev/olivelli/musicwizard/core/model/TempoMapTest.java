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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
     * Bar lookup got the same treatment as segment lookup, and for the same
     * reason: it counted bars one at a time from zero, so it was O(position) per
     * call and quadratic for any stage converting per note. It also never
     * returned for a NaN beat. Bar length is not constant, so the walk over
     * meter changes has to keep honouring them exactly.
     */
    @Nested
    @DisplayName("musical time lookup")
    class MusicalTimeLookup {

        /** {@code toMusicalTime} exactly as it was, kept as the oracle. */
        private MusicalTime walkBars(TempoMap map, double beat) {
            int bar = 0;
            double remaining = beat;
            while (true) {
                double barLength = map.timeSignatureAtBar(bar).quarterBeatsPerBar();
                if (remaining < barLength || barLength <= 0) {
                    break;
                }
                remaining -= barLength;
                bar++;
            }
            return new MusicalTime(bar, remaining, map.timeSignatureAtBar(bar));
        }

        /** {@code toBeat} exactly as it was, kept as the oracle. */
        private double sumBars(TempoMap map, MusicalTime musicalTime) {
            double beat = 0;
            for (int bar = 0; bar < musicalTime.bar(); bar++) {
                beat += map.timeSignatureAtBar(bar).quarterBeatsPerBar();
            }
            return beat + musicalTime.beatInBar();
        }

        /** A map with a random but valid stack of meter changes. */
        private TempoMap meterChangeMap(int changeCount, Random random) {
            TimeSignature[] catalogue = {
                    TimeSignature.FOUR_FOUR, TimeSignature.THREE_FOUR, TimeSignature.SIX_EIGHT,
                    new TimeSignature(5, 8), new TimeSignature(7, 8), new TimeSignature(2, 2),
                    new TimeSignature(9, 8), new TimeSignature(12, 8), new TimeSignature(1, 64),
                    new TimeSignature(64, 1), new TimeSignature(3, 16), new TimeSignature(2, 4)};
            List<TempoMap.MeterChange> changes = new ArrayList<>(changeCount);
            int bar = 0;
            for (int i = 0; i < changeCount; i++) {
                changes.add(new TempoMap.MeterChange(bar, catalogue[random.nextInt(catalogue.length)]));
                bar += 1 + random.nextInt(20);
            }
            return new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0, 60 + random.nextInt(120))), changes);
        }

        @Test
        @DisplayName("agrees with the bar-by-bar walk it replaced, bit for bit")
        void agreesWithTheBarWalk() {
            Random random = new Random(31337L);

            for (int trial = 0; trial < 400; trial++) {
                TempoMap map = meterChangeMap(1 + random.nextInt(6), random);
                int lastChange = map.meterChanges().get(map.meterChanges().size() - 1).startBar();

                List<Double> beats = new ArrayList<>();
                beats.add(0.0);
                // Exactly on, just before and just after every bar line, since
                // that is where a count computed by division rather than by
                // subtraction goes wrong.
                for (int bar = 0; bar <= lastChange + 6; bar++) {
                    double at = map.toBeat(new MusicalTime(bar, 0, map.timeSignatureAtBar(bar)));
                    beats.add(at);
                    beats.add(Math.nextDown(at));
                    beats.add(Math.nextUp(at));
                }
                for (int i = 0; i < 24; i++) {
                    beats.add(random.nextDouble() * 400);
                }

                for (double beat : beats) {
                    if (beat < 0) {
                        continue;
                    }
                    String origin = "trial " + trial + " beat " + beat;
                    MusicalTime expected = walkBars(map, beat);
                    MusicalTime actual = map.toMusicalTime(beat);
                    assertThat(actual.bar()).describedAs("%s: bar", origin).isEqualTo(expected.bar());
                    assertThat(Double.doubleToLongBits(actual.beatInBar()))
                            .describedAs("%s: beatInBar %s vs %s",
                                    origin, actual.beatInBar(), expected.beatInBar())
                            .isEqualTo(Double.doubleToLongBits(expected.beatInBar()));
                    assertThat(actual.timeSignature())
                            .describedAs("%s: signature", origin)
                            .isEqualTo(expected.timeSignature());

                    assertThat(Double.doubleToLongBits(map.toBeat(actual)))
                            .describedAs("%s: toBeat", origin)
                            .isEqualTo(Double.doubleToLongBits(sumBars(map, actual)));
                }
            }
        }

        @Test
        @DisplayName("rejects a non-finite beat instead of never returning")
        void rejectsNonFiniteBeat() {
            TempoMap map = TempoMap.constant(120);

            // NaN got past the old "beat < 0" guard, and then "remaining <
            // barLength" was false for it on every iteration, so the loop never
            // ended. A negative is not a substitute for this case: it was always
            // rejected, so a test using one passes without touching the fix.
            assertThatThrownBy(() -> map.toMusicalTime(Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite");
            assertThatThrownBy(() -> map.toMusicalTime(Double.POSITIVE_INFINITY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite");
            assertThatThrownBy(() -> map.toMusicalTime(Double.NEGATIVE_INFINITY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite");
            assertThatThrownBy(() -> map.toMusicalTime(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("a beat past the last representable bar is an error, not a wrong bar")
        void rejectsABeatPastTheLastBar() {
            // 1/64 bars are a sixteenth of a quarter beat, so a bar index runs
            // out four billion bars in. The walk this replaced took the same
            // input to an int overflow and a negative bar; failing outright is
            // the only honest answer.
            TempoMap map = TempoMap.constant(120, new TimeSignature(1, 64));

            assertThatThrownBy(() -> map.toMusicalTime(1e9))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("past bar");
            // Just inside is fine.
            assertThat(map.toMusicalTime(1e8).bar()).isEqualTo(1_600_000_000);

            // The largest double over the shortest bar divides to infinity, and
            // a block before it has already advanced the bar index. Without the
            // saturating count the two would add to a negative long, wrap to a
            // plausible-looking bar, and the failure would surface as a
            // complaint about the beat not fitting the bar -- which is true but
            // says nothing about what went wrong.
            TempoMap mixed = TempoMap.constant(120, TimeSignature.FOUR_FOUR)
                    .withMeterChange(1000, new TimeSignature(1, 64));
            assertThatThrownBy(() -> mixed.toMusicalTime(Double.MAX_VALUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("past bar");
        }

        @Test
        @DisplayName("cost does not grow with how far into the piece the beat is")
        void isNotLinearInBarPosition() {
            // Late and early positions on the *same* map, which is two meter
            // changes and fits in L1 either way, so the only thing varying is
            // the argument. That matters: the segment-lookup equivalent of this
            // test was flaky until it stopped comparing a small map against a
            // large one, where the cache hierarchy varied along with the
            // algorithm. Here there is no such asymmetry to conflate.
            TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR)
                    .withMeterChange(1000, TimeSignature.THREE_FOUR);
            // Beat 100 is bar 25; beat 800,000 is bar 266,333 -- 10,653x
            // further in, and 10,653x the iterations for the walk this replaced.
            // Measured 1.1-1.2x here and 400x once the walk is restored, so the
            // threshold of 20 has an order of magnitude of margin on both sides;
            // the earlier 1,053x spread only separated them 1.2x from 40x, which
            // is the sort of margin that eventually goes red on a loaded runner.
            assertThat(map.toMusicalTime(100).bar()).isEqualTo(25);
            assertThat(map.toMusicalTime(800_000).bar()).isEqualTo(266_333);

            timeConversions(map, 100);          // warm up and let the JIT settle
            timeConversions(map, 800_000);

            long earlyNanos = Long.MAX_VALUE;
            long lateNanos = Long.MAX_VALUE;
            for (int trial = 0; trial < 5; trial++) {
                earlyNanos = Math.min(earlyNanos, timeConversions(map, 100));
                lateNanos = Math.min(lateNanos, timeConversions(map, 800_000));
            }

            double ratio = (double) lateNanos / Math.max(1, earlyNanos);
            assertThat(ratio)
                    .describedAs("a beat 10653x further into the piece cost %.1fx more"
                            + " (%d ns vs %d ns); toMusicalTime is walking bars again",
                            ratio, lateNanos, earlyNanos)
                    .isLessThan(20.0);
        }

        /**
         * Both directions, for the reason the segment-lookup timing test walks
         * both axes: {@code toBeat} is the same bar walk backwards, and the
         * differential test cannot see its cost because the walk is that test's
         * oracle rather than its subject. Timing only {@code toMusicalTime}
         * would leave the direction notation and arrangement actually drive --
         * a quantized position back to beats, per note -- guarded by nothing.
         * The result of one feeds the other, so one probe covers both.
         */
        private long timeConversions(TempoMap map, int beat) {
            long start = System.nanoTime();
            double sink = 0;
            for (int i = 0; i < 5_000; i++) {
                MusicalTime at = map.toMusicalTime(beat + i);
                sink += at.bar() + map.toBeat(at);
            }
            long elapsed = System.nanoTime() - start;
            assertThat(sink).isGreaterThan(0.0);
            return elapsed;
        }

        @Test
        @DisplayName("honours meter changes at the block boundaries")
        void honoursMeterChangesAtBlockBoundaries() {
            // 4/4 for bars 0-1, then 3/4 from bar 2, then 6/8 (three beats) from
            // bar 5. Checked by hand: bar 2 starts at beat 8, bar 5 at beat 17.
            TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR)
                    .withMeterChange(2, TimeSignature.THREE_FOUR)
                    .withMeterChange(5, TimeSignature.SIX_EIGHT);

            assertThat(map.toMusicalTime(7.5)).isEqualTo(
                    new MusicalTime(1, 3.5, TimeSignature.FOUR_FOUR));
            assertThat(map.toMusicalTime(8)).isEqualTo(
                    new MusicalTime(2, 0, TimeSignature.THREE_FOUR));
            assertThat(map.toMusicalTime(16.5)).isEqualTo(
                    new MusicalTime(4, 2.5, TimeSignature.THREE_FOUR));
            assertThat(map.toMusicalTime(17)).isEqualTo(
                    new MusicalTime(5, 0, TimeSignature.SIX_EIGHT));
            assertThat(map.toMusicalTime(20)).isEqualTo(
                    new MusicalTime(6, 0, TimeSignature.SIX_EIGHT));

            assertThat(map.toBeat(new MusicalTime(2, 0, TimeSignature.THREE_FOUR))).isEqualTo(8.0);
            assertThat(map.toBeat(new MusicalTime(5, 0, TimeSignature.SIX_EIGHT))).isEqualTo(17.0);
            assertThat(map.toBeat(new MusicalTime(6, 1.5, TimeSignature.SIX_EIGHT))).isEqualTo(21.5);
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

        /**
         * Asserts binary search and scan agree on every interesting key.
         *
         * @param origin names the generator and trial, because a failure here is
         *               a needle in a corpus of 600 maps and the map that
         *               produced it has to be identifiable from the message alone
         */
        private void assertAgreesWithScan(TempoMap map, String origin, Random random) {
            List<TempoMap.TempoSegment> segments = map.segments();
            for (double beat : probeKeys(segments, TempoMap.TempoSegment::startBeat, random)) {
                TempoMap.TempoSegment expected =
                        scan(segments, beat, TempoMap.TempoSegment::startBeat);
                assertThat(map.segmentAtBeat(beat))
                        .describedAs("%s: segmentAtBeat(%s) over %d segments",
                                origin, beat, segments.size())
                        .isSameAs(expected);
                // Cheap, and it pins beatsToSeconds to segmentAtBeat: the only way
                // this can fail while the assertion above passes is the conversion
                // ceasing to route through the lookup at all.
                assertThat(Double.doubleToLongBits(map.beatsToSeconds(beat)))
                        .describedAs("%s: beatsToSeconds(%s)", origin, beat)
                        .isEqualTo(Double.doubleToLongBits(expected.startSeconds()
                                + (beat - expected.startBeat()) * expected.secondsPerBeat()));
            }
            for (double seconds : probeKeys(segments, TempoMap.TempoSegment::startSeconds, random)) {
                TempoMap.TempoSegment expected =
                        scan(segments, seconds, TempoMap.TempoSegment::startSeconds);
                assertThat(map.segmentAtSeconds(seconds))
                        .describedAs("%s: segmentAtSeconds(%s) over %d segments",
                                origin, seconds, segments.size())
                        .isSameAs(expected);
                assertThat(Double.doubleToLongBits(map.secondsToBeats(seconds)))
                        .describedAs("%s: secondsToBeats(%s)", origin, seconds)
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

        /**
         * A map whose boundaries are one representable double apart on both axes.
         * The constructor permits this -- consecutive doubles are strictly
         * increasing, and the implied seconds land exactly -- and it is the
         * degenerate case for a search: any comparison carrying slack, or any
         * assumption that boundaries are separated by something measurable,
         * lands on the wrong segment here and nowhere else.
         */
        private TempoMap ulpAdjacentMap(int segmentCount) {
            List<TempoMap.TempoSegment> segments = new ArrayList<>(segmentCount);
            // Anchored at zero, then stepping from 1.0 so the boundaries are
            // adjacent normals rather than adjacent subnormals. At 60 BPM one
            // beat is one second, so both axes advance by the same ULP.
            segments.add(new TempoMap.TempoSegment(0, 0, 60));
            double at = 1.0;
            for (int i = 1; i < segmentCount; i++) {
                segments.add(new TempoMap.TempoSegment(at, at, 60));
                at = Math.nextUp(at);
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
            // One Random drives both map generation and probe selection, so the
            // corpus is reproducible from this seed alone -- java.util.Random's
            // algorithm is specified, so it is the same corpus on every JDK and
            // platform. The flip side is that adding or removing a single probe
            // key reshuffles every map after it, so a failure quoted against an
            // older revision is not reproducible against a newer one.
            Random random = new Random(20250727L);

            // Single-segment maps: the search must never move off index 0.
            assertAgreesWithScan(TempoMap.constant(120), "constant(120)", random);
            assertAgreesWithScan(TempoMap.constant(37.4), "constant(37.4)", random);

            for (int trial = 0; trial < 150; trial++) {
                // Sizes 1..48 so both parities and both loop shapes are covered;
                // an odd/even midpoint bug survives a single size.
                int size = 1 + random.nextInt(48);
                assertAgreesWithScan(exactMap(size, random), "exactMap trial " + trial, random);
                assertAgreesWithScan(millisecondRoundedMap(size, random),
                        "millisecondRoundedMap trial " + trial, random);
                assertAgreesWithScan(trackedMap(1 + size, random),
                        "trackedMap trial " + trial, random);
            }

            // ulpAdjacentMap takes no randomness, so every size is a distinct
            // map and repeating one buys nothing.
            for (int size = 1; size <= 16; size++) {
                assertAgreesWithScan(ulpAdjacentMap(size), "ulpAdjacentMap size " + size, random);
            }

            // And maps large enough that the search does real work.
            assertAgreesWithScan(exactMap(4096, random), "exactMap 4096", random);
            assertAgreesWithScan(trackedMap(3000, random), "trackedMap 3000", random);
        }

        @Test
        @DisplayName("agrees with the linear scan at the size the issue is about")
        void agreesWithLinearScanOnATrackLengthMap() {
            // The corpus above stops at a few thousand segments because the scan
            // oracle costs O(segments) per probe and every boundary is probed.
            // #20 is about ~100,000 segments -- a quarter-hour of tracked beats --
            // so check that size too, on a sampled budget of boundaries rather
            // than all of them.
            Random random = new Random(99L);
            TempoMap map = trackedMap(100_000, random);
            List<TempoMap.TempoSegment> segments = map.segments();
            // One segment per beat interval, plus a lead-in if the first beat is
            // not at t=0, so 99,999 or 100,000. Asserted because a generator that
            // quietly produced a short map would make this test meaningless.
            assertThat(segments).hasSizeGreaterThan(99_000);

            for (int probe = 0; probe < 150; probe++) {
                TempoMap.TempoSegment at = segments.get(random.nextInt(segments.size()));
                for (double beat : new double[] {
                        Math.nextDown(at.startBeat()), at.startBeat(), Math.nextUp(at.startBeat())}) {
                    assertThat(map.segmentAtBeat(beat))
                            .describedAs("segmentAtBeat(%s) over %d segments", beat, segments.size())
                            .isSameAs(scan(segments, beat, TempoMap.TempoSegment::startBeat));
                }
                for (double seconds : new double[] {
                        Math.nextDown(at.startSeconds()), at.startSeconds(),
                        Math.nextUp(at.startSeconds())}) {
                    assertThat(map.segmentAtSeconds(seconds))
                            .describedAs("segmentAtSeconds(%s) over %d segments",
                                    seconds, segments.size())
                            .isSameAs(scan(segments, seconds, TempoMap.TempoSegment::startSeconds));
                }
            }
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
        @DisplayName("lookup is far cheaper than the scan it replaced")
        void lookupBeatsTheScanItReplaced() {
            // Both sides of this comparison walk the *same* map. An earlier
            // version timed one implementation over a small map and a large one
            // and asserted the ratio, which conflated the algorithm with the
            // cache hierarchy: the small map fits in L2 and the large one does
            // not, so the ratio moved with whatever else on the machine was
            // competing for memory bandwidth, and it was reproduced failing at
            // 24.7x on correct code under memory contention. Comparing the two
            // implementations over one map makes the test self-calibrating --
            // load slows both sides together -- and the measured margin then
            // *grows* under contention rather than shrinking: 595x idle, 3322x
            // under heavy memory pressure, against a threshold of 20.
            //
            // The baseline is this class's own scan oracle, which is deliberate
            // but couples the two: making scan faster would silently lower this
            // threshold, so if you ever optimise it, re-measure here.
            TempoMap map = constantIntervalMap(256_000);
            List<TempoMap.TempoSegment> segments = map.segments();

            // Spread across the map, so the scan's cost is its average rather
            // than its best case, and few enough that the scan side stays cheap.
            double[] probes = new double[64];
            for (int i = 0; i < probes.length; i++) {
                probes[i] = segments.size() * (i + 0.5) / probes.length;
            }

            timeSearch(map, probes);        // warm up and let the JIT settle
            timeScan(segments, probes);

            long searchNanos = Long.MAX_VALUE;
            long scanNanos = Long.MAX_VALUE;
            for (int trial = 0; trial < 5; trial++) {
                searchNanos = Math.min(searchNanos, timeSearch(map, probes));
                scanNanos = Math.min(scanNanos, timeScan(segments, probes));
            }

            assertThat(scanNanos)
                    .describedAs("over %d segments the binary search took %d ns and the scan"
                            + " it replaced took %d ns (%.1fx); lookup has gone linear again",
                            segments.size(), searchNanos, scanNanos,
                            (double) scanNanos / Math.max(1, searchNanos))
                    .isGreaterThan(20 * Math.max(1, searchNanos));
        }

        @Test
        @DisplayName("lookup allocates nothing per call")
        void lookupAllocatesNothingPerCall() {
            // The timing test above cannot see a constant-factor regression: with
            // a 20x threshold against a ~10 ms scan, a lookup could get twice as
            // slow and still pass. Raising that threshold is not the answer -- the
            // margin under an adversarial JIT configuration is only ~1.7x, so a
            // higher one would put the flakiness straight back. Allocation is the
            // part of the constant factor worth pinning, and it can be measured
            // exactly instead of timed: the two axes share one search only because
            // it takes a ToDoubleFunction, and the whole case for that shape is
            // that the two method references are non-capturing singletons. If a
            // future edit captures anything, boxes a key, or materialises a list
            // of axis values, that assumption is gone and this fails.
            // JUnit's own assumption, not AssertJ's: AssertJ implements
            // assumptions with a generated proxy whose first use costs ~0.6 s,
            // which is most of a second of `mvn verify` spent on a guard that
            // never fires.
            com.sun.management.ThreadMXBean threads = allocationCountingThreadBean();
            assumeTrue(threads != null, "thread allocation counting is unavailable");

            // Size is irrelevant here -- allocation per call does not depend on
            // how many steps the search takes -- so keep the map small.
            TempoMap map = constantIntervalMap(5_000);
            long self = Thread.currentThread().threadId();

            // Warm up: class loading, lambda linkage and JIT all allocate, and
            // none of that is per-call cost.
            double sink = 0;
            for (int i = 0; i < 100_000; i++) {
                sink += map.secondsToBeats(i % 5_000) + map.beatsToSeconds(i % 5_000);
            }

            int calls = 200_000;
            long before = threads.getThreadAllocatedBytes(self);
            // The capability checks above can both pass and the reading still be
            // -1, on a virtual thread among others. Then before and after are
            // both -1, the difference is 0, and the assertion below passes
            // having measured nothing -- a test that keeps passing after the
            // thing it guards is gone. Skip instead.
            assumeTrue(before >= 0, "thread allocation counting returned no reading");
            for (int i = 0; i < calls; i++) {
                sink += map.secondsToBeats(i % 5_000) + map.beatsToSeconds(i % 5_000);
            }
            long allocated = threads.getThreadAllocatedBytes(self) - before;

            assertThat(sink).isGreaterThan(0.0);
            // Measured at exactly 0 bytes; the allowance is 5 bytes per call, for
            // TLAB accounting noise and anything the harness allocates on this
            // thread. One boxed key per search step would be ~100 MB over this
            // loop, so the bound is nowhere near tight enough to be delicate.
            assertThat(allocated)
                    .describedAs("%d lookups allocated %d bytes (%.3f per call);"
                            + " the lookup is allocating again",
                            calls, allocated, (double) allocated / calls)
                    .isLessThan(1L << 20);
        }

        /**
         * The allocation-counting bean, or null where it is unavailable -- the
         * interface is HotSpot's rather than the JMX standard's, and a
         * measurement this precise should skip rather than fail elsewhere.
         */
        private com.sun.management.ThreadMXBean allocationCountingThreadBean() {
            if (!(java.lang.management.ManagementFactory.getThreadMXBean()
                    instanceof com.sun.management.ThreadMXBean bean)) {
                return null;
            }
            if (!bean.isThreadAllocatedMemorySupported() || !bean.isThreadAllocatedMemoryEnabled()) {
                return null;
            }
            return bean;
        }

        /** Beat i at second i, so a probe is trivially placed by index. */
        private TempoMap constantIntervalMap(int segmentCount) {
            List<TempoMap.TempoSegment> segments = new ArrayList<>(segmentCount);
            for (int i = 0; i < segmentCount; i++) {
                segments.add(new TempoMap.TempoSegment(i, i, 60));
            }
            return new TempoMap(segments, List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        }

        /**
         * Both axes, because they are only cheap together. The two directions
         * share one private search today, so a regression on one alone has to be
         * introduced deliberately -- but the differential tests cannot see it
         * either, since the scan is their oracle rather than their subject, so
         * timing only {@code secondsToBeats} would leave {@code beatsToSeconds}
         * unguarded by anything at all. In this map beat i falls at second i, so
         * one probe value serves both.
         */
        private long timeSearch(TempoMap map, double[] probes) {
            long start = System.nanoTime();
            double sink = 0;
            for (double probe : probes) {
                sink += map.secondsToBeats(probe) + map.beatsToSeconds(probe);
            }
            long elapsed = System.nanoTime() - start;
            // An escaping, data-dependent use, so the loop cannot be elided.
            assertThat(sink).isGreaterThan(0.0);
            return elapsed;
        }

        private long timeScan(List<TempoMap.TempoSegment> segments, double[] probes) {
            long start = System.nanoTime();
            double sink = 0;
            for (double probe : probes) {
                sink += scan(segments, probe, TempoMap.TempoSegment::startSeconds).startBeat()
                        + scan(segments, probe, TempoMap.TempoSegment::startBeat).startSeconds();
            }
            long elapsed = System.nanoTime() - start;
            assertThat(sink).isGreaterThan(0.0);
            return elapsed;
        }
    }
}
