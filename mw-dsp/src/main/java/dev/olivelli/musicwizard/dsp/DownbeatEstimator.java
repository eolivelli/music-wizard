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
 * Picks which of the tracked beats begin bars.
 *
 * <p>The beats say where the pulse is; they say nothing about where the bar
 * starts, and that phase has to be chosen from other evidence. Choosing it from
 * onset energy alone — bars tend to start with an accent — is weak in two
 * different ways. On a click track every beat carries an identical click, so the
 * choice is close to arbitrary; on real music a backbeat routinely carries
 * <em>more</em> energy than the downbeat, so the heuristic is not merely
 * uninformative but sometimes actively wrong.
 *
 * <p>Harmony is the stronger signal. Chords change preferentially at bar lines
 * in popular music, so the phase whose beats coincide with harmonic change is
 * the phase that starts the bars. That is measured here as the cosine distance
 * between the beat-synchronous chroma either side of each beat, which needs no
 * chord labels at all — only the chroma the chord stage is computed from anyway.
 * Deliberately so: scoring against decoded chords would make downbeat detection
 * depend on chord estimation, which already depends on the beats.
 *
 * <p>Onset energy is kept as a weak second term rather than discarded, because
 * there are signals where the accent genuinely does mark the bar and dropping it
 * would trade one blind spot for another. It is bounded rather than weighted:
 * the onset term moves a phase's score by at most {@code ONSET_WEIGHT} either
 * way, so a phase whose mean harmonic novelty leads by more than twice that
 * cannot be overturned by any accent, however loud. Below that margin the
 * harmony is not distinguishing the phases and the accent decides. On a
 * one-chord passage the harmonic differences are a thousandth of a cosine
 * distance and the accent always decides; on a chord change per bar they are
 * tenths and it never does.
 *
 * <p>Confidence asks three separate questions of the harmony rather than one:
 * did it decide the phase, does it prefer that phase over the alternatives, and
 * was there enough of it to mean anything. A margin alone is not enough —
 * material that changes chord often at no consistent phase produces a wide
 * margin by luck — so the three are multiplied and any one of them failing
 * brings the number down. The ceiling on the product is the reliability of what
 * harmony assumes rather than the weight of what it measured — see
 * {@link #HARMONIC_PHASE_CEILING}.
 *
 * <p><b>The accent is asked nothing.</b> It chooses the phase, in the score
 * above, and is then not asked to vouch for the phase it chose. It used to be,
 * and that was wrong twice over. It is the same observation counted a second
 * time — evidence that picks an answer cannot also certify it — and it certifies
 * the wrong answer, because {@link OnsetEnvelope} sums flux over forty mel
 * bands, so a broadband snare excites all of them and a sixty-hertz kick about
 * one, and the loudest phase it reports on ordinary drum material is the
 * backbeat (#70). A chord pushed one beat lands on beat 4, which is a backbeat,
 * so on exactly the material where the doubt below lives the accent sits on the
 * anticipation rather than on the bar. Measured, the pushed reading of a fixture
 * collected the bonus where the correct reading of the same fixture did not, and
 * the anticipation reported 0.600 against the bar line's 0.580 — the wrong
 * answer reading higher, which is worse than the flat 0.85 it replaced. So a
 * phase chosen by the accent alone reports the floor: it is a guess, rather than
 * a better guess than one chosen by nothing.
 *
 * <p>What to rely on in that number is its <em>ordering</em>, not its value.
 * The ordering is structural — a phase nothing supports lands on the floor, a
 * phase the accent alone chose lands there too, and only harmony lifts one off
 * it — but the constants that space it out were calibrated on synthetic
 * fixtures, and synthetic audio is
 * systematically easier than real audio. Two of the three factors are already
 * known not to measure quite what they claim on material with a chroma novelty
 * floor, which every real recording has: see #45.
 *
 * <p>The known limit of the approach is that it measures agreement with
 * harmonic change, not with bar lines, and the step from one to the other is an
 * assumption. Where a style consistently anticipates the chord — the pushed
 * change an eighth or a beat before the bar, ordinary in pop and near-universal
 * in some Latin idioms — the harmony moves a beat early and this agrees with the
 * anticipation, unanimously, because by its own measure it is right. Nothing in
 * the chroma separates the two readings: the same recording is a pushed bar and
 * a bar that starts a beat later, and no further harmonic evidence tells them
 * apart. So the phase is still the anticipation, and what changed for #48 is
 * that it is no longer reported as settled — see {@link #HARMONIC_PHASE_CEILING}.
 * Moving it needs evidence this stage does not have; the bass keeps landing on
 * the bar line when the upper voices are pushed, which is #42.
 *
 * <p>The meter is assumed, never inferred; see {@link BeatTracker#toBeatGrid}.
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
     * The most any phase {@link #estimate} chooses may report, whatever backs it.
     *
     * <p>The scoring measures agreement with harmonic change and takes a bar line
     * to be where the harmony moves. That is a good assumption and a wrong one
     * often enough to matter. A chord pushed ahead of the bar puts every change
     * one beat early, and the scoring then agrees with the anticipation rather
     * than with the bar — unanimously, because by its own measure it is right.
     * The chroma cannot tell the two apart: the same audio is a pushed bar and a
     * bar that starts a beat later, which is what #48 reported and what
     * {@code anAnticipationLooksExactlyLikeAMidBarStart} pins.
     *
     * <p>So this ceiling is the reliability of that assumption, not the strength
     * of the evidence for it, and nothing may pass it — which since the accent
     * stopped contributing to the confidence is a property of the arithmetic
     * rather than a clamp on it: it is exactly {@code BASE_CONFIDENCE} plus what
     * unanimous harmonic agreement is worth. It was briefly a {@code Math.min}
     * over a total the accent could also raise, and that was the wrong shape. A
     * cap binds only where the harmony is already unanimous, real material sits
     * below that, and below it the accent still lifted the anticipation above the
     * bar line it was pushed off.
     *
     * <p>It is set the way {@link #BASE_CONFIDENCE} is — by counting what is left
     * open. Unanimous harmonic agreement narrows the phase from every beat of the
     * bar to two: the beat the harmony moves on, or the beat after it, since a
     * push arrives early and never late. A one-in-two choice sits here for the
     * same reason a one-in-four choice sits at {@code BASE_CONFIDENCE}.
     *
     * <p>That count is stated for the common meter and applied flat, exactly as
     * {@code BASE_CONFIDENCE}'s "one phase in four" is: at 2/4 it narrows
     * nothing, since two candidates are the whole bar, and this then reads
     * generously. Making either of them depend on the meter is one change, and
     * neither is calibrated well enough for that to be the improvement it looks
     * like — the numbers want tier-2 audio (#12), not more arithmetic.
     *
     * <p>Deliberately in the band that reads as "probably, but check it". A
     * confidently wrong phase costs a user more than an uncertain right one:
     * correcting the first downbeat by hand is the highest-value action the tool
     * asks of them, and a number that says not to bother is what stops them.
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
     *                    required at every bar length but one
     * @param envelope    the onset envelope the beats were tracked from
     * @param beatsPerBar the assumed bar length; not inferred
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
     * The answer for a one-beat bar, which is not an estimate at all.
     *
     * <p>Every beat begins a bar, so phase 0 is the only phase there is and it
     * is right whatever the recording contains. Answered here, before any
     * evidence is gathered, rather than derived from it: the whole scoring
     * apparatus is about choosing between phases, and deriving "there is nothing
     * to choose" through measures of how well the evidence discriminates makes
     * the answer depend on how much harmonic change a recording with no choice
     * to make happened to have.
     *
     * <p>{@link Confidence#CERTAIN} rather than the ceiling an estimated phase
     * can reach, because this is not a claim that could be wrong, and reporting
     * it below a 4/4 phase that could be would invert the only thing about these
     * numbers a caller should rely on.
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
            // An empty span is silence, not a chord change. Cosine is undefined
            // against a zero vector, and reading the undefined value as maximum
            // novelty would make a silent passage the most persuasive evidence
            // in the recording -- more persuasive than any real chord change,
            // which never reaches a full cosine distance of 1.
            // Floored at zero because a cosine can come back a hair above 1 for
            // two numerically parallel spans, and the arithmetic downstream
            // assumes novelty is non-negative in three places: a share of the
            // total cannot exceed one, the best score among the other phases
            // cannot be below zero, and a sum of squares is zero only when every
            // term is. None of the three is observable on its own — each of them
            // needs a negative to reach — which is exactly why the floor belongs
            // here, at the one point where the sign is decided, rather than as
            // three defensive checks that each look unnecessary.
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
