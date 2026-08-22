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

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Recognises chords by matching chroma against binary templates, then
 * smoothing the result with Viterbi decoding — frame-wise matching alone
 * chatters, and the transition model is worth more than any template tuning.
 *
 * <p>On a real recording the emission model, not the front end, was the fix
 * for #185: measured on a recording with known changes, a better chroma alone
 * changed nothing — the flat no-chord template swallowed whatever it was
 * handed — while this class's three changes (the seventh templates, the
 * no-chord level, the emission sharpness) made the recording speak, and the
 * NNLS front end (#3) added its worth only on top of them.
 * {@code tools/ChordSweep.java} re-derives the sweeps;
 * {@code tools/baselines/} carries the current readings. (Historical sweep
 * figures were measured on the pre-#196 beat grid; #232 tracks re-measuring.)
 *
 * <p><b>The root and the quality are decided separately</b>, from different
 * chroma and over different spans: the root beat by beat from both registers,
 * the quality once per chord from the treble alone (#208; see
 * {@link #estimate(Chroma, Chroma, List)}). The two read different
 * vocabularies, {@link #DECODED} and {@link #QUALITY_ONLY}, because a
 * template that competes across roots is a different risk from one that
 * cannot move a root. And both registers added is still a fold, which cannot
 * say which of a chord's own notes is its root — the whole difference between
 * a chord and its relative minor — so the decoder is also given the bass
 * register, as a prior over roots rather than as another template to match
 * ({@link #BASS_ROOT_WEIGHT}, #448).
 */
public final class ChordEstimator {

    /**
     * Probability of staying on the same chord between beats. Lower it and the
     * output chatters; raising it was swept and costs accuracy monotonically
     * for slight chatter benefit. The chatter that remains is addressed by the
     * chart's reduction, a notation decision rather than an estimation one.
     */
    private static final double SELF_TRANSITION = 0.6;

    /**
     * Sharpness of the emission distribution. Cosine similarity is a very flat
     * score, so unsharpened the transition prior silently overwhelms the
     * evidence and the decoder sits on one state for a whole recording — which
     * it used to do. Swept to a real peak, not a plateau: below it the prior
     * wins arguments the evidence should win, above it the evidence chases
     * noise, visible first in the quality column.
     */
    private static final double EMISSION_SHARPNESS = 50.0;

    /**
     * The similarity the no-chord state is credited with, against which every
     * template has to compete. Replaces scoring no-chord as a flat template,
     * which is #185: cosine against a flat profile grows stronger the less a
     * frame looks like music, so on a real mix no chord could beat "no chord"
     * and whole songs came back as one N.C. span. A fixed level says report a
     * chord when some chord actually fits.
     *
     * <p>The level is not delicate over its working range and falls off a
     * cliff above it; it sits to leave room before that edge. On real material
     * it is close to an off switch rather than a discriminator — a seventh
     * also clears it before a triad does on no evidence, template size being
     * what it is — so a no-chord model reading energy and flatness is #195.
     */
    private static final double NO_CHORD_SIMILARITY = 0.60;

    /**
     * Chroma energy below which a span is treated as having no chord.
     *
     * <p>Compared against a raw magnitude sum, so the scale is arbitrary; it is
     * only meant to catch genuine silence, not quiet passages.
     */
    private static final double SILENCE_THRESHOLD = 1e-6;

    /**
     * Log-likelihood a bass register that names a single root outright is
     * worth. A fold to pitch classes cannot say which of a chord's notes is
     * its root — {@code A6} and {@code F#m7} are the same set, and a boogie
     * shuffle's root-and-sixth comping reads as the relative minor over any
     * window (#448); the bass register separates them because that is where a
     * root is played. Added to the emission score, so it is read against
     * {@link #EMISSION_SHARPNESS}; a bass naming nothing spreads its share
     * evenly and decides nothing.
     *
     * <p>Swept by {@code tools/ChordSweep.java score}: the gain and the
     * ceiling move against each other, and this sits at the last weight where
     * a vamp whose bass is a figure rather than a pedal has given up only a
     * bar of root. Roots are the column the rest of the chart hangs on. The
     * bass also reaches the root through its share of the combined fold, and
     * a package where either channel alone suffices (#514) is not a case for
     * removing the prior — the fold's bass share is what a real mix can lose.
     */
    private static final double BASS_ROOT_WEIGHT = 20;

    /**
     * Beats either side of the current one that the bass root is read over:
     * about a bar. A walking bass passes through the third and the sixth, and
     * read beat by beat the prior asserts a root at every passing note — the
     * run it splits then has its quality decided twice from half the evidence
     * each time, so the window is part of the mechanism, not a smoothing
     * detail. Swept by {@code tools/ChordSweep.java score}; the corpus rows
     * disagree on a best window above this value, and #488 carries whether it
     * should grow.
     */
    private static final int BASS_ROOT_BEATS = 2;

    /**
     * The qualities the Viterbi decoder chooses between, one state per root.
     * Deliberately smaller than the set the quality decision may report: a
     * template competes across <em>roots</em> here, and a four-note template
     * contains a triad on another root — {@code Am7} is {@code C} with an A —
     * so a minor seventh in this list moves roots wherever the sixth degree
     * sounds, measured as a collapse of root accuracy. {@link #QUALITY_ONLY}
     * is where the seventh goes instead, and it cannot move a root.
     *
     * <p><b>The major seventh is here rather than there</b> (#588), and it is
     * the exception that shows what the list is for: {@code Cmaj7} is
     * {@code Em} with a C, so it carries the same risk — but the recordings
     * whose truth holds one do not have those bars decoded onto their own root
     * at all, and a quality that cannot move a root cannot reach a bar it never
     * got. It is admitted on the fit's residual rather than on the chroma
     * ({@link #DECODED_MAJOR_SEVENTH_SHARE_OF_ROOT}), so an estimate made
     * without a {@link PitchClassAblation} still decodes the vocabulary it
     * decoded before this was added.
     */
    private static final ChordQuality[] DECODED = {
            ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.DOMINANT_SEVENTH,
            ChordQuality.MAJOR_SEVENTH};

    /**
     * Share of the residual its root removes that a major seventh must remove
     * before the decoder scores that degree at all. Only {@link #emissions}
     * reads it. Where it does not clear this the template is scored on the
     * triad it contains over its own four-note norm, which the triad wins, so
     * the gate is an admission rather than a penalty.
     *
     * <p><b>Gated here and in {@link #qualityScore} together</b>: a quality in
     * {@link #DECODED} is also a candidate of the quality decision, so a gate
     * in one place alone measures the ungated rule.
     *
     * <p>What sets it is the ninth over the relative minor seventh —
     * {@code Abmaj7} is {@code Fm7} with a G for its F, and that G is the ninth
     * a soloist plays over the vamp — which a fold cannot tell from the chord
     * itself and the bass prior was measured not to separate (#635). The degree
     * is asked to be load-bearing enough that it does not clear this. Swept by
     * {@code tools/ChordSweep.java score} and {@code tools/score-samples.py};
     * {@code tools/baselines/} carries what both sides of the band cost.
     */
    private static final double DECODED_MAJOR_SEVENTH_SHARE_OF_ROOT = 1.5;

    /**
     * Share of the residual its root removes that a major seventh must remove
     * to be counted in the quality decision. Only {@link #qualityScore} reads
     * it, and only where a {@link PitchClassAblation} is available.
     *
     * <p>{@link #ADDED_NOTE_SHARE_OF_ROOT}'s rule applied to the one degree
     * that is manufactured rather than merely shared: the major seventh is the
     * perfect fifth above the chord's own major third, so a plain triad carries
     * it as that third's third partial. It is asked for more than the sixth and
     * the diminished fifth are, and that is measured — swept by {@code
     * tools/ChordSweep.java score} against the plain-triad control of #273,
     * below this band that recording is named with sevenths it does not hold,
     * and above it the recordings whose truth holds them lose bars.
     */
    private static final double MAJOR_SEVENTH_SHARE_OF_ROOT = 0.5;

    /**
     * Qualities the quality decision may report but the decoder may not
     * choose. These carry the same root as one of {@link #DECODED}, so they
     * can only relabel a chord the decoder has already placed, never move it.
     * See {@link #chooseQualities} for what keeps a minor seventh from being
     * reported wherever a dominant one is right, and #274 for the
     * false-seventh rate on plain-triad material.
     *
     * <p><b>The plain sixth is deliberately absent</b> (#287). It adds a note
     * to a triad, where a binary template only asks the note to carry
     * 2/sqrt(3) - 1 of the triad's mass, and it is a note the mix carries
     * without it being played — a boogie shuffle plays it under a dominant — so
     * with the residual test below applied it still takes the dominant-seventh
     * benchmarks; #287 carries the table. Telling {@code A6} from
     * {@code F#m7} needs evidence about which sounding note the chord is built
     * on rather than about whether a note is sounding (#274).
     *
     * <p><b>The added ninth is absent for a sharper version of the same
     * reason</b> (#651). Its degree is the perfect fifth above the chord's own
     * fifth, so a plain triad manufactures it as that fifth's third partial —
     * the major seventh's relation to the third, on the louder note.
     *
     * <p>The two here are decided on the same test where the fit's residual is
     * available ({@link #ADDED_NOTE_SHARE_OF_ROOT}), on the sixth and on the
     * diminished fifth. The half-diminished also depends on the root the
     * decoder found: {@code Am7b5} is a {@code Cm} triad with an A in it, and
     * before the bass root prior ({@link #BASS_ROOT_WEIGHT}) the fold put the
     * package's bars on C, where no relabelling can reach them.
     */
    private static final ChordQuality[] QUALITY_ONLY = {ChordQuality.MINOR_SEVENTH,
            ChordQuality.MINOR_SIXTH, ChordQuality.HALF_DIMINISHED_SEVENTH};

    /** States the decoder chooses between: {@link #DECODED} on every root, plus no-chord. */
    private static final int DECODED_STATES = 12 * DECODED.length + 1;

    /**
     * How much of a chord's major third the root's own fifth partial accounts
     * for, as a share of the root's chroma. Only {@link #qualityScore} reads
     * it. Swept by {@code tools/ChordSweep.java score} and sitting inside a
     * band where every scored benchmark holds: below it a major third that is
     * only the root's own partial counts against minor chords — a blues third
     * or a strongly voiced root turns minor chords major — and above it the
     * correction stops firing where the minor third really is a colour over a
     * dominant. The zero end scores best on the chord table and is wrong: it
     * buys one recording by taking the mode away from two that state one
     * plainly.
     */
    private static final double ROOT_EXPLAINS_MAJOR_THIRD = 0.25;

    /**
     * Share of the residual its root removes that a major third must remove to
     * count as sounding at all, where a minor third over the same root removes
     * more than it does. Only {@link #qualityScore} reads it, and only where a
     * {@link PitchClassAblation} is available.
     *
     * <p>A level as well as the ranking, because the ranking alone also fires
     * on a blues third over a dominant, where both thirds are played and the
     * minor one is louder — measured, that is most of a dominant vamp turning
     * minor. The phantom is generated by the root's own fifth partial, so it
     * scales with the root: what separates the two populations is how much of
     * the root's own residual the major third accounts for, and not how much it
     * accounts for in absolute terms, which is a property of the production.
     *
     * <p>Swept by {@code tools/ChordSweep.java score}, and this sits in the
     * middle of the band where every scored benchmark holds. Below it the
     * phantom survives on the recording of #527; above the band a played major
     * third starts being discounted and the dominant-seventh benchmarks fall
     * away.
     */
    private static final double PHANTOM_THIRD_SHARE_OF_ROOT = 0.20;

    /**
     * Share of the residual its root removes that a sixth or a diminished fifth
     * must remove to be counted at all — the two notes the qualities #287 adds
     * are decided by. Only {@link #qualityScore} reads it, and only where a
     * {@link PitchClassAblation} is available.
     *
     * <p>What ranks those candidates on the fit rather than on which extra note
     * is louder, which is what #287 said was missing. The fold cannot answer
     * it: over a minor triad the flat seventh is the root's own seventh partial
     * and the sixth is a note another chord is built on, so both are in the
     * chroma whichever is played, and the half-diminished — the same size as
     * the minor seventh — is decided on nothing but which of the two fifths
     * carries more of it. Deleting the pitch class and refitting separates
     * them, because a note that is sounding cannot be deleted cheaply (#537).
     *
     * <p><b>The flat seventh is not tested the same way</b>, which is measured
     * rather than an omission: a flat seventh really played on a real mix
     * removes less residual, as a share of its root's, than a manufactured one
     * does on a rendered package, so the two populations are ordered the wrong
     * way round and no share separates them. #274 carries the seventh's own
     * false-positive rate.
     *
     * <p>Swept by {@code tools/ChordSweep.java score} and {@code
     * tools/score-chart.py} over shares from a twentieth to a half. Across that
     * sweep no benchmark's chord accuracy moves at all, and this sits inside
     * the narrower band where {@code synthetic_samples/pop-m6-m7b5-gm-100} also
     * reads every bar right: above that band a voiced diminished fifth stops
     * being counted, and below it half-diminished labels appear on a minor
     * blues whose dominants hold no flat fifth. That it lands at the same share
     * as {@link #PHANTOM_THIRD_SHARE_OF_ROOT} is not a shared constant: they
     * are separate rules, free to move apart, whose bands happen to overlap.
     */
    private static final double ADDED_NOTE_SHARE_OF_ROOT = 0.20;

    /**
     * Share of the residual its root removes that a minor third must remove to
     * be counted at all. Only {@link #qualityScore} reads it, and only where a
     * {@link PitchClassAblation} is available.
     *
     * <p>{@link #ADDED_NOTE_SHARE_OF_ROOT}'s rule — a note is evidence where
     * the fit needs it, not where the chroma carries it — applied to the third.
     * Without it, the test above decides a run in which <em>neither</em> third
     * is in the fit: the major third's mass is zeroed and the minor third's is
     * read at face value, so the minor candidate takes the run on whatever
     * noise sits on its pitch class. On a recording that holds no minor chord
     * that is seconds of false minor at a time, turned by two significances a
     * fraction of a percent apart (#546). With both thirds discounted the two
     * triads score alike, so where both clear their own floors the argmax keeps
     * the first of them, which is the major one ({@link #buildTemplates} fixes
     * that order) — a run with no third in it reads as a plain triad. Their
     * floors are not the same bar, though, and #557 is that: {@link #flatScore}
     * charges a minor candidate the {@link #ROOT_EXPLAINS_MAJOR_THIRD}
     * subtraction that a discounted score no longer pays, so between the two
     * floors the major candidate is ruled out and the minor one admitted.
     *
     * <p>Much smaller than its neighbours, which is the measurement rather than
     * caution: a minor third that is really played removes a large share of its
     * root's residual, so this has only to clear the noise. Swept by {@code
     * tools/ChordSweep.java score} from a four-hundredth to a twentieth, and
     * this is the largest share swept at which no scored benchmark loses a bar.
     * Above it they go one at a time, so a reader raising this should watch all
     * three: at a hundredth {@code jazz-251-c-140} gives up two {@code Dm7}
     * bars, and by a twentieth {@code cm-blues-68-95} has turned an {@code m7}
     * into a {@code 7} and {@code pop-c-g-am-f-120} — the plain-triad benchmark
     * — two of its {@code Am} bars major.
     */
    private static final double MINOR_THIRD_SHARE_OF_ROOT = 0.0075;

    private ChordEstimator() {
    }

    /** A chord candidate: a root pitch class and a quality. */
    private record Template(int rootPitchClass, ChordQuality quality, double[] profile) {
    }

    /**
     * Estimates chords over beat-synchronous chroma, deciding quality from the
     * same chroma as the root.
     *
     * <p>What a caller with only one chroma to offer should use. A caller that
     * has the treble register separately should hand it over — see
     * {@link #estimate(Chroma, Chroma, List)} for what it is worth and why.
     *
     * @param chroma    beat-synchronous chroma, one vector per inter-beat span
     * @param beatTimes the beat instants those spans lie between
     */
    public static ChordProgression estimate(Chroma chroma, List<Double> beatTimes) {
        return estimate(chroma, chroma, beatTimes);
    }

    /**
     * Estimates chords, deciding the root from one chroma and the quality from
     * another. The two questions want different evidence (#208): the root is
     * best read from both registers added, because the bass is where a root is
     * played; the quality from the treble alone, because that same bass scales
     * up the root's share of the chroma by however loud it was mixed, and the
     * chord's own colour is what gets scaled down —
     * {@code tools/ChordSweep.java profile} prints the per-register
     * measurement. Quality is also decided once per run of beats sharing a
     * root rather than beat by beat, because a chord is one chord for its
     * whole duration and its seventh need not sound on every beat of it. Both
     * halves were measured necessary on different benchmarks.
     *
     * <p><b>This form runs without the root prior of
     * {@link #estimate(Chroma, Chroma, Chroma, List)}</b>, which is what the
     * pipeline runs and what {@code tools/baselines/} was measured through. A
     * caller that has a separated bass register and passes this form is
     * quietly asking a different question.
     *
     * @param chroma        beat-synchronous chroma the root and the chord
     *                      boundaries are decoded from
     * @param qualityChroma beat-synchronous chroma, over the same beats, the
     *                      quality is decided from
     * @param beatTimes     the beat instants those spans lie between
     * @throws IllegalArgumentException if the two chromas do not describe the
     *     same frames
     */
    public static ChordProgression estimate(Chroma chroma, Chroma qualityChroma,
                                            List<Double> beatTimes) {
        return decode(chroma, qualityChroma, null, null, beatTimes);
    }

    /**
     * Estimates chords with the bass register available as a root prior.
     *
     * <p>What the pipeline uses. The root and the quality are decided as
     * {@link #estimate(Chroma, Chroma, List)} describes; this adds the one thing
     * neither chroma can say, which is which of a chord's own notes is its root.
     * See {@link #BASS_ROOT_WEIGHT}.
     *
     * @param chroma        beat-synchronous chroma the root and the chord
     *                      boundaries are decoded from
     * @param qualityChroma beat-synchronous chroma, over the same beats, the
     *                      quality is decided from
     * @param bassChroma    beat-synchronous chroma, over the same beats, of the
     *                      bass register alone — {@link NnlsChroma#bass()}
     * @param beatTimes     the beat instants those spans lie between
     * @throws IllegalArgumentException if the three chromas do not describe the
     *     same frames
     */
    public static ChordProgression estimate(Chroma chroma, Chroma qualityChroma,
                                            Chroma bassChroma, List<Double> beatTimes) {
        return decode(chroma, qualityChroma,
                Objects.requireNonNull(bassChroma, "bassChroma"), null, beatTimes);
    }

    /**
     * Estimates chords with the fit's own residual available to the quality
     * decision as well.
     *
     * <p>What the pipeline uses. Everything else is as
     * {@link #estimate(Chroma, Chroma, Chroma, List)}; this adds the one thing
     * no chroma can say, which is whether a pitch class the fit turned on is
     * carrying the spectrum or standing in for the root's own fifth partial
     * (#537). It is read by {@link #qualityScore} and, for the one template
     * admitted on it rather than on the chroma, by {@link #emissions} — so
     * supplying it decides which roots are decoded and not only which qualities
     * are reported.
     *
     * @param chroma        beat-synchronous chroma the root and the chord
     *                      boundaries are decoded from
     * @param qualityChroma beat-synchronous chroma, over the same beats, the
     *                      quality is decided from
     * @param bassChroma    beat-synchronous chroma, over the same beats, of the
     *                      bass register alone — {@link NnlsChroma#bass()}
     * @param ablation      leave-one-out residual over the same spans —
     *                      {@link NnlsAblation}
     * @param beatTimes     the beat instants those spans lie between
     * @throws IllegalArgumentException if the three chromas do not describe the
     *     same frames
     */
    public static ChordProgression estimate(Chroma chroma, Chroma qualityChroma,
                                            Chroma bassChroma, PitchClassAblation ablation,
                                            List<Double> beatTimes) {
        return decode(chroma, qualityChroma, Objects.requireNonNull(bassChroma, "bassChroma"),
                Objects.requireNonNull(ablation, "ablation"), beatTimes);
    }

    /**
     * The body of all three, with {@code bassChroma} null where there is no bass
     * register and {@code ablation} null where the fit's residual is not
     * available.
     */
    private static ChordProgression decode(Chroma chroma, Chroma qualityChroma,
                                           Chroma bassChroma, PitchClassAblation ablation,
                                           List<Double> beatTimes) {
        Objects.requireNonNull(chroma, "chroma");
        Objects.requireNonNull(qualityChroma, "qualityChroma");
        Objects.requireNonNull(beatTimes, "beatTimes");
        if (chroma.frameCount() != qualityChroma.frameCount()) {
            throw new IllegalArgumentException("chroma has " + chroma.frameCount()
                    + " frames and qualityChroma has " + qualityChroma.frameCount()
                    + "; the two describe the same beats");
        }
        if (bassChroma != null && chroma.frameCount() != bassChroma.frameCount()) {
            throw new IllegalArgumentException("chroma has " + chroma.frameCount()
                    + " frames and bassChroma has " + bassChroma.frameCount()
                    + "; the two describe the same beats");
        }
        if (ablation != null && chroma.frameCount() != ablation.spanCount()) {
            throw new IllegalArgumentException("chroma has " + chroma.frameCount()
                    + " frames and the ablation covers " + ablation.spanCount()
                    + " spans; the two describe the same beats");
        }
        if (chroma.frameCount() == 0 || beatTimes.size() < 2) {
            return ChordProgression.empty();
        }

        List<Template> templates = buildTemplates();
        double[][] similarity = similarities(chroma, templates);
        double[][] emission = emissions(chroma, templates, similarity, ablation);
        double[][] prior = bassRootPrior(bassChroma, similarity.length);
        double[][] logLikelihood = new double[similarity.length][templates.size()];
        for (int frame = 0; frame < similarity.length; frame++) {
            for (int t = 0; t < templates.size(); t++) {
                Template template = templates.get(t);
                logLikelihood[frame][t] = EMISSION_SHARPNESS
                        * Math.log(Math.max(1e-9, emission[frame][t]));
                // No-chord carries no root and so takes no term. That is why the
                // term below is at most zero: see bassRootPrior.
                if (prior != null && template.quality() != ChordQuality.NONE) {
                    logLikelihood[frame][t] +=
                            BASS_ROOT_WEIGHT * prior[frame][template.rootPitchClass()];
                }
            }
        }
        int[] path = viterbi(logLikelihood, DECODED_STATES);
        int[] chosen = chooseQualities(path, templates, qualityChroma, ablation);

        // Confidence is reported from the raw similarity, not the sharpened
        // score: the exponent exists to make the decoder behave, and letting it
        // leak into a number a user reads would make every chord look shaky.
        return toProgression(chosen, templates, beatTimes, similarity);
    }

    /**
     * Re-decides each chord's quality over the whole run of beats the decoder put
     * on one root, from the summed chroma of that run.
     *
     * <p>One argmax over every quality available on that root —
     * {@link #DECODED} and {@link #QUALITY_ONLY} both, scored by
     * {@link #qualityScore} — not a triad decision followed by a seventh
     * decision: the flat seventh is itself evidence for the major third, and
     * taking the third alone first throws that away before it can be used.
     *
     * <p><b>A candidate has to explain the run better than a flat chroma
     * would, or the decoder's own answer stands.</b> The decoder never needed
     * that rule because {@link #NO_CHORD_SIMILARITY} sits above every
     * template's flat score; this decision reads a different chroma and has no
     * no-chord state to lose to, so it carries the rule itself. It rejects
     * evidence <em>worse</em> than noise, not evidence that is merely weak —
     * {@link #flatScore} carries the difference and its cost.
     *
     * <p>Returns a new state path — same root and no-chord decisions, quality
     * replaced — so everything downstream keeps reading one array.
     */
    private static int[] chooseQualities(int[] path, List<Template> templates,
                                         Chroma qualityChroma, PitchClassAblation ablation) {
        int[] out = path.clone();
        // The run's residual test, held per beat of the run: the ablation is
        // asked once per run and read again by decideSeventhsPerRoot, which
        // groups the same array into the same runs.
        double[][] significance = new double[path.length][];
        int i = 0;
        while (i < path.length) {
            Template start = templates.get(path[i]);
            int j = i;
            while (j < path.length && sameChord(templates.get(path[j]), start)) {
                j++;
            }
            if (start.quality() != ChordQuality.NONE) {
                double[] runSignificance =
                        ablation == null ? null : ablation.significanceOver(i, j);
                for (int frame = i; frame < j; frame++) {
                    significance[frame] = runSignificance;
                }
                int chosen = bestQuality(sum(qualityChroma, i, j), templates,
                        start.rootPitchClass(), quality -> true, runSignificance);
                // Nothing explained the run better than a flat chroma would, so
                // there is nothing here to overrule the decoder with.
                if (chosen >= 0) {
                    for (int frame = i; frame < j; frame++) {
                        out[frame] = chosen;
                    }
                }
            }
            i = j;
        }
        decideSeventhsPerRoot(path, out, templates, qualityChroma, significance);
        decideThirdsPerRoot(path, out, templates, qualityChroma, significance);
        return out;
    }

    /**
     * Share of a root's beats that decides its third for all of them. Only
     * {@link #decideThirdsPerRoot} reads it, and it is the same definition
     * {@link #SEVENTH_MUST_HOLD_FOR} is — a half, read strictly, so an even
     * split leaves each run as it read — rather than a second fitted constant.
     */
    private static final double THIRD_MUST_HOLD_FOR = 0.5;

    /**
     * How well a run has to fit its own minor-ish candidate to keep its third
     * against {@link #THIRD_MUST_HOLD_FOR}'s count.
     *
     * <p>Read against {@link #qualityScore}, so it is the same cosine the
     * decision itself is made on and one is the whole of the run's chordal
     * register being those notes and nothing else. Absolute rather than a
     * multiple of {@link #flatScore}, because that floor moves with the
     * template's size and a four-note minor-ish label could then not reach any
     * bar at all — and #583 is chiefly about those.
     *
     * <p>Swept by {@code tools/ChordSweep.java score} and by both sample
     * harnesses: the corpus stands still across a band around it and moves at
     * either edge, so the rule is not delicate. What it costs, on real audio,
     * is a false minor that fits its run this well; the baselines carry
     * whether any does.
     */
    private static final double MINOR_OVERRULES_THE_COUNT = 0.90;

    /**
     * Withdraws the minor third from every run on a root the recording states a
     * major third on, in place.
     *
     * <p>{@link #decideSeventhsPerRoot}'s prior — a chord recurs, and its
     * quality belongs to the chord rather than to the bar — read on the third.
     * The third is the decision the residual instrument leaves weakest: a major
     * third the fit does not need is discounted ({@link
     * #PHANTOM_THIRD_SHARE_OF_ROOT}) and a minor third only has to clear the
     * noise ({@link #MINOR_THIRD_SHARE_OF_ROOT}), so a run holding no third at
     * all is settled by whichever pitch class the mix happened to leave
     * something on. Over one run that is a coin toss; over every run on a root
     * it is a count, and the count is what the recordings of #558 have.
     *
     * <p><b>Withdrawal only</b>, where the seventh's rule reads its count both
     * ways. The two directions are not the same claim: a major third is
     * manufactured by the root's own fifth partial and a minor third is not, so
     * a majority of major-third runs can be an artefact of the production while
     * a majority of minor-third ones cannot. #581 carries the other direction.
     *
     * <p><b>Runs after {@link #decideSeventhsPerRoot}</b>, so the count is read
     * over labels that rule has already settled. Its withdrawal turns minor
     * sevenths into triads, which moves this count's numerator and can tip a
     * root from mostly minor to mostly major — and a root read the other way
     * round is one this rule leaves entirely alone. So the order decides which
     * roots are counted as major at all, not merely what is left over: run
     * first, this rule declines those roots and the runs stay minor, measured.
     * Nothing is lost the other way, because the count there deliberately
     * excludes the dominant seventh, so a major-third candidate chosen here
     * carries no claim that rule has made.
     *
     * <p>The re-decision is held to {@link #bestQuality}'s floor like every
     * other, and that floor is a weak guard here: a major triad is scored on the
     * root and the fifth, which clear it between them with no third in the run at
     * all. So the count is overruled where the run states its minor third
     * plainly ({@link #MINOR_OVERRULES_THE_COUNT}, #583) — the cost of this rule
     * is a whole chord where the seventh's is a colour, and a borrowed minor
     * fourth is ordinary in pop. It is the run's own fit that says so and not
     * the residual: measured over the corpus, the share of the root's residual
     * a withdrawn run's minor third holds does not separate the true minors from
     * the false, in either direction.
     */
    private static void decideThirdsPerRoot(int[] path, int[] out, List<Template> templates,
                                            Chroma qualityChroma, double[][] significance) {
        int[] minorThirds = new int[12];
        int[] beats = new int[12];
        for (int state : out) {
            Template template = templates.get(state);
            if (template.quality() == ChordQuality.NONE) {
                continue;
            }
            beats[template.rootPitchClass()]++;
            if (template.quality().isMinorish()) {
                minorThirds[template.rootPitchClass()]++;
            }
        }

        int i = 0;
        while (i < path.length) {
            Template start = templates.get(path[i]);
            int j = i;
            while (j < path.length && sameChord(templates.get(path[j]), start)) {
                j++;
            }
            int root = templates.get(out[i]).rootPitchClass();
            double[] run = sum(qualityChroma, i, j);
            if (templates.get(out[i]).quality().isMinorish()
                    && minorThirds[root] < THIRD_MUST_HOLD_FOR * beats[root]
                    && qualityScore(run, templates.get(out[i]), significance[i])
                            < MINOR_OVERRULES_THE_COUNT) {
                int major = bestQuality(run, templates, root,
                        quality -> !quality.isMinorish(), significance[i]);
                if (major >= 0) {
                    for (int frame = i; frame < j; frame++) {
                        out[frame] = major;
                    }
                }
            }
            i = j;
        }
    }

    /**
     * Share of a root's beats that decides the seventh for all of them. Only
     * {@link #decideSeventhsPerRoot} reads it. A half — "a minority" one way,
     * "most of them" the other — is a definition rather than a fitted
     * constant, read strictly on both sides so an even split leaves each run
     * as it read. The synthetic corpus stands well apart on either side of it
     * ({@code tools/score-synthetic.py}), so the rule is not delicate.
     */
    private static final double SEVENTH_MUST_HOLD_FOR = 0.5;

    /**
     * Settles the minor seventh across every run on a root, in place:
     * withdrawn from all of them where the recording as a whole does not
     * support it, added to the minor triads among them where it does.
     * {@link #chooseQualities} weighs each run against its own chroma alone,
     * so a run clearing the level by a hair keeps the seventh however the rest
     * of the recording reads (#446) and one missing by a hair loses it (#479).
     *
     * <p><b>The prior is that a chord recurs and its seventh is a property of
     * the chord, not of the bar</b> — one count read in both directions, so
     * the two halves are the same rule rather than two thresholds that could
     * drift apart. Counting across runs is orthogonal to how strong the
     * evidence must be, which is #274's axis and deliberately left alone. The
     * cost, both ways, is a quality sounded once in a piece that otherwise
     * states the other; {@code tools/baselines/score-samples.txt} carries both
     * sides.
     *
     * <p><b>The withdrawal argmax is over triads only</b> — ranking against
     * the dominant seventh, which differs only in the third, turns "the
     * recording does not hold this seventh" into a flip of the third. <b>Only
     * a minor triad gains one</b>, for the same reason read the other way: a
     * count of sevenths is no evidence about a third. Adding is held to
     * {@link #bestQuality}'s floor too. Where no triad beats a flat chroma the
     * decoder's answer stands — which may be a dominant seventh — read from
     * {@code path}, the only array still holding it.
     *
     * <p><b>A half-diminished run is counted and never itself changed</b>
     * ({@link #carriesMinorSeventh}): it is the same seventh on the same root,
     * so it is evidence in the count — left out, a root reading {@code m7b5}
     * for part of a recording could fall under the half and lose sevenths the
     * recording states plainly — while withdrawing one would be a statement
     * about its diminished fifth, which a count of sevenths does not make.
     *
     * <p><b>A sixth run is not counted at all</b> ({@link #statesASixth}), on
     * either side. Counting it in the total alone would make it a vote against
     * the seventh on its root, and a vote that cannot be right: the withdrawal
     * it triggers drops the other runs to <em>triads</em>, so a recording that
     * really states sixths would still not be labelled with them. What this
     * does not fix is the count's own edge — a beat that used to vote for the
     * seventh and now states a sixth leaves the numerator too, and a root
     * sitting exactly at {@link #SEVENTH_MUST_HOLD_FOR} turns on it (#548).
     *
     * <p>Grouping either array gives the same runs, since {@link #sameChord}
     * reads only the root and nothing here changes one.
     */
    private static void decideSeventhsPerRoot(int[] path, int[] out,
                                              List<Template> templates,
                                              Chroma qualityChroma,
                                              double[][] significance) {
        int[] sevenths = new int[12];
        int[] beats = new int[12];
        for (int state : out) {
            Template template = templates.get(state);
            if (template.quality() == ChordQuality.NONE || statesASixth(template.quality())) {
                continue;
            }
            beats[template.rootPitchClass()]++;
            if (carriesMinorSeventh(template.quality())) {
                sevenths[template.rootPitchClass()]++;
            }
        }

        int i = 0;
        while (i < path.length) {
            Template start = templates.get(path[i]);
            int j = i;
            while (j < path.length && sameChord(templates.get(path[j]), start)) {
                j++;
            }
            int root = templates.get(out[i]).rootPitchClass();
            ChordQuality quality = templates.get(out[i]).quality();
            if (quality == ChordQuality.MINOR_SEVENTH
                    && sevenths[root] < SEVENTH_MUST_HOLD_FOR * beats[root]) {
                int fallback = bestQuality(sum(qualityChroma, i, j), templates, root,
                        triad -> triad.intervals().length == 3, significance[i]);
                // No triad beat a flat chroma: the decoder's answer stands.
                for (int frame = i; frame < j; frame++) {
                    out[frame] = fallback >= 0 ? fallback : path[frame];
                }
            } else if (quality == ChordQuality.MINOR
                    && sevenths[root] > SEVENTH_MUST_HOLD_FOR * beats[root]) {
                int seventh = indexOf(templates, root, ChordQuality.MINOR_SEVENTH);
                if (qualityScore(sum(qualityChroma, i, j), templates.get(seventh),
                        significance[i]) > flatScore(templates.get(seventh))) {
                    for (int frame = i; frame < j; frame++) {
                        out[frame] = seventh;
                    }
                }
            }
            i = j;
        }
    }

    /**
     * Whether {@code quality} states a minor seventh over a minor third, which
     * is what {@link #decideSeventhsPerRoot} counts.
     *
     * <p>The minor seventh and the half-diminished, and not the dominant
     * seventh, whose flat seventh sits over a major third: a count including it
     * would have a root read {@code C7} through most of a recording adding the
     * seventh to its {@code Cm} runs, which is a question about the third and
     * not about the seventh.
     */
    private static boolean carriesMinorSeventh(ChordQuality quality) {
        boolean minorThird = false;
        boolean minorSeventh = false;
        for (int interval : quality.intervals()) {
            minorThird |= interval == 3;
            minorSeventh |= interval == 10;
        }
        return minorThird && minorSeventh;
    }

    /**
     * Whether {@code quality} states a sixth where a seventh would go: a
     * four-note chord declaring no seventh, which is what {@link
     * ChordQuality#hasSeventh()} exists to distinguish, since nine semitones is
     * the sixth of a {@code 6} chord and the diminished seventh of a
     * {@code dim7}. Four notes and no seventh is the sixths and nothing else in
     * this vocabulary; it is a reading of the enum, not a definition of the
     * chord.
     */
    private static boolean statesASixth(ChordQuality quality) {
        return !quality.hasSeventh() && quality.intervals().length == 4;
    }

    /** Whether {@code quality} states a major seventh, eleven semitones up. */
    private static boolean statesAMajorSeventh(ChordQuality quality) {
        for (int interval : quality.intervals()) {
            if (interval == 11) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a candidate's major seventh is evidence here: its own degree
     * removes {@code share} of the residual the root removes, and there is a
     * residual to read at all.
     *
     * <p><b>Closed where nothing measured it</b>, unlike the other residual
     * tests, which credit a note the chroma carries when no ablation was
     * handed over. This is the only gated template in {@link #DECODED}, so it
     * is the only one that can move a root — and it is separated from the triad
     * it contains by a degree the root's own fifth partial manufactures, which
     * is exactly the reading a chroma cannot make. Which is why a root the fit
     * does not need is closed too, and not left to the comparison: against a
     * root that removes nothing, every share is cleared by every value,
     * including the all-zero answer {@link PitchClassAblation} gives for spans
     * holding nothing to fit.
     */
    private static boolean majorSeventhSounds(double[] significance, int root, double share) {
        return significance != null
                && significance[root] > 0
                && significance[Math.floorMod(root + 11, 12)] >= share * significance[root];
    }

    /** The template for {@code quality} on {@code root}. */
    private static int indexOf(List<Template> templates, int root, ChordQuality quality) {
        for (int t = 0; t < templates.size(); t++) {
            if (templates.get(t).rootPitchClass() == root
                    && templates.get(t).quality() == quality) {
                return t;
            }
        }
        throw new IllegalStateException("no template for " + quality + " on root " + root);
    }

    /**
     * The best-scoring candidate on {@code root} that also beats a flat chroma,
     * or -1 if none does.
     *
     * @param admissible which qualities the argmax may choose between. A
     *     fallback that means "drop back to a triad" asks for three notes rather
     *     than for {@link ChordQuality#hasSeventh()}, which is declared per
     *     constant and is false for the sixths — four-note chords that would
     *     otherwise slip through it. Never asked of {@code NONE}, which has no
     *     intervals at all.
     */
    private static int bestQuality(double[] summed, List<Template> templates, int root,
                                   java.util.function.Predicate<ChordQuality> admissible,
                                   double[] significance) {
        int chosen = -1;
        double best = 0;
        for (int t = 0; t < templates.size(); t++) {
            Template candidate = templates.get(t);
            if (candidate.quality() == ChordQuality.NONE
                    || candidate.rootPitchClass() != root
                    || !admissible.test(candidate.quality())
                    // Reported only on the residual's evidence, like the
                    // decoder's own choice of it: see majorSeventhSounds.
                    || (significance == null && statesAMajorSeventh(candidate.quality()))) {
                continue;
            }
            double score = qualityScore(summed, candidate, significance);
            if (score > best && score > flatScore(candidate)) {
                best = score;
                chosen = t;
            }
        }
        return chosen;
    }

    /**
     * How well a candidate explains a run, for the quality decision only. The
     * cosine the decoder uses, with one change: <b>a minor-third candidate is
     * scored on its notes' mass less the major third the root cannot account
     * for</b> ({@link #ROOT_EXPLAINS_MAJOR_THIRD} is the whole of the tuning);
     * a major-third candidate is scored exactly as {@link #cosine} scores it.
     *
     * <p>Needed the moment a minor seventh joins the vocabulary: it and the
     * dominant seventh differ in nothing but the third, so the argmax reduces
     * to "is the minor third louder than the major third" — and on a blues
     * comping riff it is, so dominant sevenths came back minor. One-sided
     * because the root's own fifth partial <em>is</em> the major third, so
     * some is there whenever the root is played and none of it is evidence;
     * and a minor third over a dominant is the commonest colour in blues,
     * while a major third over a minor chord is not idiomatic.
     *
     * <p><b>Not applied to the decoder</b>, where it would change which root
     * wins rather than which quality — measured there, it trades root accuracy
     * for quality, and roots are the column the rest of the chart hangs on
     * (#274 carries that question).
     *
     * <p><b>Where the fit's own residual is available, it is asked first
     * whether the major third is there at all</b>, and where it is not the
     * third is no evidence — neither for a major candidate nor against a minor
     * one. It stays in the chroma's norm, which every candidate on the run
     * divides by alike. The subtraction above cannot reach that case: it only
     * ever discounts, and a phantom third is routinely larger than the minor
     * third it is competing with, so no share of the root leaves the minor
     * candidate ahead (#527). Nothing here is an absolute level, because how
     * much residual a sounding note removes is a property of the production —
     * a dense mix leaves its real thirds where a sparse one leaves its
     * phantoms; both halves of the test are comparisons within the run, one
     * against the other third and one against the root
     * ({@link #PHANTOM_THIRD_SHARE_OF_ROOT}). It is one-sided for the same
     * reason the subtraction is: partial 5 of the root is the major third and
     * no partial of it is the minor third, so only one of the two can be
     * manufactured. What the minor third is asked instead is whether the fit
     * needs it at all ({@link #MINOR_THIRD_SHARE_OF_ROOT}) — a different
     * question, and the one that keeps a run holding neither third from going
     * minor on noise. See {@link PitchClassAblation} for the register the
     * residual is read over against the register this chroma is.
     *
     * <p><b>The same residual decides whether a sixth, a diminished fifth or a
     * major seventh is there</b> ({@link #ADDED_NOTE_SHARE_OF_ROOT},
     * {@link #MAJOR_SEVENTH_SHARE_OF_ROOT}), and one that is not counts for
     * nothing — which leaves the candidate scored on the triad's own notes over
     * a larger norm, so the chord it competes with wins. That is what ranks the
     * qualities #287 adds on evidence rather than on which extra note is
     * louder.
     */
    private static double qualityScore(double[] chroma, Template template,
                                       double[] significance) {
        int root = template.rootPitchClass();
        int majorThird = Math.floorMod(root + 4, 12);
        double majorThirdMass = chroma[majorThird];
        if (significance != null
                && significance[majorThird] < significance[Math.floorMod(root + 3, 12)]
                && significance[majorThird]
                        < PHANTOM_THIRD_SHARE_OF_ROOT * significance[root]) {
            majorThirdMass = 0;
        }
        // Nine semitones above the root is a sixth here and a diminished
        // seventh in a dim7 (ChordQuality.hasSeventh), and the two are not the
        // same claim about the recording — so the note is looked up as this
        // candidate's own degree rather than as a pitch class.
        int sixth = statesASixth(template.quality()) ? Math.floorMod(root + 9, 12) : -1;
        int diminishedFifth = Math.floorMod(root + 6, 12);
        int minorThird = Math.floorMod(root + 3, 12);
        int majorSeventh = statesAMajorSeventh(template.quality())
                ? Math.floorMod(root + 11, 12) : -1;
        double mass = 0;
        double energy = 0;
        for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
            energy += chroma[pitchClass] * chroma[pitchClass];
            if (template.profile()[pitchClass] > 0) {
                double value = pitchClass == majorThird ? majorThirdMass : chroma[pitchClass];
                if (significance != null
                        && (pitchClass == sixth || pitchClass == diminishedFifth)
                        && significance[pitchClass]
                                < ADDED_NOTE_SHARE_OF_ROOT * significance[root]) {
                    value = 0;
                }
                if (significance != null && pitchClass == minorThird
                        && significance[minorThird]
                                < MINOR_THIRD_SHARE_OF_ROOT * significance[root]) {
                    value = 0;
                }
                if (significance != null && pitchClass == majorSeventh
                        && !majorSeventhSounds(significance, root,
                                MAJOR_SEVENTH_SHARE_OF_ROOT)) {
                    value = 0;
                }
                mass += value;
            }
        }
        if (template.quality().isMinorish()) {
            mass -= Math.max(0, majorThirdMass - ROOT_EXPLAINS_MAJOR_THIRD * chroma[root]);
        }
        return energy > 0
                ? mass / (Math.sqrt(energy) * Math.sqrt(template.quality().intervals().length))
                : 0;
    }

    /**
     * What a candidate scores against a chroma carrying no harmonic
     * information — a flat one. Asked of {@link #qualityScore} rather than
     * derived, so there is no second expression to keep in step; a larger
     * template scores higher on noise by size alone, and a minor-third one
     * lower, moving with {@link #ROOT_EXPLAINS_MAJOR_THIRD} — the floor has to
     * be the bar each candidate is actually scored against.
     *
     * <p><b>The residual test is deliberately not applied here</b>, though the
     * subtraction is. The difference is what each of them is a statement
     * about: the subtraction is a rule about the candidate, which a null model
     * of that candidate must carry, while the residual test is a reading of
     * this recording, which a chroma carrying no information cannot have made.
     * Letting it through makes the floor move with the audio, and in the wrong
     * direction — a vetoed major candidate's floor falls and a minor
     * candidate's rises, so the veto ends up admitting the chord it was meant
     * to rule out.
     * {@code ChordEstimationTest#theFloorDoesNotMoveWithTheResidual} is the run
     * where that shows.
     *
     * <p>Used as a floor, and <b>a floor rules out only candidates that fit
     * worse than noise — not a bad fit that is still better than noise</b>. So
     * the seventh goes on winning on weak evidence, which is the cost of
     * keeping the size bias in the ranking.
     * {@code ChordEstimationTest#weakTrebleEvidenceStillFavoursTheSeventh}
     * pins it; #274 carries the sweep and the cures measured against it.
     */
    private static double flatScore(Template template) {
        double[] flat = new double[12];
        java.util.Arrays.fill(flat, 1.0 / 12);
        return qualityScore(flat, template, null);
    }

    /**
     * The per-beat term each root's chord states take, or null if there is no
     * bass register to read: each root's share of the bass energy over the
     * {@link #BASS_ROOT_BEATS} window, <b>less the largest share any root
     * holds there</b>. So the term is zero for the root the bass names and
     * negative for every other: it ranks roots against each other and never
     * argues that a chord is sounding at all — a positive term would silently
     * lower {@link #NO_CHORD_SIMILARITY} by however peaked the bass is, and a
     * beat where the bass dropped out votes its noise floor at full strength.
     * Where the bass says nothing every share is equal and every term is zero.
     *
     * <p>The cost of subtracting the largest share: a chord on any
     * <em>other</em> root is pushed toward silence by up to
     * {@link #BASS_ROOT_WEIGHT}, so weakly backed chroma under a bass naming a
     * different root can go to no-chord where it used to be named. Neither
     * direction is the safe one to err in (#185 one way, #446 the other); the
     * size of the trade is small on the scored corpus, and where the line
     * really belongs is #195.
     */
    private static double[][] bassRootPrior(Chroma bass, int frames) {
        if (bass == null) {
            return null;
        }
        double[][] out = new double[frames][12];
        for (int frame = 0; frame < frames; frame++) {
            double total = 0;
            for (int f = Math.max(0, frame - BASS_ROOT_BEATS);
                    f <= Math.min(frames - 1, frame + BASS_ROOT_BEATS); f++) {
                for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                    // Clamped, so that a hand-built chroma carrying a negative
                    // magnitude cannot make a share that is not one. Chroma
                    // checks its values are finite and not that they are
                    // positive, and everything below divides by this total.
                    double value = Math.max(0, bass.vectors()[f][pitchClass]);
                    out[frame][pitchClass] += value;
                    total += value;
                }
            }
            double best = 0;
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                if (total > 0) {
                    out[frame][pitchClass] /= total;
                }
                best = Math.max(best, out[frame][pitchClass]);
            }
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                out[frame][pitchClass] -= best;
            }
        }
        return out;
    }

    /** Chroma summed over the beats {@code [from, to)}. */
    private static double[] sum(Chroma chroma, int from, int to) {
        double[] out = new double[12];
        for (int frame = from; frame < to; frame++) {
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                out[pitchClass] += chroma.vectors()[frame][pitchClass];
            }
        }
        return out;
    }

    /**
     * Whether two states are the same chord for the purpose of grouping beats:
     * the same root, or both no-chord.
     *
     * <p>Quality is deliberately not part of it. A run the decoder split into
     * "C then C7" is one chord whose seventh was audible for part of it, and
     * deciding its quality twice from half the evidence each time is the defect
     * this grouping exists to avoid.
     *
     * <p>Its cost is a suspension that resolves onto its own root: it shares a
     * run with the chord it resolves to, and one run carries one label (#650).
     */
    private static boolean sameChord(Template a, Template b) {
        if (a.quality() == ChordQuality.NONE || b.quality() == ChordQuality.NONE) {
            return a.quality() == b.quality();
        }
        return a.rootPitchClass() == b.rootPitchClass();
    }

    /**
     * {@link #DECODED} on all twelve roots, plus a no-chord state, and after
     * them the templates only the quality decision may choose.
     *
     * <p>The sevenths are not a luxury: a triad-only vocabulary asked to
     * explain a seventh chord with three notes prefers an unrelated root
     * altogether, not merely the wrong label. The confusion they introduce is
     * kept in check because a four-note template only wins when the seventh is
     * actually present — cosine divides by the template's own norm — and the
     * tier-0/1 fixtures, pure triads, still come back as triads
     * ({@code ChordEstimationTest}, {@code EndToEndIT}).
     */
    private static List<Template> buildTemplates() {
        List<Template> templates = new ArrayList<>(DECODED_STATES + 12 * QUALITY_ONLY.length);
        for (ChordQuality quality : DECODED) {
            addTemplates(templates, quality);
        }
        // No-chord carries no profile: it is scored at a fixed level rather than
        // matched, so the array here is never read. See NO_CHORD_SIMILARITY for
        // why a flat profile was the wrong model.
        templates.add(new Template(0, ChordQuality.NONE, new double[12]));
        // After the no-chord state, so the decoder's states are the list's first
        // DECODED_STATES entries and Viterbi can be handed a prefix.
        for (ChordQuality quality : QUALITY_ONLY) {
            addTemplates(templates, quality);
        }
        if (templates.get(DECODED_STATES - 1).quality() != ChordQuality.NONE) {
            throw new IllegalStateException("the decoder's states are the first "
                    + DECODED_STATES + " templates and the last of them is no-chord");
        }
        return templates;
    }

    private static void addTemplates(List<Template> templates, ChordQuality quality) {
        for (int root = 0; root < 12; root++) {
            double[] profile = new double[12];
            for (int interval : quality.intervals()) {
                profile[Math.floorMod(root + interval, 12)] = 1;
            }
            normalise(profile);
            templates.add(new Template(root, quality, profile));
        }
    }

    /**
     * Raw cosine similarity of every template for every frame. Not what the
     * decoder scores on, which is {@link #emissions}.
     */
    private static double[][] similarities(Chroma chroma, List<Template> templates) {
        int frames = chroma.frameCount();
        double[][] out = new double[frames][templates.size()];

        for (int frame = 0; frame < frames; frame++) {
            double[] vector = chroma.vectors()[frame];
            double energy = 0;
            for (double value : vector) {
                energy += value;
            }

            for (int t = 0; t < templates.size(); t++) {
                Template template = templates.get(t);
                boolean noChord = template.quality() == ChordQuality.NONE;
                if (energy < SILENCE_THRESHOLD) {
                    // Nothing sounding: only the no-chord state is plausible.
                    out[frame][t] = noChord ? 1.0 : 1e-9;
                } else if (noChord) {
                    // A level to clear, not a shape to match.
                    out[frame][t] = NO_CHORD_SIMILARITY;
                } else {
                    // Raw cosine, in 0 to 1. Cosine rather than a dot product so
                    // a loud frame is not automatically a better match than a
                    // quiet one. Sharpening happens once, in estimate().
                    out[frame][t] = cosine(vector, template.profile());
                }
            }
        }
        return out;
    }

    /**
     * What the decoder scores each template on: {@link #similarities}, with a
     * major seventh whose own degree the fit does not need scored without it
     * ({@link #DECODED_MAJOR_SEVENTH_SHARE_OF_ROOT}).
     *
     * <p><b>Separate from the similarity rather than folded into it</b>,
     * because a caller reads that as the confidence in the chord it was given,
     * and a template scored on less than itself would report a chord the
     * estimator is sure of as a doubtful one — reaching, among others, the
     * chart floor {@code PlayableMelody} breaks melody ties on.
     *
     * <p><b>The residual is read once per beat here</b>, where
     * {@link #chooseQualities} reads it once per chord. A run does not exist
     * until this has been decoded, so there is no cheaper span to ask about;
     * {@code ChordEstimationTest#theAblationIsAskedOncePerRunAndOncePerBeat}
     * pins what is asked.
     */
    private static double[][] emissions(Chroma chroma, List<Template> templates,
                                        double[][] similarity, PitchClassAblation ablation) {
        double[][] out = new double[similarity.length][];
        for (int frame = 0; frame < similarity.length; frame++) {
            out[frame] = similarity[frame].clone();
            double[] vector = chroma.vectors()[frame];
            double energy = 0;
            for (double value : vector) {
                energy += value;
            }
            // A silent frame's similarities are the silence answer rather than
            // a match to gate, and the ablation is an expensive thing to ask
            // about nothing.
            if (energy < SILENCE_THRESHOLD) {
                continue;
            }
            double[] significance =
                    ablation == null ? null : ablation.significanceOver(frame, frame + 1);
            for (int t = 0; t < templates.size(); t++) {
                Template template = templates.get(t);
                if (statesAMajorSeventh(template.quality())
                        && !majorSeventhSounds(significance, template.rootPitchClass(),
                                DECODED_MAJOR_SEVENTH_SHARE_OF_ROOT)) {
                    out[frame][t] = cosineWithout(vector, template.profile(),
                            Math.floorMod(template.rootPitchClass() + 11, 12));
                }
            }
        }
        return out;
    }

    /**
     * Viterbi decoding over a chain whose only structure is a preference for
     * staying put. This is what turns a chattering frame-wise argmax into
     * something that looks like a chord chart.
     *
     * <p>Decodes over the <em>first</em> {@code states} columns and ignores the
     * rest, which is how {@link #QUALITY_ONLY} stays out of the decoder. The
     * transition prior is 1/(states-1) per alternative, so the columns it
     * ignores do not change what it decides either.
     */
    private static int[] viterbi(double[][] logLikelihood, int states) {
        int frames = logLikelihood.length;
        double stay = Math.log(SELF_TRANSITION);
        double move = Math.log((1 - SELF_TRANSITION) / (states - 1));

        double[][] score = new double[frames][states];
        int[][] previous = new int[frames][states];

        System.arraycopy(logLikelihood[0], 0, score[0], 0, states);

        for (int frame = 1; frame < frames; frame++) {
            // The transition matrix has only two distinct values, so the best
            // predecessor is either the previous best overall or the same state.
            // Finding it once per frame keeps this linear in the state count
            // rather than quadratic.
            int bestPrevious = 0;
            for (int s = 1; s < states; s++) {
                if (score[frame - 1][s] > score[frame - 1][bestPrevious]) {
                    bestPrevious = s;
                }
            }
            for (int s = 0; s < states; s++) {
                double staying = score[frame - 1][s] + stay;
                double moving = score[frame - 1][bestPrevious] + move;
                if (staying >= moving) {
                    score[frame][s] = staying + logLikelihood[frame][s];
                    previous[frame][s] = s;
                } else {
                    score[frame][s] = moving + logLikelihood[frame][s];
                    previous[frame][s] = bestPrevious;
                }
            }
        }

        int last = 0;
        for (int s = 1; s < states; s++) {
            if (score[frames - 1][s] > score[frames - 1][last]) {
                last = s;
            }
        }
        int[] path = new int[frames];
        path[frames - 1] = last;
        for (int frame = frames - 1; frame > 0; frame--) {
            path[frame - 1] = previous[frame][path[frame]];
        }
        return path;
    }

    /** Merges runs of identical states into chord spans. */
    private static ChordProgression toProgression(int[] path, List<Template> templates,
                                                  List<Double> beatTimes,
                                                  double[][] similarity) {
        List<Chord> chords = new ArrayList<>();
        int spanStart = 0;

        for (int i = 1; i <= path.length; i++) {
            boolean boundary = i == path.length || path[i] != path[spanStart];
            if (!boundary) {
                continue;
            }
            Template template = templates.get(path[spanStart]);
            double startSeconds = beatTimes.get(spanStart);
            double endSeconds = beatTimes.get(Math.min(i, beatTimes.size() - 1));
            if (endSeconds <= startSeconds) {
                spanStart = i;
                continue;
            }

            // Confidence is the raw similarity of the reported template,
            // averaged over the span, against the chroma the root was decoded
            // from — the number answers "how well does this chord explain the
            // mix". A quality found on the treble's evidence (#208) therefore
            // reads lower than the triad it replaced; #201 reworks what is
            // reported.
            double total = 0;
            for (int frame = spanStart; frame < i; frame++) {
                total += similarity[frame][path[spanStart]];
            }
            // Rescaled from cosine's useful range, whose bottom even an
            // unrelated chord clears. A no-chord beat won on
            // NO_CHORD_SIMILARITY lands below it and contributes zero; one won
            // on SILENCE_THRESHOLD contributes full confidence, and a merged
            // no-chord span averages the two, so the number cannot say which
            // kind of "no chord" it is (#201).
            double mean = total / (i - spanStart);
            double confidence = Math.clamp((mean - 0.65) / 0.35, 0, 1);

            chords.add(template.quality() == ChordQuality.NONE
                    ? Chord.noChord(startSeconds, endSeconds,
                            dev.olivelli.musicwizard.core.model.Confidence.clamped(confidence))
                    : Chord.ofSeconds(spell(template.rootPitchClass()), template.quality(),
                            startSeconds, endSeconds,
                            dev.olivelli.musicwizard.core.model.Confidence.clamped(confidence)));
            spanStart = i;
        }

        double mean = chords.stream()
                .mapToDouble(c -> c.confidence().value())
                .average().orElse(0);
        return new ChordProgression(chords,
                dev.olivelli.musicwizard.core.model.Confidence.clamped(mean));
    }

    /**
     * A default spelling for a pitch class, preferring sharps. Provisional on
     * purpose: how a root is actually written is decided over the whole
     * progression by {@code ChordSpeller} in {@code mw-arrange} (#227).
     * Anything reading these spellings as a decision is reading a table, which
     * is what put A sharp on a real B flat chart (#190).
     */
    static PitchSpelling spell(int pitchClass) {
        NoteLetter[] letters = {NoteLetter.C, NoteLetter.C, NoteLetter.D, NoteLetter.D,
                NoteLetter.E, NoteLetter.F, NoteLetter.F, NoteLetter.G, NoteLetter.G,
                NoteLetter.A, NoteLetter.A, NoteLetter.B};
        boolean[] sharp = {false, true, false, true, false, false, true, false, true,
                false, true, false};
        int index = Math.floorMod(pitchClass, 12);
        return new PitchSpelling(letters[index],
                sharp[index] ? Accidental.SHARP : Accidental.NATURAL, 4);
    }

    /**
     * The cosine of {@code a} against {@code b} with one pitch class left out of
     * the numerator and both norms kept whole, so a template scored this way is
     * being asked how well the rest of it fits over its own full size.
     */
    private static double cosineWithout(double[] a, double[] b, int skip) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            if (i != skip) {
                dot += a[i] * b[i];
            }
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator > 0 ? dot / denominator : 0;
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator > 0 ? dot / denominator : 0;
    }

    private static void normalise(double[] vector) {
        double sum = 0;
        for (double value : vector) {
            sum += value;
        }
        if (sum > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= sum;
            }
        }
    }
}
