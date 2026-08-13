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

import dev.olivelli.musicwizard.core.model.Score;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the chart's bar lines fall in the recording, and back.
 *
 * <p>A chart hung on one downbeat plus one constant bar length cannot follow
 * a recording that does not hold one (#187). The wander is small bar to bar
 * and it accumulates: on the recording that motivated this the printed bar
 * line ran about a beat and a half early by the ninth bar and further
 * later, and with
 * the words at their measured times a reader saw them sitting a syllable or
 * two late against bar lines that were themselves early.
 *
 * <p>So the axis is anchored on the tracked downbeats, and time inside a bar
 * is proportional to that bar's own length. It is not anchored on every
 * tracked <em>beat</em>: that imports the tracker's per-beat jitter into
 * every chord position, and on material that holds one tempo it splits
 * chords across bar lines the constant rate placed cleanly — a benchmark
 * lost most of twenty points of root accuracy that way.
 *
 * <p><b>Following is not always right, and what decides it here is weaker
 * than it looks.</b> A recording whose tempo is fixed has nothing of its own
 * to follow, so the tracker's scatter is all error and following it makes
 * the chart worse. {@link #WANDER} is the guard, and swept over the corpus
 * the two populations it means to separate overlap: a backing track can
 * scatter more than a band does, and the measure is quantised to whole onset
 * frames, so on a fast recording the decision can turn on a frame or two.
 * It keeps most fixed-tempo material on the constant path and that is all it
 * claims. What makes a wrong answer survivable is the rest of the class: a
 * dropped or doubled downbeat costs its own bar rather than the ones after
 * it, and a bar the stated tempo disagrees with reverts to the stated
 * length, so the axis cannot walk away from the recording.
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
     * <p>One percent, and it is a rough guard rather than a line between two
     * separable things: measured across the corpus, fixed-tempo backing
     * tracks and live recordings both land on either side of it. Beat times
     * are quantised to the onset hop, so this compares a small whole number
     * of frames and the nearest other answer is one frame away.
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
     * <p>A downbeat is only taken while its bar is close to the stated one:
     * a fifth shorter to a quarter longer. The band is narrow because two
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
                    // This bar is not one: the tracker and the stated tempo
                    // disagree about it. Only this bar reverts to the stated
                    // length -- ending the axis here instead would change the
                    // derivation of every later bar line on the strength of
                    // one, and on one recording measured that decision hung
                    // on six onset frames at three minutes in.
                    bounds.add(previous + barSeconds);
                    previous = bounds.get(bounds.size() - 1);
                    continue;
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
     * <p>Fewer than four downbeats give no median successive difference worth
     * the name, and are discarded: the constant rate is the honest answer.
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
        List<Double> sorted = new ArrayList<>(gaps);
        Collections.sort(sorted);
        double typical = sorted.get(sorted.size() / 2);

        // How much a bar typically differs from the one before it. A median
        // rather than a spread, so the dropped and doubled downbeats that
        // every tracker produces cannot make a metronome look like a band.
        List<Double> changes = new ArrayList<>(n - 2);
        for (int i = 2; i < n; i++) {
            changes.add(Math.abs(gaps.get(i - 1) - gaps.get(i - 2)));
        }
        Collections.sort(changes);
        if (changes.get(changes.size() / 2) <= WANDER * typical) {
            // Machine-timed: it has no bars of its own to follow.
            return List.of();
        }

        // The rate the predictions run at is the MEAN of the bars that look
        // like bars, not the median of all of them. A median is a typical
        // value and not a rate: half the bars are longer than it, so
        // predicting with it walks steadily behind a recording whose bars are
        // unevenly distributed -- the correction #200 made one layer up, for
        // the same reason.
        double total = 0;
        int counted = 0;
        for (double gap : gaps) {
            if (Math.abs(gap - typical) <= TOLERANCE * typical) {
                total += gap;
                counted++;
            }
        }
        double rate = counted > 0 ? total / counted : typical;

        List<Double> out = new ArrayList<>(n);
        out.add(downbeats.get(0));
        int consecutiveEarly = 0;
        for (int i = 1; i < n; i++) {
            double predicted = out.get(out.size() - 1) + rate;
            double error = downbeats.get(i) - predicted;
            if (Math.abs(error) <= TOLERANCE * rate) {
                out.add(downbeats.get(i));
                consecutiveEarly = 0;
            } else if (error < 0) {
                // Too early to be the next bar. Once, that is a downbeat the
                // tracker invented, and honouring it would give this bar a
                // line the recording does not have. Twice running, it is this
                // axis that is wrong -- a bar line accepted earlier was not
                // one, and every real downbeat since has looked early against
                // it. The recording gets the benefit of the doubt, because a
                // base that cannot be corrected free-runs exactly as far as
                // the open loop this replaced.
                if (++consecutiveEarly >= 2) {
                    out.add(downbeats.get(i));
                    consecutiveEarly = 0;
                }
            } else {
                // Too late: a downbeat the tracker missed. The prediction
                // stands in for the bar that has none, and this measurement
                // is offered again to the bar after it, so a miss costs its
                // own bar rather than every bar after it.
                out.add(predicted);
                consecutiveEarly = 0;
                if (out.size() > n * 2) {
                    // A gap so long that filling it is guesswork. Whatever
                    // follows is not this recording's bar line.
                    break;
                }
                i--;
            }
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
