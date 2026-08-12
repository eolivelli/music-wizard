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

package dev.olivelli.musicwizard.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the singing is: sung stretches of a vocal stem, as sample ranges.
 *
 * <p>The transcriber is fed these rather than the whole recording for two
 * reasons. A song is mostly not singing — intros, solos, the gaps between
 * lines — and a recognizer fed silence is free to hallucinate words into it.
 * And an offline recognizer's cost grows faster than linearly with the window,
 * so a three-minute song goes in as many short windows, not one enormous one.
 *
 * <p>The threshold is relative to the stem's own loud frames, because a vocal
 * stem's scale depends on the mix and the separator; an absolute number would
 * be tuned to one recording. Runs of quiet shorter than {@link #MIN_GAP_SECONDS}
 * stay inside a segment — singers breathe — and a segment longer than
 * {@link #MAX_SEGMENT_SECONDS} is split at its quietest interior frame, the
 * least bad place to cut a phrase.
 */
final class VocalSegments {

    /** A sung stretch: {@code [start, end)} in samples. */
    record Segment(int start, int end) {
        double startSeconds(int sampleRate) {
            return (double) start / sampleRate;
        }
    }

    static final double MIN_GAP_SECONDS = 0.6;
    static final double MAX_SEGMENT_SECONDS = 25;
    static final double MIN_SEGMENT_SECONDS = 0.25;
    /** Hearing starts this much before the first loud frame, and after the last. */
    static final double PADDING_SECONDS = 0.2;

    private static final double HOP_SECONDS = 0.05;
    /** Quiet is this far below the loud frames (95th percentile RMS). */
    private static final double RELATIVE_THRESHOLD = 0.05;

    private VocalSegments() {
    }

    static List<Segment> split(float[] samples, int sampleRate) {
        int hop = Math.max(1, (int) (sampleRate * HOP_SECONDS));
        double[] rms = frameRms(samples, hop);
        double threshold = threshold(rms);
        if (threshold == 0) {
            return List.of();
        }
        List<Segment> runs = loudRuns(rms, threshold, hop, samples.length, sampleRate);
        List<Segment> bounded = new ArrayList<>();
        for (Segment run : runs) {
            splitLongRun(run, rms, hop, sampleRate, bounded);
        }
        return List.copyOf(bounded);
    }

    private static double[] frameRms(float[] samples, int hop) {
        double[] rms = new double[(samples.length + hop - 1) / hop];
        for (int frame = 0; frame < rms.length; frame++) {
            int from = frame * hop;
            int to = Math.min(samples.length, from + hop);
            double sum = 0;
            for (int i = from; i < to; i++) {
                sum += (double) samples[i] * samples[i];
            }
            rms[frame] = Math.sqrt(sum / (to - from));
        }
        return rms;
    }

    /** Quiet cut-off, or 0 when the whole stem is silence. */
    private static double threshold(double[] rms) {
        double[] sorted = rms.clone();
        java.util.Arrays.sort(sorted);
        double loud = sorted[(int) ((sorted.length - 1) * 0.95)];
        return loud < 1e-4 ? 0 : loud * RELATIVE_THRESHOLD;
    }

    private static List<Segment> loudRuns(double[] rms, double threshold, int hop,
                                          int totalSamples, int sampleRate) {
        int maxGapFrames = (int) Math.round(MIN_GAP_SECONDS / HOP_SECONDS);
        int padding = (int) (PADDING_SECONDS * sampleRate);
        List<Segment> runs = new ArrayList<>();
        int runStart = -1;
        int lastLoud = -1;
        for (int frame = 0; frame <= rms.length; frame++) {
            boolean loud = frame < rms.length && rms[frame] >= threshold;
            if (loud) {
                if (runStart < 0) {
                    runStart = frame;
                }
                lastLoud = frame;
            } else if (runStart >= 0
                    && (frame - lastLoud > maxGapFrames || frame == rms.length)) {
                double seconds = (lastLoud - runStart + 1) * HOP_SECONDS;
                if (seconds >= MIN_SEGMENT_SECONDS) {
                    runs.add(new Segment(
                            Math.max(0, runStart * hop - padding),
                            Math.min(totalSamples, (lastLoud + 1) * hop + padding)));
                }
                runStart = -1;
            }
        }
        return runs;
    }

    /** The run itself when short enough, else split at its quietest interior. */
    private static void splitLongRun(Segment run, double[] rms, int hop,
                                     int sampleRate, List<Segment> out) {
        int maxSamples = (int) (MAX_SEGMENT_SECONDS * sampleRate);
        if (run.end() - run.start() <= maxSamples) {
            out.add(run);
            return;
        }
        // The quietest frame in the middle half: a cut near either edge would
        // leave one side as long as the whole was.
        int fromFrame = (run.start() + (run.end() - run.start()) / 4) / hop;
        int toFrame = (run.start() + 3 * (run.end() - run.start()) / 4) / hop;
        int quietest = fromFrame;
        for (int frame = fromFrame; frame <= Math.min(toFrame, rms.length - 1); frame++) {
            if (rms[frame] < rms[quietest]) {
                quietest = frame;
            }
        }
        int cut = quietest * hop;
        splitLongRun(new Segment(run.start(), cut), rms, hop, sampleRate, out);
        splitLongRun(new Segment(cut, run.end()), rms, hop, sampleRate, out);
    }
}
