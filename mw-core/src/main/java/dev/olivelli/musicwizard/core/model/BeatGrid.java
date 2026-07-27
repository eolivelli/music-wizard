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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tracked beat positions, with downbeats marked.
 *
 * <p>Produced by beat tracking and consumed by everything that needs to speak in
 * musical time. Downbeat detection is markedly less reliable than beat
 * detection, so the two carry separate confidences and callers are expected to
 * check them independently.
 *
 * @param beats  the tracked beats, ordered in time and non-empty
 * @param beatConfidence     trust in the beat positions themselves
 * @param downbeatConfidence trust in which of those beats are downbeats
 */
public record BeatGrid(List<Beat> beats, Confidence beatConfidence, Confidence downbeatConfidence) {

    /**
     * One tracked beat.
     *
     * @param seconds    when the beat falls
     * @param downbeat   whether this beat begins a bar
     * @param positionInBar zero-based position within its bar, or -1 if unknown
     */
    public record Beat(double seconds, boolean downbeat, int positionInBar) {
        public Beat {
            if (!Double.isFinite(seconds) || seconds < 0) {
                throw new IllegalArgumentException("beat time must be finite and non-negative, got: " + seconds);
            }
            if (positionInBar < -1) {
                throw new IllegalArgumentException("positionInBar must be -1 or non-negative, got: " + positionInBar);
            }
        }

        /** A beat whose position within the bar has not been determined. */
        public static Beat unphased(double seconds) {
            return new Beat(seconds, false, -1);
        }
    }

    public BeatGrid {
        Objects.requireNonNull(beats, "beats");
        Objects.requireNonNull(beatConfidence, "beatConfidence");
        Objects.requireNonNull(downbeatConfidence, "downbeatConfidence");
        if (beats.isEmpty()) {
            throw new IllegalArgumentException("a beat grid needs at least one beat");
        }
        beats = List.copyOf(beats);
        for (int i = 1; i < beats.size(); i++) {
            if (beats.get(i).seconds() <= beats.get(i - 1).seconds()) {
                throw new IllegalArgumentException(
                        "beats must strictly increase in time; beat " + i + " at "
                                + beats.get(i).seconds() + "s does not follow beat " + (i - 1)
                                + " at " + beats.get(i - 1).seconds() + "s");
            }
        }
    }

    /** Builds a grid from bare beat times, assuming a fixed meter and a downbeat on the first beat. */
    public static BeatGrid ofTimes(List<Double> beatSeconds, int beatsPerBar, Confidence confidence) {
        Objects.requireNonNull(beatSeconds, "beatSeconds");
        if (beatsPerBar < 1) {
            throw new IllegalArgumentException("beatsPerBar must be positive, got: " + beatsPerBar);
        }
        List<Beat> built = new ArrayList<>(beatSeconds.size());
        for (int i = 0; i < beatSeconds.size(); i++) {
            int position = i % beatsPerBar;
            built.add(new Beat(beatSeconds.get(i), position == 0, position));
        }
        return new BeatGrid(built, confidence, confidence);
    }

    /** Just the beat times. */
    public List<Double> beatTimes() {
        return beats.stream().map(Beat::seconds).toList();
    }

    /** Just the downbeat times. */
    public List<Double> downbeatTimes() {
        return beats.stream().filter(Beat::downbeat).map(Beat::seconds).toList();
    }

    /** Median tempo implied by the beat intervals, in beats per minute. */
    public double medianTempo() {
        if (beats.size() < 2) {
            throw new IllegalStateException("cannot infer tempo from fewer than two beats");
        }
        double[] intervals = new double[beats.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = beats.get(i + 1).seconds() - beats.get(i).seconds();
        }
        java.util.Arrays.sort(intervals);
        int middle = intervals.length / 2;
        double median = intervals.length % 2 == 1
                ? intervals[middle]
                : (intervals[middle - 1] + intervals[middle]) / 2.0;
        return 60.0 / median;
    }

    /** Index of the beat nearest a given time. */
    public int nearestBeatIndex(double seconds) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < beats.size(); i++) {
            double distance = Math.abs(beats.get(i).seconds() - seconds);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            } else {
                // Times are sorted, so once distance grows we are past the nearest.
                break;
            }
        }
        return best;
    }

    public int size() {
        return beats.size();
    }
}
