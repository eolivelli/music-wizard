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
     * Weight of the spacing penalty against onset strength.
     *
     * <p>Higher makes the tracker insist on even spacing and ignore evidence;
     * lower lets it chase every syncopation. This is the reference
     * implementations' value for the penalty as Ellis writes it, in natural
     * logs.
     *
     * <p><strong>It has to be read together with the base of the logarithm,
     * and that is how it was wrong.</strong> The penalty here used to be
     * {@code -(log2(gap / period))^2} weighted at 1, quoting a figure of
     * "around 1" that belongs to the natural-log form. The two differ by
     * {@code 1 / (ln 2)^2}, so the shipped penalty was <b>one forty-eighth</b>
     * of the published one — not a loose setting of the algorithm but a
     * different algorithm, which is why no constant downstream could
     * compensate.
     *
     * <p>What it cost is worth stating in the units the recursion works in.
     * The envelope is normalised to unit variance and a loud attack reads
     * several, while inserting one extra beat — two gaps of half a period
     * where there was one — cost {@code 2 * log2(1/2)^2 = 2} at the old
     * weight. So an offbeat worth two standard deviations bought its own beat,
     * and on a shuffle the swung eighth is exactly that. The tracker left the
     * grid for it and came back a beat later, and the detours that failed to
     * pair up accumulated into the 1.9% rate error of #196 — a whole extra
     * beat per twelve-bar cycle.
     *
     * <p>Both halves of that are pinned rather than described.
     * {@code BeatTrackingTest.aLouderOffbeatDoesNotBuyItselfABeat} is the
     * mechanism on a synthetic shuffle, and {@code BluesLoopIT} is the
     * consequence on the real recording: the share of intervals that are two
     * thirds of a beat rather than a beat, and the tracked rate against the
     * loop's own. Both carry their before-and-after figures.
     *
     * <p>The one thing not to read into the choice of value: it is the
     * published one and it was not tuned. A sweep over the five benchmarks of
     * {@code tools/score-samples.py} says the failure is on the low side and
     * that this is not a cliff edge, and it says no more than that — every one
     * of those recordings is a programmed loop with rigid timing, so a sweep
     * on them rewards rigidity without bound and cannot choose a value. Two of
     * the five have their best point a little below this one and two a little
     * above, by margins far smaller than the distance from the old weight.
     * Somewhere above it the tracker must stop following a human rubato, and
     * nothing here measures where.
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
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.length() < 16 || envelope.isFlat()) {
            return new Result(List.of(), TempoEstimator.PREFERRED_TEMPO, Confidence.UNKNOWN);
        }

        int windowFrames = (int) Math.round(WINDOW_SECONDS * envelope.frameRate());
        if (envelope.length() <= windowFrames) {
            TempoEstimator.Estimate tempo = TempoEstimator.estimate(envelope);
            List<Double> beats = trackFixedTempo(envelope, tempo.beatsPerMinute(), 0, envelope.length());
            return new Result(beats, tempoOf(beats, tempo.beatsPerMinute()),
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
        List<int[]> bounds = new ArrayList<>();
        List<TempoEstimator.Estimate> seeds = new ArrayList<>();
        List<TempoEstimator.Estimate> voters = new ArrayList<>();
        for (int start = 0; start < envelope.length(); start += step) {
            int end = Math.min(envelope.length(), start + windowFrames);
            if (end - start < 16) {
                break;
            }
            TempoEstimator.Estimate seed = TempoEstimator.estimateWindow(envelope, start, end);
            bounds.add(new int[] {start, end});
            seeds.add(seed);
            // The tail window can be a fraction of a second -- the guard above
            // admits sixteen frames, about a tenth of one -- and a rate measured
            // over that is not a reading of the recording. Such a window is
            // still tracked, since its beats are wanted, but it does not get a
            // vote on what the rest of the recording's pulse is. The first
            // window always spans a full one, since a shorter envelope than that
            // returned above, so there is always at least one voter.
            if (end - start >= step) {
                voters.add(seed);
            }
        }

        double reference = pulseReference(voters);

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
     * The tempo actually implied by the tracked beats, as the median interval.
     *
     * <p>Reported rather than the seed estimate, because the two can disagree:
     * the seed is one number for a window and this is what the beats in it
     * actually did. Reporting the seed would contradict the very beats returned
     * alongside it.
     *
     * <p><strong>Since #200 nothing in production reads the median this
     * computes, and the paragraph above used to claim every stage downstream
     * did.</strong> {@code Score.estimatedTempo()} answers from
     * {@link dev.olivelli.musicwizard.core.model.BeatGrid#steadyPulseRate()}
     * instead, and {@code AudioTranscriber}'s progress line followed it there.
     * The one remaining reader of {@link Result#beatsPerMinute()} <em>in
     * production</em> is that progress line's fewer-than-two-beats arm -- where
     * this method returns the {@code fallback} seed and never reaches the median
     * at all, which is precisely the case the paragraph above says must not
     * happen. #240 carries that.
     *
     * <p>The median itself is still pinned by one test, and it is the only thing
     * pinning it: {@code BeatTrackingTest.tracksEvenlySpacedBeats} asserts this
     * figure within 2% of the click rate at five tempi. So retiring the field is
     * a call site plus that test, not a call site alone, and it is a change to
     * this module's public shape either way -- #241, not here.
     *
     * <p><strong>What this no longer means is that the dynamic program will
     * rescue an octave error in the seed.</strong> An earlier version of this
     * comment said it would, on the grounds that onset strength outvotes a
     * mistaken period once the spacing penalty allows the correct gap. That was
     * true only because the penalty was a forty-eighth of the published weight,
     * and it was true in only one of the two directions even then: measured on
     * clean click tracks at 90, 120 and 160 BPM, a seed at half the true rate
     * was rescued and a seed at double it was not.
     *
     * <p>The two are not mirror images, although the penalties are — halving and
     * doubling a gap both cost {@code (ln 2)^2} times the weight. What differs is
     * what the correction buys. Correcting a half-rate seed <em>adds</em> beats,
     * and each one collects an onset the seed was stepping over; correcting a
     * double-rate seed <em>removes</em> beats that were sitting between onsets
     * and were nearly free to keep.
     *
     * <p>Both cases have a closed form, which is better than a measurement of
     * one. Write {@code A} for the strength a grid at the true period collects
     * per beat, {@code F} for the envelope's floor between onsets, and {@code p}
     * for what one halving or doubling of a gap costs — 1 at the old weight,
     * {@code 100 * (ln 2)^2} at this one. Then the margin by which the tracker
     * prefers to <em>follow</em> its seed rather than correct it is as below,
     * each row measured over the span on which its two grids realign — one true
     * beat in the first, two in the second, so the rows are not comparable with
     * each other in magnitude, only in sign:
     *
     * <pre>
     *   double-rate seed    p + F     following alternates onset and floor,
     *                                 so A cancels out of it entirely
     *   half-rate seed      2p - A    following collects one onset where
     *                                 correcting collects two and pays two
     *                                 penalties for them
     * </pre>
     *
     * <p>So the two are not merely asymmetric; they turn on different things.
     * <b>A double-rate seed is followed whenever {@code p} is deeper than the
     * floor</b>, and how loud the onsets are never enters it. That is not
     * "always": the form says the tracker should start correcting just below
     * {@code p = -F}, and it does. On a 120 BPM click track that fixture's
     * {@code F} of −0.221 puts the crossing at a {@link #TIGHTNESS} of
     * 0.4596, and the dynamic program was swept either side of it and does
     * change there. The sweep's step is far coarser than the margin, so read it
     * as the form predicting where the behaviour turns and not as a bound on
     * where. What matters here is that the condition holds at every weight
     * this tracker has had, and by a wide margin at both, so <b>raising the
     * weight cannot have taken that rescue away — there was never one to
     * take</b>. <b>A half-rate seed is corrected exactly while an onset is worth
     * more than two penalties</b>, which it comfortably is at the old weight and
     * is nowhere near at this one. That is the whole of the asymmetry, and it is
     * arithmetic rather than a property of the search window.
     *
     * <p><strong>The measured value of {@code A} is deliberately not quoted
     * here, and that is the fourth answer to this question rather than the
     * first.</strong> ({@code F} is quoted above, where the closed form is
     * checked against the tracker, because it is the envelope's mean over its
     * standard deviation rather than a peak that has to be found. It is not a
     * constant of the code: #306 rescaled the envelope and moved it, which is
     * exactly why the closed forms are written to need only its sign.) Four review passes went on
     * correcting figures in this paragraph — an onset of 5.8, then 7.06, then
     * 7.23, each a better measurement of a quantity nothing asserts, the first
     * two of them a phase swept too coarsely. Then the trouble moved to the
     * margins, where independent measurements of {@code p + F} landed at 0.68,
     * 0.78 and 0.89: not disagreement, but the same quantity taken over grids
     * ending with one more onset than floor, or one fewer, which shifts it by
     * one beat's worth of the difference between them. No summary of that
     * survived a reading, and a claim made along the way that the earlier error
     * had left every margin understated was true of exactly one of the four
     * margins in view — the half-rate one at the old weight — and false of the
     * other three.
     *
     * <p>The closed forms need none of it. What they need is that an onset is
     * worth several units on an envelope normalised to unit variance and that
     * the floor between onsets is slightly negative, and both are properties of
     * {@link OnsetEnvelope} rather than of a fixture.
     *
     * <p>What is pinned is the two outcomes still reachable — both directions at
     * the shipped weight — in
     * {@code BeatTrackingTest.theDynamicProgramFollowsItsSeedRatherThanFixingIt}.
     * The old weight's two are history and no test in the tree can hold them.
     * See also {@link #TIGHTNESS}.
     *
     * <p>That is the algorithm working as designed rather than a hole opened by
     * fixing it — resolving the octave is what {@link TempoEstimator}'s
     * perceptual prior is for, and a tracker that quietly disagrees with the
     * period it was given is a tracker whose reported tempo means nothing. But
     * it does move where an octave error becomes visible, so it is worth saying
     * here rather than only where the weight is set.
     *
     * <p><strong>Seeds an octave out still occur; they no longer reach the
     * recursion.</strong> {@link #track} reads every window's seed against the
     * pulse the rest of the recording agrees on and divides out the subdivision
     * before tracking — see {@link #divideOutSubdivision} — so the limitation
     * above is one the tracker is no longer asked to work around. A window whose
     * seed disagrees with the recording by something that is <em>not</em> a
     * subdivision is left alone and still lands where the seed points, which is
     * {@code bossa-cm.mp3}: its windows read three eighths of that recording's
     * bar, which is #231 and is not an octave error at all. That is one and a
     * half quarter notes, since the bar holds four.
     *
     * <p>Median rather than mean so that one dropped or doubled beat does not
     * drag the answer.
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
     * <p>Median rather than mean, and rather than
     * {@link TempoEstimator#estimate} over the whole envelope, because both of
     * those answer this recording's question wrongly. A mean sits between two
     * subdivisions and is neither. The whole-envelope estimate is not a summary
     * of the windows at all -- on {@code g-blues-shuffle-cc.mp3} it reads 52.5
     * where 25 of the 26 windows read about 105, since one autocorrelation over
     * five minutes of shuffle has its strongest peak at the half-bar. Anchoring
     * on it would correct the whole recording <em>to</em> the rate this exists
     * to correct away from.
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
