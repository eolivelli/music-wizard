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

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.AudioDecoder;
import dev.olivelli.musicwizard.dsp.BeatTracker;
import dev.olivelli.musicwizard.dsp.OnsetEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scores the beat grid itself, where the two Python harnesses score the chords
 * laid on it.
 *
 * <p>{@code tools/score-samples.py} and {@code tools/score-chart.py} both ask
 * whether the right chord is in the right bar, so a beat-tracking defect only
 * reaches them through chord recognition and arrives mixed with it. This asks
 * the beat question directly: are the tracked pulses the recording's pulses.
 *
 * <p>Usage, from the repository root, once {@code mvn -DskipTests package} has
 * built the shaded jar:
 *
 * <pre>
 *   java -cp mw-cli/target/mw.jar tools/ScoreBeats.java
 * </pre>
 *
 * <p>It decodes every benchmark present in {@code samples/} and takes a few
 * minutes, most of it in the twelve-minute one.
 *
 * <h2>Where the ground truth comes from, and what it is worth</h2>
 *
 * <p>All five benchmarks are programmed loops with rigid timing, which is the
 * only reason this is possible: a recording that repeats exactly has a beat
 * period that can be measured from the recording rather than estimated. The
 * loop length is the onset envelope's autocorrelation peak in a range around
 * the known cycle, refined on a high-order repeat so the frame quantisation is
 * divided by the number of cycles out; the phase is whichever offset of a comb
 * at that period collects the most envelope energy.
 *
 * <p><strong>That reference is derived, not given, and two things follow.</strong>
 * It is only as good as the assumption that the recording holds one tempo, so
 * this says nothing about material that does not — which is every real
 * recording. And it is independent of {@link BeatTracker} but not of
 * {@link OnsetEnvelope}, which both sides read, so a defect in the envelope
 * would move both and go unseen here.
 *
 * <p>The printed reference tempo is the check on all of that: it should land on
 * the round number the benchmark is named for. Measured on the five, it gives
 * 106.000, 89.998, 105.000, 89.999 and 74.944 BPM, against three files named
 * for 90, 106 and 90 and a bossa with no stated tempo. Four agree to three
 * decimal places. {@code blues-shuffle-a-106bpm.mp3} does not: its loop measures
 * 105.000 over 27.4285 s, and the discrepancy with the name is unresolved.
 *
 * <h2>The columns</h2>
 *
 * <dl>
 *   <dt>F, P, R</dt>
 *   <dd>The standard beat-tracking F-measure at a 70 ms tolerance, and the
 *       precision and recall it comes from. <b>It is unforgiving of a constant
 *       offset</b>: a grid that is right in every respect but sits 80 ms late
 *       throughout scores near zero, which is why the offset is printed beside
 *       it rather than left to be inferred.</dd>
 *   <dt>beats</dt>
 *   <dd>Tracked against the reference's own count. This is the column #196 was
 *       about — a tracker that inserts a beat per cycle shows up here before it
 *       shows up anywhere else.</dd>
 *   <dt>slips</dt>
 *   <dd>Places where the tracked grid advances by other than one reference beat.
 *       Zero is the claim worth making: it means the grid never loses its place,
 *       whatever the timing error within a beat.</dd>
 *   <dt>offset</dt>
 *   <dd>Mean and root-mean-square distance from the nearest reference beat, over
 *       the beats within half a period of one. The mean is a phase error and the
 *       RMS is jitter; the F-measure conflates them.</dd>
 *   <dt>on grid, 2/3</dt>
 *   <dd>Share of tracked intervals within a tenth of the tracked median, and
 *       within a tenth of two thirds of it. The second is the detour #196 is
 *       made of, and unlike everything above it needs no reference at all, which
 *       is why it is the form {@code BluesLoopIT} asserts.</dd>
 * </dl>
 */
public final class ScoreBeats {

    /**
     * One benchmark: its file, its bars per cycle, and where to look for the
     * cycle in the autocorrelation.
     *
     * <p>The search range is wide enough to hold the cycle and narrow enough to
     * exclude its double. Getting it wrong is not silent — the reference tempo
     * comes out at some fraction of the true one and the printed value says so.
     */
    private record Job(String file, int barsPerCycle, double lowSeconds, double highSeconds) {}

    private static final List<Job> JOBS = List.of(
            new Job("gmajorblues.mp3", 12, 20, 40),
            new Job("blues-a-90bpm.mp3", 12, 24, 44),
            new Job("blues-shuffle-a-106bpm.mp3", 12, 20, 40),
            new Job("blues-e-90bpm.mp3", 12, 24, 44),
            new Job("bossa-cm.mp3", 16, 24, 56));

    /** Half the standard beat-tracking tolerance, either side. */
    private static final double TOLERANCE_SECONDS = 0.070;

    private ScoreBeats() {
    }

    public static void main(String[] args) {
        Path samples = Path.of("samples");
        if (!Files.isDirectory(samples)) {
            System.err.println("run this from the repository root: samples/ not found");
            System.exit(1);
        }
        System.out.printf("%-28s %9s  %-34s %-14s %-16s%n",
                "recording", "reference", "beats", "offset", "intervals");
        for (Job job : JOBS) {
            Path file = samples.resolve(job.file());
            if (!Files.isRegularFile(file)) {
                System.out.printf("%-28s not present (local-only; see samples/list.txt)%n",
                        job.file());
                continue;
            }
            score(job, file);
        }
    }

    private static void score(Job job, Path file) {
        AudioBuffer audio = AudioDecoder.decode(file, AudioDecoder.ANALYSIS_SAMPLE_RATE);
        OnsetEnvelope envelope = OnsetEnvelope.fromAudio(audio);
        double period = referencePeriod(envelope, job);
        double phase = referencePhase(envelope, period);
        double duration = envelope.length() / envelope.frameRate();
        int expected = (int) Math.floor((duration - phase) / period) + 1;

        List<Double> beats = BeatTracker.track(envelope).beatTimes();
        if (beats.size() < 2) {
            System.out.printf("%-28s %9.3f  no usable grid%n", job.file(), 60 / period);
            return;
        }

        Set<Long> matched = new HashSet<>();
        int hits = 0;
        int slips = 0;
        long previous = Long.MIN_VALUE;
        double sum = 0;
        double sumOfSquares = 0;
        int near = 0;
        for (double beat : beats) {
            long index = Math.round((beat - phase) / period);
            double error = beat - (phase + index * period);
            if (index >= 0 && Math.abs(error) <= TOLERANCE_SECONDS && matched.add(index)) {
                hits++;
            }
            if (previous != Long.MIN_VALUE && index != previous + 1) {
                slips++;
            }
            previous = index;
            if (Math.abs(error) < 0.5 * period) {
                sum += error;
                sumOfSquares += error * error;
                near++;
            }
        }
        double precision = (double) hits / beats.size();
        double recall = (double) hits / expected;
        double f = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);

        double median = medianInterval(beats);
        int onGrid = 0;
        int detours = 0;
        for (int i = 1; i < beats.size(); i++) {
            double ratio = (beats.get(i) - beats.get(i - 1)) / median;
            if (Math.abs(ratio - 1) < 0.10) {
                onGrid++;
            } else if (Math.abs(ratio - 2.0 / 3) < 0.10) {
                detours++;
            }
        }
        int intervals = beats.size() - 1;

        System.out.printf("%-28s %6.3f BPM  F=%.3f P=%.3f R=%.3f %5d/%-5d slips=%-3d "
                        + "mean=%+.3f rms=%.3f  on grid %5.1f%%  2/3 %4.1f%%%n",
                job.file(), 60 / period, f, precision, recall, beats.size(), expected, slips,
                near == 0 ? 0 : sum / near, near == 0 ? 0 : Math.sqrt(sumOfSquares / near),
                100.0 * onGrid / intervals, 100.0 * detours / intervals);
    }

    /**
     * The recording's beat period, in seconds, from the length of its loop.
     *
     * <p>Two stages, because one is not accurate enough. The coarse peak is
     * quantised to the 5.8 ms frame, which over a 27 s cycle is already 0.02%,
     * but the parabolic interpolation that would fix that is itself only as good
     * as the peak's symmetry. Re-finding the same peak {@code k} cycles out and
     * dividing by {@code k} divides both errors by {@code k}.
     *
     * <p>The far search is bounded to a fraction of one beat so it cannot slide
     * onto the neighbouring cycle's peak, which is what an earlier version did
     * on the bossa: its 16-bar cycle sits at 51.2 s and a wide window found the
     * 15-bar repeat instead, reporting 74.94 BPM as 79.94.
     */
    private static double referencePeriod(OnsetEnvelope envelope, Job job) {
        double[] strength = envelope.strength();
        double frameRate = envelope.frameRate();
        double best = Double.NEGATIVE_INFINITY;
        int bestLag = (int) (job.lowSeconds() * frameRate);
        for (int lag = (int) (job.lowSeconds() * frameRate);
                lag <= (int) (job.highSeconds() * frameRate); lag++) {
            double value = autocorrelation(strength, lag);
            if (value > best) {
                best = value;
                bestLag = lag;
            }
        }
        double coarse = interpolatedPeak(strength, bestLag, 2);
        int beatsPerCycle = job.barsPerCycle() * 4;
        int cycles = Math.max(1, (int) (0.35 * strength.length / coarse));
        double far = interpolatedPeak(strength, (int) Math.round(cycles * coarse),
                (int) (0.3 * coarse / beatsPerCycle));
        return far / cycles / frameRate / beatsPerCycle;
    }

    /** The autocorrelation peak nearest a lag, to sub-frame resolution. */
    private static double interpolatedPeak(double[] strength, int near, int radius) {
        int best = near;
        for (int lag = Math.max(1, near - radius); lag <= near + radius; lag++) {
            if (lag < strength.length && autocorrelation(strength, lag)
                    > autocorrelation(strength, best)) {
                best = lag;
            }
        }
        double before = autocorrelation(strength, best - 1);
        double at = autocorrelation(strength, best);
        double after = autocorrelation(strength, best + 1);
        double curvature = before - 2 * at + after;
        return best + (curvature != 0 ? 0.5 * (before - after) / curvature : 0);
    }

    private static double autocorrelation(double[] strength, int lag) {
        double sum = 0;
        for (int i = 0; i + lag < strength.length; i++) {
            sum += strength[i] * strength[i + lag];
        }
        return sum / strength.length;
    }

    /**
     * The comb phase that collects the most onset energy at this period.
     *
     * <p>A millisecond grid, which is far finer than the envelope's 5.8 ms
     * frame, because the comb sums hundreds of frames and its maximum is not at
     * a frame boundary.
     */
    private static double referencePhase(OnsetEnvelope envelope, double period) {
        double[] strength = envelope.strength();
        double frameRate = envelope.frameRate();
        double best = Double.NEGATIVE_INFINITY;
        double bestPhase = 0;
        for (double phase = 0; phase < period; phase += 0.001) {
            double sum = 0;
            for (double t = phase; t < strength.length / frameRate; t += period) {
                int frame = (int) Math.round(t * frameRate);
                if (frame >= 0 && frame < strength.length) {
                    sum += strength[frame];
                }
            }
            if (sum > best) {
                best = sum;
                bestPhase = phase;
            }
        }
        return bestPhase;
    }

    private static double medianInterval(List<Double> beats) {
        double[] intervals = new double[beats.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = beats.get(i + 1) - beats.get(i);
        }
        Arrays.sort(intervals);
        int middle = intervals.length / 2;
        return intervals.length % 2 == 1
                ? intervals[middle]
                : (intervals[middle - 1] + intervals[middle]) / 2.0;
    }
}
