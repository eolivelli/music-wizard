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
        // A wandering recording with a doubled bar in the middle: the tracker
        // missed a downbeat. The bars after it must stay where they were, not
        // slide by a whole bar.
        List<Double> beats = new ArrayList<>();
        double at = 0;
        for (int bar = 0; bar < 24; bar++) {
            double length = bar == 10 ? BAR * 2 : BAR * (bar % 2 == 0 ? 0.95 : 1.08);
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

        // Monotone throughout, and no bar swallowed a neighbour.
        double previous = axis.secondsAt(0);
        for (int bar = 1; bar <= 20; bar++) {
            double now = axis.secondsAt(bar * 4.0);
            assertThat(now).isGreaterThan(previous);
            assertThat(now - previous).isLessThan(BAR * 1.6);
            previous = now;
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
