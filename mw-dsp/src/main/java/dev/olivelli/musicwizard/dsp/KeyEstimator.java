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

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
import java.util.Objects;
import java.util.Optional;

/**
 * Names the key from the estimated chords.
 *
 * <p>From the chords rather than from raw chroma, which is the whole point: the
 * chord sequence has already had a transition model applied to it, so a key read
 * off it inherits that smoothing instead of re-deriving a weaker version of it
 * from the same frames.
 *
 * <p>Every one of the twenty-four keys is scored over the progression, and the
 * score is a duration-weighted average of two things a chord can be worth to a
 * key:
 *
 * <ul>
 *   <li><b>fit</b> — the share of the chord's <em>triad</em> tones that lie in
 *       the key's scale;</li>
 *   <li><b>half again</b> when the chord is the key's own tonic chord — see
 *       {@link #TONIC_CHORD_WEIGHT}.</li>
 * </ul>
 *
 * <h2>Why the triad and not the whole chord</h2>
 *
 * <p>A seventh or a sixth is a colour tone, and in the material this project is
 * aimed at it routinely leaves the key: every chord of a blues is a dominant
 * seventh whose flat seventh the key does not contain. Scoring them asks how
 * bluesy the piece is rather than what key it is in. The triad is what makes a
 * chord that chord, so the triad is what is scored, and sevenths are read for
 * one thing only, below.
 *
 * <h2>The relative minor</h2>
 *
 * <p>A key and its relative minor share every diatonic note, so nothing in the
 * scale can separate them; a detector that leans on the scale alone answers the
 * relative major every time, which is wrong on half of pop. Two things separate
 * them here, and only two:
 *
 * <ul>
 *   <li><b>The harmonic-minor dominant.</b> A major or dominant chord on the
 *       fifth degree of a minor key carries that key's raised seventh — the
 *       E major in A minor, the F sharp seventh in B minor. That note is
 *       diatonic to the minor and chromatic in the relative major, so the chord
 *       is scored as fully in the key for the minor and docked for the major.
 *       This is the strongest evidence available and it is why the minor
 *       benchmarks stay minor.</li>
 *   <li><b>Which chord is the tonic chord.</b> An A minor triad is evidence for
 *       A minor and none at all for C major, whatever they share.</li>
 * </ul>
 *
 * <p>The mode is taken from the chord's third, so a suspended chord on the tonic
 * counts for both modes rather than for neither: it is still the tonic.
 *
 * <p>When neither of the two says anything the pair really is undecidable, and
 * {@link #beats} breaks it on a stated prior — which is not the same as leaning
 * on one, since any margin at all outranks it and the tonic confidence reports
 * that the prior was all there was.
 *
 * <h2>What is deliberately not used</h2>
 *
 * <p><b>The opening and closing chord.</b> They are the tie-breakers a musician
 * would reach for, and on a recording they are the two least trustworthy spans
 * in the progression: a fade-in, a count-in, or a tail decoded from almost no
 * signal. A term reading them is evidence about where the decoder ran out of
 * music, not about where the music comes to rest, and a wrong one is worth as
 * much as a right one. What leaving them out costs is real and is stated rather
 * than hidden: a loop that neither begins nor ends on its tonic — as the
 * estimator hears it — leaves nothing to separate the relative pair, and the
 * tonic confidence then reports a coin flip instead of inventing an answer.
 *
 * <p><b>Chord function beyond the tonic.</b> Weighting a chord by how idiomatic
 * its degree is — I and V above vi — is standard and is exactly the bias that
 * makes a detector answer the relative major, since the minor's tonic is the
 * major's vi. It is left out on purpose.
 *
 * <p>Modulation is out of scope (#228): one key is returned for the whole span.
 */
public final class KeyEstimator {

    /** Semitones above the tonic in a major scale. */
    private static final int[] MAJOR_SCALE = {0, 2, 4, 5, 7, 9, 11};

    /**
     * Semitones above the tonic in a natural minor scale.
     *
     * <p>Natural rather than harmonic, so that a minor key's scale is the same
     * seven notes as its relative major's and the two are separated by the
     * evidence named in the class javadoc rather than by a larger scale winning
     * on size.
     */
    private static final int[] NATURAL_MINOR = {0, 2, 3, 5, 7, 8, 10};

    /** Semitones from a tonic to its dominant. */
    private static final int DOMINANT = 7;

    /** How much the key's own tonic chord is worth beyond fitting the scale. */
    private static final double TONIC_CHORD_WEIGHT = 0.5;

    /** Scores this close are one score; see {@link #beats}. */
    private static final double TIE = 1e-9;

    /**
     * The margin between two keys at which the better of them is taken as
     * settled.
     *
     * <p>One third: the score is a duration-weighted average over triads, so a
     * margin of a third is the runner-up failing to explain one tone of every
     * chord in the piece. Nothing above that is more decided than decided.
     */
    private static final double DECISIVE_MARGIN = 1.0 / 3.0;

    /**
     * Confidence in a key signature nothing distinguishes.
     *
     * <p>One in twelve, which is what picking a signature at random is worth.
     */
    private static final double SIGNATURE_BASE = 1.0 / 12.0;

    /**
     * Confidence in a tonic nothing distinguishes.
     *
     * <p>One in two, and not lower: the signature decision has already narrowed
     * the answer to a relative pair, and a piece really is in one of them.
     */
    private static final double TONIC_BASE = 0.5;

    /**
     * The signature conventionally used for each tonic and mode, indexed by
     * pitch class.
     *
     * <p>Fewest accidentals wins, and a tie goes to the flat spelling — the same
     * rule, for the same reason, as {@code PitchSpeller.onLineOfFifths} applies
     * to a note. It is what writes pitch class 3 as E flat minor rather than as
     * D sharp minor, both of which need six.
     */
    private static final int[] MAJOR_SIGNATURES = conventionalSignatures(Mode.MAJOR);
    private static final int[] MINOR_SIGNATURES = conventionalSignatures(Mode.MINOR);

    private KeyEstimator() {
    }

    /**
     * A key, with the two decisions behind it reported separately.
     *
     * <p>Separately because they are not equally reliable and averaging them
     * would hide which one is shaky. Naming the signature is the safe half;
     * choosing which of the relative pair is home is the half that fails, and a
     * reader deciding whether to correct the answer by hand needs to know which
     * of the two to look at. {@code key.confidence()} is their product, since the
     * key is right only if both are.
     *
     * @param key                   the key, spanning the whole requested range
     * @param signatureConfidence   trust in the key signature
     * @param tonicConfidence       trust in this tonic over its relative's
     */
    public record Estimate(Key key, Confidence signatureConfidence, Confidence tonicConfidence) {
        public Estimate {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(signatureConfidence, "signatureConfidence");
            Objects.requireNonNull(tonicConfidence, "tonicConfidence");
        }
    }

    /**
     * The key a progression is in, over a given span.
     *
     * <p>Empty when there is no sounding chord to read: silence and a run of
     * {@code N.C.} name no key, and answering C major for them would be a guess
     * wearing a confidence.
     *
     * @param progression  the estimated chords
     * @param startSeconds when the returned key takes effect
     * @param endSeconds   when it stops
     */
    public static Optional<Estimate> estimate(ChordProgression progression,
                                              double startSeconds, double endSeconds) {
        Objects.requireNonNull(progression, "progression");
        double sounding = 0;
        for (Chord chord : progression.chords()) {
            if (!chord.isNoChord()) {
                sounding += chord.durationSeconds();
            }
        }
        if (sounding <= 0) {
            return Optional.empty();
        }

        double[][] scores = new double[12][2];
        for (int tonic = 0; tonic < 12; tonic++) {
            for (Mode mode : Mode.values()) {
                scores[tonic][mode.ordinal()] =
                        score(progression, tonic, mode) / sounding;
            }
        }

        int bestTonic = 0;
        Mode bestMode = Mode.MAJOR;
        for (int tonic = 0; tonic < 12; tonic++) {
            for (Mode mode : Mode.values()) {
                if (beats(scores, tonic, mode, bestTonic, bestMode)) {
                    bestTonic = tonic;
                    bestMode = mode;
                }
            }
        }

        double best = scores[bestTonic][bestMode.ordinal()];
        int relativeTonic = relativeTonic(bestTonic, bestMode);
        Mode relativeMode = bestMode == Mode.MAJOR ? Mode.MINOR : Mode.MAJOR;
        double relative = scores[relativeTonic][relativeMode.ordinal()];

        // The best key in some other signature: the relative pair share one, so
        // both of them are excluded here and separated by the tonic margin below.
        double otherSignature = Double.NEGATIVE_INFINITY;
        for (int tonic = 0; tonic < 12; tonic++) {
            for (Mode mode : Mode.values()) {
                boolean samePair = (tonic == bestTonic && mode == bestMode)
                        || (tonic == relativeTonic && mode == relativeMode);
                if (!samePair) {
                    otherSignature = Math.max(otherSignature, scores[tonic][mode.ordinal()]);
                }
            }
        }

        // How much of the span the chords actually accounted for. A margin says
        // which key won; it says nothing about how much was weighed, and the two
        // come apart badly -- half a second of one chord inside four minutes of
        // silence produces a maximal margin, because the score is an average
        // over the sounding time and half a second of it averages perfectly.
        // Same reason and same shape as DownbeatEstimator's "there was enough of
        // it" factor, which exists because a margin arrived at cheaply reports
        // full confidence in a guess.
        //
        // It counts what the chords covered, which is not quite the same as what
        // the music covered: the lead-in before the first tracked beat and the
        // tail after the last carry no chord span and are docked here as though
        // they were silent. On the benchmarks that is a point or two. On a track
        // opening with a long unpitched intro it would dock a key the harmony
        // settles outright, which is #280.
        double weighed = Math.clamp(sounding / (endSeconds - startSeconds), 0, 1);
        Confidence signature = confidence(SIGNATURE_BASE, best - otherSignature, weighed);
        Confidence tonic = confidence(TONIC_BASE, best - relative, weighed);
        Key key = Key.ofSeconds(
                Key.tonicOf(signatureOf(bestTonic, bestMode), bestMode), bestMode,
                startSeconds, endSeconds, signature.and(tonic));
        return Optional.of(new Estimate(key, signature, tonic));
    }

    /** Duration-weighted score of one key, not yet divided by the sounding time. */
    private static double score(ChordProgression progression, int tonic, Mode mode) {
        int[] scale = mode == Mode.MINOR ? NATURAL_MINOR : MAJOR_SCALE;
        double total = 0;
        for (Chord chord : progression.chords()) {
            if (chord.isNoChord()) {
                continue;
            }
            int[] intervals = chord.quality().intervals();
            int triadTones = Math.min(3, intervals.length);
            int root = chord.root().pitchClass();
            // Read off the triad, like everything else here: a chord's third is
            // in its triad by definition, and asking the whole interval set
            // would let an extension that happens to be three or four semitones
            // from the root answer a question about the chord's quality.
            boolean majorThird = hasInterval(intervals, triadTones, 4);
            boolean minorThird = hasInterval(intervals, triadTones, 3);

            double fit;
            if (mode == Mode.MINOR && Math.floorMod(root - tonic, 12) == DOMINANT && majorThird) {
                // The harmonic-minor dominant: its third is the raised seventh,
                // which is in the minor key and not in the relative major.
                fit = 1;
            } else {
                int inScale = 0;
                for (int i = 0; i < triadTones; i++) {
                    if (inScale(Math.floorMod(root + intervals[i] - tonic, 12), scale)) {
                        inScale++;
                    }
                }
                fit = (double) inScale / triadTones;
            }

            // A suspended chord has no third to disagree with either mode, and it
            // is still the tonic, so it counts for both rather than for neither.
            boolean modeAgrees = mode == Mode.MINOR ? minorThird : majorThird;
            boolean tonicChord = root == tonic
                    && (modeAgrees || !(majorThird || minorThird));
            total += chord.durationSeconds() * (fit + (tonicChord ? TONIC_CHORD_WEIGHT : 0));
        }
        return total;
    }

    /**
     * Whether one candidate key beats another, ties included.
     *
     * <p>An exact tie is not an edge case: a key and its relative minor score
     * identically whenever nothing in the progression separates them, which is
     * every loop built from the shared seven notes with no dominant in it and
     * equal time on both tonics. Something has to decide, and an array index
     * must not: deciding a relative pair by pitch class makes the same loop
     * major in three keys and minor in the other nine, so the answer moves when
     * the music is transposed.
     *
     * <p><b>Major wins a tie.</b> With no evidence at all the question is which
     * answer is more often right in the repertoire this tool is aimed at, and
     * that is the major. It is a prior, not a reading, so it never overrides
     * evidence — any margin at all decides first — and the tonic confidence
     * comes back at its coin-flip floor to say the prior is all there was. What
     * a detector must not do is answer the relative major when the harmony
     * <em>does</em> say minor; that is what the harmonic-minor dominant and the
     * tonic-chord weight are for, and both outrank this.
     *
     * <p><b>A tie between two keys of the same mode goes to the simpler key
     * signature</b>, and only then to the lowest pitch class. Unlike the step
     * above it, this one <em>cannot</em> be transposition-invariant and is not:
     * {@code C G} and {@code C F} are the same shape with opposite right
     * answers — I–V and I–IV — so no function of the tied set gets both, and
     * transposing a two-chord vamp moves the answer. It is an editorial
     * preference, not a reading: with nothing to choose between, write the
     * simpler key signature. #278 measures how often it is reached.
     *
     * <p>Ties are compared with a tolerance rather than for equality, and that
     * is load-bearing rather than defensive. Two keys that tie in exact
     * arithmetic can still differ in the last bit of a double, because the same
     * terms are summed in a different order for each; four chords are enough.
     * Compared for equality, the rules below would never be reached and the
     * answer would be a function of summation order. The tolerance sits far
     * above that error, which grows with the chord count and stays near the
     * precision of a double, and far below the narrowest margin any real
     * recording here produces.
     */
    private static boolean beats(double[][] scores, int tonic, Mode mode,
                                 int bestTonic, Mode bestMode) {
        double score = scores[tonic][mode.ordinal()];
        double best = scores[bestTonic][bestMode.ordinal()];
        if (Math.abs(score - best) > TIE) {
            return score > best;
        }
        if (mode != bestMode) {
            return mode == Mode.MAJOR;
        }
        int accidentals = Math.abs(signatureOf(tonic, mode));
        int bestAccidentals = Math.abs(signatureOf(bestTonic, bestMode));
        if (accidentals != bestAccidentals) {
            return accidentals < bestAccidentals;
        }
        return tonic < bestTonic;
    }

    /**
     * The tonic of the relative key: the minor a third below a major, and the
     * major a third above a minor.
     */
    private static int relativeTonic(int tonic, Mode mode) {
        return Math.floorMod(tonic + (mode == Mode.MAJOR ? 9 : 3), 12);
    }

    /**
     * A margin turned into a confidence above the floor chance leaves.
     *
     * <p>The two terms multiply rather than average, so either one failing brings
     * the number down instead of being outvoted: a wide margin over almost no
     * music is not a confident answer, and neither is a whole recording that
     * barely prefers one key.
     *
     * @param base    what the answer is worth with no evidence at all
     * @param margin  how far the winner is clear of the runner-up
     * @param weighed the share of the span that carried a chord
     */
    private static Confidence confidence(double base, double margin, double weighed) {
        double decided = Math.clamp(margin / DECISIVE_MARGIN, 0, 1);
        return Confidence.clamped(base + (1 - base) * decided * weighed);
    }

    private static boolean hasInterval(int[] intervals, int count, int semitones) {
        for (int i = 0; i < count; i++) {
            if (intervals[i] == semitones) {
                return true;
            }
        }
        return false;
    }

    private static boolean inScale(int degree, int[] scale) {
        for (int step : scale) {
            if (step == degree) {
                return true;
            }
        }
        return false;
    }

    private static int signatureOf(int tonic, Mode mode) {
        return (mode == Mode.MINOR ? MINOR_SIGNATURES : MAJOR_SIGNATURES)[tonic];
    }

    /** See {@link #MAJOR_SIGNATURES}. */
    private static int[] conventionalSignatures(Mode mode) {
        int[] chosen = new int[12];
        boolean[] found = new boolean[12];
        // Flat side first, so that an equal number of accidentals keeps the flat
        // spelling: a later signature only wins by being strictly narrower.
        for (int signature = -7; signature <= 7; signature++) {
            int tonic = Key.tonicOf(signature, mode).pitchClass();
            if (!found[tonic] || Math.abs(signature) < Math.abs(chosen[tonic])) {
                chosen[tonic] = signature;
                found[tonic] = true;
            }
        }
        for (int tonic = 0; tonic < 12; tonic++) {
            if (!found[tonic]) {
                throw new IllegalStateException(
                        "no " + mode + " key signature spells pitch class " + tonic);
            }
        }
        return chosen;
    }
}
