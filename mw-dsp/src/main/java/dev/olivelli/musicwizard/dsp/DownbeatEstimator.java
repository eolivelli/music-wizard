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

import dev.olivelli.musicwizard.core.model.Confidence;
import java.util.List;
import java.util.Objects;

/**
 * Picks which of the tracked beats begin bars. The beats say where the pulse
 * is, not where the bar starts, and that phase is chosen from harmonic change
 * — chords change preferentially at bar lines — measured as cosine distance
 * between the beat-synchronous chroma either side of each beat, with no chord
 * labels involved (scoring against decoded chords would make this depend on a
 * stage that depends on the beats).
 *
 * <p>Onset energy is kept as a bounded second term: it moves a phase's score
 * by at most {@link #ONSET_WEIGHT} either way, so where harmony distinguishes
 * the phases no accent can overturn it, and where it does not the accent
 * decides. <b>The accent is asked nothing about confidence</b> — evidence
 * that picks an answer cannot also certify it, and this envelope's loudest
 * phase on ordinary drum material is the backbeat (#70), so an accent-chosen
 * phase reports the floor. Confidence multiplies three separate questions of
 * the harmony — did it decide, does it prefer, was there enough — so any one
 * failing brings the number down; rely on its ordering, not its value, since
 * the constants were calibrated on synthetic fixtures (#45).
 *
 * <p>The known limit: this measures agreement with harmonic change, not with
 * bar lines. A style that consistently anticipates the chord moves the
 * harmony a beat early and this agrees with the anticipation, unanimously —
 * nothing in the chroma separates the two readings (#48,
 * {@link #HARMONIC_PHASE_CEILING}); the bass evidence that would is #42. The
 * bar length is an input here and is read by {@link MeterEstimator}, off the
 * same novelty this scores phases with.
 */
public final class DownbeatEstimator {

    /**
     * The bound on what the onset term can add to or take from a phase's score.
     *
     * <p>A bound rather than a scaling, which is what makes the balance between
     * the two terms something that can be stated rather than tuned: the onset
     * term reaches this much either way and no further, so a phase whose mean
     * harmonic novelty leads by {@code 2 * ONSET_WEIGHT} or more wins whatever
     * the onsets say. Below that margin the harmony is not distinguishing the
     * phases and the accent is allowed to decide.
     *
     * <p>Inclusive at the boundary only because ties are broken on harmony:
     * {@code tanh} does reach exactly 1 in double arithmetic, so at a lead of
     * exactly {@code 2 * ONSET_WEIGHT} the two scores can be equal, and without
     * that tie rule the accent would take a decision harmony had already made.
     */
    private static final double ONSET_WEIGHT = 0.05;

    /**
     * The onset advantage, in standard deviations, at which the term is about
     * three quarters of the way to its bound.
     *
     * <p>Standard deviations because that is the unit {@link OnsetEnvelope}
     * reports in: it is normalised to zero mean and unit variance, so the
     * difference between one phase's mean strength and the average beat's needs
     * no dividing to be interpretable. Dividing would be unsafe here in any case
     * — a mean-zero envelope makes the divisor's sign arbitrary and its
     * magnitude unbounded.
     *
     * <p>A soft knee rather than a cutoff, because the values being measured are
     * strengths sampled <em>at beats</em>, which are peaks rather than a random
     * sample of frames, and their per-phase spread runs from about 0.02 to 1.9
     * deviations across the fixtures here. A hard clamp anywhere in that range
     * would flatten two genuinely different accents onto the same score and
     * hand the tie to whichever phase happens to come first; {@code tanh} is
     * strictly monotone, so it bounds the term without ever losing the ordering.
     */
    private static final double ONSET_FULL_SCALE = 1.0;

    /**
     * The harmonic margin over the runner-up above which the answer was decided
     * by harmony alone.
     *
     * <p>Not chosen: it is the margin at and above which the onset term provably
     * cannot change the winner, since that term is bounded by
     * {@code ±ONSET_WEIGHT} and ties go to harmony.
     *
     * <p>This says <em>who</em> decided, not <em>how well</em>. It is used as one
     * factor of the confidence and not as the whole of it, because a large margin
     * is easy to come by on material that changes chord often at no consistent
     * phase — the margin is then real and the phase is still a guess.
     */
    private static final double CONFIDENT_MARGIN = 2 * ONSET_WEIGHT;

    /**
     * Chord changes on one phase that count as full harmonic evidence.
     *
     * <p>Three, from the odds of the alternative. If {@code k} changes all land
     * on the same phase and phase meant nothing, that happens with probability
     * {@code (1 / beatsPerBar)^(k-1)} — for 4/4, one in four at two changes and
     * one in sixteen at three. One change lands somewhere by necessity and is no
     * evidence at all, two are an ordinary coincidence, and by three the
     * coincidence is unlikely enough to stop calling it one.
     *
     * <p>Counted in changes rather than in bars because length is not evidence:
     * a minute of one chord says no more about where the bars start than a bar
     * of it does.
     */
    private static final double CHANGES_FOR_FULL_CONFIDENCE = 3;

    /**
     * Confidence in a phase nothing at all supports.
     *
     * <p>Not zero: one phase in four is right a quarter of the time by chance,
     * and the bars have to start somewhere. Low enough to read as "correct this
     * by hand if it matters", which for a coin-flip phase it is.
     */
    private static final double BASE_CONFIDENCE = 0.35;

    /**
     * The most any phase {@link #estimate} chooses may report, whatever backs
     * it — the reliability of the bar-line assumption, not the strength of the
     * evidence for it: a pushed chord agrees with the anticipation unanimously
     * and the chroma cannot tell the two readings apart (#48,
     * {@code anAnticipationLooksExactlyLikeAMidBarStart}). Set by counting
     * what unanimous agreement leaves open — the harmony beat or the one
     * after it, a one-in-two choice, stated for the common meter and applied
     * flat like {@link #BASE_CONFIDENCE} (#12 for calibration). A property of
     * the arithmetic rather than a clamp: exactly {@code BASE_CONFIDENCE}
     * plus what unanimous harmony is worth. Deliberately in the band that
     * reads "probably, but check it" — a number that says not to bother is
     * what stops the highest-value hand correction.
     */
    private static final double HARMONIC_PHASE_CEILING = 0.6;

    /**
     * What harmony agreeing with the chosen phase is worth on top of the floor.
     *
     * <p>Derived rather than chosen, so that the ceiling stays the thing that is
     * stated and this stays the arithmetic that reaches it.
     */
    private static final double HARMONIC_CONFIDENCE = HARMONIC_PHASE_CEILING - BASE_CONFIDENCE;

    private DownbeatEstimator() {
    }

    /**
     * Which beat starts a bar, and how much to trust that.
     *
     * <p>Carries the meter it was estimated against, so that a phase computed
     * for one bar length cannot be applied to another.
     *
     * @param phase       index of the first beat of a bar, within {@code [0, beatsPerBar)}
     * @param beatsPerBar the assumed bar length in beats
     * @param confidence  trust in the phase alone, not in the beats it phases
     */
    public record Estimate(int phase, int beatsPerBar, Confidence confidence) {
        public Estimate {
            Objects.requireNonNull(confidence, "confidence");
            requireBeatsPerBar(beatsPerBar);
            if (phase < 0 || phase >= beatsPerBar) {
                throw new IllegalArgumentException(
                        "phase must be within [0, " + beatsPerBar + "), got: " + phase);
            }
        }
    }

    /**
     * Estimates the downbeat phase from harmonic change, with onset energy as a
     * tie-breaker.
     *
     * <p>A one-beat bar is answered without looking at anything, so the chroma
     * is not checked at that meter. The check exists to stop a chroma that does
     * not line up with these beats from choosing an arbitrary phase, and at one
     * beat to the bar there is no phase to choose.
     *
     * @param beatTimes   the tracked beats, in seconds and ascending
     * @param chroma      beat-synchronous chroma over exactly those beats, so that
     *                    {@code chroma.frameCount() == beatTimes.size() - 1};
     *                    required at every bar length but one, and not required
     *                    of a chroma with no frames at all — that means "no
     *                    harmony was heard", which a recording shorter than one
     *                    analysis window genuinely produces, and it takes the
     *                    same onset fallback as too few beats rather than being
     *                    rejected
     * @param envelope    the onset envelope the beats were tracked from
     * @param beatsPerBar the bar length to phase against; read by
     *                    {@link MeterEstimator}, not by this
     */
    public static Estimate estimate(List<Double> beatTimes, Chroma chroma,
                                    OnsetEnvelope envelope, int beatsPerBar) {
        Objects.requireNonNull(beatTimes, "beatTimes");
        Objects.requireNonNull(chroma, "chroma");
        Objects.requireNonNull(envelope, "envelope");
        requireBeatsPerBar(beatsPerBar);
        requireBeats(beatTimes);
        if (beatsPerBar == 1) {
            return everyBeatIsADownbeat();
        }

        // Novelty is only defined where a beat has a chroma span on both sides,
        // so the first and last beats are out of scope. Both terms are then
        // scored over that same set of beats, so neither is measured over
        // indices the other could not see.
        int firstBeat = 1;
        int lastBeat = beatTimes.size() - 2;
        if (lastBeat < firstBeat) {
            // Too few beats for any beat to have harmony on both sides. Checked
            // before the chroma is validated, because Chroma.beatSynchronous
            // cannot produce a beat-synchronous chroma from fewer than two beats
            // and would otherwise make a one-beat recording throw rather than
            // fall back.
            return fromOnsets(beatTimes, envelope, beatsPerBar);
        }
        if (chroma.frameCount() == 0) {
            // A recording shorter than one analysis window yields no frames
            // while the tracker still finds pulses; "we could not hear any
            // harmony" is a fact about the recording, not an error in the
            // argument. Narrower than tolerating any mismatch — a chroma with
            // the wrong number of frames is a caller error and still throws.
            return fromOnsets(beatTimes, envelope, beatsPerBar);
        }
        // A chroma that does not line up with these beats would score the wrong
        // spans against the wrong beats and land on a plausible-looking but
        // arbitrary phase — which is the failure this class exists to remove —
        // so it is rejected rather than tolerated.
        if (!chroma.isBeatSynchronous() || chroma.frameCount() != beatTimes.size() - 1) {
            throw new IllegalArgumentException(
                    "chroma must be beat-synchronous over these beats: expected "
                            + (beatTimes.size() - 1) + " inter-beat spans, got "
                            + chroma.frameCount()
                            + (chroma.isBeatSynchronous() ? "" : " on a fixed time grid"));
        }

        double[] novelty = harmonicNovelty(chroma);
        // Zero for a phase with no beats in range, because no observed novelty is
        // exactly what it says: nothing changed there that we saw.
        double[] harmony = meanPerPhase(novelty, firstBeat, lastBeat, beatsPerBar, 0);
        double[] accent = onsetAdvantage(
                onsetStrengthPerBeat(beatTimes, envelope), firstBeat, lastBeat, beatsPerBar);

        double[] score = new double[beatsPerBar];
        for (int phase = 0; phase < beatsPerBar; phase++) {
            score[phase] = harmony[phase] + onsetTerm(accent[phase]);
        }

        // Harmony breaks a tie rather than the phase index. tanh reaches exactly
        // 1 in double arithmetic, so the onset term does attain its bound and two
        // phases whose harmony differs by exactly 2 * ONSET_WEIGHT can score
        // equal — handing that to whichever came first would let an accent take a
        // decision the harmony had already made.
        int phase = argMax(score, harmony);
        // The accent chose the phase, above, and is not asked to vouch for the
        // phase it chose. See "The accent is asked nothing" on the class.
        double confidence = BASE_CONFIDENCE
                + HARMONIC_CONFIDENCE
                        * harmonicAgreement(harmony, novelty, phase, firstBeat, lastBeat);
        return new Estimate(phase, beatsPerBar, Confidence.clamped(confidence));
    }

    /**
     * The answer for a one-beat bar, which is not an estimate at all: phase 0
     * is the only phase there is. Answered before any evidence is gathered —
     * deriving "nothing to choose" from the evidence would make the answer
     * depend on it — and {@link Confidence#CERTAIN}, because reporting a claim
     * that cannot be wrong below one that can would invert the one thing
     * these numbers guarantee.
     */
    private static Estimate everyBeatIsADownbeat() {
        return new Estimate(0, 1, Confidence.CERTAIN);
    }

    /**
     * How far the harmony backs the chosen phase, in {@code [0, 1]}.
     *
     * <p>Three things all have to hold before harmonic evidence is worth much,
     * and each of them fails on its own in a way the others do not catch:
     *
     * <ul>
     * <li><b>Harmony decided it.</b> The margin over the runner-up has to clear
     *     what an accent could have moved, or the phase was chosen by the onset
     *     term and cannot borrow the authority of evidence that did not pick it.
     *     Negative when the accent overrode the harmony, which is no credit.</li>
     * <li><b>Harmony prefers it.</b> The winning phase has to carry a share of
     *     all the harmonic change that beats what it would get by chance. Without
     *     this, material that changes chord often at no consistent phase produces
     *     a large margin by luck and reports full confidence in a guess — the
     *     margin is real, the phase is not.</li>
     * <li><b>There was enough of it.</b> Counted in chord changes on the winning
     *     phase, not in beats: a long recording holding one chord is no more
     *     evidence than a short one, and a single change lands on some phase by
     *     necessity rather than by preference.</li>
     * </ul>
     *
     * <p>Multiplied rather than averaged, so that any one of them failing takes
     * the confidence down rather than being outvoted by the other two.
     */
    private static double harmonicAgreement(double[] harmony, double[] novelty, int phase,
                                            int firstBeat, int lastBeat) {
        int beatsPerBar = harmony.length;
        double margin = harmony[phase] - runnerUp(harmony, phase);
        double decided = Math.clamp(margin / CONFIDENT_MARGIN, 0, 1);

        double total = 0;
        for (double value : harmony) {
            total += value;
        }
        // Uniform novelty gives every phase 1/beatsPerBar, which is the share a
        // phase carries by chance and therefore worth nothing.
        double chance = 1.0 / beatsPerBar;
        double share = total > 0 ? harmony[phase] / total : chance;
        // The clamp is belt and braces rather than load-bearing: neither bound
        // can bind, given that novelty is floored at zero. A share above one
        // would need a negative novelty somewhere, and a share below chance
        // means this phase is not the harmonic maximum -- which makes the margin
        // negative and `decided` zero, so the product is zero either way.
        double preferred = Math.clamp((share - chance) / (1 - chance), 0, 1);

        double observed = Math.clamp(
                changesOn(novelty, phase, beatsPerBar, firstBeat, lastBeat)
                        / CHANGES_FOR_FULL_CONFIDENCE, 0, 1);
        return decided * preferred * observed;
    }

    /**
     * How many chord changes a phase actually carries.
     *
     * <p>The effective count {@code (sum)^2 / sum of squares}, which is one when
     * a single beat carries all of the phase's novelty and {@code k} when {@code
     * k} beats carry it equally. Counting beats over a threshold would need a
     * threshold, and every recording sits at a different novelty floor; this
     * needs none and degrades smoothly between the two cases.
     */
    private static double changesOn(double[] novelty, int phase, int beatsPerBar,
                                    int firstBeat, int lastBeat) {
        double sum = 0;
        double sumOfSquares = 0;
        for (int beat = firstBeat; beat <= lastBeat; beat++) {
            if (Math.floorMod(beat, beatsPerBar) == phase) {
                sum += novelty[beat];
                sumOfSquares += novelty[beat] * novelty[beat];
            }
        }
        return sumOfSquares > 0 ? sum * sum / sumOfSquares : 0;
    }

    /**
     * The onset term's contribution to a score, bounded by {@link #ONSET_WEIGHT}.
     *
     * <p>{@code tanh} rather than a clamp, so that the bound never costs the term
     * its ordering: two accents that both exceed a hard cutoff would score
     * identically and the tie would go to whichever phase came first, which on a
     * signal with a genuine accent is a wrong answer arrived at by arithmetic
     * rather than by evidence.
     */
    private static double onsetTerm(double advantage) {
        return ONSET_WEIGHT * Math.tanh(advantage / ONSET_FULL_SCALE);
    }

    /**
     * Estimates the downbeat phase from onset energy alone.
     *
     * <p>For callers with no chroma to hand. This is the weak heuristic
     * described above, and every phase it estimates reports
     * {@link #BASE_CONFIDENCE} — the floor — however pronounced the accent,
     * because a pronounced accent on the wrong beat is exactly how this
     * heuristic fails and the loudest phase this envelope reports is the
     * backbeat (#70). It used to scale with the accent up to {@code 0.45}, which
     * said a loud guess is a better guess than a faint one.
     *
     * <p>A one-beat bar is the exception and is not an estimate: it answers
     * {@link Confidence#CERTAIN}, above everything here, because there is no
     * phase to be wrong about. See {@code everyBeatIsADownbeat}.
     *
     * <p>Prefer {@link #estimate} wherever chroma is available.
     */
    public static Estimate fromOnsets(List<Double> beatTimes, OnsetEnvelope envelope,
                                      int beatsPerBar) {
        Objects.requireNonNull(beatTimes, "beatTimes");
        Objects.requireNonNull(envelope, "envelope");
        requireBeatsPerBar(beatsPerBar);
        requireBeats(beatTimes);
        if (beatsPerBar == 1) {
            return everyBeatIsADownbeat();
        }

        double[] accent = onsetAdvantage(onsetStrengthPerBeat(beatTimes, envelope),
                0, beatTimes.size() - 1, beatsPerBar);
        // The floor, whatever the accent said, because how loud the loudest phase
        // was is not evidence that it is the bar line -- this envelope's loudest
        // phase is the backbeat on ordinary drum material (#70). Scaling the
        // number with the accent said a pronounced accent is a better guess than
        // a faint one, and it is the same guess.
        return new Estimate(argMax(accent), beatsPerBar, Confidence.clamped(BASE_CONFIDENCE));
    }

    /**
     * How much the harmony changes at each beat, as cosine distance between the
     * beat-synchronous chroma spans either side of it.
     *
     * <p>Cosine rather than a plain difference, so that a loud bar and a quiet
     * one holding the same chord read as no change at all.
     *
     * @return one value per beat; the first and last stay at zero, having a span
     *     on one side only
     */
    static double[] harmonicNovelty(Chroma chroma) {
        double[][] spans = chroma.vectors();
        double[] novelty = new double[spans.length + 1];
        for (int beat = 1; beat < spans.length; beat++) {
            // An empty span is silence, not a chord change: cosine is
            // undefined against a zero vector, and reading that as maximum
            // novelty would make silence the most persuasive evidence in the
            // recording. Floored at zero here, the one point where the sign
            // is decided, because downstream arithmetic assumes non-negative
            // novelty in three places.
            double cosine = cosine(spans[beat - 1], spans[beat]);
            novelty[beat] = Double.isNaN(cosine) ? 0 : Math.max(0, 1 - cosine);
        }
        return novelty;
    }

    /** Onset strength sampled at each beat. */
    private static double[] onsetStrengthPerBeat(List<Double> beatTimes, OnsetEnvelope envelope) {
        double[] out = new double[beatTimes.size()];
        if (envelope.length() == 0) {
            // frameOf clamps to frame 0, which does not exist here. No onset
            // evidence at all rather than an index out of bounds.
            return out;
        }
        for (int beat = 0; beat < out.length; beat++) {
            out[beat] = envelope.strength()[envelope.frameOf(beatTimes.get(beat))];
        }
        return out;
    }

    /**
     * Averages a per-beat quantity over the beats belonging to each phase.
     *
     * <p>Mean rather than sum: the phases do not in general hold the same number
     * of beats, and a sum would quietly reward whichever one happens to have an
     * extra.
     *
     * <p>A phase with no beats in range — possible only on a recording shorter
     * than two bars — takes {@code unobserved}, because what counts as "nothing
     * was seen here" differs between the two quantities. For novelty it is zero:
     * no change was observed. For onset strength it is the overall mean, since
     * the envelope is centred on zero and a zero there is an ordinary frame
     * rather than an absence. Using the wrong one gives an unobserved phase
     * either an edge or a handicap it did nothing to earn.
     */
    private static double[] meanPerPhase(double[] perBeat, int firstBeat, int lastBeat,
                                         int beatsPerBar, double unobserved) {
        double[] totals = new double[beatsPerBar];
        int[] counts = new int[beatsPerBar];
        for (int beat = firstBeat; beat <= lastBeat; beat++) {
            int phase = Math.floorMod(beat, beatsPerBar);
            totals[phase] += perBeat[beat];
            counts[phase]++;
        }
        for (int phase = 0; phase < beatsPerBar; phase++) {
            totals[phase] = counts[phase] > 0 ? totals[phase] / counts[phase] : unobserved;
        }
        return totals;
    }

    /**
     * How far each phase's onsets stand out from the average beat, in units of a
     * full accent.
     *
     * <p>A difference rather than a ratio, deliberately. {@link OnsetEnvelope} is
     * normalised to zero mean and unit variance, so its values are already in
     * standard deviations and dividing by their mean would divide by a quantity
     * whose sign is arbitrary and whose magnitude passes through zero — which
     * turns a faint accent into an unbounded score and puts a discontinuity in
     * the middle of the scale.
     *
     * @return one value per phase, in deviations and unbounded; bounding happens
     *     in {@link #onsetTerm}, so that ranking these against each other stays
     *     possible however large they get
     */
    private static double[] onsetAdvantage(double[] perBeat, int firstBeat, int lastBeat,
                                           int beatsPerBar) {
        double overall = mean(perBeat, firstBeat, lastBeat);
        double[] perPhase = meanPerPhase(perBeat, firstBeat, lastBeat, beatsPerBar, overall);
        double[] advantage = new double[beatsPerBar];
        for (int phase = 0; phase < beatsPerBar; phase++) {
            advantage[phase] = perPhase[phase] - overall;
        }
        return advantage;
    }

    private static double mean(double[] values, int from, int to) {
        double sum = 0;
        for (int i = from; i <= to; i++) {
            sum += values[i];
        }
        int count = to - from + 1;
        return count > 0 ? sum / count : 0;
    }

    /** The highest-scoring phase, earliest first so that a tie is not arbitrary. */
    private static int argMax(double[] score) {
        int best = 0;
        for (int phase = 1; phase < score.length; phase++) {
            if (score[phase] > score[best]) {
                best = phase;
            }
        }
        return best;
    }

    /**
     * The highest-scoring phase, with ties broken on a second quantity.
     *
     * <p>Exists so that the guarantee about harmony outranking accent holds at
     * its boundary rather than just inside it.
     */
    private static int argMax(double[] score, double[] tieBreak) {
        int best = 0;
        for (int phase = 1; phase < score.length; phase++) {
            if (score[phase] > score[best]
                    || (score[phase] == score[best] && tieBreak[phase] > tieBreak[best])) {
                best = phase;
            }
        }
        return best;
    }

    /**
     * The best score among the phases other than one.
     *
     * <p>Only ever called where there are at least two phases, a one-beat bar
     * having been answered before any of this arithmetic runs.
     */
    private static double runnerUp(double[] score, int except) {
        double best = Double.NEGATIVE_INFINITY;
        for (int phase = 0; phase < score.length; phase++) {
            if (phase != except && score[phase] > best) {
                best = score[phase];
            }
        }
        return best;
    }

    private static void requireBeatsPerBar(int beatsPerBar) {
        if (beatsPerBar < 1) {
            throw new IllegalArgumentException("beatsPerBar must be positive, got: " + beatsPerBar);
        }
    }

    private static void requireBeats(List<Double> beatTimes) {
        if (beatTimes.isEmpty()) {
            throw new IllegalArgumentException("cannot estimate a downbeat phase with no beats");
        }
    }

    private static double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            // Unreachable through Chroma since #77: its constructor now rejects a
            // frame that is not twelve values wide, so harmonicNovelty -- the
            // only caller, and one that takes a Chroma -- cannot be handed a
            // ragged one. Kept because it costs one comparison and because this
            // is a private helper that a future caller could reach with two
            // arrays of its own.
            return Double.NaN;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        // NaN rather than zero for a vector with no length: zero would mean
        // "orthogonal", which is the strongest claim this measure can make, and
        // the truth is that it has nothing to compare.
        return denominator > 0 ? dot / denominator : Double.NaN;
    }
}
