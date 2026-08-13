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

import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which recordings the chart's bar lines follow, and which it holds steady for. */
@DisplayName("the chart's bar axis")
class BarAxisTest {

    private static final double QUARTER = 0.5;
    private static final double BAR = 4 * QUARTER;

    /** A grid of {@code bars} bars whose lengths come from {@code lengths}, cycling. */
    private static Score scoreWith(double... barLengths) {
        List<Double> beats = new ArrayList<>();
        double at = 0;
        for (int bar = 0; bar < 24; bar++) {
            double length = barLengths[bar % barLengths.length];
            for (int beat = 0; beat < 4; beat++) {
                beats.add(at + beat * length / 4);
            }
            at += length;
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), at)
                .withBeatGrid(BeatGrid.ofTimes(beats, TimeSignature.FOUR_FOUR,
                        Confidence.of(0.9)));
    }

    @Test
    @DisplayName("a machine-timed recording keeps the constant bar it always had")
    void machineTimedIsUnmoved() {
        // Every bar the same length: there is nothing of the recording's own
        // to follow, and the tracker's scatter would be all error. The axis
        // must be the multiplication it replaced, exactly -- this is what
        // keeps every scored benchmark unmoved.
        BarAxis axis = BarAxis.of(scoreWith(BAR), 0.0, BAR, QUARTER);

        for (int bar = 0; bar <= 12; bar++) {
            assertThat(axis.secondsAt(bar * 4.0))
                    .isCloseTo(bar * BAR, within(1e-9));
        }
    }

    @Test
    @DisplayName("a recording that wanders is followed, bar by bar")
    void wanderIsFollowed() {
        // Bars alternating 5% either side of the mean: a band, not a click.
        // Followed, the sixth bar line sits where the tracker heard it; held
        // constant it would be adrift by the accumulated difference.
        double shortBar = BAR * 0.95;
        double longBar = BAR * 1.08;
        BarAxis axis = BarAxis.of(scoreWith(shortBar, longBar), 0.0, BAR, QUARTER);

        double expected = 3 * (shortBar + longBar);
        assertThat(axis.secondsAt(6 * 4.0)).isCloseTo(expected, within(0.02));
        assertThat(Math.abs(expected - 6 * BAR)).isGreaterThan(0.1);
    }

    @Test
    @DisplayName("seconds and quarters are inverses on both kinds of recording")
    void conversionsRoundTrip() {
        for (Score score : List.of(scoreWith(BAR), scoreWith(BAR * 0.95, BAR * 1.08))) {
            BarAxis axis = BarAxis.of(score, 0.0, BAR, QUARTER);
            for (double quarters = 0; quarters <= 40; quarters += 0.25) {
                assertThat(axis.quartersAt(axis.secondsAt(quarters)))
                        .isCloseTo(quarters, within(1e-6));
            }
        }
    }

    @Test
    @DisplayName("one dropped downbeat costs its own bar, not every bar after it")
    void oneGrossMissDoesNotShiftTheRest() {
        // A wandering recording with one downbeat removed. Asserting only
        // that the axis stays monotone would pass on an axis that had given
        // up and gone constant -- which is what an earlier version did, ten
        // bars adrift by the end. So the bar lines are compared against the
        // times the fixture actually laid down.
        double[] lengths = {BAR * 0.95, BAR * 1.08};
        List<Double> truth = new ArrayList<>();
        List<Double> beats = new ArrayList<>();
        double at = 0;
        for (int bar = 0; bar < 24; bar++) {
            truth.add(at);
            double length = lengths[bar % lengths.length];
            if (bar != 10) {
                for (int beat = 0; beat < 4; beat++) {
                    beats.add(at + beat * length / 4);
                }
            }
            at += length;
        }
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), at)
                .withBeatGrid(BeatGrid.ofTimes(beats, TimeSignature.FOUR_FOUR,
                        Confidence.of(0.9)));
        BarAxis axis = BarAxis.of(score, 0.0, BAR, QUARTER);

        // Every bar after the missing one is still where the recording put
        // it, to well inside a beat.
        for (int bar = 12; bar <= 20; bar++) {
            assertThat(axis.secondsAt(bar * 4.0))
                    .as("bar %d", bar)
                    .isCloseTo(truth.get(bar), within(QUARTER / 2));
        }
    }

    @Test
    @DisplayName("a downbeat the tracker invented is dropped, not honoured")
    void aSpuriousDownbeatIsDropped() {
        // An extra downbeat halfway through bar 10. Honouring it would give
        // that bar a line the recording does not have and push every later
        // bar one along.
        double[] lengths = {BAR * 0.95, BAR * 1.08};
        List<Double> truth = new ArrayList<>();
        List<Double> beats = new ArrayList<>();
        double at = 0;
        for (int bar = 0; bar < 24; bar++) {
            truth.add(at);
            double length = lengths[bar % lengths.length];
            for (int beat = 0; beat < 4; beat++) {
                beats.add(at + beat * length / 4);
            }
            if (bar == 10) {
                // Four extra beats offset from the four above, so the grid
                // gains a whole spurious bar without repeating a time.
                for (int beat = 0; beat < 4; beat++) {
                    beats.add(at + length / 8 + beat * length / 4);
                }
            }
            at += length;
        }
        java.util.Collections.sort(beats);
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), at)
                .withBeatGrid(BeatGrid.ofTimes(beats, TimeSignature.FOUR_FOUR,
                        Confidence.of(0.9)));
        BarAxis axis = BarAxis.of(score, 0.0, BAR, QUARTER);

        for (int bar = 12; bar <= 20; bar++) {
            assertThat(axis.secondsAt(bar * 4.0))
                    .as("bar %d", bar)
                    .isCloseTo(truth.get(bar), within(QUARTER));
        }
    }

    @Test
    @DisplayName("with no grid at all the axis is the constant rate")
    void noGridIsTheConstantRate() {
        Score bare = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 30.0);
        BarAxis axis = BarAxis.of(bare, 1.5, BAR, QUARTER);

        assertThat(axis.secondsAt(8.0)).isCloseTo(1.5 + 2 * BAR, within(1e-9));
        assertThat(axis.quartersAt(1.5 + 2 * BAR)).isCloseTo(8.0, within(1e-9));
    }
}
