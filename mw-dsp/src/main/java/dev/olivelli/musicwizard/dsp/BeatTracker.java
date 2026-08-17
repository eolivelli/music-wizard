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

import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Confidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Beat tracking by dynamic programming, after Ellis (2007).
 *
 * <p>The idea is that a good beat sequence is one where beats land on onsets
 * <em>and</em> are evenly spaced, and that the best trade-off between those two
 * can be found exactly rather than greedily. Maximise
 *
 * <pre>
 *   S(B) = sum over beats of onset strength
 *        + lambda * sum over gaps of a penalty on deviating from the period
 * </pre>
 *
 * <p>with the recursion {@code D(n) = max over m of { D(m) + lambda * P(n - m) }
 * + strength(n)} and {@code P(delta) = -(ln(delta / period))^2}. Because the
 * penalty is a function of the log ratio, being 10% fast costs the same as being
 * 10% slow, which is what keeps the tracker from drifting in one direction.
 *
 * <p>Backtracking from the best final score gives the globally optimal sequence
 * for the assumed tempo — no greedy commitment, no local minima. The cost is
 * that a single tempo is assumed, so a piece that drifts is tracked in
 * overlapping windows with the tempo re-estimated in each.
 */
public final class BeatTracker {

    /**
     * Weight of the spacing penalty against onset strength — the reference
     * implementations' value for the penalty as Ellis writes it, in natural
     * logs. <strong>It has to be read together with the base of the
     * logarithm, and that is how it was wrong</strong>: the penalty used to
     * be written in log2 at weight 1, a factor of {@code (ln 2)^2} under the
     * published one — a different algorithm, not a loose setting, which is
     * why no constant downstream could compensate (#196: a swung eighth
     * bought its own beat and the detours accumulated into a rate error).
     * {@code BeatTrackingTest.aLouderOffbeatDoesNotBuyItselfABeat} pins the
     * mechanism, {@code BluesLoopIT} the consequence. The value is the
     * published one and was not tuned: a sweep over programmed loops rewards
     * rigidity without bound and cannot choose one, and somewhere above it
     * the tracker must stop following a human rubato, which nothing here
     * measures.
     */
    private static final double TIGHTNESS = 100.0;

    /** Window over which one tempo is assumed, in seconds. */
    private static final double WINDOW_SECONDS = 25.0;

    private BeatTracker() {
    }

    /** Tracked beats and the tempo they imply. */
    public record Result(List<Double> beatTimes, double beatsPerMinute, Confidence confidence) {
        public Result {
            beatTimes = List.copyOf(Objects.requireNonNull(beatTimes, "beatTimes"));
            Objects.requireNonNull(confidence, "confidence");
        }

        public boolean isEmpty() {
            return beatTimes.isEmpty();
        }
    }

    /**
     * Tracks beats across a whole recording, re-estimating tempo per window so
     * that a gradual change of pace does not break the constant-tempo
     * assumption the recursion depends on.
     */
    public static Result track(OnsetEnvelope envelope) {
        return track(envelope, HarmonicRhythm.none());
    }

    /**
     * The same, with the recording's harmonic rhythm weighing every window's
     * tempo candidates — see
     * {@link TempoEstimator#estimate(OnsetEnvelope, HarmonicRhythm)}.
     */
    public static Result track(OnsetEnvelope envelope, HarmonicRhythm rhythm) {
        return track(envelope, rhythm, null);
    }

    /**
     * The same again, with the recording's bass register available to say
     * whether the pulse everything else settled on is stated or is a
     * subdivision of the stated one — see {@link MarkedPulse}. Pass
     * {@code null} for a caller that has no register; the octave is then left
     * where the envelope and the prior put it.
     */
    public static Result track(OnsetEnvelope envelope, HarmonicRhythm rhythm,
                               OnsetEnvelope pulseRegister) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(rhythm, "rhythm");
        if (envelope.length() < 16 || envelope.isFlat()) {
            return new Result(List.of(), TempoEstimator.PREFERRED_TEMPO, Confidence.UNKNOWN);
        }

        int windowFrames = (int) Math.round(WINDOW_SECONDS * envelope.frameRate());
        if (envelope.length() <= windowFrames) {
            TempoEstimator.Estimate tempo = TempoEstimator.estimate(envelope, rhythm);
            double rate = MarkedPulse.resolveOctave(tempo.beatsPerMinute(), envelope,
                    pulseRegister, List.of(new int[] {0, envelope.length()}));
            List<Double> beats = trackFixedTempo(envelope, rate, 0, envelope.length());
            return new Result(beats, tempoOf(beats, rate),
                    Confidence.clamped(tempo.strength()));
        }

        // Half-overlapping windows; each contributes only its first half, so
        // every beat comes from a window where it sits away from the edge.
        int step = windowFrames / 2;

        // Every window's seed is estimated before any of them is tracked, so
        // that each can be read against the pulse the recording as a whole
        // settled on. Tracking inside the estimation loop is what made a window
        // an island: the seed is per-window by design -- that is what follows a
        // drifting tempo -- but which subdivision of the beat it names is a
        // property of the recording, and nothing was comparing the two.
        List<int[]> bounds = analysisWindows(envelope);
        List<TempoEstimator.Estimate> seeds = new ArrayList<>();
        List<TempoEstimator.Estimate> voters = new ArrayList<>();
        for (int[] window : bounds) {
            TempoEstimator.Estimate seed =
                    TempoEstimator.estimateWindow(envelope, window[0], window[1], rhythm);
            seeds.add(seed);
            if (votes(window, step)) {
                voters.add(seed);
            }
        }

        // The register is read over the same windows that voted, and for the
        // same reason: a sliver measures a phrase rather than the recording.
        double reference =
                MarkedPulse.resolveOctave(pulseReference(voters), envelope, pulseRegister,
                        votingWindows(envelope));

        List<Double> beats = new ArrayList<>();
        double tempoSum = 0;
        double strengthSum = 0;
        int windows = bounds.size();

        for (int w = 0; w < windows; w++) {
            int start = bounds.get(w)[0];
            int end = bounds.get(w)[1];
            TempoEstimator.Estimate seed = seeds.get(w);
            double beatsPerMinute = divideOutSubdivision(seed.beatsPerMinute(), reference);
            List<Double> windowBeats = trackFixedTempo(envelope, beatsPerMinute, start, end);

            // The gap test below scales with the period actually tracked, not
            // the one the estimator proposed, or a corrected window would go on
            // rejecting beats at the uncorrected spacing.
            double periodSeconds = 60.0 / beatsPerMinute;
            double acceptUntil = (start + step) / envelope.frameRate();
            boolean lastWindow = end >= envelope.length();
            for (double beat : windowBeats) {
                if ((lastWindow || beat < acceptUntil) && isNewBeat(beats, beat, periodSeconds)) {
                    beats.add(beat);
                }
            }
            tempoSum += beatsPerMinute;
            strengthSum += seed.strength();
        }

        double meanStrength = windows > 0 ? strengthSum / windows : 0;
        double fallback = windows > 0 ? tempoSum / windows : TempoEstimator.PREFERRED_TEMPO;
        return new Result(beats, tempoOf(beats, fallback), Confidence.clamped(meanStrength));
    }

    /**
     * The half-overlapping windows one tempo is assumed within, as
     * {@code {fromFrame, toFrame}}. Each contributes only its first half to the
     * beats, so every beat comes from a window where it sits away from the
     * edge.
     */
    static List<int[]> analysisWindows(OnsetEnvelope envelope) {
        int windowFrames = (int) Math.round(WINDOW_SECONDS * envelope.frameRate());
        int step = Math.max(1, windowFrames / 2);
        List<int[]> windows = new ArrayList<>();
        for (int start = 0; start < envelope.length(); start += step) {
            int end = Math.min(envelope.length(), start + windowFrames);
            if (end - start < 16) {
                break;
            }
            windows.add(new int[] {start, end});
        }
        return windows;
    }

    /**
     * The windows that get a say in what the recording's pulse is.
     *
     * <p>The tail window can be a fraction of a second — {@link
     * #analysisWindows} admits sixteen frames, about a tenth of one — and a
     * rate measured over that is not a reading of the recording. Such a window
     * is still tracked, since its beats are wanted. The first window always
     * spans a full one, since a shorter envelope than that never reaches here,
     * so there is always at least one voter.
     */
    static List<int[]> votingWindows(OnsetEnvelope envelope) {
        int step = Math.max(1, (int) Math.round(WINDOW_SECONDS * envelope.frameRate()) / 2);
        List<int[]> windows = new ArrayList<>();
        for (int[] window : analysisWindows(envelope)) {
            if (votes(window, step)) {
                windows.add(window);
            }
        }
        return windows;
    }

    private static boolean votes(int[] window, int step) {
        return window[1] - window[0] >= step;
    }

    /**
     * The tempo actually implied by the tracked beats, as the median interval
     * — reported rather than the seed estimate, which can disagree with the
     * very beats returned alongside it. Since #200 nothing in production
     * reads this median; the one production reader of
     * {@link Result#beatsPerMinute()} is the progress line's
     * fewer-than-two-beats arm, which never reaches it (#240), and retiring
     * the field is #241.
     *
     * <p><strong>The dynamic program does not rescue an octave error in the
     * seed</strong>, and the two directions turn on different things — both
     * have a closed form. A double-rate seed is followed whenever one
     * gap-halving penalty is deeper than the envelope's floor between onsets,
     * however loud the onsets are; a half-rate seed is corrected exactly
     * while an onset is worth more than two penalties, which it is nowhere
     * near at the shipped {@link #TIGHTNESS}. The forms need only that an
     * onset is worth several units on a unit-variance envelope and that the
     * floor is slightly negative — properties of {@link OnsetEnvelope}, not
     * of a fixture.
     * {@code BeatTrackingTest.theDynamicProgramFollowsItsSeedRatherThanFixingIt}
     * pins both reachable outcomes. That is the design, not a hole: resolving
     * the octave is {@link TempoEstimator}'s perceptual prior's job, and
     * seeds an octave out no longer reach the recursion —
     * {@link #divideOutSubdivision} corrects them against the recording's
     * pulse first, which {@link MarkedPulse} may have halved.
     *
     * <p>Median rather than mean so one dropped or doubled beat does not drag
     * the answer.
     */
    private static double tempoOf(List<Double> beats, double fallback) {
        if (beats.size() < 2) {
            return fallback;
        }
        double[] intervals = new double[beats.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = beats.get(i + 1) - beats.get(i);
        }
        java.util.Arrays.sort(intervals);
        int middle = intervals.length / 2;
        double median = intervals.length % 2 == 1
                ? intervals[middle]
                : (intervals[middle - 1] + intervals[middle]) / 2.0;
        return median > 0 ? 60.0 / median : fallback;
    }

    /** Rejects a beat that would land on top of one already accepted. */
    private static boolean isNewBeat(List<Double> beats, double candidate,
                                     double beatPeriodSeconds) {
        if (beats.isEmpty()) {
            return true;
        }
        double last = beats.get(beats.size() - 1);
        return candidate - last > 0.4 * beatPeriodSeconds;
    }

    /**
     * The ratios by which a window's seed may be a subdivision of the
     * recording's pulse rather than a different tempo.
     *
     * <p><b>Not the powers of two, and that is the whole of why this is a list.</b>
     * The obvious correction is an octave fold, and on a corpus of duple
     * material it looks right. It is wrong on the two recordings here that need
     * anything else: {@code cm-blues-68-95.mp3} is in 6/8 and its bad windows
     * read three times the pulse, {@code fm7-vamp-110.mp3} has two that read two
     * thirds of it. Folding by powers of two moves both nearer the pulse and
     * leaves neither on it -- three quarters of it in the first case, four
     * thirds in the second. Nearer is not the point: a rate that is no whole
     * subdivision of the pulse cannot bar the recording, and a window tracked at
     * three quarters of the beat is not tracking anything the music has.
     *
     * <p>So the relations that matter are the ones a beat is actually divided
     * by: halves and quarters, thirds for compound time, and the two-against-
     * three of {@code 3/2}. Anything else is left alone, because a window that
     * is not a subdivision of the recording's pulse is a window at a different
     * tempo, which is what the per-window seed exists to follow.
     */
    private static final double[] SUBDIVISIONS =
            {1.0 / 4, 1.0 / 3, 1.0 / 2, 2.0 / 3, 3.0 / 2, 2.0, 3.0, 4.0};

    /**
     * How far a ratio may sit from a subdivision and still be read as one.
     *
     * <p>Wide enough to cover the estimator's own quantisation -- the seeds this
     * corrects sit within 1% of an exact ratio -- and far narrower than the gap
     * between adjacent entries in {@link #SUBDIVISIONS}, the closest pair being
     * {@code 3/2} and {@code 2}. A genuine tempo change of a few percent is
     * nowhere near any of them and passes through untouched.
     */
    private static final double SUBDIVISION_TOLERANCE = 0.05;

    /**
     * The pulse the recording is read against: the median of the window seeds.
     *
     * <p>Median rather than mean — a mean sits between two subdivisions and
     * is neither — and rather than {@link TempoEstimator#estimate} over the
     * whole envelope, whose one autocorrelation over minutes of shuffle peaks
     * at the half-bar: anchoring on it would correct the whole recording
     * <em>to</em> the rate this exists to correct away from.
     *
     * <p><b>The median is a vote, so it is only right where most windows are,
     * and where they are not this makes things worse rather than leaving them
     * alone</b> — a recording whose windows mostly read a subdivision has its
     * correctly tracked minority pulled onto that subdivision. #305 carries it,
     * with the measurement that weighting the vote by
     * {@link TempoEstimator.Estimate#strength()} does not separate the two
     * populations, and
     * {@code BeatTrackingTest.theReferenceFollowsTheMajorityEvenWhereTheMajorityIsWrong}
     * pins it. Which of two subdivisions is the musical pulse is what
     * {@link TempoEstimator}'s perceptual prior decides, and a consensus over
     * windows cannot second-guess it.
     *
     * <p><b>Always an observed rate, never the average of two.</b> The usual
     * even-length median averages the two middle seeds, and a recording whose
     * windows split between two subdivisions can put one on each side of the
     * middle -- whereupon the average is a rate no window proposed and no
     * subdivision of either, and every seed is then measured against a fiction.
     * No benchmark here does that today; this is the cheaper of two ways to
     * write the same statistic, not a repair of an observed failure.
     */
    private static double pulseReference(List<TempoEstimator.Estimate> seeds) {
        double[] rates = new double[seeds.size()];
        for (int i = 0; i < rates.length; i++) {
            rates[i] = seeds.get(i).beatsPerMinute();
        }
        java.util.Arrays.sort(rates);
        return rates[rates.length / 2];
    }

    /**
     * Divides a subdivision out of a window's seed, where the seed is one of the
     * recording's pulse.
     *
     * <p>This is the correction the dynamic program cannot make for itself.
     * {@link #tempoOf} derives why: at the shipped {@link #TIGHTNESS} a
     * half-rate seed is followed rather than corrected, because correcting it
     * has to pay two spacing penalties to collect two onsets and the penalty is
     * the larger of the two. So a window seeded a subdivision out tracks a
     * subdivision out for its whole length, however plain the onsets underneath
     * it are. The seed is the only place it can be fixed.
     *
     * <p>The seed is divided by the ratio rather than replaced by the reference,
     * so that a window keeps its own reading of the tempo where the recording
     * drifts -- which is what the per-window seed is for. The two differ by
     * whatever that window has drifted.
     *
     * <p>Left alone if the correction would leave {@link TempoEstimator}'s own
     * range, since a rate outside it is one no window could have been seeded
     * with.
     *
     * <p>Package-private so the ratio table can be asserted directly. Reaching
     * it through {@link #track} would need a fixture per entry, and the entry
     * that matters most is the one no duple fixture has: the compound-time 3.
     */
    static double divideOutSubdivision(double beatsPerMinute, double reference) {
        if (!(beatsPerMinute > 0) || !(reference > 0)) {
            return beatsPerMinute;
        }
        double observed = beatsPerMinute / reference;
        double bestRatio = 1.0;
        double bestDistance = SUBDIVISION_TOLERANCE;
        for (double ratio : SUBDIVISIONS) {
            // Compared as a relative distance so that 1/3 and 3 are held to the
            // same standard; an absolute one would be three times slacker on the
            // multiples than on the divisors.
            double distance = Math.abs(observed - ratio) / ratio;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestRatio = ratio;
            }
        }
        double corrected = beatsPerMinute / bestRatio;
        return corrected >= TempoEstimator.MIN_TEMPO && corrected <= TempoEstimator.MAX_TEMPO
                ? corrected
                : beatsPerMinute;
    }

    /**
     * The dynamic program itself, over one window at one tempo.
     *
     * @return beat times in seconds, in order
     */
    static List<Double> trackFixedTempo(OnsetEnvelope envelope, double beatsPerMinute,
                                        int fromFrame, int toFrame) {
        double frameRate = envelope.frameRate();
        double periodFrames = frameRate * 60.0 / beatsPerMinute;
        if (!(periodFrames >= 1)) {
            return List.of();
        }

        int length = toFrame - fromFrame;
        double[] strength = envelope.strength();

        double[] score = new double[length];
        int[] previous = new int[length];
        java.util.Arrays.fill(previous, -1);

        // Only predecessors within roughly half to double the period can be the
        // previous beat, which bounds the inner loop and keeps this O(n * window)
        // rather than O(n^2).
        int minGap = Math.max(1, (int) Math.floor(periodFrames * 0.5));
        int maxGap = (int) Math.ceil(periodFrames * 2.0);

        for (int n = 0; n < length; n++) {
            double best = Double.NEGATIVE_INFINITY;
            int bestPrevious = -1;

            int earliest = Math.max(0, n - maxGap);
            int latest = n - minGap;
            for (int m = earliest; m <= latest; m++) {
                double gap = n - m;
                double deviation = Math.log(gap / periodFrames);
                double candidate = score[m] - TIGHTNESS * deviation * deviation;
                if (candidate > best) {
                    best = candidate;
                    bestPrevious = m;
                }
            }
            if (bestPrevious < 0) {
                // No valid predecessor: this frame can only start a sequence.
                best = 0;
            }
            score[n] = best + strength[fromFrame + n];
            previous[n] = bestPrevious;
        }

        int end = 0;
        for (int n = 1; n < length; n++) {
            if (score[n] > score[end]) {
                end = n;
            }
        }

        // Backtrack, then reverse: the chain is discovered from the end.
        List<Double> reversed = new ArrayList<>();
        for (int n = end; n >= 0; n = previous[n]) {
            reversed.add((fromFrame + n) / frameRate);
            if (previous[n] < 0) {
                break;
            }
        }
        List<Double> beats = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            beats.add(reversed.get(i));
        }
        return beats;
    }

    /**
     * Assigns bar positions to tracked beats, phasing the bars from onset
     * energy alone.
     *
     * <p>Kept for callers that have no chroma to hand, and weak for the reasons
     * {@link DownbeatEstimator} sets out: on a click track every beat carries the
     * same accent, and on real music a backbeat can carry more energy than the
     * downbeat. Prefer {@link #toBeatGrid(Result, DownbeatEstimator.Estimate)}
     * with a chroma-based estimate.
     *
     * <p>The meter is not inferred here or anywhere else — 4/4 is assumed, since
     * it covers the large majority of the material this tool targets and
     * guessing wrong is far more damaging than not guessing.
     */
    public static BeatGrid toBeatGrid(Result result, OnsetEnvelope envelope, int beatsPerBar) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(envelope, "envelope");
        requireBeats(result);
        return toBeatGrid(result,
                DownbeatEstimator.fromOnsets(result.beatTimes(), envelope, beatsPerBar));
    }

    /**
     * Assigns bar positions to tracked beats using a downbeat phase estimated
     * elsewhere.
     *
     * <p>Separating the two is what lets the phase be chosen from harmony, which
     * is a far better signal than onset energy but only exists after chroma has
     * been extracted — and chroma is extracted over these very beats. Keeping
     * the phase an input rather than something this method computes is what
     * keeps that ordering acyclic.
     */
    public static BeatGrid toBeatGrid(Result result, DownbeatEstimator.Estimate downbeat) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(downbeat, "downbeat");
        requireBeats(result);

        List<Double> times = result.beatTimes();
        int beatsPerBar = downbeat.beatsPerBar();
        List<BeatGrid.Beat> beats = new ArrayList<>(times.size());
        for (int i = 0; i < times.size(); i++) {
            int position = Math.floorMod(i - downbeat.phase(), beatsPerBar);
            beats.add(new BeatGrid.Beat(times.get(i), position == 0, position));
        }
        // Two independent doubts multiply: a phase is only as good as the beats
        // it phases, so a confident phase over shaky beats is still shaky. This
        // is also what keeps the downbeat claim from ever reading as stronger
        // than the beat claim it rests on.
        return new BeatGrid(beats, result.confidence(),
                Confidence.clamped(result.confidence().value() * downbeat.confidence().value()));
    }

    private static void requireBeats(Result result) {
        if (result.isEmpty()) {
            throw new IllegalArgumentException("cannot build a beat grid with no beats");
        }
    }
}
