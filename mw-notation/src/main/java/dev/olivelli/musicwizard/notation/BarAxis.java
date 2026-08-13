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

import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Score;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the chart's bar lines fall in the recording, and back.
 *
 * <p>A chart hung on one downbeat plus one constant bar length cannot follow a
 * recording that does not hold one (#187). The wander is small bar to bar —
 * this recording's bars ran between 1.92 s and 2.26 s around a mean of 2.05 s
 * — and it accumulates: by bar 9 the printed line was 0.74 s early, about a
 * beat and a half, and a reader saw the words sitting one or two syllables
 * late against it.
 *
 * <p>So the axis is anchored on the tracked downbeats, and time inside a bar
 * is proportional to that bar's own length — but only on a recording whose
 * bars are actually its own. Measured over the benchmark corpus, a
 * machine-timed backing track's bar length is the same to within a fifth of
 * a percent, half the bars over; a band playing to no click varies by a
 * percent and a half. On the first kind there is nothing to follow and the
 * tracker's scatter is all error, so following it splits chords across bar
 * lines a constant rate placed cleanly — measured, seven points of root
 * accuracy on one benchmark. On the second the wander is the recording, and
 * not following it puts the bar line nearly a second early by the ninth bar.
 *
 * <p>{@link #WANDER} is the line between them, read robustly so a handful of
 * dropped or doubled downbeats cannot make a metronome look human. Under it
 * this class is the constant rate it replaced, exactly. Over it, each
 * downbeat is kept when it lands within {@link #TOLERANCE} of where the bar
 * before predicts, and replaced by that prediction when it does not — so
 * small wander is followed and one gross miss costs one bar rather than
 * shifting every bar after it.
 *
 * <p>It is not anchored on every tracked <em>beat</em> either: that imports
 * the tracker's per-beat jitter into every chord position, which the same
 * benchmark measured as worse still.
 *
 * <p>Outside the tracked downbeats — before the first, after the last, or when
 * there are none — the constant rate is what is left, and the conversions
 * reduce to multiplying and dividing by it. That is also the whole of a MIDI
 * import or a typed tempo, so those charts are unmoved.
 */
final class BarAxis {

    /**
     * How far from the predicted bar line a downbeat may fall and still be
     * believed, as a fraction of the typical bar. A quarter is well outside
     * the wander measured on a band playing to no click — a percent or two —
     * and well inside a dropped or doubled downbeat, which lands half a bar
     * out or more.
     */
    private static final double TOLERANCE = 0.25;

    /**
     * How much a bar must typically differ from the one before it for the
     * recording to be taken as having bars of its own, as a fraction of the
     * bar.
     *
     * <p>Measured over the corpus: machine-timed backing tracks sit between
     * a sixth and three quarters of a percent, and the band recording that
     * motivated this sits at one and a half. One percent is the line, and it
     * is not an order of magnitude clear of either side -- the closest
     * backing track is within a factor of two -- so the failure it is set to
     * prefer is the safe one. A recording wrongly called machine-timed keeps
     * the chart it has today; one wrongly followed gets a worse chart than
     * it had.
     *
     * <p>The measure is the typical change from bar to bar, not the typical
     * distance from the average bar, because the second cannot see a
     * recording whose bars alternate long and short: half of them sit exactly
     * at the average, and a shuffle then reads as a metronome.
     */
    private static final double WANDER = 0.01;

    /** Bar boundaries in seconds, ascending, starting at the chart's origin. */
    private final double[] boundaries;
    private final double barSeconds;
    private final double quarterSeconds;

    private BarAxis(double[] boundaries, double barSeconds, double quarterSeconds) {
        this.boundaries = boundaries;
        this.barSeconds = barSeconds;
        this.quarterSeconds = quarterSeconds;
    }

    /**
     * The axis for a chart starting at {@code origin}, reading the score's
     * tracked downbeats where it has them.
     *
     * <p>The chart may begin whole bars before the first tracked downbeat --
     * {@code firstBarStart} steps back from it in bar lengths so a chord
     * heard before the tracker's first bar still has one -- and those lead-in
     * bars have no measurement, so they keep the constant length. From the
     * first tracked downbeat on, each bar is the span the tracker heard.
     *
     * <p>A downbeat is only taken while its bar is close to the stated one.
     * The band is deliberately narrow -- a fifth either way -- because two
     * different things would otherwise walk through it. A tracker that drops
     * or doubles a downbeat gives half or twice the bar, and honouring that
     * stretches a printed bar to swallow a neighbour. And a corrected tempo
     * ({@code --tempo}) leaves a grid whose downbeats are a half or a third
     * of the stated bar on purpose: the user has said the tracker was wrong,
     * so its downbeats cannot define the bars. The wander this class exists
     * for is much smaller than either -- the recording that motivated it ran
     * a tenth either side -- so the band separates them cleanly, and the
     * constant rate resumes at the first downbeat that fails it.
     */
    static BarAxis of(Score score, double origin, double barSeconds, double quarterSeconds) {
        List<Double> bounds = new ArrayList<>();
        bounds.add(origin);
        if (barSeconds > 0 && Double.isFinite(barSeconds) && score.beatGrid().isPresent()) {
            double previous = origin;
            for (double downbeat : deglitched(score.beatGrid().get().downbeatTimes())) {
                if (downbeat <= origin + barSeconds * 0.8) {
                    continue;
                }
                if (bounds.size() == 1) {
                    // The lead-in: whole constant bars up to the first
                    // measured downbeat, so a chart that starts before the
                    // tracker still counts its bars the way it always did.
                    long leadIn = Math.round((downbeat - origin) / barSeconds);
                    for (long b = 1; b < leadIn; b++) {
                        bounds.add(origin + b * barSeconds);
                    }
                    previous = bounds.get(bounds.size() - 1);
                }
                double length = downbeat - previous;
                if (length < barSeconds * 0.8 || length > barSeconds * 1.25) {
                    break;
                }
                bounds.add(downbeat);
                previous = downbeat;
            }
        }
        double[] kept = new double[bounds.size()];
        for (int i = 0; i < kept.length; i++) {
            kept[i] = bounds.get(i);
        }
        return new BarAxis(kept, barSeconds, quarterSeconds);
    }

    /**
     * The tracked downbeats with gross misses replaced by prediction.
     *
     * <p>The typical bar is the median gap — robust, so the misses this is
     * about cannot move it. Each downbeat is then read against where the
     * previous anchor plus that typical bar says it should fall: inside
     * {@link #TOLERANCE} of the prediction it is the measurement and is
     * taken, outside it is a miss and the prediction stands in. Because each
     * accepted downbeat becomes the next prediction's base, a recording that
     * genuinely speeds up is followed rather than corrected back.
     *
     * <p>Fewer than three downbeats give no median worth the name and are
     * returned untouched; the plausibility band in {@link #of} still applies.
     */
    private static List<Double> deglitched(List<Double> downbeats) {
        int n = downbeats.size();
        if (n < 4) {
            // Three downbeats give one change to read and no median worth the
            // name; the constant rate is the honest answer.
            return List.of();
        }
        List<Double> gaps = new ArrayList<>(n - 1);
        for (int i = 1; i < n; i++) {
            gaps.add(downbeats.get(i) - downbeats.get(i - 1));
        }
        java.util.Collections.sort(gaps);
        double typical = gaps.get(gaps.size() / 2);
        if (!(typical > 0)) {
            return downbeats;
        }
        // How much a bar typically differs from the one before it. A median
        // rather than a spread, so the dropped and doubled downbeats that
        // every tracker produces cannot make a metronome look like a band.
        List<Double> changes = new ArrayList<>(n - 2);
        for (int i = 2; i < n; i++) {
            changes.add(Math.abs((downbeats.get(i) - downbeats.get(i - 1))
                    - (downbeats.get(i - 1) - downbeats.get(i - 2))));
        }
        java.util.Collections.sort(changes);
        if (changes.get(changes.size() / 2) <= WANDER * typical) {
            // Machine-timed: it has no bars of its own to follow.
            return List.of();
        }
        List<Double> out = new ArrayList<>(n);
        out.add(downbeats.get(0));
        for (int i = 1; i < n; i++) {
            double predicted = out.get(i - 1) + typical;
            double measured = downbeats.get(i);
            out.add(Math.abs(measured - predicted) <= TOLERANCE * typical
                    ? measured : predicted);
        }
        return out;
    }

    private double quartersPerBar() {
        return barSeconds / quarterSeconds;
    }

    /** How many quarter notes into the chart a moment is. */
    double quartersAt(double seconds) {
        double perBar = quartersPerBar();
        if (!(perBar > 0) || seconds <= boundaries[0]) {
            return (seconds - boundaries[0]) / quarterSeconds;
        }
        int bar = 0;
        while (bar + 1 < boundaries.length && boundaries[bar + 1] <= seconds) {
            bar++;
        }
        double start = boundaries[bar];
        double length = bar + 1 < boundaries.length
                ? boundaries[bar + 1] - start : barSeconds;
        return bar * perBar + (seconds - start) / length * perBar;
    }

    /** When a position in quarter notes falls, in the recording. */
    double secondsAt(double quarters) {
        double perBar = quartersPerBar();
        if (!(perBar > 0) || quarters <= 0) {
            return boundaries[0] + quarters * quarterSeconds;
        }
        int bar = (int) Math.min(boundaries.length - 1, Math.floor(quarters / perBar));
        double start = boundaries[bar];
        double length = bar + 1 < boundaries.length
                ? boundaries[bar + 1] - start : barSeconds;
        return start + (quarters - bar * perBar) / perBar * length;
    }
}
