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
 * <p>The vocabulary is major and minor triads and dominant sevenths on all
 * twelve roots, plus "no chord".
 *
 * <p><b>The root and the quality are decided separately</b>, from different
 * chroma and over different spans: the root beat by beat from both registers,
 * the quality once per chord from the treble alone. Why, and what it is worth,
 * is on {@link #estimate(Chroma, Chroma, List)}.
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
     * <p>Mean chroma over each recording's decoded beats, as the share of the
     * root-third-fifth sum carried by the flat seventh. A binary four-note
     * template beats the three-note one on the same root exactly when that share
     * clears 2/sqrt(3) - 1, or 0.155. Reproduced by {@code
     * tools/ChordSweep.java profile}:
     *
     * <pre>
     *   recording                  chords are    combined   treble   bass
     *   gmajorblues.mp3            sevenths        0.282     0.324   0.201
     *   blues-a-90bpm.mp3          sevenths        0.295     0.346   0.226
     *   blues-shuffle-a-106bpm     sevenths        0.130     0.181   0.050
     *   blues-e-90bpm.mp3          sevenths        0.106     0.227   0.033
     *   eb7-vamp-130.mp3           sevenths        0.191     0.264   0.085
     *   fm7-vamp-110.mp3           minor sevenths  0.138     0.196   0.064
     *   pop-c-g-am-f-120.mp3       plain triads    0.037     0.023   0.083
     *   pop-am-f-c-g-144.mp3       plain triads    0.045     0.046   0.043
     * </pre>
     *
     * <p>In the treble column every recording whose chords carry a seventh
     * clears the level and both recordings whose chords do not sit far under it,
     * with nothing in between. In the combined column three of the six seventh
     * recordings fall below. So the flat seventh is in the chroma on all of
     * them, and the discriminator was being asked about a vector the bass had
     * reweighted.
     *
     * <p>The bass column says why, and its root row says it plainest: that
     * register puts 0.19 of its energy on the root pitch class on {@code
     * gmajorblues.mp3} and 0.60 on {@code blues-e-90bpm.mp3}. How loud the bass
     * sits in the mix is an arrangement decision, and under one chroma it was
     * deciding whether a dominant seventh got reported as one.
     *
     * <p>Quality is also decided once per run of beats sharing a root rather
     * than beat by beat, because a chord is one chord for its whole duration and
     * its seventh need not sound on every beat of it. The table is a mean and so
     * cannot show that; what it shows is where the evidence sits, not how much
     * of it any one span gets. Both halves were needed —
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
        int[] path = viterbi(logLikelihood, templates.size());
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
     * <p>One argmax over the three qualities, not a triad decision followed by a
     * seventh decision. Deciding the third first and then asking about the
     * seventh was tried and is much worse: the flat seventh is itself evidence
     * for the major third, so a chord whose third is ambiguous between the two
     * templates is resolved by it, and taking the third alone first throws that
     * away before it can be used. On {@code samples/eb7-vamp-130.mp3} the
     * two-stage form calls almost every bar E-flat minor.
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
                double[] summed = sum(qualityChroma, i, j);
                int chosen = path[i];
                double best = -1;
                for (int t = 0; t < templates.size(); t++) {
                    Template candidate = templates.get(t);
                    if (candidate.quality() == ChordQuality.NONE
                            || candidate.rootPitchClass() != start.rootPitchClass()) {
                        continue;
                    }
                    double score = cosine(summed, candidate.profile());
                    if (score > best) {
                        best = score;
                        chosen = t;
                    }
                }
                for (int frame = i; frame < j; frame++) {
                    out[frame] = chosen;
                }
            }
            i = j;
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
     */
    private static boolean sameChord(Template a, Template b) {
        if (a.quality() == ChordQuality.NONE || b.quality() == ChordQuality.NONE) {
            return a.quality() == b.quality();
        }
        return a.rootPitchClass() == b.rootPitchClass();
    }

    /**
     * Major and minor triads and dominant sevenths on all twelve roots, plus a
     * no-chord state.
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
        List<Template> templates = new ArrayList<>(37);
        for (ChordQuality quality : new ChordQuality[] {ChordQuality.MAJOR, ChordQuality.MINOR,
                ChordQuality.DOMINANT_SEVENTH}) {
            for (int root = 0; root < 12; root++) {
                double[] profile = new double[12];
                for (int interval : quality.intervals()) {
                    profile[Math.floorMod(root + interval, 12)] = 1;
                }
                normalise(profile);
                templates.add(new Template(root, quality, profile));
            }
        }
        // No-chord carries no profile: it is scored at a fixed level rather than
        // matched, so the array here is never read. See NO_CHORD_SIMILARITY for
        // why a flat profile was the wrong model.
        templates.add(new Template(0, ChordQuality.NONE, new double[12]));
        return templates;
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
