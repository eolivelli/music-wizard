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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.Spectrogram;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tier-0 harmony: synthesised chords whose identity is known exactly. */
class ChordEstimationTest {

    private static final int RATE = SignalFactory.DEFAULT_SAMPLE_RATE;

    private static ChordProgression analyse(float[] samples) {
        AudioBuffer audio = new AudioBuffer(samples, RATE);
        List<Double> beats = BeatTracker.track(OnsetEnvelope.fromAudio(audio)).beatTimes();
        return ChordEstimator.estimate(Chroma.extract(audio).beatSynchronous(beats), beats);
    }

    /** Beat-synchronous chroma: one vector per beat, so no frame rate. */
    private static Chroma beats(double[]... vectors) {
        return new Chroma(vectors, 0);
    }

    private static List<Double> beatTimes(int count) {
        return java.util.stream.IntStream.rangeClosed(0, count)
                .mapToObj(i -> i * 0.5).toList();
    }

    @Nested
    @DisplayName("chroma")
    class ChromaExtraction {

        @Test
        @DisplayName("puts a C major triad in C, E and G")
        void findsTriadPitchClasses() {
            AudioBuffer audio = new AudioBuffer(
                    SignalFactory.chord(SignalFactory.majorTriad(60), 2.0, RATE), RATE);

            double[] vector = Chroma.extract(audio).normalisedPerFrame().vectors()[20];

            // C=0, E=4, G=7 must dominate the other nine pitch classes.
            double chordTones = vector[0] + vector[4] + vector[7];
            double rest = 1.0 - chordTones;
            assertThat(chordTones).isGreaterThan(rest * 3);
        }

        @Test
        @DisplayName("detects a recording tuned away from A440")
        void detectsTuningOffset() {
            // A quarter-tone sharp: every pitch sits halfway between two chroma
            // bins, which is the case that destroys template matching.
            double quarterToneSharp = Math.pow(2, 0.25 / 12);
            float[] detuned = SignalFactory.chord(new double[] {
                    440 * quarterToneSharp, 554.37 * quarterToneSharp,
                    659.26 * quarterToneSharp}, 3.0, RATE);

            double offset = Chroma.estimateTuning(
                    Spectrogram.compute(new AudioBuffer(detuned, RATE), 4096, 1024));

            assertThat(offset).isCloseTo(0.25, within(0.15));
        }

        @Test
        @DisplayName("beat-synchronous averaging yields one vector per inter-beat span")
        void averagesBetweenBeats() {
            AudioBuffer audio = new AudioBuffer(SignalFactory.clickTrack(120, 8, RATE), RATE);
            List<Double> beats = BeatTracker.track(OnsetEnvelope.fromAudio(audio)).beatTimes();

            Chroma sync = Chroma.extract(audio).beatSynchronous(beats);

            assertThat(sync.frameCount()).isEqualTo(beats.size() - 1);
            assertThat(sync.isBeatSynchronous()).isTrue();
        }
    }

    @Nested
    @DisplayName("chord recognition")
    class Recognition {

        @Test
        @DisplayName("recovers a I-V-vi-IV progression in C")
        void recognisesTheFourChordProgression() {
            // The most common progression in popular music: C, G, Am, F, one
            // chord per bar of four beats at 120 BPM.
            float[] signal = SignalFactory.clickTrackWithChords(120, new double[][] {
                    SignalFactory.majorTriad(60),   // C
                    SignalFactory.majorTriad(67),   // G
                    SignalFactory.minorTriad(69),   // Am
                    SignalFactory.majorTriad(65),   // F
            }, 4, 32, RATE);

            ChordProgression chords = analyse(signal);

            assertThat(chords.chords()).extracting(Chord::symbol)
                    .startsWith("C", "G", "Am", "F", "C", "G", "Am", "F");
            // Sixteen bars of four beats in 32 seconds.
            assertThat(chords.size()).isBetween(14, 18);
            assertThat(chords.confidence().value()).isGreaterThan(0.5);
        }

        @Test
        @DisplayName("distinguishes major from minor on the same root")
        void distinguishesMajorFromMinor() {
            float[] major = SignalFactory.clickTrackWithChords(120,
                    new double[][] {SignalFactory.majorTriad(60)}, 4, 12, RATE);
            float[] minor = SignalFactory.clickTrackWithChords(120,
                    new double[][] {SignalFactory.minorTriad(60)}, 4, 12, RATE);

            assertThat(analyse(major).chords().get(0).symbol()).isEqualTo("C");
            assertThat(analyse(minor).chords().get(0).symbol()).isEqualTo("Cm");
        }

        @Test
        @DisplayName("holds one chord across its bar instead of chattering")
        void doesNotChatter() {
            // Without the transition model this returns a different chord almost
            // every beat. Four bars of one chord must come back as one span.
            float[] signal = SignalFactory.clickTrackWithChords(120,
                    new double[][] {SignalFactory.majorTriad(60)}, 4, 16, RATE);

            ChordProgression chords = analyse(signal);

            assertThat(chords.size()).isLessThanOrEqualTo(2);
            assertThat(chords.chords().get(0).symbol()).isEqualTo("C");
        }

        @Test
        @DisplayName("reports no chord for silence rather than inventing one")
        void silenceHasNoChords() {
            AudioBuffer silent = new AudioBuffer(SignalFactory.silence(8, RATE), RATE);
            List<Double> beats = BeatTracker.track(OnsetEnvelope.fromAudio(silent)).beatTimes();

            // Silence yields no beats at all, so there is nothing to segment.
            assertThat(beats).isEmpty();
            assertThat(ChordEstimator.estimate(
                    Chroma.extract(silent).beatSynchronous(beats), beats).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("chord spans are contiguous and ordered")
        void spansAreContiguous() {
            ChordProgression chords = analyse(SignalFactory.clickTrackWithChords(120,
                    new double[][] {SignalFactory.majorTriad(60), SignalFactory.majorTriad(65)},
                    4, 24, RATE));

            List<Chord> list = chords.chords();
            for (int i = 1; i < list.size(); i++) {
                assertThat(list.get(i).startSeconds())
                        .isGreaterThanOrEqualTo(list.get(i - 1).startSeconds());
                assertThat(list.get(i).startSeconds())
                        .isCloseTo(list.get(i - 1).endSeconds(), within(0.05));
            }
        }
    }

    /**
     * #208: the seventh is decided from the treble register and over the whole
     * chord, not from both registers and not beat by beat.
     *
     * <p>Chroma is built here rather than synthesised, because the defect is
     * about the <em>proportions</em> between registers on a real mix and a
     * synthesised chord has whatever proportions the synthesiser was asked for.
     * The vectors below are the shapes {@code tools/ChordSweep.java profile}
     * measures over the benchmarks.
     */
    @Nested
    @DisplayName("dominant sevenths (#208)")
    class SeventhQuality {

        /**
         * A chroma vector: the named pitch classes at the given shares, the rest
         * of the mass spread evenly over the other nine.
         */
        private static double[] chroma(double root, double third, double fifth, double seventh) {
            double[] out = new double[12];
            out[0] = root;
            out[4] = third;
            out[7] = fifth;
            out[10] = seventh;
            double rest = (1 - root - third - fifth - seventh) / 8;
            for (int i : new int[] {1, 2, 3, 5, 6, 8, 9, 11}) {
                out[i] = rest;
            }
            return out;
        }

        @Test
        @DisplayName("finds the seventh the bass register was hiding")
        void trebleFindsWhatCombinedCannot() {
            // The shapes of blues-e-90bpm.mp3: in the treble the flat seventh
            // carries 0.226 of the triad's mass, well over the 0.155 a four-note
            // binary template needs; added to a bass that puts 0.6 of its energy
            // on the root alone, the same seventh carries 0.106 and loses.
            double[] treble = chroma(0.115, 0.133, 0.164, 0.093);
            double[] combined = chroma(0.329, 0.084, 0.174, 0.062);
            Chroma both = beats(combined, combined, combined, combined);
            List<Double> times = beatTimes(4);

            // The defect: quality decided from the chroma the root came from.
            assertThat(ChordEstimator.estimate(both, both, times).chords().get(0).symbol())
                    .isEqualTo("C");

            // The fix: quality decided from the treble, same root, same spans.
            ChordProgression fixed = ChordEstimator.estimate(both,
                    beats(treble, treble, treble, treble), times);
            assertThat(fixed.chords()).extracting(Chord::symbol).containsExactly("C7");
        }

        @Test
        @DisplayName("a seventh voiced on some beats of a chord still names the chord")
        void poolsTheSeventhOverTheWholeChord() {
            // Three beats of the eight carry the seventh. Beat by beat the
            // majority holds no seventh at all, and summed over the chord it
            // carries 0.29 of the triad's mass.
            double[] with = chroma(0.15, 0.15, 0.15, 0.35);
            double[] without = chroma(0.15, 0.15, 0.15, 0.0);
            double[][] span = {with, without, without, with, without, without, with, without};

            ChordProgression chords =
                    ChordEstimator.estimate(beats(span), beats(span), beatTimes(span.length));

            assertThat(chords.chords()).extracting(Chord::symbol).containsExactly("C7");
        }

        @Test
        @DisplayName("leaves a plain triad alone")
        void doesNotInventSevenths() {
            // pop-c-g-am-f-120.mp3's treble puts 0.023 of the triad's mass on the
            // flat seventh. Nothing in this change may promote that to a C7 --
            // which is the risk #198 named when the seventh templates landed.
            double[] triad = chroma(0.281, 0.152, 0.269, 0.016);

            ChordProgression chords = ChordEstimator.estimate(
                    beats(triad, triad, triad, triad), beats(triad, triad, triad, triad),
                    beatTimes(4));

            assertThat(chords.chords()).extracting(Chord::symbol).containsExactly("C");
        }

        @Test
        @DisplayName("says nothing about quality when the treble says nothing")
        void keepsTheDecodersQualityOnNoEvidence() {
            // A flat chroma scores 0.577 against a four-note template and 0.500
            // against a three-note one, so an argmax over the two reports a
            // dominant seventh on pure noise. The decoder cannot be fooled that
            // way -- NO_CHORD_SIMILARITY is above both -- and this decision
            // reads a chroma that guard never sees.
            double[] minor = new double[12];
            minor[9] = 0.30;
            minor[0] = 0.24;
            minor[4] = 0.28;
            for (int i : new int[] {1, 2, 3, 5, 6, 7, 8, 10, 11}) {
                minor[i] = 0.02;
            }
            double[] flat = new double[12];
            java.util.Arrays.fill(flat, 1.0 / 12);
            double[] silent = new double[12];

            Chroma combined = beats(minor, minor, minor, minor);
            List<Double> times = beatTimes(4);

            assertThat(combined(combined, times)).containsExactly("Am");
            assertThat(ChordEstimator.estimate(combined, beats(flat, flat, flat, flat), times)
                    .chords()).extracting(Chord::symbol).containsExactly("Am");
            assertThat(ChordEstimator
                    .estimate(combined, beats(silent, silent, silent, silent), times)
                    .chords()).extracting(Chord::symbol).containsExactly("Am");
        }

        @Test
        @DisplayName("a treble that barely says anything is still called a seventh")
        void weakTrebleEvidenceStillFavoursTheSeventh() {
            // What the evidence floor does not do, pinned so that it is not
            // mistaken for what it does. The floor rejects a candidate that fits
            // worse than noise; it cannot reject one that fits badly but better
            // than noise, and there the four-note template wins on size.
            //
            // A C major triad diluted into an otherwise flat treble comes back
            // as C7 until the triad carries about a quarter of the register.
            // The third assertion is there because the other two depend on a
            // dimension they do not sweep: with the flat seventh absent the
            // answer is C at every dilution. Where it turns over in between,
            // and whether it turns over below the background level at all,
            // depends on the dilution -- #274 has the sweep, and three cells
            // cannot be interpolated.
            assertThat(dilutedTriad(0.15, 1.0)).isEqualTo("C7");
            assertThat(dilutedTriad(0.30, 1.0)).isEqualTo("C");
            assertThat(dilutedTriad(0.15, 0.0)).isEqualTo("C");
        }

        /**
         * A C major triad carrying {@code share} of an otherwise flat treble,
         * with the flat seventh at {@code b7} times the background level.
         */
        private static String dilutedTriad(double share, double b7) {
            double background = (1 - share) / 12;
            double[] treble = new double[12];
            java.util.Arrays.fill(treble, background);
            for (int i : new int[] {0, 4, 7}) {
                treble[i] += share / 3;
            }
            treble[10] = background * b7;
            double[] combined = chroma(0.28, 0.26, 0.26, 0);
            return ChordEstimator.estimate(
                            beats(combined, combined, combined, combined),
                            beats(treble, treble, treble, treble), beatTimes(4))
                    .chords().get(0).symbol();
        }

        @Test
        @DisplayName("the seventh needs the share of the chord a four-note template asks for")
        void theSeventhIsFoundExactlyWhereTheGeometrySaysItIs() {
            // A four-note binary template beats the three-note one on the same
            // root exactly when the flat seventh carries 2/sqrt(3) - 1 of the
            // triad's mass. Nothing names that number, so pin the boundary: it
            // moves if the templates or the score ever change, which #272 will
            // do when the vocabulary grows.
            double share = 2 / Math.sqrt(3) - 1;
            assertThat(qualityAt(share - 0.001)).isEqualTo("C");
            assertThat(qualityAt(share + 0.001)).isEqualTo("C7");
        }

        /** The reported chord when the b7 carries {@code share} of the triad's mass. */
        private static String qualityAt(double share) {
            double each = 0.15;
            double[] v = chroma(each, each, each, share * 3 * each);
            Chroma c = beats(v, v, v, v);
            return ChordEstimator.estimate(c, c, beatTimes(4)).chords().get(0).symbol();
        }

        private static List<String> combined(Chroma chroma, List<Double> times) {
            return ChordEstimator.estimate(chroma, chroma, times).chords().stream()
                    .map(Chord::symbol).toList();
        }

        @Test
        @DisplayName("refuses two chromas that do not describe the same beats")
        void rejectsMismatchedChromas() {
            double[] v = chroma(0.25, 0.15, 0.2, 0.1);
            assertThatIllegalArgumentException().isThrownBy(() -> ChordEstimator.estimate(
                            beats(v, v, v), beats(v, v), beatTimes(3)))
                    .withMessageContaining("the same beats");
        }
    }

    /**
     * #272: the minor seventh, which the quality decision may report and the
     * decoder may not choose.
     *
     * <p>Chroma is built rather than synthesised, for the reason the class above
     * gives. The two vamps' trebles are what {@code tools/ChordSweep.java
     * profile} prints for those recordings; the pop bar is one span of {@code
     * pop-c-g-am-f-120.mp3}, read off the treble the estimator actually gave the
     * quality decision there; the two-thirds vector is constructed.
     */
    @Nested
    @DisplayName("minor sevenths (#272)")
    class MinorSeventhQuality {

        /**
         * A chroma vector from {@code pitchClass, share} pairs, the rest of the
         * mass spread evenly over the pitch classes not named.
         */
        private static double[] chroma(double... pairs) {
            double[] out = new double[12];
            double named = 0;
            for (int i = 0; i < pairs.length; i += 2) {
                out[(int) pairs[i]] = pairs[i + 1];
                named += pairs[i + 1];
            }
            double rest = (1 - named) / (12 - pairs.length / 2);
            for (int i = 0; i < 12; i++) {
                if (out[i] == 0) {
                    out[i] = rest;
                }
            }
            return out;
        }

        private static String reported(double[] combined, double[] treble) {
            return ChordEstimator.estimate(beats(combined, combined, combined, combined),
                            beats(treble, treble, treble, treble), beatTimes(4))
                    .chords().get(0).symbol();
        }

        /** A C minor triad, which is what the decoder answers on both vamps. */
        private static double[] minorTriad() {
            return chroma(0, 0.23, 3, 0.13, 7, 0.23);
        }

        @Test
        @DisplayName("names the minor seventh the vocabulary used to have no word for")
        void reportsAMinorSeventh() {
            // fm7-vamp-110.mp3's treble: the minor third at 0.160 and the flat
            // seventh at 0.091, against a major third at 0.044. Every bar of that
            // recording is a minor seventh and every one came back a minor triad.
            double[] treble = chroma(0, 0.191, 3, 0.160, 4, 0.044, 7, 0.230, 10, 0.091);

            assertThat(reported(minorTriad(), treble)).isEqualTo("Cm7");
        }

        @Test
        @DisplayName("keeps the dominant seventh when the major third is sounding too")
        void doesNotTakeABlueThirdForAMinorChord() {
            // eb7-vamp-130.mp3's treble, where the minor third is the louder of
            // the two: 0.149 against 0.118. The recording is a dominant seventh
            // throughout -- its comping riff is a tritone a half-step up, which
            // states the minor third of the written chord -- so an argmax on the
            // louder third alone answers every bar wrongly.
            double[] treble = chroma(0, 0.152, 3, 0.149, 4, 0.118, 7, 0.151, 10, 0.111);

            assertThat(reported(minorTriad(), treble)).isEqualTo("C7");
        }

        @Test
        @DisplayName("the minor seventh cannot move a root")
        void staysOnTheDecodersRoot() {
            // A guard, not a fail-before test: it holds on origin/main too,
            // where no vocabulary can express the wrong answer at all. It fails
            // if the minor seventh is moved into DECODED, which is the mistake
            // it is here to catch.
            //
            // C-E-G with an A: a C major triad and an A minor seventh are the
            // same four notes, so a decoder offered both answers A -- 84.1% of
            // gmajorblues.mp3's roots to 48.4% when this was measured. The
            // decoder is not offered it, and the quality decision only ever
            // considers the root the decoder found.
            double[] four = chroma(0, 0.25, 4, 0.22, 7, 0.24, 9, 0.15);

            assertThat(reported(four, four)).startsWith("C");
        }

        @Test
        @DisplayName("a major third no louder than the root's own partial is not read")
        void theRootsOwnPartialIsNotAMajorThird() {
            // An A minor bar of pop-c-g-am-f-120.mp3: root and fifth at a third
            // of the register each and both thirds an order of magnitude below
            // that, the major third at 0.086 of the root, which is about what
            // its fifth partial puts there. Subtracting all of it rather than
            // the part the root cannot account for costs five of that file's
            // fourteen A minor bars and names bm-blues-slow.mp3, a B minor
            // blues, B major.
            double[] barelyMinor = chroma(0, 0.3425, 3, 0.0475, 4, 0.0294,
                    7, 0.3140, 10, 0.0290);

            assertThat(reported(barelyMinor, barelyMinor)).isEqualTo("Cm");
        }

        /**
         * #446: a seventh a minority of the root's beats carry is withdrawn.
         *
         * <p>The first three hold the per-run evidence fixed and vary only how
         * much of the recording agrees with it, which is the axis the rule reads;
         * the even-split case shows that shared vector is read {@code Cm7} on its
         * own evidence. The fourth needs a vector of its own and says why.
         */
        @Test
        @DisplayName("withdraws a seventh most of the root's beats do not carry")
        void aSeventhTheRecordingDoesNotHoldIsWithdrawn() {
            assertThat(alternating(1, 2)).containsExactly("Cm", "D", "Cm", "D", "Cm");
        }

        @Test
        @DisplayName("keeps a seventh most of the root's beats do carry")
        void aSeventhTheRecordingHoldsIsKept() {
            // A guard, not a fail-before test: it holds on origin/main too. It
            // fails if the withdrawal is ever applied without counting, which
            // would cost every recording the vocabulary was widened for.
            assertThat(alternating(2, 1)).containsExactly("Cm7", "D", "Cm7", "D", "Cm");
        }

        @Test
        @DisplayName("an even split is not a minority, so the seventh stands")
        void anEvenSplitKeepsTheSeventh() {
            // The boundary the constant's "a minority" wording implies, pinned
            // because nothing else does: at exactly half the rule must not fire.
            assertThat(alternating(1, 1)).containsExactly("Cm7", "D", "Cm");
        }

        @Test
        @DisplayName("withdrawing a seventh does not flip the third")
        void withdrawalFallsBackToATriadNotAnotherSeventh() {
            // The dominant seventh shares its root, fifth and flat seventh with
            // the minor one, so a fallback that only drops the minor seventh
            // answers with the dominant -- a major third arrived at by
            // withdrawing a minor one. Measured on the corpus, that reading put a
            // major third on three runs of a B minor blues.
            //
            // Separating the two needs a run this vocabulary can express both
            // ways: a flat seventh loud enough that C7 outscores Cm among the
            // triads-and-dominant candidates, and a major third under the share
            // the root's own fifth partial explains, so the minorish correction
            // is zero and Cm7 still wins the full argmax. The default vector
            // cannot do it -- C7 loses to Cm there whether or not it is excluded,
            // which is why this needs its own.
            double[] loudSeventh = chroma(0, 0.25, 3, 0.10, 4, 0.06, 7, 0.25, 10, 0.22);

            assertThat(alternating(1, 2, loudSeventh))
                    .containsExactly("Cm", "D", "Cm", "D", "Cm");
        }

        /**
         * Runs on C separated by D major, the first {@code sevenths} of them
         * carrying a flat seventh above the level and the next {@code plain} not,
         * reported in the order the estimator returns them.
         *
         * <p>The C runs are separated because {@code sameChord} groups adjacent
         * beats on one root into a single run: with nothing between them the whole
         * of C would be one run and there would be nothing to count. The first run
         * is the seventh-bearing one, so it is the same evidence read against
         * different amounts of agreement.
         */
        private static List<String> alternating(int sevenths, int plain) {
            // A minor triad whose flat seventh clears 2/sqrt(3) - 1 of the triad's
            // mass, which is all its callers need of it.
            return alternating(sevenths, plain,
                    chroma(0, 0.23, 3, 0.13, 7, 0.23, 10, 0.13));
        }

        /** As {@link #alternating(int, int)}, with the seventh-bearing run given. */
        private static List<String> alternating(int sevenths, int plain,
                                                double[] withSeventh) {
            double[] withoutSeventh = chroma(0, 0.23, 3, 0.13, 7, 0.23, 10, 0.02);
            double[] away = chroma(2, 0.25, 6, 0.22, 9, 0.24);

            List<double[]> runs = new java.util.ArrayList<>();
            for (int i = 0; i < sevenths + plain; i++) {
                if (i > 0) {
                    runs.add(away);
                }
                runs.add(i < sevenths ? withSeventh : withoutSeventh);
            }

            List<double[]> vectors = new java.util.ArrayList<>();
            for (double[] run : runs) {
                for (int beat = 0; beat < 4; beat++) {
                    vectors.add(run);
                }
            }
            Chroma chroma = beats(vectors.toArray(double[][]::new));
            return ChordEstimator.estimate(chroma, chroma, beatTimes(vectors.size()))
                    .chords().stream().map(Chord::symbol).toList();
        }
    }

    /**
     * #448: which of a chord's own notes is its root, which only the bass says.
     *
     * <p>The shape is a boogie bar of A7 — the comping plays root-and-sixth for
     * half of it, so A, C#, E and F# all sound and the F# is louder than the E.
     * Folded to pitch classes that is F#m7's set, and the F# minor triad wins.
     * The bass plays A throughout.
     */
    @Nested
    @DisplayName("the bass register as a root prior (#448)")
    class BassRoot {

        /** A chroma vector from {@code pitchClass, share} pairs, the rest spread evenly. */
        private static double[] chroma(double... pairs) {
            double[] out = new double[12];
            double named = 0;
            for (int i = 0; i < pairs.length; i += 2) {
                out[(int) pairs[i]] = pairs[i + 1];
                named += pairs[i + 1];
            }
            double rest = (1 - named) / (12 - pairs.length / 2);
            for (int i = 0; i < 12; i++) {
                if (out[i] == 0) {
                    out[i] = rest;
                }
            }
            return out;
        }

        private static Chroma four(double[] vector) {
            return beats(vector, vector, vector, vector);
        }

        /** A=9, C#=1, E=4, F#=6. The sixth outweighs the fifth, as it is played. */
        private static final double[] BOOGIE = chroma(9, 0.30, 1, 0.25, 6, 0.20, 4, 0.10);

        @Test
        @DisplayName("the sixth alone moves the root to the relative minor")
        void withoutTheBassTheSixthDecidesTheRoot() {
            assertThat(ChordEstimator.estimate(four(BOOGIE), four(BOOGIE), beatTimes(4))
                    .chords()).extracting(Chord::symbol).containsExactly("F#m");
        }

        @Test
        @DisplayName("a bass on the root keeps the root")
        void theBassDecidesWhichSharedNoteIsTheRoot() {
            double[] bass = chroma(9, 0.60, 1, 0.10, 4, 0.10, 6, 0.10);

            assertThat(ChordEstimator
                    .estimate(four(BOOGIE), four(BOOGIE), four(bass), beatTimes(4))
                    .chords()).extracting(Chord::symbol).containsExactly("A");
        }

        @Test
        @DisplayName("a bass on the sixth moves the root the other way")
        void thePriorReadsTheBassRatherThanPreferringOneRoot() {
            // The mirror, so that the case above cannot be passing because the
            // prior always prefers the lower root: the fifth outweighs the sixth
            // here, so the templates answer A on their own, and a bass that
            // really is playing F# takes the root the other way. The quality
            // that comes back with it is F#m7, because A's fifth is F# minor's
            // flat seventh -- the prior decides roots and nothing else.
            double[] fifthOverSixth = chroma(9, 0.30, 1, 0.25, 4, 0.20, 6, 0.10);
            double[] bass = chroma(6, 0.60, 9, 0.10, 1, 0.10, 4, 0.10);

            assertThat(ChordEstimator.estimate(four(fifthOverSixth), four(fifthOverSixth),
                            beatTimes(4)).chords())
                    .extracting(Chord::symbol).containsExactly("A");
            assertThat(ChordEstimator.estimate(four(fifthOverSixth), four(fifthOverSixth),
                            four(bass), beatTimes(4)).chords())
                    .extracting(Chord::symbol).containsExactly("F#m7");
        }

        @Test
        @DisplayName("a bass naming a root does not make a chord out of noise")
        void thePriorNeverArguesThatSomethingIsSounding() {
            // The prior is at most zero, so it cannot lower the level
            // NO_CHORD_SIMILARITY sets. A chroma no template fits -- flat, where
            // the best of them scores sqrt(4/12) against the no-chord state's
            // 0.60 -- stays no-chord however loudly the bass names a root, and
            // at any bass level at all, since beat-synchronising has already
            // scaled every beat to sum to one.
            double[] flat = new double[12];
            java.util.Arrays.fill(flat, 1.0 / 12);
            double[] loud = chroma(9, 0.99);
            double[] whisper = new double[12];
            whisper[9] = 1e-8;

            assertThat(ChordEstimator.estimate(four(flat), four(flat), beatTimes(4))
                    .chords()).extracting(Chord::symbol).containsExactly("N.C.");
            for (double[] bass : List.of(loud, whisper)) {
                assertThat(ChordEstimator
                        .estimate(four(flat), four(flat), four(bass), beatTimes(4))
                        .chords()).extracting(Chord::symbol).containsExactly("N.C.");
            }
        }

        @Test
        @DisplayName("one beat of bass on another note is a passing note, not a chord")
        void aPassingBassNoteDoesNotSplitTheChord() {
            // What the window is for, and the only thing that pins it: over two
            // bars of the boogie the bass steps onto the sixth for a single
            // beat. Read that beat alone the prior asserts a root there and the
            // chord is split in two -- which is how the run whose quality is
            // decided once comes to be decided twice.
            double[] onA = chroma(9, 0.60, 1, 0.10, 4, 0.10, 6, 0.10);
            double[] onSixth = chroma(6, 0.60, 9, 0.10, 1, 0.10, 4, 0.10);
            double[][] bass = {onA, onA, onA, onSixth, onA, onA, onA, onA};
            double[][] boogie = new double[8][];
            java.util.Arrays.fill(boogie, BOOGIE);

            assertThat(ChordEstimator.estimate(beats(boogie), beats(boogie), beats(bass),
                            beatTimes(8)).chords())
                    .extracting(Chord::symbol).containsExactly("A");
        }

        @Test
        @DisplayName("refuses a bass chroma that does not describe the same beats")
        void rejectsAMismatchedBass() {
            assertThatIllegalArgumentException().isThrownBy(() -> ChordEstimator.estimate(
                            four(BOOGIE), four(BOOGIE), beats(BOOGIE, BOOGIE), beatTimes(4)))
                    .withMessageContaining("the same beats");
        }
    }
}
