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
 * Recognises chords by matching chroma against templates, then smoothing the
 * result with Viterbi decoding.
 *
 * <p>Frame-by-frame template matching alone chatters badly — it will happily
 * report six different chords across a bar that holds one. The fix is not a
 * better template but a transition model: a hidden Markov chain whose
 * self-transition probability is high, decoded with Viterbi so that changing
 * chord has to be worth the cost. That single addition is worth more than any
 * amount of template tuning.
 *
 * <p>Templates are binary — a chord tone is 1, everything else 0 — which is
 * deliberately crude, and crude turns out not to be the problem. On a real
 * recording with known changes this stage was returning one N.C. span for the
 * whole song (#185). Per-bar root accuracy on {@code samples/gmajorblues.mp3},
 * varying the front end and all three of this class's changes together — the
 * seventh templates, the no-chord level and the emission sharpness, which the
 * left column holds at their pre-#3 values and the right column at their
 * current ones:
 *
 * <pre>
 *                                        all three at     all three at
 *                                        their pre-#3     their current
 *                                        values           values
 *   plain chroma                            0.0%             58.9%
 *   {@link NnlsChroma} combined             0.0%             86.6%
 * </pre>
 *
 * <p>Down the first column, a better front end on its own is worth <em>nothing
 * whatever</em>: both cells are one N.C. span covering 99.9% of the recording,
 * bit for bit the same answer, because the flat no-chord template swallows it
 * whichever chroma it is handed. Along the first row, these changes on their own
 * are worth 58.9. And the front end adds 27.7 on top of them, 58.9% to 86.6%,
 * which is the only cell where it is worth anything at all.
 *
 * <p>Not one of the three carries that 58.9 alone, and the decomposition is
 * worth having because two review rounds went wrong for want of it. Over plain
 * chroma: the flat template replaced and nothing else, 17.5%; add the seventh
 * templates, 48.4%; add the sharpness, 58.9%. Over the NNLS fold the same three
 * steps read 28.0%, 80.6% and 86.6%.
 *
 * <p>So the no-chord level is what makes the recording speak at all, the
 * sevenths are the largest single step, and the sharpness is worth ten points
 * that are easy to attribute to one of the other two. This is the one place the
 * decomposition is measured; {@link NnlsChroma} used to restate it and now links
 * here instead, having been corrected three rounds running, and {@code
 * CLAUDE.md} carries a two-line summary that defers here for the rest.
 *
 * <p><strong>Every accuracy figure in this class, and in the sweeps that cite
 * it, was measured on the beat grid as it was before #196.</strong> Chroma is
 * averaged per tracked beat, so removing that grid's 1.9% rate error moved the
 * top cell above from 86.6% to 85.7%. None of the comparisons is in doubt — the
 * differences they rest on are tens of points and the anchor moved by one — but
 * the cells read as current and are not. Re-measuring them is #232; do not
 * subtract the difference from each cell, which would be inventing
 * measurements.
 *
 * <p>The surprising column is the first one, and it is why this class changed
 * at all: #3 called the front end the fix for #185, and measured alone against
 * the estimator it was written for, the front end does not fix it.
 *
 * <p><b>The root and the quality are decided separately</b>, from different
 * chroma and over different spans: the root beat by beat from both registers,
 * the quality once per chord from the treble alone. Why, and what it is worth,
 * is on {@link #estimate(Chroma, Chroma, List)}. The two also read different
 * vocabularies, {@link #DECODED} and {@link #QUALITY_ONLY}, because a template
 * that competes across roots is a different risk from one that cannot move a
 * root at all.
 */
public final class ChordEstimator {

    /**
     * Probability of staying on the same chord between beats.
     *
     * <p>High because chords last for several beats. Lower it and the output
     * chatters; raise it much further and genuine changes are missed.
     *
     * <p>Left at 0.6 after trying to raise it. On {@code samples/gmajorblues.mp3}
     * the accuracy cost is monotone in this value and the chatter benefit is
     * slight, so there is no trade worth making:
     *
     * <pre>
     *   self-transition   per-bar root accuracy   chord spans over 711 s
     *        0.6                 86.6%                    740
     *        0.8                 85.7%                    684
     *        0.9                 85.4%                    642
     *        0.95                83.4%                    604
     *        0.98                82.8%                    565
     * </pre>
     *
     * <p>Worth recording because an earlier revision of this change did set it
     * to 0.9, on a measurement taken before {@link NnlsChroma#combined()} was
     * being folded in the right order. Against the wrong ordering 0.9 improved
     * both columns; against the right one it improves neither enough. The
     * constant is the same as it was, and the reason it is the same is not.
     *
     * <p>740 spans over 314 bars is 2.4 chords a bar, which is far more than
     * this music contains. The chatter is real and is not addressed here: it
     * wants the chart to snap changes to beats or bars, which is a notation
     * decision rather than an estimation one.
     */
    private static final double SELF_TRANSITION = 0.6;

    /**
     * Sharpness of the emission distribution.
     *
     * <p>Needed because cosine similarity is a very flat score: across all the
     * templates it spans roughly 0.5 to 1.0, so the log-likelihood gap between
     * the right chord and the wrong one is small per frame, while the cost of
     * changing chord is log(0.6) against log(0.4/36), a penalty of about 4.0.
     * Left unsharpened the transition prior silently overwhelms the evidence and
     * the decoder sits on one state for the whole recording -- which is exactly
     * what it used to do.
     *
     * <p>Raised from 20 to 50 with the front end change, and measured rather
     * than reasoned. On {@code samples/gmajorblues.mp3}, per-bar root accuracy
     * against the known cycle and the number of chord spans over 711 seconds:
     *
     * <pre>
     *   sharpness   root accuracy   root+quality   spans
     *       20          80.6%          80.6%        440
     *       35          85.4%          85.4%        645
     *       50          86.6%          86.3%        740
     *       80          85.0%          82.8%        837
     *      120          84.4%          81.2%        898
     * </pre>
     *
     * <p>A real peak at 50 rather than a plateau, and the fall on either side
     * has different causes: below it the transition prior is still winning
     * arguments the evidence should win, and above it the evidence is sharp
     * enough to chase noise, which shows up first in the quality column.
     */
    private static final double EMISSION_SHARPNESS = 50.0;

    /**
     * The similarity the no-chord state is credited with, against which every
     * template has to compete.
     *
     * <p>This replaces scoring no-chord as a flat template, which is the defect
     * #185 is about. Cosine against a flat profile is high whenever the chroma
     * is <em>spread</em>, and the chroma of a real mix folded naively is very
     * spread: measured over all 15,305 frames of a 711-second recording, plain
     * chroma scored 0.882 against the flat profile and 0.691 against the best of
     * all twenty-four triads. No chord could beat "no chord", so the whole song
     * came back as one N.C. span.
     *
     * <p>A fixed level says something the flat template cannot: report a chord
     * when some chord actually fits, not when it fits better than a profile that
     * grows stronger the less the frame looks like music.
     *
     * <p>How far above chance 0.60 sits depends on the template, and the same
     * commit that set this level made that dependence worse. Against a genuinely
     * flat chroma a three-note template scores sqrt(3/12) = 0.500 by
     * construction and a four-note one sqrt(4/12) = 0.577, so the headroom is
     * 0.100 for a triad and 0.023 for a seventh. That asymmetry also biases the
     * vocabulary: on a frame carrying no harmony at all, a seventh clears this
     * level before a triad does. It is not visible in the sweep below, whose
     * flat top runs from 0.50 to 0.65, but it is the reason a single cosine
     * level is the wrong shape of model rather than merely an untuned one.
     *
     * <p>This is what carries the change, and by more than the front end does.
     * Holding the chroma fixed at what {@link NnlsChroma} produces and varying
     * only this, per-bar root accuracy on {@code samples/gmajorblues.mp3} is
     * 1.0% with the flat template and 86.6% with a fixed level -- the flat
     * template still swallows 95.8% of the recording as N.C. even on a chroma
     * that NNLS has sharpened. So #3 alone does not fix #185; it needed this
     * too. The level itself is not delicate over its working range: 0.50, 0.60
     * and 0.65 all give exactly the same 86.6%. It falls off a cliff shortly
     * afterwards -- 80.9% at 0.70 with 8.0% N.C., and 20.7% at 0.75 with 77.7%
     * N.C. -- so 0.60 is placed to leave room before that edge rather than
     * because 0.65 measured worse.
     *
     * <p><b>What this does not do, stated because it is easy to assume it
     * does.</b> 0.60 is not, on real material, much of a threshold. Measured
     * over every beat span, the best of the thirty-six templates clears it on
     * 100% of spans of the blues and 98% of a second full-mix recording -- and
     * on plain chroma, 100% and 97.1%. So the state is close to an off switch on
     * both front ends rather than a discriminator, and what actually keeps the
     * two recordings honest is that it fires at all on the quiet passages of the
     * second one (2.1% of its duration). #185's warning that removing no-chord
     * outright would put a chord over every drum fill is therefore only
     * half-answered here: digital silence is caught by
     * {@link #SILENCE_THRESHOLD}, and a quiet or percussive passage is caught
     * only weakly. A no-chord model that reads energy and flatness rather than a
     * cosine level is #195.
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
     * The qualities the Viterbi decoder chooses between, one state per root.
     *
     * <p>Deliberately smaller than the set the quality decision may report. A
     * template competes across <em>roots</em> here, and a four-note template
     * contains a triad on another root: {@code Am7} is {@code C} with an A, and
     * with the minor seventh in this list the decoder answers A wherever a C
     * major triad has any energy on A at all. Measured on the benchmarks that
     * way, per-bar root accuracy falls from 84.1% to 48.4% on {@code
     * samples/gmajorblues.mp3} and from 95.1% to 51.0% on {@code
     * samples/blues-shuffle-a-106bpm.mp3}. {@link #QUALITY_ONLY} is where the
     * seventh goes instead, and it cannot move a root.
     */
    private static final ChordQuality[] DECODED = {
            ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.DOMINANT_SEVENTH};

    /**
     * Qualities the quality decision may report but the decoder may not choose.
     *
     * <p>These carry the same root as one of {@link #DECODED}, so they can only
     * relabel a chord the decoder has already placed, never move it. See
     * {@link #chooseQualities} for what keeps a minor seventh from being
     * reported wherever a dominant one is right, and #287 for the qualities
     * that are not here.
     *
     * <p>What it costs on material that has no sevenths in it is the question
     * #273 is about, and the two plain-triad controls answer it differently.
     * {@code tools/ChordSweep.java profile} labels every span of {@code
     * pop-c-g-am-f-120.mp3} exactly as before — no four-note label anywhere, on
     * a recording where the roots are all found. On {@code
     * pop-am-f-c-g-144.mp3}, where under half the roots are found on its stated
     * grid, four-note labels go from 12 spans of 247 to 35. Whether that is the
     * minor seventh winning on weak evidence or a label on a root that was
     * already wrong, this corpus cannot say; it is the rate #274 exists to
     * bring down.
     */
    private static final ChordQuality[] QUALITY_ONLY = {ChordQuality.MINOR_SEVENTH};

    /** States the decoder chooses between: {@link #DECODED} on every root, plus no-chord. */
    private static final int DECODED_STATES = 12 * DECODED.length + 1;

    /**
     * How much of a chord's major third the root's own fifth partial accounts
     * for, as a share of the root's chroma. Only {@link #qualityScore} reads it.
     *
     * <p>Every cell below is per-bar {@code root+quality} from {@code
     * tools/ChordSweep.java score} with the minor seventh in the vocabulary, so
     * the left-hand column is not a baseline — without a correction of some size
     * the minor seventh cannot be admitted at all. The scored benchmarks that
     * move by more than a bar, and how many of the eleven rows of {@code
     * tools/score-samples.py}'s key table name the right key:
     *
     * <pre>
     *   share    0.00   0.10   0.15   0.20   0.25   0.40   1.00   2.00
     *   fm7-vamp 86.1   74.5   74.5   58.4   58.4   58.4   58.4   58.4
     *   eb7-vamp 59.3   59.3   59.3   59.3   59.3   59.3   39.5    7.8
     *   blues-a  89.4   89.4   89.4   89.4   89.4   89.4   73.5   69.9
     *   pop      91.2   98.2   98.2   98.2   98.2   98.2   98.2   98.2
     *   keys       8     10     10     11     11     11     11     10
     * </pre>
     *
     * <p>Everything holds from 0.20 to 0.40 and this sits inside that. Below it
     * a major third that is only the root's own partial counts against a minor
     * chord: {@code pop-c-g-am-f-120.mp3}, plain triads over a strongly voiced
     * root, loses five of its fourteen A minor bars, and {@code
     * bm-blues-slow.mp3} — a B minor blues — is named B major. Above it the
     * correction stops firing where the minor third really is a colour over a
     * dominant, which is what {@code eb7-vamp-130.mp3} measures.
     *
     * <p>The left column is worth reading twice, because it is the version of
     * this change that scores best on the chord table and is wrong: it buys
     * {@code fm7-vamp-110.mp3} twenty-eight points by taking the mode away from
     * two recordings that state one plainly.
     */
    private static final double ROOT_EXPLAINS_MAJOR_THIRD = 0.25;

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
     * another.
     *
     * <p>The two questions want different evidence, which is #208. The root is
     * best read from both registers added together: {@link NnlsChroma#combined()}
     * measures the bass as worth tens of points of root accuracy, because it is
     * where a root is actually played. The quality is best read from the treble
     * alone, because that same bass is what destroys it — a bass part states the
     * root and little else, so adding it in scales up the root's share of the
     * chroma by an amount that depends on how loud the bass was mixed, and the
     * chord's own colour is what gets scaled down.
     *
     * <p>{@code tools/ChordSweep.java profile} prints the measurement, per
     * register: the mean chroma above each recording's decoded root, and from it
     * the share of the root-third-fifth mass that the flat seventh carries. A
     * binary four-note template beats the <em>major</em> triad on the same root
     * exactly when that share clears 2/sqrt(3) - 1.
     *
     * <p>Read the treble column and the five benchmarks whose chords are
     * dominant sevenths all clear the level, while the two whose chords are
     * plain triads sit far under it, with nothing in between. Read the combined
     * column and two of those five fall below. So the flat seventh is in the
     * chroma on all of them, and the discriminator was being asked about a
     * vector the bass had reweighted.
     *
     * <p>The bass column says why, and its root row says it plainest: that
     * register puts under a fifth of its energy on the root pitch class on
     * {@code gmajorblues.mp3} and three fifths of it on {@code
     * blues-e-90bpm.mp3}. How loud the bass sits in the mix is an arrangement
     * decision, and under one chroma it was deciding whether a dominant seventh
     * got reported as one.
     *
     * <p>Two of the recordings it prints do not belong to this argument, and
     * the threshold is why. It is derived against the major triad, so it says
     * nothing about a chord whose third is minor, which is what {@code
     * fm7-vamp-110.mp3} holds throughout. {@code bossa-cm.mp3} finds its root
     * on too few bars for a mean above it to mean anything.
     *
     * <p>Quality is also decided once per run of beats sharing a root rather
     * than beat by beat, because a chord is one chord for its whole duration and
     * its seventh need not sound on every beat of it. A mean over beats cannot
     * show that; what it shows is where the evidence sits, not how much of it
     * any one span gets. Both halves were needed —
     * {@code samples/eb7-vamp-130.mp3} is five minutes of one chord and moves on
     * the grouping, {@code samples/blues-e-90bpm.mp3} moves on the register.
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
        Objects.requireNonNull(chroma, "chroma");
        Objects.requireNonNull(qualityChroma, "qualityChroma");
        Objects.requireNonNull(beatTimes, "beatTimes");
        if (chroma.frameCount() != qualityChroma.frameCount()) {
            throw new IllegalArgumentException("chroma has " + chroma.frameCount()
                    + " frames and qualityChroma has " + qualityChroma.frameCount()
                    + "; the two describe the same beats");
        }
        if (chroma.frameCount() == 0 || beatTimes.size() < 2) {
            return ChordProgression.empty();
        }

        List<Template> templates = buildTemplates();
        double[][] similarity = similarities(chroma, templates);
        double[][] logLikelihood = new double[similarity.length][templates.size()];
        for (int frame = 0; frame < similarity.length; frame++) {
            for (int t = 0; t < templates.size(); t++) {
                logLikelihood[frame][t] = EMISSION_SHARPNESS
                        * Math.log(Math.max(1e-9, similarity[frame][t]));
            }
        }
        int[] path = viterbi(logLikelihood, DECODED_STATES);
        int[] chosen = chooseQualities(path, templates, qualityChroma);

        // Confidence is reported from the raw similarity, not the sharpened
        // score: the exponent exists to make the decoder behave, and letting it
        // leak into a number a user reads would make every chord look shaky.
        return toProgression(chosen, templates, beatTimes, similarity);
    }

    /**
     * Re-decides each chord's quality over the whole run of beats the decoder put
     * on one root, from the summed chroma of that run.
     *
     * <p>One argmax over every quality available on that root — {@link #DECODED}
     * and {@link #QUALITY_ONLY} both, scored by {@link #qualityScore} — not a
     * triad decision followed by a
     * seventh decision. Deciding the third first and then asking about the
     * seventh was tried and is much worse: the flat seventh is itself evidence
     * for the major third, so a chord whose third is ambiguous between the two
     * templates is resolved by it, and taking the third alone first throws that
     * away before it can be used. On {@code samples/eb7-vamp-130.mp3} the
     * two-stage form calls almost every bar E-flat minor.
     *
     * <p><b>A candidate has to explain the run better than a flat chroma would,
     * or the decoder's own answer stands.</b> The decoder never needed that rule
     * because {@link #NO_CHORD_SIMILARITY} sits above every template's flat
     * score, so a frame carrying no harmony loses to "no chord" before the
     * vocabulary can be biased. This decision reads a different chroma and has
     * no no-chord state to lose to, so it carries the rule itself.
     *
     * <p>It rejects evidence that is <em>worse</em> than noise, not evidence
     * that is merely weak, and {@link #flatScore} is where the difference and
     * its cost are written down.
     *
     * <p>Returns a new state path: same root and same no-chord decisions as the
     * decoder made, with the quality replaced. Feeding this back as a state index
     * rather than as a separate list of qualities keeps everything downstream —
     * span merging, confidence, spelling — reading one array as it did before.
     */
    private static int[] chooseQualities(int[] path, List<Template> templates,
                                         Chroma qualityChroma) {
        int[] out = path.clone();
        int i = 0;
        while (i < path.length) {
            Template start = templates.get(path[i]);
            int j = i;
            while (j < path.length && sameChord(templates.get(path[j]), start)) {
                j++;
            }
            if (start.quality() != ChordQuality.NONE) {
                int chosen = bestQuality(sum(qualityChroma, i, j), templates,
                        start.rootPitchClass(), false);
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
        withdrawMinoritySevenths(path, out, templates, qualityChroma);
        return out;
    }

    /**
     * Share of a root's beats that must carry a minor seventh for any of them to
     * keep it. Only {@link #withdrawMinoritySevenths} reads it.
     *
     * <p>A half, meaning "a minority of the root's beats", which is a definition
     * rather than a fitted constant — and the corpus pins it only loosely.
     * {@code synthetic_samples/pop-axis-g-116.mp3} sets the floor: its E runs
     * carry the seventh on 15 beats of 36, so at or below that share nothing is
     * withdrawn there. {@code samples/fm7-vamp-110.mp3} sets the ceiling: it
     * keeps its sevenths at 0.65 and loses all of them at 0.70. No scored
     * benchmark moves anywhere between.
     */
    private static final double SEVENTH_MUST_HOLD_FOR = 0.5;

    /**
     * Withdraws a minor seventh from every run on a root where the recording as a
     * whole does not support it, in place.
     *
     * <p>{@link #chooseQualities} weighs each run against its own chroma alone,
     * so a run whose flat seventh clears the level by a hair keeps it however the
     * rest of the recording reads. That is what #446 measures: on a package whose
     * grid is known exactly, four of ten {@code Em} bars come back {@code Em7} —
     * two because the melody sounds a D over the triad, two on partial bleed —
     * while the same chord elsewhere reads plain.
     *
     * <p><b>The prior is that a chord recurs and its seventh is a property of the
     * chord, not of the bar.</b> So the seventh is believed on a root where most
     * of that root's beats carry it, and withdrawn where a minority do. This is
     * orthogonal to how strong the evidence has to be, which is what #274's sweep
     * is about and what this deliberately leaves alone: every threshold there was
     * measured to strip {@code samples/fm7-vamp-110.mp3} of its sevenths before it
     * stripped #446's package of its false ones, because on this corpus the false
     * minor sevenths are the stronger per-run evidence. Counting across runs is
     * the axis on which the two separate.
     *
     * <p>What it costs is a seventh sounded once in a piece that otherwise states
     * the triad — the passing {@code Am7} is read {@code Am}. {@code
     * tools/baselines/score-samples.txt} carries both sides of that.
     *
     * <p><b>The argmax it falls back to is over triads only.</b> The one that
     * placed the minor seventh ranks it against the dominant one, which shares
     * its root, fifth and flat seventh, so leaving that candidate in turns "the
     * recording does not hold this seventh" into a flip of the third: measured
     * with it left in, three runs of {@code samples/bm-blues-slow.mp3} — a B
     * minor blues — were given a major third, which is the failure {@link
     * #qualityScore}'s correction exists to stop, and restricting the fallback
     * is worth seven bars of that recording.
     *
     * <p>Where no triad beats a flat chroma either, the run has nothing to say
     * about its own quality and <b>the decoder's answer stands — which may be a
     * dominant seventh</b>, so a withdrawal can still end on one. That is the
     * decoder's reading of the run rather than this pass's, and it is the same
     * deference {@link #chooseQualities} shows when nothing clears the floor; on
     * the scored corpus it is what most of those runs are. {@code path} is the
     * only array still holding it, {@code out} having been overwritten.
     *
     * <p>Grouping either array gives the same runs, since {@link #sameChord}
     * reads only the root and nothing here changes one.
     */
    private static void withdrawMinoritySevenths(int[] path, int[] out,
                                                 List<Template> templates,
                                                 Chroma qualityChroma) {
        int[] sevenths = new int[12];
        int[] beats = new int[12];
        for (int state : out) {
            Template template = templates.get(state);
            if (template.quality() == ChordQuality.NONE) {
                continue;
            }
            beats[template.rootPitchClass()]++;
            if (template.quality() == ChordQuality.MINOR_SEVENTH) {
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
            if (templates.get(out[i]).quality() == ChordQuality.MINOR_SEVENTH
                    && sevenths[root] < SEVENTH_MUST_HOLD_FOR * beats[root]) {
                int fallback = bestQuality(sum(qualityChroma, i, j), templates, root, true);
                // No triad beat a flat chroma, so the decoder's answer stands.
                // Restated here rather than left to chooseQualities because the
                // answer being withdrawn is the quality pass's own, which has
                // already overwritten out; the decoder cannot propose a minor
                // seventh at all, since QUALITY_ONLY is where that lives.
                for (int frame = i; frame < j; frame++) {
                    out[frame] = fallback >= 0 ? fallback : path[frame];
                }
            }
            i = j;
        }
    }

    /**
     * The best-scoring candidate on {@code root} that also beats a flat chroma,
     * or -1 if none does.
     *
     * @param triadsOnly whether to leave out of the argmax any quality that is
     *     not a three-note one. Counted rather than asked of {@link
     *     ChordQuality#hasSeventh()}, which is declared per constant and is false
     *     for the sixths — four-note chords that would otherwise slip into a
     *     fallback whose whole point is to drop back to three, on the day #287
     *     puts one in the vocabulary. Counting also holds without the
     *     {@code NONE} clause above it, which has no intervals at all.
     */
    private static int bestQuality(double[] summed, List<Template> templates, int root,
                                   boolean triadsOnly) {
        int chosen = -1;
        double best = 0;
        for (int t = 0; t < templates.size(); t++) {
            Template candidate = templates.get(t);
            if (candidate.quality() == ChordQuality.NONE
                    || candidate.rootPitchClass() != root
                    || (triadsOnly && candidate.quality().intervals().length != 3)) {
                continue;
            }
            double score = qualityScore(summed, candidate);
            if (score > best && score > flatScore(candidate)) {
                best = score;
                chosen = t;
            }
        }
        return chosen;
    }

    /**
     * How well a candidate explains a run, for the quality decision only.
     *
     * <p>The cosine the decoder uses, with one change: <b>a minor-third
     * candidate is scored on its notes' mass less the major third the root
     * cannot account for.</b> Written out, the score is the mass the chord's own
     * notes carry, less that correction, over {@code |chroma| * sqrt(k)} — which
     * is what cosine against a binary k-note template already is, so a
     * major-third candidate is scored exactly as {@link #cosine} scores it.
     *
     * <p>Needed the moment a minor seventh joins the vocabulary. It and the
     * dominant seventh have the same size and the same root, fifth and flat
     * seventh, so nothing but the third separates them and the argmax reduces to
     * "is the minor third louder than the major third". On {@code
     * samples/eb7-vamp-130.mp3} it is — its comping riff is a tritone a
     * half-step up, which states the minor third of the written chord — and
     * without this correction that recording's dominant sevenths come back as
     * minor sevenths, 56.3% of bars to 2.4%.
     *
     * <p>Two reasons the correction is one-sided rather than a comparison. The
     * fifth partial of the root <em>is</em> the major third, two octaves up, so
     * some major third is there whenever the root is played and none of it is
     * evidence of anything; the minor third has no such source below the
     * nineteenth partial. And a minor third over a dominant chord is the
     * commonest colour in blues and jazz — the blue third, the sharp ninth — so
     * its presence is not evidence against a dominant, while a major third over
     * a minor chord is not idiomatic.
     *
     * <p>{@link #ROOT_EXPLAINS_MAJOR_THIRD} is how much of it the root accounts
     * for, and it is the whole of the tuning here.
     *
     * <p>It also decides the plain major-minor question, which the decision was
     * getting wrong on eight bars of {@code samples/blues-a-90bpm.mp3} before
     * any seventh was involved.
     *
     * <p><b>It is not applied to the decoder</b>, where it would change which
     * root wins rather than which quality. Tried there as well it takes {@code
     * samples/fm7-vamp-110.mp3} from 92.7% of roots to 83.9% and {@code
     * samples/eb7-vamp-130.mp3} from 93.4% to 88.6%, while gaining twenty-three
     * points of the latter's quality column. Roots are the column the rest of
     * the chart hangs on, so that trade is not taken here; it is the shape of
     * question #274 carries.
     */
    private static double qualityScore(double[] chroma, Template template) {
        double mass = 0;
        double energy = 0;
        for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
            energy += chroma[pitchClass] * chroma[pitchClass];
            if (template.profile()[pitchClass] > 0) {
                mass += chroma[pitchClass];
            }
        }
        if (template.quality().isMinorish()) {
            mass -= Math.max(0, chroma[Math.floorMod(template.rootPitchClass() + 4, 12)]
                    - ROOT_EXPLAINS_MAJOR_THIRD * chroma[template.rootPitchClass()]);
        }
        return energy > 0
                ? mass / (Math.sqrt(energy) * Math.sqrt(template.quality().intervals().length))
                : 0;
    }

    /**
     * What a candidate scores against a chroma carrying no harmonic information —
     * a flat one. Asked of {@link #qualityScore} rather than derived, because a
     * second derivation of the same expression is a second thing to keep in
     * step, and the first attempt at one was wrong above a share of 1.
     *
     * <p>It is sqrt(k/12) for a k-note template — 0.500 for a triad on noise and
     * 0.577 for a dominant seventh, so an argmax over the two picks the seventh
     * every time on no evidence whatever, by template size alone; {@link
     * #NO_CHORD_SIMILARITY} names the same asymmetry from the other side. A
     * minor-third template scores less, 0.375 and 0.469 at the share shipped,
     * because a flat chroma carries as much of the major third as of the root
     * and the correction takes off the part the root cannot account for — so
     * these two move with {@link #ROOT_EXPLAINS_MAJOR_THIRD}. Intended: the
     * floor is the bar a candidate clears, and it has to be the bar that
     * candidate is actually scored against.
     *
     * <p>Used as a floor rather than subtracted off, and <b>a floor rules out
     * exactly the candidates that fit worse than noise does — it does not rule
     * out a bad fit that is still better than noise.</b> So the seventh goes on
     * winning on weak evidence, which is the cost of keeping the size bias
     * rather than removing it from the ranking as the textbook cures do.
     * {@code ChordEstimationTest#weakTrebleEvidenceStillFavoursTheSeventh} pins
     * three cells of it, and <b>#274 carries the sweep, the two cures measured
     * against the benchmarks, and why this corpus cannot yet settle it.</b>
     */
    private static double flatScore(Template template) {
        double[] flat = new double[12];
        java.util.Arrays.fill(flat, 1.0 / 12);
        return qualityScore(flat, template);
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
     */
    private static boolean sameChord(Template a, Template b) {
        if (a.quality() == ChordQuality.NONE || b.quality() == ChordQuality.NONE) {
            return a.quality() == b.quality();
        }
        return a.rootPitchClass() == b.rootPitchClass();
    }

    /**
     * Major and minor triads and dominant sevenths on all twelve roots, plus a
     * no-chord state, and after them the templates only the quality decision
     * may choose.
     *
     * <p>The sevenths are not a luxury. On {@code samples/gmajorblues.mp3},
     * whose changes are entirely dominant sevenths, adding them takes per-bar
     * root accuracy from 32.5% to 86.6%, and recall on the C7 and D7 bars from
     * nothing at all -- 0% each -- to 96% and 68%. A triad-only vocabulary does
     * not merely mislabel a seventh as a triad: asked to explain D-F#-A-C with
     * three notes it prefers an unrelated root altogether, which is why the
     * figure without them is far below even the 58.3% that writing G7 in every
     * bar of this particular cycle would score.
     *
     * <p>The confusion this introduces is real and is worth naming, because a
     * dominant seventh shares three of its four notes with the major triad on
     * the same root. Two things keep it in check. A four-note template only wins
     * if the seventh is actually present, since cosine divides by the template's
     * own norm -- a pure major triad scores 1.00 against the major template and
     * 0.87 against the seventh. And the tier-0 and tier-1 fixtures, whose chords
     * are synthesised triads with no seventh in them at all, still come back as
     * triads; {@code ChordEstimationTest} and {@code EndToEndIT} assert exactly
     * that and would fail if a seventh were winning too easily.
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

    /** Raw cosine similarity of every template for every frame. */
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

            // Confidence is how well the reported template matched, averaged
            // over the span and rescaled from cosine similarity into something a
            // reader can interpret.
            //
            // Against the chroma the root was decoded from, not the one the
            // quality was: the number answers "how well does this chord explain
            // the mix", and the mix is both registers. The consequence is that a
            // seventh found on the treble's evidence (#208) reports a lower
            // confidence than the triad it replaced, because it explains the
            // combined chroma less well -- which is exactly why the combined
            // chroma was not asked. Truthful about the fit and misleading about
            // the chord; #201 is where the reported number is being reworked.
            double total = 0;
            for (int frame = spanStart; frame < i; frame++) {
                total += similarity[frame][path[spanStart]];
            }
            // Cosine against a chord template is around 0.65 even for an
            // unrelated chord, so the useful range is roughly 0.65 to 1. Rescaled
            // to span 0 to 1 rather than reporting a number that never drops
            // below two thirds.
            //
            // A no-chord span's confidence is the mean over its beats of two
            // very different scores, and it can land anywhere between them. A
            // beat that won on NO_CHORD_SIMILARITY contributes 0.60, below the
            // bottom of this range, and so contributes zero confidence: nothing
            // was clearly sounding and there is no chord there to be confident
            // about. A beat that won on SILENCE_THRESHOLD contributes 1.0, which
            // is not a stray number attached to a non-statement -- the recording
            // really is silent and "no chord" really is certain.
            //
            // Viterbi merges consecutive no-chord beats into one span, so a
            // silent lead-in running into a quiet passage reports the average of
            // the two, and a reader cannot tell from the number which kind of
            // "no chord" they have. That is a defect in what this reports rather
            // than in what it decides; #201.
            //
            // Two earlier drafts of this comment were wrong about it, each in
            // the way the fix before it had been: the first said a no-chord span
            // always reports zero, which the silence branch falsifies, and the
            // second said it reports one of exactly two values, which merging
            // falsifies. Both stopped at the layer the previous mistake was
            // noticed in.
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
     * A default spelling for a pitch class, preferring sharps.
     *
     * <p>Provisional on purpose, and it carries no intent: which of A sharp and
     * B flat this returns says only that the pitch class is 10. How a root is
     * actually written is decided over the whole progression once it is known,
     * by {@code ChordSpeller} in {@code mw-arrange} (#227), because one chord
     * cannot answer it -- the same pitch class is A sharp in one piece and B
     * flat in another.
     *
     * <p>Anything reading these spellings as a decision is reading a table: that
     * is what put A sharp on a real B flat chart, and it is what {@code
     * PitchSpeller.centreFromChords} still does (#190).
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
