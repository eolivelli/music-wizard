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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

/**
 * The NNLS front end, tested on the thing it exists to do: attribute a note's
 * partials to the note rather than to the pitch classes they land on.
 */
class NnlsChromaTest {

    private static final int RATE = SignalFactory.DEFAULT_SAMPLE_RATE;

    /**
     * A single sustained note with a realistic harmonic series.
     *
     * <p>Not {@link SignalFactory#chord}, which sums equal-amplitude sines. The
     * point of these fixtures is that the partials roll off the way a plucked or
     * bowed note's do, because a flat harmonic series is not what the dictionary
     * is a model of and would test the front end against a signal no instrument
     * produces.
     */
    private static float[] harmonicNote(double fundamentalHz, int partials, double seconds) {
        int length = (int) Math.round(seconds * RATE);
        float[] out = new float[length];
        for (int partial = 1; partial <= partials; partial++) {
            double frequency = fundamentalHz * partial;
            if (frequency >= RATE / 2.0) {
                break;
            }
            double amplitude = 0.35 * Math.pow(0.75, partial - 1);
            for (int i = 0; i < length; i++) {
                out[i] += (float) (amplitude * Math.sin(2 * Math.PI * frequency * i / RATE));
            }
        }
        return out;
    }

    /** The mean chroma over the frames of a steady signal, scaled to sum to one. */
    private static double[] meanChroma(Chroma chroma) {
        double[] total = new double[12];
        for (double[] frame : chroma.vectors()) {
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                total[pitchClass] += frame[pitchClass];
            }
        }
        double sum = 0;
        for (double value : total) {
            sum += value;
        }
        if (sum > 0) {
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                total[pitchClass] /= sum;
            }
        }
        return total;
    }

    @Nested
    @DisplayName("approximate transcription")
    class Transcription {

        @Test
        @DisplayName("attributes a note's partials to the note, where plain chroma spells a triad")
        void stripsThePartialsThatMasqueradeAsChordTones() {
            // The mechanism behind #185, in one signal. A single low C with six
            // partials puts energy at C, C, G, C, E, G -- a C major triad, played
            // by one finger. Plain chroma has no way to know that and reports the
            // triad; the whole reason NNLS is here is that it does.
            AudioBuffer audio = new AudioBuffer(
                    harmonicNote(SignalFactory.midiToHz(36), 6, 3.0), RATE);

            double[] plain = meanChroma(Chroma.extract(audio));
            double[] nnls = meanChroma(NnlsChroma.extract(audio).bass());

            // C=0, E=4, G=7. Plain chroma genuinely spells the triad: the two
            // pitch classes nobody played carry a large share of the energy.
            assertThat(plain[4] + plain[7]).isGreaterThan(0.3);

            // NNLS keeps the C and collapses the invented E and G. Parenthesised
            // deliberately: written as "plain[4] + plain[7] / 4" this divides
            // only the G term, which makes the bound roughly four times looser
            // than it reads and would pass on a front end that removed almost
            // nothing.
            assertThat(nnls[0]).isGreaterThan(0.8);
            assertThat(nnls[4] + nnls[7]).isLessThan((plain[4] + plain[7]) / 4);
        }

        @Test
        @DisplayName("keeps the three notes of a triad that really was played")
        void keepsRealChordTones() {
            // The converse of the test above, and the one that stops it from
            // being satisfied by a front end that simply reports the lowest note.
            // Three separate harmonic notes, C4 E4 G4, must come back as three
            // pitch classes rather than as one.
            int length = (int) Math.round(3.0 * RATE);
            float[] mix = new float[length];
            for (int midi : new int[] {60, 64, 67}) {
                float[] note = harmonicNote(SignalFactory.midiToHz(midi), 6, 3.0);
                for (int i = 0; i < length; i++) {
                    mix[i] += note[i] / 3;
                }
            }

            double[] nnls = meanChroma(NnlsChroma.extract(new AudioBuffer(mix, RATE)).treble());

            assertThat(nnls[0]).isGreaterThan(0.15);
            assertThat(nnls[4]).isGreaterThan(0.15);
            assertThat(nnls[7]).isGreaterThan(0.15);
            assertThat(nnls[0] + nnls[4] + nnls[7]).isGreaterThan(0.75);
        }

        @Test
        @DisplayName("separates a note into the register it was played in")
        void splitsTrebleFromBass() {
            AudioBuffer low = new AudioBuffer(
                    harmonicNote(SignalFactory.midiToHz(33), 6, 2.0), RATE);   // A1
            AudioBuffer high = new AudioBuffer(
                    harmonicNote(SignalFactory.midiToHz(69), 6, 2.0), RATE);   // A4

            NnlsChroma fromLow = NnlsChroma.extract(low);
            NnlsChroma fromHigh = NnlsChroma.extract(high);

            // Asymmetric bounds, and the asymmetry is the finding rather than a
            // convenience. A high note is placed confidently -- nothing below it
            // has partials that could account for it, so essentially none of its
            // energy reaches the bass, and the measured ratio is 671. A low note
            // is not, because placing it depends on its upper partials, and
            // those are exactly what NoteDictionary.PARTIAL_ROLL_OFF was lowered
            // to stop trusting: the change that made a chord's pitch classes
            // right made a bass note's octave much less certain. Measured here,
            // the ratio falls from 10.6 at a roll-off of 0.70 to 2.43 at the
            // shipped 0.50.
            //
            // Pinned as floors rather than ranges so that the bass register
            // getting better again -- which is what #194 would do -- does not
            // fail a test whose point is that it must not get worse.
            assertThat(energy(fromLow.bass())).isGreaterThan(2.0 * energy(fromLow.treble()));
            assertThat(energy(fromHigh.treble())).isGreaterThan(400 * energy(fromHigh.bass()));
        }

        @Test
        @DisplayName("follows a recording tuned away from A440")
        void followsTheRecordingsTuning() {
            // A third of a semitone sharp puts every fundamental between two bins
            // of the analysis grid, which is where an uncorrected front end
            // spreads one note over two pitch classes.
            double sharp = Math.pow(2, (1.0 / 3) / 12);
            AudioBuffer audio = new AudioBuffer(
                    harmonicNote(SignalFactory.midiToHz(60) * sharp, 6, 3.0), RATE);
            AudioBuffer inTune = new AudioBuffer(
                    harmonicNote(SignalFactory.midiToHz(60), 6, 3.0), RATE);

            int windowSize = NnlsChroma.windowSizeFor(RATE);
            Spectrogram spectrogram = Spectrogram.compute(audio, windowSize, windowSize / 8);
            double[] corrected = meanChroma(NnlsChroma.extract(spectrogram, 1.0 / 3).treble());
            double[] reference = meanChroma(NnlsChroma.extract(
                    Spectrogram.compute(inTune, windowSize, windowSize / 8), 0).treble());

            // Told the truth about the tuning, the detuned note is read the same
            // way the in-tune one is: the correction has put it back where the
            // dictionary expects it. Compared against the in-tune reading rather
            // than against an absolute figure, because how much of a single
            // note's chroma stays on its own pitch class is a property of the
            // whitening window and the partial roll-off, and pinning it here
            // would make this a test of those instead of of the correction.
            assertThat(corrected[0]).isCloseTo(reference[0], within(0.05));
            assertThat(argMax(corrected)).isZero();

            // The gap the correction closes, asserted directly rather than left
            // for the tolerance above to imply. Without this the test passes on
            // a front end that ignores the offset entirely: argMax is C either
            // way, and at an offset of a quarter semitone the uncorrected
            // reading is already inside the 0.05 tolerance. Measured at a third
            // of a semitone: 0.567 uncorrected against 0.631 corrected.
            double[] uncorrected = meanChroma(NnlsChroma.extract(spectrogram, 0).treble());
            assertThat(corrected[0] - uncorrected[0])
                    .as("what telling the front end the truth about tuning is worth")
                    .isGreaterThan(0.04);

            // And the offset is one the estimator can actually find, which is
            // what makes the correction available without being told.
            assertThat(Chroma.estimateTuning(spectrogram)).isCloseTo(1.0 / 3, within(0.05));

            // This used to also assert that the *uncorrected* reading leaks
            // heavily into the neighbour -- specifically that it falls at least
            // 0.1 below the corrected one. That claim is not true and was not
            // measured. Swept across offsets of 1/6, 1/4, 1/3 and 0.45 of a
            // semitone the uncorrected reading is 0.624, 0.582, 0.567 and 1.000
            // against a corrected 0.631 throughout: the damage is smaller than
            // claimed at every offset and is not even monotone, because near
            // half a semitone the fit collapses onto a single column and looks
            // cleaner while being no more correct. The property worth asserting
            // is the one above -- correction reproduces the in-tune reading --
            // and it holds at every offset tried.
        }

        private static int argMax(double[] values) {
            int best = 0;
            for (int i = 1; i < values.length; i++) {
                if (values[i] > values[best]) {
                    best = i;
                }
            }
            return best;
        }

        private static double energy(Chroma chroma) {
            double total = 0;
            for (double[] frame : chroma.vectors()) {
                for (double value : frame) {
                    total += value;
                }
            }
            return total;
        }
    }

    @Nested
    @DisplayName("chord recognition through the new front end")
    class Recognition {

        @Test
        @DisplayName("recovers I-V-vi-IV, so the tier-0 gate does not move")
        void recognisesTheFourChordProgression() {
            // The same fixture and the same assertion as ChordEstimationTest
            // makes of the plain front end. Replacing the front end is only a
            // free choice if the synthetic gate is unchanged by it; if this ever
            // diverges from that test, the two front ends disagree about a case
            // whose answer is known, and that is worth stopping for.
            float[] signal = SignalFactory.clickTrackWithChords(120, new double[][] {
                    SignalFactory.majorTriad(60),
                    SignalFactory.majorTriad(67),
                    SignalFactory.minorTriad(69),
                    SignalFactory.majorTriad(65),
            }, 4, 32, RATE);
            AudioBuffer audio = new AudioBuffer(signal, RATE);
            List<Double> beats = BeatTracker.track(OnsetEnvelope.fromAudio(audio)).beatTimes();

            // Through combined(), which is what AudioTranscriber uses. Asserting
            // this of treble() instead -- as an earlier draft did -- leaves the
            // composition the pipeline actually runs uncovered at unit level,
            // and it is a composition that has already been wrong once.
            ChordProgression chords = ChordEstimator.estimate(
                    NnlsChroma.extract(audio).combined().beatSynchronous(beats), beats);

            assertThat(chords.chords()).extracting(Chord::symbol)
                    .startsWith("C", "G", "Am", "F", "C", "G", "Am", "F");
            assertThat(chords.size()).isBetween(14, 18);
            // 0.753 measured. The treble register alone reaches 0.916 on this
            // fixture, where nothing is playing below A3 at all, so the floor is
            // set for the fold that carries less of it rather than more.
            assertThat(chords.confidence().value()).isGreaterThan(0.6);
        }

        @Test
        @DisplayName("says nothing rather than something about silence")
        void silenceIsNotAChord() {
            AudioBuffer audio = new AudioBuffer(SignalFactory.silence(3.0, RATE), RATE);

            NnlsChroma chroma = NnlsChroma.extract(audio);

            for (double[] frame : chroma.treble().vectors()) {
                assertThat(frame).containsOnly(0.0);
            }
            for (double[] frame : chroma.bass().vectors()) {
                assertThat(frame).containsOnly(0.0);
            }
        }

        @Test
        @DisplayName("survives a signal shorter than one analysis window")
        void handlesASignalShorterThanTheWindow() {
            // windowSizeFor(22050) is 8192 samples, so a tenth of a second yields
            // no frames at all rather than a partial one.
            AudioBuffer audio = new AudioBuffer(SignalFactory.sine(440, 0.1, RATE), RATE);

            NnlsChroma chroma = NnlsChroma.extract(audio);

            assertThat(chroma.treble().frameCount()).isZero();
            assertThat(chroma.bass().frameCount()).isZero();
        }
    }

    @Nested
    @DisplayName("the pair of registers")
    class Registers {

        @Test
        @DisplayName("beat-synchronises both registers or neither")
        void beatSynchronisesBothRegisters() {
            AudioBuffer audio = new AudioBuffer(
                    SignalFactory.clickTrack(120, 8, RATE), RATE);
            List<Double> beats = BeatTracker.track(OnsetEnvelope.fromAudio(audio)).beatTimes();

            NnlsChroma folded = NnlsChroma.extract(audio).beatSynchronous(beats);

            assertThat(folded.treble().frameCount()).isEqualTo(beats.size() - 1);
            assertThat(folded.bass().frameCount()).isEqualTo(beats.size() - 1);
            assertThat(folded.treble().isBeatSynchronous()).isTrue();
            assertThat(folded.bass().isBeatSynchronous()).isTrue();
        }

        @Test
        @DisplayName("folding the registers before and after beat-synchronising differ")
        void foldingBeforeAndAfterAreNotTheSame() {
            // Why combined() refuses a beat-synchronous input, demonstrated
            // rather than asserted. The refusal itself is the test below; this
            // one shows what it is refusing, and has to build the wrong answer
            // by hand because the API no longer will.
            //
            // beatSynchronous scales each span to sum to one. Fold first and
            // that happens once; fold second and it happens to each register
            // separately, so the sum is half treble and half bass in every beat
            // regardless of what they held. Here the signal is entirely in the
            // treble -- a harmonic C4, nothing below A3 -- so the wrong order
            // promotes whatever the bass register invented to an equal share.
            AudioBuffer audio = new AudioBuffer(
                    harmonicNote(SignalFactory.midiToHz(60), 6, 3.0), RATE);
            List<Double> beats = List.of(0.5, 1.0, 1.5, 2.0, 2.5);

            NnlsChroma nnls = NnlsChroma.extract(audio);
            double[] foldedFirst = meanChroma(nnls.combined().beatSynchronous(beats));

            NnlsChroma synced = nnls.beatSynchronous(beats);
            double[][] byHand = new double[synced.treble().frameCount()][12];
            for (int frame = 0; frame < byHand.length; frame++) {
                for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                    byHand[frame][pitchClass] = synced.treble().vectors()[frame][pitchClass]
                            + synced.bass().vectors()[frame][pitchClass];
                }
            }
            double[] syncedFirst = meanChroma(new Chroma(byHand, 0));

            // Both name C, so neither is nonsense and the difference is one of
            // proportion rather than of answer -- which is why it went unnoticed
            // through four measurement harnesses.
            assertThat(argMax(foldedFirst)).isZero();

            // Folding first keeps the C dominant; synchronising first dilutes it
            // with a register that contributed almost no energy.
            assertThat(foldedFirst[0]).isGreaterThan(syncedFirst[0] + 0.05);
        }

        private static int argMax(double[] values) {
            int best = 0;
            for (int i = 1; i < values.length; i++) {
                if (values[i] > values[best]) {
                    best = i;
                }
            }
            return best;
        }

        @Test
        @DisplayName("refuses to fold registers that are already beat-synchronous")
        void refusesToFoldRegistersThatAreAlreadyBeatSynchronous() {
            // The other half of foldingBeforeAndAfterAreNotTheSame: that test
            // says the two orders differ, this one says the worse of them cannot
            // be reached. Documenting it was not enough -- four of the
            // measurement harnesses written for this change took the wrong
            // order, and BluesLoopIT's accuracy floors still passed with it.
            AudioBuffer audio = new AudioBuffer(
                    SignalFactory.clickTrack(120, 8, RATE), RATE);
            List<Double> beats = BeatTracker.track(OnsetEnvelope.fromAudio(audio)).beatTimes();

            NnlsChroma folded = NnlsChroma.extract(audio).beatSynchronous(beats);

            assertThatThrownBy(folded::combined)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already beat-synchronous");
        }

        @Test
        @DisplayName("carries the tuning it was analysed at through the fold")
        void keepsTheTuningThroughTheFold() {
            AudioBuffer audio = new AudioBuffer(
                    SignalFactory.clickTrack(120, 8, RATE), RATE);
            List<Double> beats = BeatTracker.track(OnsetEnvelope.fromAudio(audio)).beatTimes();

            NnlsChroma chroma = NnlsChroma.extract(audio);

            assertThat(chroma.beatSynchronous(beats).tuningOffsetSemitones())
                    .isEqualTo(chroma.tuningOffsetSemitones());
        }

        @Test
        @DisplayName("refuses registers describing different numbers of frames")
        void refusesMismatchedRegisters() {
            Chroma two = new Chroma(new double[2][12], 10);
            Chroma three = new Chroma(new double[3][12], 10);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new NnlsChroma(two, three, 0))
                    .withMessageContaining("same frames");
        }

        @Test
        @DisplayName("refuses a tuning offset that is not a number")
        void refusesANonFiniteTuning() {
            Chroma empty = new Chroma(new double[0][], 10);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new NnlsChroma(empty, empty, Double.NaN))
                    .withMessageContaining("tuningOffsetSemitones");
        }
    }

    @Nested
    @DisplayName("analysis window")
    class Window {

        @Test
        @DisplayName("is long enough to resolve adjacent bass semitones at any rate")
        void isLongEnoughToResolveTheBass() {
            // The property, not the number: an FFT bin must be narrower than the
            // gap between two adjacent semitones at the bottom of the bass, or
            // the transcription cannot tell them apart however good the fit is.
            // C2 is 65.41 Hz and B1 is 61.74 Hz, so the gap is 3.67 Hz.
            for (int rate : new int[] {8_000, 16_000, 22_050, 44_100, 48_000}) {
                double binWidth = (double) rate / NnlsChroma.windowSizeFor(rate);
                assertThat(binWidth)
                        .as("bin width at %d Hz", rate)
                        .isLessThan(65.41 - 61.74);
            }
        }

        @Test
        @DisplayName("is a power of two, because the transform requires it")
        void isAPowerOfTwo() {
            for (int rate : new int[] {8_000, 11_025, 16_000, 22_050, 44_100, 48_000, 96_000}) {
                assertThat(Integer.bitCount(NnlsChroma.windowSizeFor(rate)))
                        .as("window at %d Hz", rate)
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("rejects a sample rate that is not one")
        void rejectsANonPositiveSampleRate() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NnlsChroma.windowSizeFor(0))
                    .withMessageContaining("sampleRate");
        }
    }

    @Nested
    @DisplayName("the plain path is untouched")
    class PlainPathIsUntouched {

        @Test
        @DisplayName("still extracts chroma at its own resolution")
        void plainChromaStillWorks() {
            // The fallback has to keep working: the tier-0 tests are written
            // against it and it is what this falls back to if the transcription
            // ever underperforms on a recording.
            AudioBuffer audio = new AudioBuffer(
                    SignalFactory.chord(SignalFactory.majorTriad(60), 2.0, RATE), RATE);

            double[] vector = Chroma.extract(audio).normalisedPerFrame().vectors()[20];

            double chordTones = vector[0] + vector[4] + vector[7];
            assertThat(chordTones).isGreaterThan((1.0 - chordTones) * 3);
        }
    }

    @Nested
    @DisplayName("tuning estimation")
    class TuningEstimation {

        @Test
        @DisplayName("answers no-tuning-evidence rather than half a semitone flat")
        void emptyEvidenceGivesZero() {
            // #77. The mode search used to seed its winner at slot 0 and never
            // move off it when nothing beat it, and slot 0 of a forty-slot
            // histogram is -0.4875 semitones -- the most extreme claim the
            // function can make, returned on no evidence at all.
            AudioBuffer silence = new AudioBuffer(SignalFactory.silence(2.0, RATE), RATE);

            assertThat(Chroma.estimateTuning(Spectrogram.compute(silence, 4096, 1024)))
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("still finds a real offset")
        void findsARealOffset() {
            double quarterToneSharp = Math.pow(2, 0.25 / 12);
            float[] detuned = SignalFactory.chord(new double[] {
                    440 * quarterToneSharp, 554.37 * quarterToneSharp,
                    659.26 * quarterToneSharp}, 3.0, RATE);

            assertThat(Chroma.estimateTuning(
                    Spectrogram.compute(new AudioBuffer(detuned, RATE), 4096, 1024)))
                    .isCloseTo(0.25, within(0.15));
        }
    }
}
