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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.Spectrogram;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.workspace.ChordTrace;
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
         * #446 and #479: the seventh is settled over all of a root's beats, in
         * whichever direction they point.
         *
         * <p>The first three hold the per-run evidence fixed and vary only how
         * much of the recording agrees with it, which is the axis the rule reads;
         * the even-split case shows that shared vector is read {@code Cm7} on its
         * own evidence. The rest need a vector of their own and say why.
         */
        @Test
        @DisplayName("withdraws a seventh most of the root's beats do not carry")
        void aSeventhTheRecordingDoesNotHoldIsWithdrawn() {
            assertThat(alternating(1, 2)).containsExactly("Cm", "D", "Cm", "D", "Cm");
        }

        @Test
        @DisplayName("carries a seventh most of the root's beats do carry to the run that missed it")
        void aSeventhTheRecordingHoldsIsCarriedToTheRunThatMissedIt() {
            // #479: the same voicing repeated, and the last run's seventh does not
            // clear the ratio a four-note template asks for. Its own chroma says
            // triad and the root's other beats say seventh; the count decides.
            assertThat(alternating(2, 1)).containsExactly("Cm7", "D", "Cm7", "D", "Cm7");
        }

        @Test
        @DisplayName("an even split settles nothing, so each run keeps its own reading")
        void anEvenSplitLeavesEachRunAsItRead() {
            // The boundary the constant's "a minority" and "most of them" wording
            // implies, pinned because nothing else does: at exactly half neither
            // direction may fire, so the two runs disagree and stay that way.
            assertThat(alternating(1, 1)).containsExactly("Cm7", "D", "Cm");
        }

        @Test
        @DisplayName("carrying a seventh does not flip a third")
        void promotionLeavesAMajorTriadAlone() {
            // The mirror of the withdrawal's fallback rule. A count of sevenths
            // is evidence about a seventh and none at all about a third, so a run
            // read as a major triad on that root keeps its third -- naming it Cm7
            // would flip one on the strength of the other runs' colour.
            double[] majorTriad = chroma(0, 0.25, 4, 0.20, 7, 0.25);

            assertThat(alternating(2, 1, withSeventh(), majorTriad))
                    .containsExactly("Cm7", "D", "Cm7", "D", "C");
        }

        @Test
        @DisplayName("a run nothing explained is not given a seventh by the count")
        void promotionKeepsTheFloor() {
            // The run's treble carries the melody and nothing of the chord, so no
            // candidate beats a flat chroma there and the decoder's own answer
            // stands -- the deference chooseQualities shows when it declines. The
            // count must not overrule that: a seventh added here would be one no
            // chroma anywhere in the run argued for.
            double[] blankTreble = chroma(2, 0.30, 6, 0.30, 9, 0.30);

            assertThat(alternatingTreble(2, 1, blankTreble))
                    .containsExactly("Cm7", "D", "Cm7", "D", "Cm");
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
            return alternating(sevenths, plain, withSeventh());
        }

        /** As {@link #alternating(int, int)}, with the seventh-bearing run given. */
        private static List<String> alternating(int sevenths, int plain,
                                                double[] withSeventh) {
            return alternating(sevenths, plain, withSeventh, withoutSeventh());
        }

        /** As {@link #alternating(int, int)}, with both runs' chroma given. */
        private static List<String> alternating(int sevenths, int plain,
                                                double[] withSeventh, double[] plainRun) {
            List<double[]> vectors = beatsOf(sevenths, plain, withSeventh, plainRun);
            return symbols(vectors, vectors);
        }

        /**
         * As {@link #alternating(int, int)} with the plain run's <em>treble</em>
         * given: its combined chroma still says C minor, so the decoder places
         * the run where it always did and only the quality decision reads the
         * vector given.
         */
        private static List<String> alternatingTreble(int sevenths, int plain,
                                                      double[] treble) {
            return symbols(beatsOf(sevenths, plain, withSeventh(), withoutSeventh()),
                    beatsOf(sevenths, plain, withSeventh(), treble));
        }

        /** A minor triad whose flat seventh clears 2/sqrt(3) - 1 of the triad's mass. */
        private static double[] withSeventh() {
            return chroma(0, 0.23, 3, 0.13, 7, 0.23, 10, 0.13);
        }

        /** The same triad with its flat seventh below the background level. */
        private static double[] withoutSeventh() {
            return chroma(0, 0.23, 3, 0.13, 7, 0.23, 10, 0.02);
        }

        /** Four beats per run, the C runs separated by four beats of D major. */
        private static List<double[]> beatsOf(int sevenths, int plain,
                                              double[] withSeventh, double[] plainRun) {
            double[] away = chroma(2, 0.25, 6, 0.22, 9, 0.24);
            List<double[]> vectors = new java.util.ArrayList<>();
            for (int i = 0; i < sevenths + plain; i++) {
                for (int beat = 0; i > 0 && beat < 4; beat++) {
                    vectors.add(away);
                }
                for (int beat = 0; beat < 4; beat++) {
                    vectors.add(i < sevenths ? withSeventh : plainRun);
                }
            }
            return vectors;
        }

        private static List<String> symbols(List<double[]> combined, List<double[]> treble) {
            return ChordEstimator.estimate(beats(combined.toArray(double[][]::new)),
                            beats(treble.toArray(double[][]::new)),
                            beatTimes(combined.size()))
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

    /**
     * #537: a strongly voiced root manufactures its own major third, because
     * partial 5 of a note is the major third and the dictionary under-models
     * it. The chroma cannot tell that third from a played one; deleting the
     * pitch class and refitting can, so the quality decision asks.
     *
     * <p>Every significance vector below is a reading of a real span, taken
     * with {@code tools/ThirdProbe.java}: the recording of #527 for the
     * phantom, {@code samples/blues-a-90bpm.mp3} for the two thirds that are
     * both played.
     */
    @Nested
    @DisplayName("phantom major thirds (#537)")
    class PhantomThird {

        /** A=9, C=0, C#=1, E=4: the treble of an A run in the recording of #527. */
        private static final double[] TREBLE = chroma(9, 0.21, 0, 0.036, 1, 0.164, 4, 0.23);

        /** The same run in both registers added, which is what the root is decoded from. */
        private static final double[] COMBINED = chroma(9, 0.342, 0, 0.091, 1, 0.059, 4, 0.18);

        /** And the bass alone, which names A and nothing else. */
        private static final double[] BASS = chroma(9, 0.523, 0, 0.074, 1, 0.008, 4, 0.109);

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

        /** A significance vector holding the three values the decision reads. */
        private static PitchClassAblation ablation(double minorThird, double majorThird,
                                                   double root) {
            double[] out = new double[12];
            out[0] = minorThird;
            out[1] = majorThird;
            out[9] = root;
            return over(4, (from, to) -> out);
        }

        /** An ablation over {@code spans} spans, answering with {@code answer}. */
        private static PitchClassAblation over(int spans,
                                               java.util.function.BiFunction<Integer, Integer,
                                                       double[]> answer) {
            return new PitchClassAblation() {
                @Override
                public int spanCount() {
                    return spans;
                }

                @Override
                public double[] significanceOver(int fromSpan, int toSpan) {
                    return answer.apply(fromSpan, toSpan);
                }
            };
        }

        private static String label(PitchClassAblation ablation) {
            return ChordEstimator.estimate(four(COMBINED), four(TREBLE), four(BASS), ablation,
                    beatTimes(4)).chords().get(0).symbol();
        }

        @Test
        @DisplayName("a major third the fit does not need is not evidence of a major chord")
        void thePhantomThirdDoesNotDecideTheQuality() {
            // The treble puts four and a half times as much on the major third
            // as on the minor one, and the recording holds no A major (#527).
            // Deleting the pitch class says why: the minor third is carrying
            // more of the spectrum than the major third is, and the major third
            // is at the level the root's own partial accounts for.
            assertThat(ChordEstimator.estimate(four(COMBINED), four(TREBLE), four(BASS),
                    beatTimes(4)).chords()).extracting(Chord::symbol).containsExactly("A");

            assertThat(label(ablation(0.072, 0.045, 0.819))).isEqualTo("Am");
        }

        @Test
        @DisplayName("the two readings the veto compared are written down (#677)")
        void theVetoWritesDownWhatItCompared() {
            ChordTrace.Span span = ChordEstimator.explain(four(COMBINED), four(TREBLE),
                    four(BASS), ablation(0.072, 0.045, 0.819), beatTimes(4))
                    .trace().spans().get(0);

            assertThat(span.chord()).isEqualTo("Am");
            // The bass names the root the decoder took, so it argued for
            // nothing against it.
            assertThat(span.bassRoot()).isEqualTo("A");
            assertThat(span.bassOnDecoded()).isZero();
            // The major third is quieter in the fit than the minor third and
            // below the share the root's own partial accounts for, so it fails
            // both of its comparisons and is withheld.
            assertThat(span.gates()).filteredOn(gate -> gate.degree().equals("major third"))
                    .hasSize(2)
                    .allSatisfy(gate -> {
                        assertThat(gate.counted()).isFalse();
                        assertThat(gate.reading()).isEqualTo(0.045);
                    })
                    .extracting(ChordTrace.Gate::required)
                    .containsExactly(0.1638, 0.072);
            // And the minor third clears its own floor, which is the reading
            // that made this chord minor.
            assertThat(span.gates()).filteredOn(gate -> gate.degree().equals("minor third"))
                    .singleElement()
                    .satisfies(gate -> {
                        assertThat(gate.counted()).isTrue();
                        assertThat(gate.reading()).isEqualTo(0.072);
                    });
        }

        @Test
        @DisplayName("a major third the fit needs is left alone")
        void aPlayedMajorThirdStillDecidesTheQuality() {
            // The same chroma, and an A7 span of samples/blues-a-90bpm.mp3's
            // residual: the major third removes nearly three times what the
            // minor third does, so nothing is discounted and the treble decides
            // as it did before.
            assertThat(label(ablation(0.065, 0.175, 0.287))).isEqualTo("A");
        }

        @Test
        @DisplayName("a blues third over a dominant does not turn the chord minor")
        void aMinorThirdThatOutweighsTheMajorOneIsNotEnough() {
            // What the level test is for. Later in the same recording both
            // thirds are played and the minor one carries more -- and this is a
            // dominant seventh chord, not a minor one, so ranking the two
            // thirds against each other is not on its own a reason to drop the
            // major one. It stays because it removes far more residual than the
            // root's own partial would account for.
            assertThat(label(ablation(0.121, 0.096, 0.330))).isEqualTo("A");
        }

        @Test
        @DisplayName("a run the major triad cannot explain does not fall to the vetoed chord")
        void theFloorDoesNotMoveWithTheResidual() {
            // The floor is what a candidate scores against a chroma carrying no
            // information, and the residual test is a reading of this
            // recording, so the floor must not see it: the two families move
            // opposite ways -- a vetoed major candidate's floor falls and a
            // minor candidate's rises -- which admits the chord the veto was
            // meant to rule out.
            //
            // The run where that shows is one the major triad explains worse
            // than a flat chroma would, with the phantom third the loudest
            // thing in it. The minor triad is the only candidate above its own
            // floor, and the answer is the veto's: read the third at face
            // value and the same run is A.
            double[] weak = chroma(9, 0.10, 0, 0.04, 4, 0.10, 1, 0.175, 7, 0.02);

            assertThat(ChordEstimator.estimate(four(COMBINED), four(weak), four(BASS),
                            ablation(0.072, 0.045, 0.819), beatTimes(4)).chords())
                    .extracting(Chord::symbol).containsExactly("Am");
            assertThat(ChordEstimator.estimate(four(COMBINED), four(weak), four(BASS),
                            beatTimes(4)).chords())
                    .extracting(Chord::symbol).containsExactly("A");
        }

        @Test
        @DisplayName("the residual is read once a beat to decode and once a chord to label")
        void theAblationIsAskedOncePerRunAndOncePerBeat() {
            // Two cadences, and the split is the whole shape of the decision: a
            // third that sounds on some beats of a chord is the chord's third,
            // so the quality decision asks over the chord; the decoder has no
            // chord yet, so its own gate (#588) asks over the beat. Thirteen
            // fits a beat is the price of that, and it is why nothing else here
            // reads the residual per beat.
            List<int[]> asked = new java.util.ArrayList<>();
            PitchClassAblation recorder = over(8, (from, to) -> {
                asked.add(new int[] {from, to});
                return new double[12];
            });
            double[] onD = chroma(2, 0.35, 6, 0.20, 9, 0.25);
            Chroma both = beats(COMBINED, COMBINED, COMBINED, COMBINED, onD, onD, onD, onD);

            assertThat(ChordEstimator.estimate(both, both, both, recorder, beatTimes(8))
                    .chords()).extracting(Chord::symbol).containsExactly("Am", "D");
            assertThat(asked).extracting(a -> a[0] + "-" + a[1]).containsExactly(
                    "0-1", "1-2", "2-3", "3-4", "4-5", "5-6", "6-7", "7-8", "0-4", "4-8");
        }

        @Test
        @DisplayName("refuses a null ablation rather than quietly deciding without it")
        void rejectsANullAblation() {
            assertThatNullPointerException().isThrownBy(() -> ChordEstimator.estimate(
                    four(COMBINED), four(TREBLE), four(BASS), null, beatTimes(4)));
        }

        @Test
        @DisplayName("refuses an ablation that does not describe the same beats")
        void rejectsAnAblationOverOtherSpans() {
            // An ablation that was never beat-synchronised covers analysis
            // frames, and every chord would then be measured over the opening
            // seconds of the recording with nothing to show for it.
            assertThatIllegalArgumentException().isThrownBy(() -> ChordEstimator.estimate(
                            four(COMBINED), four(TREBLE), four(BASS),
                            over(900, (from, to) -> new double[12]), beatTimes(4)))
                    .withMessageContaining("the same beats");
        }
    }

    /**
     * #546: a run in which neither third is in the fit.
     *
     * <p>The vectors are one span of {@code uncommitted/johnny-b-goode.mp3},
     * eight seconds of a twelve-bar blues in B flat that the estimator labelled
     * {@code A#m} — a recording confirmed to hold no minor chord at all.
     */
    @Nested
    @DisplayName("a run with no third in it (#546)")
    class ThirdlessRun {

        /** Both registers added: B flat and F, and neither third to speak of. */
        private static final double[] COMBINED = {
                0.0702, 0.0467, 0.0552, 0.0504, 0.0654, 0.2461,
                0.0092, 0.1073, 0.0244, 0.0508, 0.2470, 0.0272};
        private static final double[] TREBLE = {
                0.0555, 0.0393, 0.0475, 0.0733, 0.0571, 0.3091,
                0.0103, 0.0819, 0.0275, 0.0304, 0.2393, 0.0287};
        private static final double[] BASS = {
                0.0947, 0.0587, 0.0682, 0.0133, 0.0807, 0.1399,
                0.0075, 0.1487, 0.0196, 0.0821, 0.2617, 0.0249};

        /**
         * And the run's own residual. The root removes a third of the
         * spectrum's; both thirds — D at index 2 and C sharp at index 1 —
         * remove under a three-hundredth of what it does, and the minor one
         * beats the major one by a fourteenth of that. Which is what decided
         * eight seconds of chart before this rule.
         */
        private static final double[] RESIDUAL = {
                0.0176, 0.0009, 0.0008, 0.0118, 0.0100, 0.3608,
                0.0000, 0.0472, 0.0001, 0.0084, 0.3114, 0.0008};

        private static Chroma four(double[] vector) {
            return beats(vector, vector, vector, vector);
        }

        private static String label(double[] residual) {
            return ChordEstimator.estimate(four(COMBINED), four(TREBLE), four(BASS),
                    new PitchClassAblation() {
                        @Override
                        public int spanCount() {
                            return 4;
                        }

                        @Override
                        public double[] significanceOver(int fromSpan, int toSpan) {
                            return residual;
                        }
                    }, beatTimes(4)).chords().get(0).symbol();
        }

        @Test
        @DisplayName("a minor third the fit does not need does not turn the chord minor")
        void aMinorThirdTheFitDoesNotNeedIsNotEvidence() {
            // The veto reads the major third as a phantom -- correctly, it is
            // at the level a silent pitch class sits at -- and before this rule
            // that left the minor candidate holding the only third anyone was
            // counting, on a pitch class whose residual is just as empty.
            assertThat(label(RESIDUAL)).isEqualTo("A#");
        }

        @Test
        @DisplayName("a minor third the fit does need still decides the quality")
        void aPlayedMinorThirdIsStillEvidence() {
            // The same run with its minor third moved to the share the minor
            // tonic of #527 shows -- nothing else touched, the chroma least of
            // all. The rule is a floor under the noise, not a discount on the
            // third.
            double[] played = RESIDUAL.clone();
            played[1] = 0.088 * RESIDUAL[10];

            assertThat(label(played)).isEqualTo("A#m");
        }
    }

    /**
     * #287: the two qualities of that issue the corpus admits, measured again
     * on the estimator #543 left behind.
     *
     * <p>Every vector below is a reading of a real span. The first two are what
     * the pipeline gave the estimator over one {@code Cm6} and one
     * {@code Am7b5} presentation of {@code
     * synthetic_samples/pop-m6-m7b5-gm-100.mp3}, whose spec compiled the MIDI:
     * the guitar plays the same four pitch classes on both, and the bass plays
     * C under one and A under the other, and #514 read those bars {@code Cm7}
     * and {@code Am7} — each asserting a note the music does not play. The
     * third is a run of {@code samples/cm-blues-68-95.mp3}, which is where the
     * residual test earns its place on a real recording.
     */
    @Nested
    @DisplayName("the sixth and the half-diminished (#287)")
    class VocabularyBeyondTheSeventh {

        /** The Cm6 run: C-Eb-G-A over a C bass, in both registers added. */
        private static final double[] SIXTH_COMBINED = {
                0.2891, 0.0158, 0.0399, 0.1505, 0.0607, 0.0134,
                0.0063, 0.1478, 0.0205, 0.1715, 0.0705, 0.0139};
        private static final double[] SIXTH_TREBLE = {
                0.1556, 0.0131, 0.0508, 0.2036, 0.0782, 0.0020,
                0.0039, 0.1815, 0.0235, 0.1970, 0.0860, 0.0047};
        private static final double[] SIXTH_BASS = {
                0.6480, 0.0233, 0.0103, 0.0083, 0.0139, 0.0422,
                0.0119, 0.0576, 0.0123, 0.1045, 0.0290, 0.0389};

        /**
         * And that run's leave-one-out residual. The sixth removes over half
         * of what the root removes and the flat seventh a tenth of it, where
         * the chroma has them within a factor of three — which is why the
         * decision reads this and not the chroma.
         */
        private static final double[] SIXTH_RESIDUAL = {
                1.4547, 0.0037, 0.0665, 0.5840, 0.0879, 0.0035,
                0.0002, 0.5056, 0.0120, 0.8178, 0.1469, 0.0641};

        /** The Am7b5 run: the same four pitch classes over an A bass. */
        private static final double[] HALF_COMBINED = {
                0.1087, 0.0389, 0.0455, 0.1410, 0.0853, 0.0065,
                0.0151, 0.1112, 0.0156, 0.3661, 0.0590, 0.0070};
        private static final double[] HALF_TREBLE = {
                0.1441, 0.0493, 0.0438, 0.1928, 0.0831, 0.0027,
                0.0139, 0.1411, 0.0184, 0.2258, 0.0804, 0.0047};
        private static final double[] HALF_BASS = {
                0.0141, 0.0110, 0.0490, 0.0019, 0.0919, 0.0164,
                0.0187, 0.0305, 0.0080, 0.7438, 0.0017, 0.0130};

        /**
         * And that run's residual: the diminished fifth removes over a quarter
         * of what the root removes, the perfect fifth less than a third of
         * that.
         */
        private static final double[] HALF_RESIDUAL = {
                0.4990, 0.0288, 0.0361, 0.4914, 0.1418, 0.0000,
                0.0027, 0.3346, 0.0053, 1.8086, 0.1211, 0.0390};

        /**
         * A G-rooted run of {@code samples/cm-blues-68-95.mp3} and its own
         * residual, where the flat fifth is in the band a silent pitch class
         * occupies. The recording's truth holds {@code G7}.
         */
        private static final double[] BLUES_COMBINED = {
                0.1030, 0.0206, 0.0357, 0.0781, 0.0426, 0.2074,
                0.0177, 0.1816, 0.0392, 0.0334, 0.1328, 0.1080};
        private static final double[] BLUES_TREBLE = {
                0.1467, 0.0251, 0.0143, 0.0990, 0.0452, 0.2603,
                0.0183, 0.0360, 0.0284, 0.0167, 0.1812, 0.1288};
        private static final double[] BLUES_BASS = {
                0.0135, 0.0115, 0.0794, 0.0353, 0.0372, 0.0993,
                0.0164, 0.4793, 0.0612, 0.0675, 0.0339, 0.0654};
        private static final double[] BLUES_RESIDUAL = {
                0.1601, 0.0004, 0.0009, 0.0456, 0.0063, 0.3375,
                0.0003, 0.2722, 0.0041, 0.0283, 0.2337, 0.1218};

        private static Chroma four(double[] vector) {
            return beats(vector, vector, vector, vector);
        }

        /** An ablation over four spans answering {@code residual} for each. */
        private static PitchClassAblation ablation(double[] residual) {
            return over(4, residual);
        }

        /** An ablation over {@code spans} spans, answering {@code residual}. */
        private static PitchClassAblation over(int spans, double[] residual) {
            return new PitchClassAblation() {
                @Override
                public int spanCount() {
                    return spans;
                }

                @Override
                public double[] significanceOver(int fromSpan, int toSpan) {
                    return residual;
                }
            };
        }

        private static String sixthLabel(double[] residual) {
            return ChordEstimator.estimate(four(SIXTH_COMBINED), four(SIXTH_TREBLE),
                            four(SIXTH_BASS), ablation(residual), beatTimes(4))
                    .chords().get(0).symbol();
        }

        private static String halfLabel(double[] residual) {
            return ChordEstimator.estimate(four(HALF_COMBINED), four(HALF_TREBLE),
                            four(HALF_BASS), ablation(residual), beatTimes(4))
                    .chords().get(0).symbol();
        }

        private static String bluesLabel(double[] residual) {
            return ChordEstimator.estimate(four(BLUES_COMBINED), four(BLUES_TREBLE),
                            four(BLUES_BASS), ablation(residual), beatTimes(4))
                    .chords().get(0).symbol();
        }

        @Test
        @DisplayName("names a sixth the fold hears as a seventh")
        void reportsASixth() {
            assertThat(sixthLabel(SIXTH_RESIDUAL)).isEqualTo("Cm6");
        }

        @Test
        @DisplayName("a sixth the fit does not need is not a sixth")
        void aSixthTheFitDoesNotNeedIsNotReported() {
            // The same run with its sixth moved into the band this run's own
            // silent notes sit in — its B, which the music does not play. The
            // chroma is untouched and still puts more on the sixth than on the
            // flat seventh, so the sixth is the only thing that has changed
            // hands, and the label falls back to what the fold alone says.
            double[] silent = SIXTH_RESIDUAL.clone();
            silent[9] = SIXTH_RESIDUAL[11];

            assertThat(sixthLabel(silent)).isEqualTo("Cm7");
        }

        @Test
        @DisplayName("a sixth is no evidence about the seventh on its own root")
        void aSixthDoesNotWithdrawSeventhsElsewhereOnItsRoot() {
            // The count that settles the minor seventh across a root's runs
            // reads neither side of a sixth. Counted in the total alone it
            // would be a vote against, and one that cannot come out right: the
            // withdrawal it triggers drops the other runs to triads, so a
            // recording that really states sixths would still not be labelled
            // with them. Here the third C run is an unaltered Cm7 and keeps its
            // seventh; counting the two Cm6 runs against it takes it away.
            double[] seventh = SIXTH_TREBLE.clone();
            seventh[9] = SIXTH_TREBLE[10];
            seventh[10] = SIXTH_TREBLE[9];
            double[] onG = spread(7, 0.30, 10, 0.24, 2, 0.22);
            double[] gBass = spread(7, 0.74);

            Chroma treble = beats(SIXTH_TREBLE, SIXTH_TREBLE, SIXTH_TREBLE, SIXTH_TREBLE,
                    onG, onG, onG, onG,
                    SIXTH_TREBLE, SIXTH_TREBLE, SIXTH_TREBLE, SIXTH_TREBLE,
                    onG, onG, onG, onG,
                    seventh, seventh, seventh, seventh);
            Chroma combined = beats(SIXTH_COMBINED, SIXTH_COMBINED, SIXTH_COMBINED,
                    SIXTH_COMBINED, onG, onG, onG, onG,
                    SIXTH_COMBINED, SIXTH_COMBINED, SIXTH_COMBINED, SIXTH_COMBINED,
                    onG, onG, onG, onG,
                    SIXTH_COMBINED, SIXTH_COMBINED, SIXTH_COMBINED, SIXTH_COMBINED);
            Chroma bass = beats(SIXTH_BASS, SIXTH_BASS, SIXTH_BASS, SIXTH_BASS,
                    gBass, gBass, gBass, gBass,
                    SIXTH_BASS, SIXTH_BASS, SIXTH_BASS, SIXTH_BASS,
                    gBass, gBass, gBass, gBass,
                    SIXTH_BASS, SIXTH_BASS, SIXTH_BASS, SIXTH_BASS);

            assertThat(ChordEstimator.estimate(combined, treble, bass,
                            over(20, SIXTH_RESIDUAL), beatTimes(20)).chords())
                    .extracting(Chord::symbol).containsSubsequence("Cm6", "Cm6", "Cm7");
        }

        @Test
        @DisplayName("names the diminished fifth the vocabulary had no word for")
        void reportsAHalfDiminishedSeventh() {
            assertThat(halfLabel(HALF_RESIDUAL)).isEqualTo("Am7b5");
        }

        @Test
        @DisplayName("the perfect fifth is what decides it, not the template's size")
        void aMinorSeventhIsStillAMinorSeventh() {
            // The same run with the fifth and the flat fifth exchanged in the
            // chroma. The two candidates are the same size and share every
            // other note, so this is the whole of what the fold has to go on.
            double[] fifth = HALF_TREBLE.clone();
            fifth[3] = HALF_TREBLE[4];
            fifth[4] = HALF_TREBLE[3];

            assertThat(ChordEstimator.estimate(four(HALF_COMBINED), four(fifth),
                            four(HALF_BASS), ablation(HALF_RESIDUAL), beatTimes(4))
                    .chords().get(0).symbol()).isEqualTo("Am7");
        }

        @Test
        @DisplayName("a diminished fifth the fit does not need is not one")
        void aFlatFifthTheFitDoesNotNeedIsNotReported() {
            // The recording's truth holds G7 twice a cycle and no flat fifth
            // anywhere: the fold takes the half-diminished on this run, and the
            // residual says the C sharp explains nothing. Voice one — the same
            // run with its flat fifth at the share the package's played one
            // carries — and the label comes back.
            double[] played = BLUES_RESIDUAL.clone();
            played[1] = 0.27 * BLUES_RESIDUAL[7];

            assertThat(bluesLabel(BLUES_RESIDUAL)).isEqualTo("Gm7");
            assertThat(bluesLabel(played)).isEqualTo("Gm7b5");
        }

        @Test
        @DisplayName("a half-diminished run is evidence for the seventh on its own root")
        void theCrossRunCountReadsBothSevenths() {
            // The count that settles the minor seventh across a root's runs
            // reads the half-diminished too, because it is the same seventh on
            // the same root. Counting only the plain minor seventh, the A runs
            // here carry one between them out of three and the second Am7 is
            // withdrawn to a triad — a quality the recording states plainly,
            // lost to a label on the same root that agrees with it.
            double[] halfDiminished = spread(9, 0.22, 0, 0.14, 3, 0.19, 7, 0.14);
            double[] minorSeventh = spread(9, 0.22, 0, 0.14, 4, 0.19, 7, 0.14);
            double[] gMinor = spread(7, 0.28, 10, 0.22, 2, 0.20);
            double[] onA = spread(9, 0.74);
            double[] onG = spread(7, 0.74);

            Chroma treble = beats(halfDiminished, halfDiminished, halfDiminished,
                    halfDiminished, halfDiminished, halfDiminished,
                    gMinor, gMinor, gMinor, gMinor,
                    minorSeventh, minorSeventh, minorSeventh);
            Chroma bass = beats(onA, onA, onA, onA, onA, onA,
                    onG, onG, onG, onG, onA, onA, onA);

            // A subsequence: the beat where the bass window holds both roots is
            // labelled on its own, which is the chatter #201 is about and not
            // what this pins.
            assertThat(ChordEstimator.estimate(treble, treble, bass, beatTimes(13)).chords())
                    .extracting(Chord::symbol).containsSubsequence("Am7b5", "Gm", "Am7");
        }

        /** A chroma from {@code pitchClass, share} pairs, the rest spread evenly. */
        private static double[] spread(double... pairs) {
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
    }

    /**
     * #558: the third decided across every run on a root rather than run by run.
     *
     * <p>Three runs of {@code uncommitted/la-canzone-del-sole.mp3}, a recording
     * confirmed to hold no minor chord: two the estimator reads on A and one it
     * reads on D, with each run's own chroma in both registers and its own
     * residual. The second A run is one of the false minors the recording's
     * harness row counts.
     */
    @Nested
    @DisplayName("the third across a root's runs (#558)")
    class ThirdPerRoot {

        private static final double[] A_COMBINED = {
                0.0452, 0.1199, 0.0608, 0.0303, 0.2079, 0.0215,
                0.0229, 0.0277, 0.0906, 0.3246, 0.0039, 0.0448};
        private static final double[] A_TREBLE = {
                0.0449, 0.2054, 0.0882, 0.0391, 0.2444, 0.0355,
                0.0222, 0.0025, 0.1133, 0.1180, 0.0069, 0.0798};
        private static final double[] A_BASS = {
                0.0492, 0.0023, 0.0352, 0.0211, 0.1661, 0.0009,
                0.0253, 0.0595, 0.0607, 0.5738, 0.0000, 0.0059};
        private static final double[] A_RESIDUAL = {
                0.0026, 0.0375, 0.0038, 0.0000, 0.0898, 0.0044,
                0.0001, 0.0000, 0.0109, 0.2010, 0.0000, 0.0390};

        private static final double[] MINOR_COMBINED = {
                0.0257, 0.0567, 0.0375, 0.0161, 0.2639, 0.0239,
                0.0221, 0.0224, 0.0272, 0.3654, 0.0277, 0.1114};
        private static final double[] MINOR_TREBLE = {
                0.0332, 0.0924, 0.0324, 0.0208, 0.3351, 0.0182,
                0.0308, 0.0138, 0.0447, 0.2434, 0.0292, 0.1061};
        private static final double[] MINOR_BASS = {
                0.0137, 0.0039, 0.0465, 0.0087, 0.1531, 0.0335,
                0.0088, 0.0358, 0.0006, 0.5488, 0.0251, 0.1215};
        private static final double[] MINOR_RESIDUAL = {
                0.0447, 0.0120, 0.0001, 0.0000, 0.2831, 0.0000,
                0.0001, 0.0000, 0.0053, 0.5158, 0.0000, 0.0370};

        private static final double[] D_COMBINED = {
                0.0086, 0.0591, 0.3431, 0.0066, 0.1145, 0.0093,
                0.0653, 0.0488, 0.0161, 0.3018, 0.0090, 0.0177};
        private static final double[] D_TREBLE = {
                0.0124, 0.0959, 0.2274, 0.0070, 0.1805, 0.0101,
                0.0903, 0.0080, 0.0103, 0.3371, 0.0115, 0.0094};
        private static final double[] D_BASS = {
                0.0034, 0.0065, 0.5007, 0.0070, 0.0284, 0.0084,
                0.0279, 0.1047, 0.0232, 0.2544, 0.0063, 0.0290};
        private static final double[] D_RESIDUAL = {
                0.0009, 0.0725, 1.2379, 0.0000, 0.2964, 0.0000,
                0.0359, 0.0273, 0.0000, 1.0165, 0.0007, 0.0000};

        /**
         * {@code majorBeats} beats of the A run, four of the D run that separates
         * them, then four of the run that reads minor. Every span answers with
         * its own residual, keyed by where the span starts.
         */
        /**
         * The same minor run with a flat seventh voiced over it, so its own
         * chroma reads a minor seventh and both per-root rules act on that one
         * run.
         */
        private static final double[] MINOR_SEVENTH_TREBLE = {
                0.0332, 0.0924, 0.0324, 0.0208, 0.2351, 0.0182,
                0.0308, 0.1138, 0.0447, 0.2434, 0.0292, 0.1061};

        private static ChordEstimator.Decoded decoded(int majorBeats) {
            return decoded(majorBeats, MINOR_TREBLE);
        }

        private static ChordEstimator.Decoded decoded(int majorBeats, double[] minorTreble) {
            int total = majorBeats + 8;
            double[][] combined = new double[total][];
            double[][] treble = new double[total][];
            double[][] bass = new double[total][];
            for (int beat = 0; beat < total; beat++) {
                boolean minor = beat >= majorBeats + 4;
                boolean onD = !minor && beat >= majorBeats;
                combined[beat] = onD ? D_COMBINED : minor ? MINOR_COMBINED : A_COMBINED;
                treble[beat] = onD ? D_TREBLE : minor ? minorTreble : A_TREBLE;
                bass[beat] = onD ? D_BASS : minor ? MINOR_BASS : A_BASS;
            }
            PitchClassAblation residual = new PitchClassAblation() {
                @Override
                public int spanCount() {
                    return total;
                }

                @Override
                public double[] significanceOver(int fromSpan, int toSpan) {
                    return fromSpan >= majorBeats + 4 ? MINOR_RESIDUAL
                            : fromSpan >= majorBeats ? D_RESIDUAL : A_RESIDUAL;
                }
            };
            return ChordEstimator.explain(beats(combined), beats(treble), beats(bass),
                    residual, beatTimes(total));
        }

        private static List<Chord> chords(int majorBeats) {
            return decoded(majorBeats).chords().chords();
        }

        @Test
        @DisplayName("a minor third a minority of a root's beats hold is withdrawn")
        void aMinorityMinorThirdIsWithdrawn() {
            // Eight beats of A against four that read minor: the recording
            // states a major third on this root, so the odd run out is read as
            // one too.
            assertThat(chords(8)).extracting(Chord::symbol)
                    .containsExactly("A", "D", "A");
        }

        @Test
        @DisplayName("a minor third most of a root's beats hold is left alone")
        void aMajorityMinorThirdStands() {
            // The same runs with the major one cut to three beats, so the minor
            // third now holds most of the root's. Nothing here says a minor
            // chord is wrong -- only that this recording does not state one.
            assertThat(chords(3)).extracting(Chord::symbol)
                    .containsExactly("A", "D", "Am");
        }

        @Test
        @DisplayName("the span the count renamed says so, and the count says what it read")
        void theCountThatRenamedTheRunIsWrittenDown() {
            // The fact a reader of the chart cannot guess: the last run read
            // minor on its own evidence and carries a major label because of
            // the other spans on its root (#677).
            ChordTrace trace = decoded(8).trace();

            List<ChordTrace.Span> spans = trace.spans();
            assertThat(spans).extracting(ChordTrace.Span::chord)
                    .containsExactly("A", "D", "A");
            assertThat(spans).extracting(ChordTrace.Span::settledBy)
                    .containsExactly("decoder", "decoder", "thirds");
            // The decoder never had this run minor: the run's own chroma made
            // it minor and the count took it back, and only the three labels
            // together say that.
            assertThat(spans.get(2).decoded().chord()).isEqualTo("A");
            assertThat(spans.get(2).fromRun()).isEqualTo("Am");

            ChordTrace.Root a = trace.roots().stream()
                    .filter(root -> root.root().equals("A")).findFirst().orElseThrow();
            assertThat(a.thirds().stated()).isEqualTo(4);
            assertThat(a.thirds().beats()).isEqualTo(12);
            assertThat(a.thirds().read()).isEqualTo("minority");
            assertThat(a.thirds().runsChanged()).isEqualTo(1);
        }

        @Test
        @DisplayName("the count that left the runs alone is written down as that")
        void aCountThatChangedNothingIsWrittenDown() {
            ChordTrace trace = decoded(3).trace();

            // The same run, named by its own chroma this time and by no count.
            assertThat(trace.spans()).extracting(ChordTrace.Span::settledBy)
                    .containsExactly("decoder", "decoder", "run");
            assertThat(trace.spans().get(2).fromRun()).isEqualTo("Am");
            ChordTrace.Root a = trace.roots().stream()
                    .filter(root -> root.root().equals("A")).findFirst().orElseThrow();
            assertThat(a.thirds().read()).isEqualTo("majority");
            assertThat(a.thirds().runsChanged()).isZero();
        }

        @Test
        @DisplayName("a run both counts rewrote is one rewrite to each of them")
        void aRunRewrittenTwiceIsCountedByBothRules() {
            // The label a span carries names only the decision that set it
            // last, so counting rewrites off it loses the earlier one — and a
            // root whose seventh count did work would read as having done none.
            ChordTrace trace = decoded(8, MINOR_SEVENTH_TREBLE).trace();

            assertThat(trace.spans()).extracting(ChordTrace.Span::chord)
                    .containsExactly("A", "D", "A7");
            assertThat(trace.spans().get(2).fromRun()).isEqualTo("Am7");
            assertThat(trace.spans().get(2).settledBy()).isEqualTo("thirds");
            ChordTrace.Root a = trace.roots().stream()
                    .filter(root -> root.root().equals("A")).findFirst().orElseThrow();
            assertThat(a.sevenths().runsChanged()).isEqualTo(1);
            assertThat(a.thirds().runsChanged()).isEqualTo(1);
        }

        @Test
        @DisplayName("one span per chord, over the same beats and the same seconds")
        void theTraceKeysToTheProgression() {
            // Span i of the trace has to be span i of the progression and of
            // the chroma trace beside it: the index is the only thing tying a
            // recorded reading to the decision made on it.
            ChordEstimator.Decoded decoded = decoded(8);

            List<Chord> chords = decoded.chords().chords();
            List<ChordTrace.Span> spans = decoded.trace().spans();
            assertThat(spans).hasSameSizeAs(chords);
            assertThat(spans.get(0).fromBeat()).isZero();
            assertThat(spans.get(spans.size() - 1).toBeat()).isEqualTo(16);
            for (int i = 0; i < spans.size(); i++) {
                assertThat(spans.get(i).chord()).isEqualTo(chords.get(i).symbol());
                assertThat(spans.get(i).fromSeconds()).isEqualTo(chords.get(i).startSeconds());
                assertThat(spans.get(i).toSeconds()).isEqualTo(chords.get(i).endSeconds());
                assertThat(spans.get(i).toBeat()).isGreaterThan(spans.get(i).fromBeat());
                if (i > 0) {
                    assertThat(spans.get(i).fromBeat()).isEqualTo(spans.get(i - 1).toBeat());
                }
            }
        }
    }

    /**
     * #583: a true minor on a root the recording otherwise plays major, which
     * {@link ChordEstimator} withdraws with the false ones. Pins the defect
     * rather than a cure; #583 carries why no guard tried so far separates this
     * run from the false minors the corpus holds.
     *
     * <p>Three runs of {@code synthetic_samples/pop-borrowed-iv-c-100.mp3},
     * whose grid states a borrowed {@code Fm} on a root it otherwise plays
     * major: the {@code F} bar of its intro, the {@code C} bar before the
     * borrowed one, and one of the borrowed fourths. Chroma and residual are
     * what the estimator was given there.
     */
    @Nested
    @DisplayName("a borrowed minor fourth (#583)")
    class BorrowedMinor {

        private static final double[] MAJOR_COMBINED = {
                0.2309, 0.0226, 0.0246, 0.0058, 0.0491, 0.2989,
                0.0188, 0.0953, 0.0270, 0.1813, 0.0185, 0.0272};
        private static final double[] MAJOR_TREBLE = {
                0.2402, 0.0313, 0.0195, 0.0070, 0.0600, 0.2239,
                0.0191, 0.1172, 0.0247, 0.2275, 0.0027, 0.0269};
        private static final double[] MAJOR_BASS = {
                0.2104, 0.0036, 0.0357, 0.0029, 0.0254, 0.4630,
                0.0180, 0.0482, 0.0319, 0.0807, 0.0532, 0.0269};
        private static final double[] MAJOR_RESIDUAL = {
                0.6146, 0.0119, 0.0027, 0.0000, 0.0595, 0.7474,
                0.0049, 0.1504, 0.0076, 0.6340, 0.0016, 0.0218};

        private static final double[] MINOR_COMBINED = {
                0.2491, 0.0024, 0.0076, 0.0667, 0.0669, 0.2912,
                0.0128, 0.0732, 0.1482, 0.0520, 0.0123, 0.0176};
        private static final double[] MINOR_TREBLE = {
                0.2385, 0.0025, 0.0061, 0.0951, 0.0889, 0.2045,
                0.0094, 0.0971, 0.2051, 0.0362, 0.0024, 0.0143};
        private static final double[] MINOR_BASS = {
                0.2733, 0.0023, 0.0110, 0.0017, 0.0169, 0.4897,
                0.0205, 0.0185, 0.0177, 0.0883, 0.0348, 0.0253};
        private static final double[] MINOR_RESIDUAL = {
                0.5679, 0.0000, 0.0000, 0.0728, 0.0854, 0.5962,
                0.0029, 0.0852, 0.2598, 0.0149, 0.0021, 0.0021};

        private static final double[] C_COMBINED = {
                0.3759, 0.0130, 0.0431, 0.0026, 0.1671, 0.0285,
                0.0065, 0.2078, 0.0233, 0.0430, 0.0112, 0.0780};
        private static final double[] C_TREBLE = {
                0.2601, 0.0084, 0.0560, 0.0008, 0.2366, 0.0189,
                0.0023, 0.2770, 0.0235, 0.0243, 0.0012, 0.0909};
        private static final double[] C_BASS = {
                0.6504, 0.0243, 0.0121, 0.0066, 0.0033, 0.0491,
                0.0157, 0.0462, 0.0222, 0.0887, 0.0338, 0.0476};
        private static final double[] C_RESIDUAL = {
                1.8496, 0.0005, 0.0642, 0.0000, 0.6193, 0.0035,
                0.0000, 0.7766, 0.0080, 0.0135, 0.0014, 0.3943};

        /**
         * {@code majorBeats} beats of the F run, four of the C run that
         * separates them, then four of the borrowed fourth, each span answering
         * with its own residual.
         */
        private static List<Chord> chords(int majorBeats) {
            int total = majorBeats + 8;
            double[][] combined = new double[total][];
            double[][] treble = new double[total][];
            double[][] bass = new double[total][];
            for (int beat = 0; beat < total; beat++) {
                boolean minor = beat >= majorBeats + 4;
                boolean onC = !minor && beat >= majorBeats;
                combined[beat] = onC ? C_COMBINED : minor ? MINOR_COMBINED : MAJOR_COMBINED;
                treble[beat] = onC ? C_TREBLE : minor ? MINOR_TREBLE : MAJOR_TREBLE;
                bass[beat] = onC ? C_BASS : minor ? MINOR_BASS : MAJOR_BASS;
            }
            PitchClassAblation residual = new PitchClassAblation() {
                @Override
                public int spanCount() {
                    return total;
                }

                @Override
                public double[] significanceOver(int fromSpan, int toSpan) {
                    return fromSpan >= majorBeats + 4 ? MINOR_RESIDUAL
                            : fromSpan >= majorBeats ? C_RESIDUAL : MAJOR_RESIDUAL;
                }
            };
            return ChordEstimator.estimate(beats(combined), beats(treble), beats(bass),
                    residual, beatTimes(total)).chords();
        }

        @Test
        @DisplayName("a borrowed fourth is withdrawn with the false minors (#583)")
        void aBorrowedFourthGoesWithTheCount() {
            // Eight beats of F against four of the borrowed fourth. The run's
            // own chroma and residual both state the minor third and neither
            // states the major one, and the count withdraws it anyway.
            assertThat(chords(8)).extracting(Chord::symbol)
                    .containsExactly("F", "C", "F7");
        }

        @Test
        @DisplayName("the same run keeps its third where the count does not fire")
        void aBorrowedFourthOnItsOwnStands() {
            // The major run cut to three beats, so the minor third holds most
            // of the root's: what the run says is read as it is.
            assertThat(chords(3)).extracting(Chord::symbol)
                    .containsExactly("F", "C", "Fm");
        }
    }

    /**
     * Both readings are of real recordings: a {@code Cmaj7} run of {@code
     * samples/jazz-251-c-140.mp3}, whose guitar leaves the root to the bass, and
     * a {@code C} bar of {@code samples/pop-c-g-am-f-120.mp3}, the plain-triad
     * control. Chroma and residual are what the estimator was given there.
     */
    @Nested
    @DisplayName("the major seventh in the decoder (#588)")
    class MajorSeventh {

        private static final double[] JAZZ_COMBINED = {
                0.1161, 0.0104, 0.1296, 0.0306, 0.1632, 0.0152,
                0.0151, 0.1746, 0.0130, 0.0547, 0.0053, 0.2723};
        private static final double[] JAZZ_TREBLE = {
                0.0067, 0.0104, 0.1744, 0.0347, 0.2218, 0.0078,
                0.0202, 0.1220, 0.0149, 0.0347, 0.0075, 0.3449};
        private static final double[] JAZZ_BASS = {
                0.3231, 0.0110, 0.0453, 0.0235, 0.0595, 0.0291,
                0.0063, 0.2554, 0.0095, 0.0900, 0.0009, 0.1464};

        /**
         * The seventh removes several times the residual the root does, where the
         * chroma has it merely as the loudest pitch class of an {@code Em} triad.
         */
        private static final double[] JAZZ_RESIDUAL = {
                0.2109, 0.0002, 0.1764, 0.0068, 0.2642, 0.0003,
                0.0043, 0.3062, 0.0062, 0.0175, 0.0000, 0.9081};

        private static final double[] POP_COMBINED = {
                0.3433, 0.0135, 0.0655, 0.0301, 0.1301, 0.0467,
                0.0150, 0.2057, 0.0265, 0.0342, 0.0137, 0.0756};
        private static final double[] POP_TREBLE = {
                0.2860, 0.0008, 0.0542, 0.0077, 0.1946, 0.0641,
                0.0231, 0.2841, 0.0030, 0.0164, 0.0061, 0.0599};
        private static final double[] POP_BASS = {
                0.4418, 0.0351, 0.0869, 0.0690, 0.0218, 0.0184,
                0.0015, 0.0720, 0.0630, 0.0648, 0.0259, 0.0998};

        /** The same degree, on a bar whose chord is a plain triad. */
        private static final double[] POP_RESIDUAL = {
                1.0237, 0.0000, 0.0205, 0.0026, 0.2650, 0.0158,
                0.0141, 0.3651, 0.0063, 0.0017, 0.0008, 0.0943};

        private static Chroma four(double[] vector) {
            return beats(vector, vector, vector, vector);
        }

        private static PitchClassAblation ablation(double[] residual) {
            return new PitchClassAblation() {
                @Override
                public int spanCount() {
                    return 4;
                }

                @Override
                public double[] significanceOver(int fromSpan, int toSpan) {
                    return residual;
                }
            };
        }

        private static String label(double[] combined, double[] treble, double[] bass,
                                    double[] residual) {
            return ChordEstimator.estimate(four(combined), four(treble), four(bass),
                            ablation(residual), beatTimes(4))
                    .chords().get(0).symbol();
        }

        private static ChordTrace.Span span(double[] combined, double[] treble, double[] bass,
                                            double[] residual) {
            return ChordEstimator.explain(four(combined), four(treble), four(bass),
                    ablation(residual), beatTimes(4)).trace().spans().get(0);
        }

        /** {@code residual} with the major seventh above C cut to a tenth of the root's. */
        private static double[] withoutTheSeventh(double[] residual) {
            double[] out = residual.clone();
            out[11] = 0.1 * residual[0];
            return out;
        }

        @Test
        @DisplayName("names a major seventh whose root only the bass states")
        void reportsAMajorSeventh() {
            assertThat(label(JAZZ_COMBINED, JAZZ_TREBLE, JAZZ_BASS, JAZZ_RESIDUAL))
                    .isEqualTo("Cmaj7");
        }

        @Test
        @DisplayName("the seventh's residual is what reaches the root, not its chroma")
        void withoutTheResidualTheRootIsLostToo() {
            // Same chroma, the same B still its loudest pitch class: with the
            // fit no longer needing that pitch class the run is not on C at all,
            // which is why a relabelling of the decoder's answer could not have
            // reached these bars.
            assertThat(label(JAZZ_COMBINED, JAZZ_TREBLE, JAZZ_BASS,
                    withoutTheSeventh(JAZZ_RESIDUAL))).isEqualTo("G");
        }

        @Test
        @DisplayName("a seventh the fit does not need is not reported")
        void aSeventhTheFitDoesNotNeedIsNotReported() {
            assertThat(label(POP_COMBINED, POP_TREBLE, POP_BASS, POP_RESIDUAL))
                    .isEqualTo("C");
        }

        @Test
        @DisplayName("both halves of the seventh's gate are written down (#677)")
        void bothHalvesOfTheGateAreWrittenDown() {
            // The decoder's half is asked per beat and the quality decision's
            // once per run, so the trace carries one as a count of beats and
            // the other as the reading it compared.
            ChordTrace.Span found = span(JAZZ_COMBINED, JAZZ_TREBLE, JAZZ_BASS, JAZZ_RESIDUAL);
            ChordTrace.Span plain = span(POP_COMBINED, POP_TREBLE, POP_BASS, POP_RESIDUAL);

            assertThat(found.chord()).isEqualTo("Cmaj7");
            assertThat(found.majorSeventhBeats()).isEqualTo(4);
            assertThat(plain.chord()).isEqualTo("C");
            assertThat(plain.majorSeventhBeats()).isZero();
            assertThat(plain.gates()).filteredOn(gate -> gate.degree().equals("major seventh"))
                    .singleElement()
                    .satisfies(gate -> {
                        assertThat(gate.counted()).isFalse();
                        assertThat(gate.reading()).isEqualTo(0.0943);
                        assertThat(gate.required()).isEqualTo(0.5119);
                    });
        }

        @Test
        @DisplayName("a residual that measured nothing does not open the gate")
        void aResidualThatMeasuredNothingDoesNotOpenTheGate() {
            // The all-zero answer PitchClassAblation gives for spans holding
            // nothing to fit. Read as a comparison it clears every share, since
            // the root removes nothing either; read as evidence it is the same
            // silence as no ablation at all, and that is what it has to be for
            // a template admitted on the residual alone.
            assertThat(label(JAZZ_COMBINED, JAZZ_TREBLE, JAZZ_BASS, new double[12]))
                    .isEqualTo(ChordEstimator.estimate(four(JAZZ_COMBINED),
                                    four(JAZZ_TREBLE), four(JAZZ_BASS), beatTimes(4))
                            .chords().get(0).symbol());
        }

        /** A bass naming C and nothing else, so the prior cannot lose the root. */
        private static double[] rootedBass() {
            double[] out = new double[12];
            java.util.Arrays.fill(out, 0.01);
            out[0] = 0.89;
            return out;
        }

        @Test
        @DisplayName("the confidence reported is the chord's own fit, not the decoder's gate")
        void theConfidenceDoesNotCarryTheGate() {
            // A run the quality decision names a major seventh over beats the
            // decoder's own gate closed: the two read the same residual at
            // different shares, so this is the ordinary case rather than a
            // contrived one. The confidence a caller reads answers how well the
            // reported chord explains the mix, so it cannot depend on a gate --
            // and a floor downstream turns on it (PlayableMelody).
            double[] gated = JAZZ_RESIDUAL.clone();
            gated[11] = MIDWAY_BETWEEN_THE_TWO_GATES * JAZZ_RESIDUAL[0];

            Chord open = ChordEstimator.estimate(four(JAZZ_COMBINED), four(JAZZ_TREBLE),
                    four(rootedBass()), ablation(JAZZ_RESIDUAL), beatTimes(4)).chords().get(0);
            Chord closed = ChordEstimator.estimate(four(JAZZ_COMBINED), four(JAZZ_TREBLE),
                    four(rootedBass()), ablation(gated), beatTimes(4)).chords().get(0);

            assertThat(open.symbol()).isEqualTo("Cmaj7");
            assertThat(closed.symbol()).isEqualTo("Cmaj7");
            assertThat(closed.confidence().value())
                    .isEqualTo(open.confidence().value(), within(1e-12));
        }

        /**
         * A share the quality decision's gate admits and the decoder's does not,
         * written as a fraction of the root's residual the way both gates read
         * it. Any value strictly between the two constants does.
         */
        private static final double MIDWAY_BETWEEN_THE_TWO_GATES = 1.0;

        @Test
        @DisplayName("the residual alone does not put a seventh on a triad")
        void theResidualAloneIsNotEnough() {
            // The same bar with the degree's residual raised past both gates.
            // Neither is a claim that the chord has four notes: the chroma has
            // to carry it too, and a four-note template scored over its own
            // larger norm loses to the triad it contains.
            double[] loud = POP_RESIDUAL.clone();
            loud[11] = 2 * POP_RESIDUAL[0];
            assertThat(label(POP_COMBINED, POP_TREBLE, POP_BASS, loud)).isEqualTo("C");
        }
    }

    /** What the trace says where there was nothing to decide (#677). */
    @Nested
    @DisplayName("the decoder's record of a span it had no evidence for")
    class DecoderTraceWithoutEvidence {

        private static PitchClassAblation nothing(int spans) {
            return new PitchClassAblation() {
                @Override
                public int spanCount() {
                    return spans;
                }

                @Override
                public double[] significanceOver(int fromSpan, int toSpan) {
                    return new double[12];
                }
            };
        }

        private static Chroma four(double[] vector) {
            return beats(vector, vector, vector, vector);
        }

        @Test
        @DisplayName("silence names no chord, gates nothing and hears no root in the bass")
        void aSilentSpanIsRecordedAsHavingDecidedNothing() {
            // A blind record would print what a decided one prints, so each of
            // these absences has to read as an absence rather than as a
            // reading: no gate ran, and a bass that dropped out names no root.
            ChordTrace.Span span = ChordEstimator.explain(four(new double[12]),
                    four(new double[12]), four(new double[12]), nothing(4), beatTimes(4))
                    .trace().spans().get(0);

            assertThat(span.chord()).isEqualTo("N.C.");
            assertThat(span.decoded().chord()).isEqualTo("N.C.");
            assertThat(span.settledBy()).isEqualTo("decoder");
            assertThat(span.gates()).isEmpty();
            assertThat(span.bassRoot()).isNull();
            assertThat(span.bassOnDecoded()).isZero();
            assertThat(span.majorSeventhBeats()).isNull();
        }

        @Test
        @DisplayName("a residual that needed nothing on the root gates nothing")
        void aRootTheFitNeedsNothingOnCarriesNoGate() {
            // The all-zero answer PitchClassAblation gives for a span holding
            // nothing to fit. Read as a comparison every share is cleared by
            // every value, so rows would say the fit admitted each degree when
            // it measured none of them.
            double[] triad = new double[12];
            java.util.Arrays.fill(triad, 0.01);
            triad[0] = 0.35;
            triad[4] = 0.28;
            triad[7] = 0.29;

            ChordTrace.Span span = ChordEstimator.explain(four(triad), four(triad), four(triad),
                    nothing(4), beatTimes(4)).trace().spans().get(0);

            assertThat(span.chord()).isNotEqualTo("N.C.");
            assertThat(span.gates()).isEmpty();
        }

        @Test
        @DisplayName("a decode with no beat to decode over records no span and no root")
        void anEmptyDecodeRecordsNothing() {
            ChordEstimator.Decoded decoded = ChordEstimator.explain(beats(), beats(), beats(),
                    nothing(0), List.of(0.0, 0.5));

            assertThat(decoded.chords().chords()).isEmpty();
            assertThat(decoded.trace().spans()).isEmpty();
            assertThat(decoded.trace().roots()).isEmpty();
        }

        @Test
        @DisplayName("the runner-up is a state the decoder could have taken instead")
        void theRunnerUpIsScoredOverTheSameBeats() {
            // A plain C triad under a bass on C. What the runner-up is depends
            // on the vocabulary and is not the point; that it is a rival the
            // path could have taken, scored over the same beats and beaten, is.
            double[] triad = new double[12];
            java.util.Arrays.fill(triad, 0.01);
            triad[0] = 0.35;
            triad[4] = 0.28;
            triad[7] = 0.29;
            double[] bass = new double[12];
            java.util.Arrays.fill(bass, 0.01);
            bass[0] = 0.6;

            ChordTrace.Span span = ChordEstimator.explain(four(triad), four(triad), four(bass),
                    nothing(4), beatTimes(4)).trace().spans().get(0);

            assertThat(span.decoded().chord()).isEqualTo("C");
            assertThat(span.runnerUp()).isNotNull();
            assertThat(span.runnerUp().chord()).isNotEqualTo(span.decoded().chord());
            assertThat(span.decoded().score()).isGreaterThan(span.runnerUp().score());
            assertThat(span.bassRoot()).isEqualTo("C");
        }
    }
}
