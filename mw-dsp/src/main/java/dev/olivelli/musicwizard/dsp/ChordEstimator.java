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
 * deliberately crude. The known improvement is NNLS approximate transcription,
 * which strips the bass partials that otherwise masquerade as upper chord tones;
 * that is a separate, larger piece of work.
 *
 * <p>The vocabulary is limited to major and minor triads plus "no chord".
 * Recognition accuracy falls off sharply beyond triads, so offering sevenths
 * here would mostly produce confident nonsense.
 */
public final class ChordEstimator {

    /**
     * Probability of staying on the same chord between frames.
     *
     * <p>High because chords last for beats, not frames. Lower it and the
     * output chatters; raise it much further and genuine changes are missed.
     */
    private static final double SELF_TRANSITION = 0.6;

    /**
     * Sharpness of the emission distribution.
     *
     * <p>Needed because cosine similarity is a very flat score: across all
     * twenty-five templates it spans roughly 0.65 to 0.97, so the log-likelihood
     * gap between the right chord and a flat no-chord profile is only about
     * 0.36 per frame. The cost of changing chord is log(0.6) versus
     * log(0.4/24), a penalty of about 3.58. On a sixteen-bar progression that is
     * fifteen changes costing 53.7 against a total likelihood gain of 22.8 --
     * so the decoder correctly concludes that sitting on "no chord" for the
     * whole song scores better, and returns exactly that.
     *
     * <p>Raising the exponent widens the emission range until it is commensurate
     * with the transition costs. This is the temperature of the model, and
     * without it the transition prior silently overwhelms the evidence.
     */
    private static final double EMISSION_SHARPNESS = 20.0;

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
     * Estimates chords over beat-synchronous chroma.
     *
     * @param chroma    beat-synchronous chroma, one vector per inter-beat span
     * @param beatTimes the beat instants those spans lie between
     */
    public static ChordProgression estimate(Chroma chroma, List<Double> beatTimes) {
        Objects.requireNonNull(chroma, "chroma");
        Objects.requireNonNull(beatTimes, "beatTimes");
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

        // Confidence is reported from the raw similarity, not the sharpened
        // score: the exponent exists to make the decoder behave, and letting it
        // leak into a number a user reads would make every chord look shaky.
        return toProgression(path, templates, beatTimes, similarity);
    }

    /** Major and minor triads on all twelve roots, plus a no-chord state. */
    private static List<Template> buildTemplates() {
        List<Template> templates = new ArrayList<>(25);
        for (ChordQuality quality : new ChordQuality[] {ChordQuality.MAJOR, ChordQuality.MINOR}) {
            for (int root = 0; root < 12; root++) {
                double[] profile = new double[12];
                for (int interval : quality.intervals()) {
                    profile[Math.floorMod(root + interval, 12)] = 1;
                }
                normalise(profile);
                templates.add(new Template(root, quality, profile));
            }
        }
        // No-chord is modelled as a flat profile, so it wins only when the
        // chroma itself is flat -- which is what silence and percussion look like.
        double[] flat = new double[12];
        java.util.Arrays.fill(flat, 1.0 / 12);
        templates.add(new Template(0, ChordQuality.NONE, flat));
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
                if (energy < SILENCE_THRESHOLD) {
                    // Nothing sounding: only the no-chord state is plausible.
                    out[frame][t] = template.quality() == ChordQuality.NONE ? 1.0 : 1e-9;
                    continue;
                }
                // Raw cosine, in 0 to 1. Cosine rather than a dot product so a
                // loud frame is not automatically a better match than a quiet
                // one. Sharpening happens once, in estimate().
                out[frame][t] = cosine(vector, template.profile());
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

            // Confidence is how well the winning template matched, averaged over
            // the span and rescaled from cosine similarity into something a
            // reader can interpret.
            double total = 0;
            for (int frame = spanStart; frame < i; frame++) {
                total += similarity[frame][path[spanStart]];
            }
            // Cosine against a triad template is around 0.65 even for an
            // unrelated chord, so the useful range is roughly 0.65 to 1. Rescaled
            // to span 0 to 1 rather than reporting a number that never drops
            // below two thirds.
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
     * <p>Provisional on purpose. Correct spelling depends on the key, which is
     * not known until the chords are, so the key estimator re-spells the
     * progression afterwards.
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
