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

package dev.olivelli.musicwizard.dsp;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.AudioDecoder;
import java.util.Arrays;
import java.util.Objects;
import org.jtransforms.fft.DoubleFFT_1D;

/**
 * Monophonic fundamental-frequency tracking, after pYIN (Mauch and Dixon,
 * 2014): YIN's difference function read at many thresholds at once, and the
 * resulting per-frame distribution over pitches decoded by Viterbi.
 *
 * <p><strong>Monophonic.</strong> Point it at a mix and it estimates one
 * fundamental for whatever is loudest and most periodic, which on a full band
 * is usually the bass. It is for a melody that has already been made the only
 * thing in the signal — a separated vocal stem, or a package generated without
 * accompaniment. Nothing here fails on a mix; it answers confidently, and the
 * answer is not the melody.
 *
 * <p>Two decisions carry the quality, and both are pYIN's rather than YIN's.
 *
 * <p>First, <b>no threshold is chosen</b>. YIN takes the first dip in the
 * cumulative-mean-normalised difference below a fixed threshold, and that one
 * constant decides every octave error the tracker will ever make: too high and
 * a shallow subharmonic dip wins, too low and the true period is passed over
 * for its double. Here every threshold in {@link #THRESHOLD_COUNT} is applied
 * and the candidate each one selects is weighted by how likely that threshold
 * is under a Beta prior. A frame with an unambiguous period gives one candidate
 * whatever the threshold; an ambiguous one gives a distribution, and the
 * ambiguity survives to be settled by the frame's neighbours rather than by a
 * constant.
 *
 * <p>Second, <b>the sequence is decoded, not the frame</b>. Pitch moves slowly
 * within a note and voicing persists, so the transition model is a triangular
 * band in pitch times a two-state voiced/unvoiced switch. This is what removes
 * the octave errors that survive the first step: a frame whose strongest
 * candidate is an octave down still loses if its neighbours are an octave up,
 * because reaching it costs more than its emission gains. The price is stated
 * on {@link PitchTrack#timeOf} — the same continuity smears a note boundary
 * over the frames the path takes to travel it, so an onset read from this track
 * is late, and a leap is later than a step.
 */
public final class PitchTracker {

    /**
     * 93 ms at the analysis rate. Long enough to hold six periods of the lowest
     * pitch tracked, which is what the difference function needs in order to see
     * a period at all; every millisecond of it is also blur across a boundary
     * between two notes.
     */
    public static final int WINDOW = 2048;

    /** 11.6 ms per frame at the analysis rate, or about 86 frames per second. */
    public static final int HOP = 256;

    /** C2. Below the bottom of a bass singer, and far below any melody. */
    public static final double MIN_HZ = 65.406;

    /** D sharp 6: above any melody, and low enough to keep the bin count down. */
    public static final double MAX_HZ = 1244.5;

    /**
     * Pitch resolution of the decoder, in bins per semitone.
     *
     * <p>Five, or 20 cents. The result is rounded to semitones downstream, so
     * this buys nothing in accuracy; what it buys is a transition band wide
     * enough to be smooth and narrow enough to decode, since Viterbi costs bins
     * times band width per frame and both grow with this number.
     */
    private static final int BINS_PER_SEMITONE = 5;

    /**
     * How far the path may move in one frame, in semitones.
     *
     * <p>Five semitones per 11.6 ms frame is far faster than anything is sung
     * and far slower than a leap between two notes. That is the compromise the
     * model is: an interval wider than this is crossed over several frames
     * rather than in one, so a leap's onset arrives late in proportion to its
     * size. Widening it until leaps land on time is not free — the band is also
     * what refuses an octave error, and at a width that admits a twelfth in one
     * frame it admits the octave below the current note just as cheaply.
     */
    private static final int MAX_SEMITONE_STEP = 5;

    /** Probability of changing between voiced and unvoiced at a frame boundary. */
    private static final double SWITCH_PROBABILITY = 0.01;

    /** How many thresholds the difference function is read at. */
    private static final int THRESHOLD_COUNT = 100;

    /**
     * Beta prior over the YIN threshold: mean {@code A / (A + B)} = 0.1.
     *
     * <p>pYIN's own shape. It says that the threshold which separates a true
     * period from a subharmonic is usually near 0.1 and rarely much higher — a
     * deep dip is strong evidence, a shallow one is weak evidence — and it is
     * what turns "which threshold" into "how much weight".
     */
    private static final double BETA_A = 2;

    /** See {@link #BETA_A}. */
    private static final double BETA_B = 18;

    /**
     * Total voiced probability one frame may claim, so that no frame is certain.
     *
     * <p>A frame whose voiced mass reached 1 would make the unvoiced state
     * unreachable through it at any cost, which lets a single confident frame in
     * the middle of a silence pin the whole path voiced.
     */
    private static final double MAX_VOICED_PROBABILITY = 0.99;

    /** Below this RMS a frame is silence, and its difference function is noise. */
    private static final double SILENCE_RMS = 1e-4;

    /** Keeps a zero emission from becoming a negative infinity that no path leaves. */
    private static final double EMISSION_FLOOR = 1e-12;

    private final int sampleRate;
    private final int minLag;
    private final int maxLag;
    private final int binCount;
    private final int bandWidth;
    private final double[] thresholds;
    private final double[] thresholdWeights;
    private final double[] bandLogWeights;
    private final double[] rowLogNormaliser;
    private final DoubleFFT_1D fft;

    private PitchTracker(int sampleRate) {
        this.sampleRate = sampleRate;
        this.minLag = (int) Math.floor(sampleRate / MAX_HZ);
        this.maxLag = (int) Math.ceil(sampleRate / MIN_HZ);
        this.binCount = (int) Math.round(12 * BINS_PER_SEMITONE * log2(MAX_HZ / MIN_HZ)) + 1;
        this.bandWidth = MAX_SEMITONE_STEP * BINS_PER_SEMITONE;
        this.thresholds = new double[THRESHOLD_COUNT];
        this.thresholdWeights = new double[THRESHOLD_COUNT];
        double weightSum = 0;
        for (int i = 0; i < THRESHOLD_COUNT; i++) {
            double threshold = (i + 1.0) / THRESHOLD_COUNT;
            thresholds[i] = threshold;
            thresholdWeights[i] = Math.pow(threshold, BETA_A - 1)
                    * Math.pow(1 - threshold, BETA_B - 1);
            weightSum += thresholdWeights[i];
        }
        for (int i = 0; i < THRESHOLD_COUNT; i++) {
            thresholdWeights[i] /= weightSum;
        }
        // Triangular across the band, so a step costs in proportion to its size.
        double[] bandWeights = new double[bandWidth + 1];
        this.bandLogWeights = new double[bandWidth + 1];
        for (int d = 0; d <= bandWidth; d++) {
            bandWeights[d] = 1.0 - (double) d / (bandWidth + 1);
            bandLogWeights[d] = Math.log(bandWeights[d]);
        }
        // Each source bin's outgoing weights sum to one. Without this the bins
        // within a band of an edge would be cheaper to sit on than the middle
        // of the range, for no reason but that they have fewer places to go.
        this.rowLogNormaliser = new double[binCount];
        for (int bin = 0; bin < binCount; bin++) {
            double sum = 0;
            for (int d = -bandWidth; d <= bandWidth; d++) {
                if (bin + d >= 0 && bin + d < binCount) {
                    sum += bandWeights[Math.abs(d)];
                }
            }
            rowLogNormaliser[bin] = Math.log(sum);
        }
        this.fft = new DoubleFFT_1D(2 * WINDOW);
    }

    /**
     * Tracks the fundamental of a monophonic signal.
     *
     * @throws IllegalArgumentException if the audio is not at the analysis rate
     */
    public static PitchTrack track(AudioBuffer audio) {
        Objects.requireNonNull(audio, "audio");
        if (audio.sampleRate() != AudioDecoder.ANALYSIS_SAMPLE_RATE) {
            throw new IllegalArgumentException("pitch tracking expects audio at "
                    + AudioDecoder.ANALYSIS_SAMPLE_RATE + " Hz, got: " + audio.sampleRate());
        }
        return new PitchTracker(audio.sampleRate()).run(audio);
    }

    private PitchTrack run(AudioBuffer audio) {
        float[] samples = audio.samples();
        int frameCount = samples.length < WINDOW ? 0 : 1 + (samples.length - WINDOW) / HOP;
        if (frameCount <= 0) {
            return new PitchTrack(new double[0], new boolean[0], new double[0],
                    sampleRate, WINDOW, HOP);
        }

        double[] emission = new double[binCount];
        double[] previous = new double[2 * binCount];
        double[] current = new double[2 * binCount];
        // One row per frame, and a state index fits in a short: at 20 cents over
        // five and a half octaves there are a few hundred of them, and the rows
        // are the one thing here whose size grows with the recording's length.
        short[][] backPointers = new short[frameCount][];
        double[] voicedness = new double[frameCount];
        double[] fromVoiced = new double[binCount];
        double[] fromUnvoiced = new double[binCount];
        int[] fromVoicedAt = new int[binCount];
        int[] fromUnvoicedAt = new int[binCount];
        double[] frame = new double[2 * WINDOW];
        double[] prefix = new double[WINDOW + 1];
        double[] difference = new double[maxLag + 1];

        double logStay = Math.log(1 - SWITCH_PROBABILITY);
        double logSwitch = Math.log(SWITCH_PROBABILITY);

        for (int f = 0; f < frameCount; f++) {
            voicedness[f] = emissionsOf(samples, f * HOP, frame, prefix, difference, emission);
            double unvoicedEmission = Math.log((1 - voicedness[f]) / binCount + EMISSION_FLOOR);

            if (f == 0) {
                // Nothing is known before the first frame, so its own evidence
                // is all there is: a flat prior over every state.
                double uniform = Math.log(0.5 / binCount);
                for (int bin = 0; bin < binCount; bin++) {
                    previous[bin] = uniform + Math.log(emission[bin] + EMISSION_FLOOR);
                    previous[binCount + bin] = uniform + unvoicedEmission;
                }
                continue;
            }

            bestWithinBand(previous, 0, fromVoiced, fromVoicedAt);
            bestWithinBand(previous, binCount, fromUnvoiced, fromUnvoicedAt);

            short[] back = new short[2 * binCount];
            for (int bin = 0; bin < binCount; bin++) {
                double stayVoiced = fromVoiced[bin] + logStay;
                double becomeVoiced = fromUnvoiced[bin] + logSwitch;
                if (stayVoiced >= becomeVoiced) {
                    current[bin] = stayVoiced;
                    back[bin] = (short) fromVoicedAt[bin];
                } else {
                    current[bin] = becomeVoiced;
                    back[bin] = (short) (binCount + fromUnvoicedAt[bin]);
                }
                current[bin] += Math.log(emission[bin] + EMISSION_FLOOR);

                double stayUnvoiced = fromUnvoiced[bin] + logStay;
                double becomeUnvoiced = fromVoiced[bin] + logSwitch;
                if (stayUnvoiced >= becomeUnvoiced) {
                    current[binCount + bin] = stayUnvoiced;
                    back[binCount + bin] = (short) (binCount + fromUnvoicedAt[bin]);
                } else {
                    current[binCount + bin] = becomeUnvoiced;
                    back[binCount + bin] = (short) fromVoicedAt[bin];
                }
                current[binCount + bin] += unvoicedEmission;
            }
            backPointers[f] = back;
            double[] swap = previous;
            previous = current;
            current = swap;
        }

        return decode(previous, backPointers, voicedness);
    }

    /**
     * The best predecessor of every bin, over the transition band.
     *
     * <p>Written as one scan per source offset rather than the obvious loop over
     * each target's neighbours, so the band's log weights are read once per
     * offset instead of once per pair.
     */
    private void bestWithinBand(double[] scores, int offset, double[] best, int[] bestAt) {
        Arrays.fill(best, Double.NEGATIVE_INFINITY);
        for (int d = -bandWidth; d <= bandWidth; d++) {
            double weight = bandLogWeights[Math.abs(d)];
            int from = Math.max(0, -d);
            int to = Math.min(binCount, binCount - d);
            for (int bin = from; bin < to; bin++) {
                int source = bin + d;
                double score = scores[offset + source] - rowLogNormaliser[source] + weight;
                if (score > best[bin]) {
                    best[bin] = score;
                    bestAt[bin] = source;
                }
            }
        }
    }

    private PitchTrack decode(double[] last, short[][] backPointers, double[] voicedness) {
        int frameCount = backPointers.length;
        int state = 0;
        for (int s = 1; s < 2 * binCount; s++) {
            if (last[s] > last[state]) {
                state = s;
            }
        }
        double[] frequencies = new double[frameCount];
        boolean[] voiced = new boolean[frameCount];
        for (int f = frameCount - 1; f >= 0; f--) {
            voiced[f] = state < binCount;
            frequencies[f] = frequencyOfBin(state % binCount);
            if (f > 0) {
                state = backPointers[f][state];
            }
        }
        return new PitchTrack(frequencies, voiced, voicedness, sampleRate, WINDOW, HOP);
    }

    /**
     * Fills {@code emission} with one frame's distribution over pitch bins, and
     * returns how much of the mass is voiced.
     *
     * <p>The thresholds are walked downwards while the lags are walked upwards,
     * which is what lets one pass serve all hundred of them. Raising the
     * threshold can only make the first qualifying trough earlier, so once a
     * trough has claimed every threshold above its own depth, the thresholds
     * still unclaimed are exactly those below it — a prefix — and the next
     * trough claims a contiguous range of that prefix in turn.
     */
    private double emissionsOf(float[] samples, int offset, double[] frame, double[] prefix,
                               double[] difference, double[] emission) {
        Arrays.fill(emission, 0);
        double energy = 0;
        for (int i = 0; i < WINDOW; i++) {
            double sample = samples[offset + i];
            frame[i] = sample;
            energy += sample * sample;
        }
        if (Math.sqrt(energy / WINDOW) < SILENCE_RMS) {
            return 0;
        }
        Arrays.fill(frame, WINDOW, 2 * WINDOW, 0);
        cumulativeMeanNormalisedDifference(frame, prefix, difference);

        double voicedMass = 0;
        int unclaimed = THRESHOLD_COUNT - 1;
        for (int lag = minLag + 1; lag < maxLag && unclaimed >= 0; lag++) {
            boolean trough = difference[lag] < difference[lag - 1]
                    && difference[lag] <= difference[lag + 1];
            if (!trough) {
                continue;
            }
            double weight = 0;
            while (unclaimed >= 0 && thresholds[unclaimed] > difference[lag]) {
                weight += thresholdWeights[unclaimed];
                unclaimed--;
            }
            if (weight > 0) {
                addCandidate(emission, refineLag(difference, lag), weight);
                voicedMass += weight;
            }
        }
        if (voicedMass <= 0) {
            return 0;
        }
        double scale = Math.min(voicedMass, MAX_VOICED_PROBABILITY);
        for (int bin = 0; bin < binCount; bin++) {
            emission[bin] *= scale / voicedMass;
        }
        return scale;
    }

    /** Spreads one candidate's weight over the bin it falls in and its neighbour. */
    private void addCandidate(double[] emission, double lag, double weight) {
        double position = binOf(sampleRate / lag);
        int low = (int) Math.floor(position);
        double fraction = position - low;
        if (low >= 0 && low < binCount) {
            emission[low] += weight * (1 - fraction);
        }
        if (low + 1 >= 0 && low + 1 < binCount) {
            emission[low + 1] += weight * fraction;
        }
    }

    /**
     * YIN's difference function, cumulative-mean normalised.
     *
     * <p>Computed through the autocorrelation rather than directly: the direct
     * form is a multiply-accumulate for every lag of every window, a few billion
     * operations per minute of audio at this hop, and the identity
     * {@code d(t) = P(W-t) + P(W) - P(t) - 2 r(t)} turns each frame into one
     * transform instead. {@code P} is the prefix sum of squares and {@code r}
     * the autocorrelation, which is linear rather than circular because the
     * second half of the buffer is zero.
     */
    private void cumulativeMeanNormalisedDifference(double[] frame, double[] prefix,
                                                    double[] difference) {
        for (int i = 0; i < WINDOW; i++) {
            prefix[i + 1] = prefix[i] + frame[i] * frame[i];
        }
        fft.realForward(frame);
        // Power spectrum in place, then back. The real-input packing keeps the
        // Nyquist term in the imaginary slot of bin zero, so both live in the
        // first two cells and neither has an imaginary part of its own.
        frame[0] = frame[0] * frame[0];
        frame[1] = frame[1] * frame[1];
        for (int k = 1; k < WINDOW; k++) {
            double re = frame[2 * k];
            double im = frame[2 * k + 1];
            frame[2 * k] = re * re + im * im;
            frame[2 * k + 1] = 0;
        }
        fft.realInverse(frame, true);

        difference[0] = 0;
        double runningSum = 0;
        for (int lag = 1; lag <= maxLag; lag++) {
            double raw = prefix[WINDOW - lag] + prefix[WINDOW] - prefix[lag] - 2 * frame[lag];
            // Rounding can put a difference of zero a hair below it, and the
            // square of a real difference cannot be negative.
            raw = Math.max(raw, 0);
            runningSum += raw;
            difference[lag] = runningSum > 0 ? raw * lag / runningSum : 1;
        }
    }

    /** Parabolic interpolation through a trough, for sub-sample lag resolution. */
    private double refineLag(double[] difference, int lag) {
        double before = difference[lag - 1];
        double at = difference[lag];
        double after = difference[lag + 1];
        double denominator = before + after - 2 * at;
        if (denominator <= 0) {
            return lag;
        }
        return lag + Math.clamp(0.5 * (before - after) / denominator, -1.0, 1.0);
    }

    private double binOf(double frequencyHz) {
        return 12 * BINS_PER_SEMITONE * log2(frequencyHz / MIN_HZ);
    }

    private double frequencyOfBin(int bin) {
        return MIN_HZ * Math.pow(2, (double) bin / (12 * BINS_PER_SEMITONE));
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}
