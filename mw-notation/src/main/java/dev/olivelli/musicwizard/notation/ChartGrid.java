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

import java.util.List;

/**
 * Where a moment in the recording lands on the engraved page.
 *
 * <p>One map, used by every lane the chart carries. Lyric syllables and beat
 * marks are both placed from seconds and the reader's whole question is how
 * they sit against each other, so two derivations of "where does this second
 * fall" would let the page disagree with itself while each lane remained right
 * about an axis of its own. That is the shape {@link ChartLayout}'s javadoc is
 * about, one level down.
 */
final class ChartGrid {

    /** The grid a lane is placed on: the shortest value a duration can name. */
    static final double UNIT = LilyPondDuration.SHORTEST_QUARTERS;

    private ChartGrid() {
    }

    /**
     * Each bar's start on the grid, with one extra entry for the chart's end.
     *
     * <p>Accumulated from the first bar's own position rather than read off each
     * bar, so the boundaries are contiguous by construction: nothing can fall in
     * a crack between two bars, and the durations of one bar always sum to the
     * distance to the next.
     */
    static long[] barStarts(List<ChartLayout.Bar> bars) {
        long[] starts = new long[bars.size() + 1];
        starts[0] = Math.round(bars.get(0).startQuarters() / UNIT);
        for (int i = 0; i < bars.size(); i++) {
            starts[i + 1] = starts[i] + Math.round(bars.get(i).lengthQuarters() / UNIT);
        }
        return starts;
    }

    /** Where a moment falls on the grid, by the bar holding it. */
    static long unitOf(double seconds, List<ChartLayout.Bar> bars, long[] barStart) {
        int index = 0;
        while (index + 1 < bars.size() && bars.get(index + 1).startSeconds() <= seconds) {
            index++;
        }
        ChartLayout.Bar bar = bars.get(index);
        // The bar's own rate, so the last bar is measured like every other and a
        // tempo change inside the chart cannot be read at a neighbour's tempo.
        double perQuarter = bar.secondsPerQuarter();
        double into = perQuarter > 0 ? (seconds - bar.startSeconds()) / perQuarter : 0;
        long unit = barStart[index] + Math.round(into / UNIT);
        return Math.max(barStart[0], Math.min(unit, barStart[index + 1]));
    }

    /** A rest of {@code units} grid steps, for the gap before the next mark. */
    static String skip(long units) {
        return "\\skip " + LilyPondDuration.scaled(units * UNIT);
    }
}
