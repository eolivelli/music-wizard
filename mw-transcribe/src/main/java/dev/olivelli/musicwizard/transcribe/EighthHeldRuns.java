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

package dev.olivelli.musicwizard.transcribe;

import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber.HeldSection;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber.MeterChangeAt;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Where a line alone in 6/8 at the tracked pulse plays bars of 3/4 with the
 * eighth held (#895): notes evenly two thirds of a pulse apart, from a bar
 * line through whole bars to the next note on a bar line, over pulses that
 * hold their length. Proposed as changes a user can supply, never applied.
 */
final class EighthHeldRuns {

    /** Fewer is a hemiola inside 6/8 rather than a change of meter. */
    static final int FEWEST_BARS = 2;

    private static final double STEP = 2.0 / 3;

    private static final double OFF_STEP = STEP / 4;

    /** How far a pulse in the run may stray from the section's median, as a share of it. */
    private static final double UNSTEADY = 0.15;

    private EighthHeldRuns() {
    }

    /** Each run as a change into 3/4 and, where the section goes on, one back. */
    static List<MeterChangeAt> propose(List<Double> beatTimes, List<HeldSection> sections,
                                       NoteTrack line) {
        double[] positions = line.notes().stream()
                .mapToDouble(note -> pulsePosition(beatTimes, note.onsetSeconds()))
                .filter(Double::isFinite)
                .sorted()
                .toArray();
        List<MeterChangeAt> proposed = new ArrayList<>();
        int last = beatTimes.size() - 1;
        for (int k = 0; k < sections.size(); k++) {
            HeldSection section = sections.get(k);
            if (!section.meter().equals(TimeSignature.SIX_EIGHT) || section.pulsesPerBar() != 2) {
                continue;
            }
            int end = k + 1 < sections.size() ? sections.get(k + 1).startPulse() : last;
            // A change after the run is counted from the run's pulse unless it holds the
            // eighth, so the run must hand back to 6/8 before it; and 3/4 cannot follow 3/4.
            boolean runsIntoNext = k + 1 < sections.size()
                    && sections.get(k + 1).eighthHeld()
                    && !sections.get(k + 1).meter().equals(TimeSignature.THREE_FOUR);
            double median = medianPulse(beatTimes, section.startPulse(), end);
            int bar = section.startPulse() + 2;
            while (bar + 2 * FEWEST_BARS <= end) {
                int bars = wholeBars(positions, bar, end);
                if (bar + 2 * bars == end && end < last && !runsIntoNext) {
                    bars--;
                }
                if (bars >= FEWEST_BARS && steady(beatTimes, bar, bar + 2 * bars, median)) {
                    proposed.add(new MeterChangeAt(beatTimes.get(bar),
                            TimeSignature.THREE_FOUR, true));
                    int back = bar + 2 * bars;
                    if (back < end) {
                        proposed.add(new MeterChangeAt(beatTimes.get(back), section.meter(), true));
                    }
                    bar = back;
                } else {
                    bar += 2;
                }
            }
        }
        return proposed;
    }

    /**
     * The {@code --time-signature} value supplying every section after the
     * first and {@code proposed}, each at the beat it takes effect on.
     */
    static String timeSignatureValue(List<Double> beatTimes, List<HeldSection> sections,
                                     List<MeterChangeAt> proposed) {
        List<MeterChangeAt> changes = new ArrayList<>(proposed);
        for (HeldSection section : sections.subList(1, sections.size())) {
            changes.add(new MeterChangeAt(beatTimes.get(section.startPulse()), section.meter(),
                    section.eighthHeld()));
        }
        changes.sort(Comparator.comparingDouble(MeterChangeAt::seconds));
        StringBuilder value = new StringBuilder(sections.get(0).meter().toString());
        for (MeterChangeAt change : changes) {
            value.append(String.format(Locale.ROOT, ",%s@%.2f%s", change.timeSignature(),
                    change.seconds(), change.eighthHeld() ? "=8" : ""));
        }
        return value.toString();
    }

    /**
     * How many whole bars of three notes a step apart start at {@code bar},
     * counted only where a note falls on the bar line closing them.
     */
    private static int wholeBars(double[] positions, int bar, int end) {
        int at = firstAtOrAfter(positions, bar - OFF_STEP);
        if (at == positions.length || Math.abs(positions[at] - bar) > OFF_STEP) {
            return 0;
        }
        int steps = 0;
        while (at + steps + 1 < positions.length) {
            double expected = bar + (steps + 1) * STEP;
            if (expected > end + OFF_STEP
                    || Math.abs(positions[at + steps + 1] - expected) > OFF_STEP) {
                break;
            }
            steps++;
        }
        return steps / 3;
    }

    private static boolean steady(List<Double> beatTimes, int from, int to, double median) {
        for (int i = from; i < to; i++) {
            if (Math.abs(beatTimes.get(i + 1) - beatTimes.get(i) - median) > UNSTEADY * median) {
                return false;
            }
        }
        return true;
    }

    private static double medianPulse(List<Double> beatTimes, int from, int to) {
        double[] pulses = new double[to - from];
        for (int i = from; i < to; i++) {
            pulses[i - from] = beatTimes.get(i + 1) - beatTimes.get(i);
        }
        java.util.Arrays.sort(pulses);
        return pulses.length == 0 ? Double.NaN : pulses[pulses.length / 2];
    }

    private static int firstAtOrAfter(double[] sorted, double value) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < value) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Where {@code seconds} falls in tracked pulses, or NaN outside them. */
    static double pulsePosition(List<Double> beatTimes, double seconds) {
        int lo = 0;
        int hi = beatTimes.size() - 1;
        if (hi < 1 || seconds < beatTimes.get(0) || seconds >= beatTimes.get(hi)) {
            return Double.NaN;
        }
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (beatTimes.get(mid) <= seconds) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo + (seconds - beatTimes.get(lo)) / (beatTimes.get(hi) - beatTimes.get(lo));
    }
}
