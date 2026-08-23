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

import dev.olivelli.musicwizard.arrange.BarGrid;
import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.MusicalTime;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What {@link AnalysisReport} counts, derived from a score and from nothing
 * else.
 *
 * <p>Separated from the markup so the derivations can be executed on their own:
 * a page is hard to assert about and a count is not.
 */
final class ReportFacts {

    /**
     * How many bar lines the timeline will draw before it stops.
     *
     * <p>The quantizer accepts a tempo map spanning bars enough to make an
     * unbounded document, and this page has to open on a phone. Reaching it is
     * reported rather than passed over — see {@link Bars#truncated()}.
     */
    static final int MAX_BAR_LINES = 4000;

    /**
     * How far past a bar line a position may sit and still be on it.
     *
     * <p>Relative to the position, because the error of the round trip that
     * produced it grows with how far into the piece it is.
     */
    private static final double ON_THE_LINE = 1e-9;

    private ReportFacts() {
    }

    /** One bar line, at the second the tempo map puts it. */
    record BarLine(int bar, double seconds, TimeSignature meter) {
    }

    /**
     * The bar lines of a recording, and whether they ran out.
     *
     * @param truncated whether the piece has more bars than {@link
     *                  #MAX_BAR_LINES}, so the page can say the axis stops early
     */
    record Bars(List<BarLine> lines, boolean truncated) {
    }

    /**
     * Where each bar begins, from the tempo map and never from a rate of our
     * own: the map is the only sanctioned conversion between beats and seconds,
     * and a second reading of it is a second answer.
     */
    static Bars barLines(TempoMap tempoMap, double durationSeconds) {
        List<BarLine> lines = new ArrayList<>();
        double lastBeat = tempoMap.secondsToBeats(durationSeconds);
        if (!(lastBeat >= 0)) {
            return new Bars(List.of(), false);
        }
        int lastBar = tempoMap.toMusicalTime(lastBeat).bar();
        int drawn = Math.min(lastBar + 1, MAX_BAR_LINES);
        for (int bar = 0; bar < drawn; bar++) {
            TimeSignature meter = tempoMap.timeSignatureAtBar(bar);
            double beat = tempoMap.toBeat(new MusicalTime(bar, 0, meter));
            lines.add(new BarLine(bar, tempoMap.beatsToSeconds(beat), meter));
        }
        return new Bars(List.copyOf(lines), lastBar + 1 > MAX_BAR_LINES);
    }

    /**
     * How many bars the recording spans, counting a part-filled last one.
     *
     * <p>One fewer than {@link #barLines} returns for a recording that ends
     * exactly on a bar line, which is the ordinary case for anything looped or
     * MIDI-derived: the closing line begins no bar. Read from the map rather
     * than from that list, so the cap the list draws under cannot be reported
     * as a count.
     */
    static int barCount(TempoMap tempoMap, double durationSeconds) {
        double lastBeat = tempoMap.secondsToBeats(durationSeconds);
        if (!(lastBeat > 0)) {
            return 0;
        }
        MusicalTime end = tempoMap.toMusicalTime(lastBeat);
        // Compared against a tolerance rather than against zero. The end is a
        // beat converted from seconds that were converted from beats, and at
        // any tempo whose beat is not an exact binary fraction of a second the
        // round trip lands a few ulps past the bar line -- which read as a bar
        // of its own, beside the quantizer's correct count on the same page.
        return end.beatInBar() > ON_THE_LINE * Math.max(1, lastBeat)
                ? end.bar() + 1 : end.bar();
    }


    /** How many spans each quality was named on, in the vocabulary's own order. */
    static Map<ChordQuality, Integer> chordQualities(ChordProgression chords) {
        Map<ChordQuality, Integer> counts = new EnumMap<>(ChordQuality.class);
        for (Chord chord : chords.chords()) {
            counts.merge(chord.quality(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * How steady the tracked pulse is.
     *
     * @param shortest the shortest gap between two tracked beats, in seconds
     * @param longest  the longest such gap
     */
    record PulseSpread(double shortest, double longest, double median, double steady) {
    }

    /** Empty for a grid carrying fewer than two beats, which has no interval. */
    static PulseSpread pulseSpread(BeatGrid grid) {
        double[] intervals = beatIntervals(grid);
        if (intervals.length == 0) {
            return null;
        }
        double[] sorted = intervals.clone();
        Arrays.sort(sorted);
        return new PulseSpread(sorted[0], sorted[sorted.length - 1],
                grid.medianPulseRate(), grid.steadyPulseRate());
    }

    /** The gaps between consecutive tracked beats, in seconds, in order. */
    static double[] beatIntervals(BeatGrid grid) {
        List<Double> times = grid.beatTimes();
        if (times.size() < 2) {
            return new double[0];
        }
        double[] intervals = new double[times.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = times.get(i + 1) - times.get(i);
        }
        return intervals;
    }

    /**
     * Counts in equal buckets over {@code [low, high]}, with everything outside
     * that range in the nearest end bucket.
     *
     * <p>Over the values' own range rather than from zero, because what these
     * measure — how far apart the beats are, how long the notes are — clusters
     * far from the origin, and a histogram anchored at zero draws every one of
     * them in the same column.
     */
    static int[] histogram(double[] values, double low, double high, int buckets) {
        if (buckets < 1 || !(high > low)) {
            throw new IllegalArgumentException(
                    "a histogram needs a range and at least one bucket, got "
                            + low + " to " + high + " in " + buckets);
        }
        int[] counts = new int[buckets];
        for (double value : values) {
            if (!Double.isFinite(value)) {
                // Counted nowhere rather than in the bottom bucket, which is
                // where the arithmetic below would put it.
                continue;
            }
            int bucket = (int) ((value - low) / (high - low) * buckets);
            counts[Math.clamp(bucket, 0, buckets - 1)]++;
        }
        return counts;
    }

    /** How many bars each subdivision was chosen for, finest last. */
    static Map<GridResolution, Integer> gridResolutions(QuantizedScore quantized) {
        Map<GridResolution, Integer> counts = new EnumMap<>(GridResolution.class);
        for (BarGrid grid : quantized.grids()) {
            counts.merge(grid.resolution(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * What the reduction to a playable part changed.
     *
     * @param carried how many of the estimate's notes reach the part with their
     *                pitch, onset and length untouched
     */
    record Reduction(int estimateNotes, int playableNotes, int carried) {
    }

    static Reduction reduction(NoteTrack estimate, NoteTrack playable) {
        Map<Integer, List<Note>> byPitch = new HashMap<>();
        for (Note note : estimate.notes()) {
            byPitch.computeIfAbsent(note.midiPitch(), pitch -> new ArrayList<>()).add(note);
        }
        int carried = 0;
        for (Note note : playable.notes()) {
            for (Note before : byPitch.getOrDefault(note.midiPitch(), List.of())) {
                if (same(before.onsetSeconds(), note.onsetSeconds())
                        && same(before.durationSeconds(), note.durationSeconds())) {
                    carried++;
                    break;
                }
            }
        }
        return new Reduction(estimate.size(), playable.size(), carried);
    }

    /**
     * Whether two times are the same moment.
     *
     * <p>Relative, because a note near the end of a long recording carries
     * fewer decimals than one near its start, and an absolute epsilon would
     * call the tail's untouched notes changed.
     */
    private static boolean same(double left, double right) {
        return Math.abs(left - right) <= 1e-9 * Math.max(1, Math.max(Math.abs(left),
                Math.abs(right)));
    }
}
