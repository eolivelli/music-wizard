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

import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Note segmentation, over pitch tracks built by hand.
 *
 * <p>By hand rather than by tracking audio, so that these say what the
 * segmenter does with a given frame sequence rather than what the tracker
 * happens to produce. {@code PitchTrackingTest} covers the tracker, and
 * {@code tools/score-melody.py} covers the two together on real signals.
 */
class MelodyEstimationTest {

    private static final int RATE = 22_050;

    /** A frame sequence written as (pitch, frames) pairs; a null pitch is silence. */
    private static PitchTrack track(Object... runs) {
        List<Double> frequencies = new ArrayList<>();
        List<Boolean> voiced = new ArrayList<>();
        for (int i = 0; i < runs.length; i += 2) {
            Double pitch = (Double) runs[i];
            int frames = (Integer) runs[i + 1];
            for (int f = 0; f < frames; f++) {
                // An unvoiced frame still carries a pitch, as the decoder's own
                // output does; the segmenter must read the voicing, not this.
                frequencies.add(440 * Math.pow(2, ((pitch == null ? 69 : pitch) - 69) / 12));
                voiced.add(pitch != null);
            }
        }
        double[] hz = new double[frequencies.size()];
        boolean[] isVoiced = new boolean[frequencies.size()];
        double[] voicedness = new double[frequencies.size()];
        for (int i = 0; i < hz.length; i++) {
            hz[i] = frequencies.get(i);
            isVoiced[i] = voiced.get(i);
            voicedness[i] = isVoiced[i] ? 0.9 : 0.0;
        }
        return new PitchTrack(hz, isVoiced, voicedness, RATE,
                PitchTracker.WINDOW, PitchTracker.HOP);
    }

    private static double frameSeconds() {
        return (double) PitchTracker.HOP / RATE;
    }

    private static List<Integer> pitches(NoteTrack melody) {
        return melody.notes().stream().map(Note::midiPitch).toList();
    }

    @Nested
    @DisplayName("re-articulations")
    class Rearticulations {

        private static final double ENVELOPE_RATE = 172.0;

        /** One voiced run at one pitch, with a voicedness dip where asked. */
        private PitchTrack heldNote(int frames, int dipFrom, int dipTo) {
            double[] hz = new double[frames];
            boolean[] voiced = new boolean[frames];
            double[] voicedness = new double[frames];
            for (int i = 0; i < frames; i++) {
                hz[i] = 440;
                voiced[i] = true;
                voicedness[i] = i >= dipFrom && i <= dipTo ? 0.3 : 0.9;
            }
            return new PitchTrack(hz, voiced, voicedness, RATE,
                    PitchTracker.WINDOW, PitchTracker.HOP);
        }

        /** An envelope that is silent except for peaks at (time, strength) pairs. */
        private OnsetEnvelope peaksAt(double totalSeconds, double... timeStrengthPairs) {
            double[] out = new double[(int) Math.ceil(totalSeconds * ENVELOPE_RATE)];
            for (int i = 0; i < timeStrengthPairs.length; i += 2) {
                out[(int) Math.round(timeStrengthPairs[i] * ENVELOPE_RATE)] =
                        timeStrengthPairs[i + 1];
            }
            return new OnsetEnvelope(out, ENVELOPE_RATE);
        }

        private OnsetEnvelope peakAt(double seconds, double strength, double totalSeconds) {
            return peaksAt(totalSeconds, seconds, strength);
        }

        @Test
        @DisplayName("a strong peak where the voice restarts cuts the note in two")
        void aStrongPeakWhereTheVoiceRestartsSplits() {
            // The dip frames straddle the peak's time on the pitch axis.
            NoteTrack melody = MelodyEstimator.estimate(
                    heldNote(100, 44, 52), peakAt(0.6, 5.0, 1.3));

            assertThat(melody.notes()).hasSize(2);
            assertThat(pitches(melody)).containsExactly(69, 69);
            assertThat(melody.notes().get(1).onsetSeconds()).isCloseTo(0.6, within(0.01));
            // The pieces cover the note with no gap: a re-articulation is a
            // boundary, not a rest.
            assertThat(melody.notes().get(0).offsetSeconds())
                    .isEqualTo(melody.notes().get(1).onsetSeconds());
        }

        @Test
        @DisplayName("an accompaniment peak under an undisturbed voice does not cut")
        void anAccompanimentPeakDoesNotCut() {
            // Same peak, no dip: the envelope says something was struck, and
            // the voice says it was not the voice.
            NoteTrack melody = MelodyEstimator.estimate(
                    heldNote(100, -1, -1), peakAt(0.6, 5.0, 1.3));

            assertThat(melody.notes()).hasSize(1);
        }

        @Test
        @DisplayName("a peak below the floor does not cut, dip or no dip")
        void aWeakPeakDoesNotCut() {
            NoteTrack melody = MelodyEstimator.estimate(
                    heldNote(100, 44, 52), peakAt(0.6, 2.0, 1.3));

            assertThat(melody.notes()).hasSize(1);
        }

        @Test
        @DisplayName("a peak at the note's own attack does not cut")
        void aPeakAtTheAttackDoesNotCut() {
            // Within MIN_NOTE_SECONDS of the onset it is the note's own
            // articulation, however strong, and whatever the voicedness there.
            NoteTrack melody = MelodyEstimator.estimate(
                    heldNote(100, 0, 8), peakAt(0.09, 5.0, 1.3));

            assertThat(melody.notes()).hasSize(1);
        }

        @Test
        @DisplayName("without an envelope the two notes stay one, as they always did")
        void withoutAnEnvelopeTheNoteStaysWhole() {
            assertThat(MelodyEstimator.estimate(heldNote(100, 44, 52)).notes()).hasSize(1);
        }

        @Test
        @DisplayName("a peak near the note's end does not cut")
        void aPeakNearTheNoteEndDoesNotCut() {
            // Within MIN_NOTE_SECONDS of the end it is the next note's attack,
            // and the piece it would leave -- a sub-60 ms duplicate right
            // before a true onset -- is the worst place to invent one.
            NoteTrack melody = MelodyEstimator.estimate(
                    heldNote(100, 92, 99), peakAt(1.18, 5.0, 1.3));

            assertThat(melody.notes()).hasSize(1);
        }

        @Test
        @DisplayName("two peaks closer than the shortest note cut once")
        void twoPeaksCloserThanTheShortestNoteCutOnce() {
            NoteTrack melody = MelodyEstimator.estimate(
                    heldNote(100, 42, 58), peaksAt(1.3, 0.6, 5.0, 0.64, 5.0));

            assertThat(melody.notes()).hasSize(2);
            assertThat(melody.notes().get(1).onsetSeconds()).isCloseTo(0.6, within(0.01));
        }

        @Test
        @DisplayName("the dip is sought on the pitch axis where the peak actually is")
        void theDipIsSoughtWhereThePeakIs() {
            // A two-frame dip placed just inside the reach only when the
            // envelope's uncentred time is converted onto the pitch track's
            // window-centred axis: dropping the centering, or flipping its
            // sign, both put the search window past these frames and lose the
            // split.
            NoteTrack melody = MelodyEstimator.estimate(
                    heldNote(100, 43, 44), peakAt(0.6, 5.0, 1.3));

            assertThat(melody.notes()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("a run of voiced frames")
    class VoicedRun {

        @Test
        @DisplayName("becomes one note per pitch it holds")
        void cutsWherePitchChanges() {
            NoteTrack melody = MelodyEstimator.estimate(
                    track(60.0, 40, 62.0, 40, 64.0, 40));

            assertThat(pitches(melody)).containsExactly(60, 62, 64);
        }

        @Test
        @DisplayName("leaves no gap between two notes that abut")
        void notesMeetWhereTheyAbut() {
            NoteTrack melody = MelodyEstimator.estimate(track(60.0, 40, 62.0, 40));

            List<Note> notes = melody.notes();
            assertThat(notes).hasSize(2);
            assertThat(notes.get(0).offsetSeconds())
                    .isCloseTo(notes.get(1).onsetSeconds(), within(1e-9));
        }

        @Test
        @DisplayName("gives the frames of a transition to the note being left")
        void absorbsTheTransition() {
            // Two frames in between, which is what the decoded path crossing an
            // interval looks like: too short to be a note, and time that has to
            // belong to something.
            NoteTrack melody = MelodyEstimator.estimate(
                    track(60.0, 40, 61.0, 2, 62.0, 40));

            assertThat(pitches(melody)).containsExactly(60, 62);
            assertThat(melody.notes().get(0).durationSeconds())
                    .as("the note ends where the pitch left it, not where the next was confirmed")
                    .isCloseTo(40 * frameSeconds(), within(1e-9));
        }

        @Test
        @DisplayName("survives a pitch that flickers between two semitones")
        void keepsANoteThatWaversAcrossTheBoundary() {
            // Sung a little sharp of 60 and wavering across the boundary:
            // rounded frame by frame this is twenty spans of three frames, every
            // one of them too short to keep, and the note disappears rather than
            // landing on the wrong semitone.
            List<Object> runs = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                runs.add(i % 2 == 0 ? 60.35 : 60.55);
                runs.add(3);
            }
            NoteTrack melody = MelodyEstimator.estimate(track(runs.toArray()));

            assertThat(melody.notes()).hasSize(1);
            assertThat(pitches(melody)).containsExactly(60);
        }
    }

    @Nested
    @DisplayName("silence")
    class Silence {

        @Test
        @DisplayName("ends a note and starts the next one")
        void breaksTheRun() {
            NoteTrack melody = MelodyEstimator.estimate(
                    track(67.0, 40, null, 20, 67.0, 40));

            assertThat(pitches(melody)).containsExactly(67, 67);
            List<Note> notes = melody.notes();
            assertThat(notes.get(1).onsetSeconds() - notes.get(0).offsetSeconds())
                    .as("the rest between them is kept")
                    .isCloseTo(20 * frameSeconds(), within(1e-6));
        }

        @Test
        @DisplayName("alone yields no notes at all")
        void yieldsNothing() {
            NoteTrack melody = MelodyEstimator.estimate(track(null, 200));

            assertThat(melody.isEmpty()).isTrue();
            assertThat(melody.role()).isEqualTo(PartRole.LEAD_VOCAL);
        }
    }

    @Nested
    @DisplayName("the part it produces")
    class Part {

        @Test
        @DisplayName("is a monophonic lead vocal")
        void isAMonophonicLeadVocal() {
            NoteTrack melody = MelodyEstimator.estimate(
                    track(60.0, 30, 64.0, 30, null, 10, 67.0, 30));

            assertThat(melody.role()).isEqualTo(PartRole.LEAD_VOCAL);
            assertThat(melody.isMonophonic()).isTrue();
            assertThat(melody.isQuantized())
                    .as("this stage works in seconds; the beat axis is decided downstream")
                    .isFalse();
        }

        @Test
        @DisplayName("drops a run's opening span when it is too short to be a note")
        void dropsSpansShorterThanANote() {
            // Only a run's *first* span can be short. A later one opens where a
            // departure began, and the departure after it cannot begin before
            // the frame that confirmed this one, so it spans at least as long
            // as a note must.
            PitchTrack pitches = track(60.0, 1, 72.0, 40);
            NoteTrack melody = MelodyEstimator.estimate(pitches);

            assertThat(pitches(melody)).containsExactly(72);
            // The dropped frame's time is lost rather than given away: a note
            // reaches forward to the next note's onset and never backwards, so
            // the surviving note starts where its own span does.
            assertThat(melody.notes().get(0).onsetSeconds())
                    .isCloseTo(pitches.timeOf(1), within(1e-9));
        }

        @Test
        @DisplayName("keeps a run that is itself barely long enough")
        void keepsARunAtTheFloor() {
            NoteTrack melody = MelodyEstimator.estimate(track(60.0, 6));

            assertThat(pitches(melody)).containsExactly(60);
        }

        @Test
        @DisplayName("drops a run shorter than a note entirely")
        void dropsARunUnderTheFloor() {
            NoteTrack melody = MelodyEstimator.estimate(track(60.0, 5));

            assertThat(melody.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("the recording's tuning")
    class Tuning {

        /** A transfer running slow, in the sense of Chroma.estimateTuning. */
        private static final double FLAT = -0.36;

        /**
         * A phrase sung on a flat transfer: every note {@link #FLAT} under its
         * nominal pitch, and the singer a little under that again.
         */
        private PitchTrack phrase() {
            return track(69 + FLAT - 0.15, 40,
                         71 + FLAT - 0.05, 40,
                         69 + FLAT - 0.20, 40);
        }

        @Test
        @DisplayName("a flat transfer takes the rounding margin off one side only")
        void aFlatTransferRoundsSomeNotesDown() {
            // What the field report on #566 describes: the line is followed,
            // and the notes whose singing strays furthest to the flat side are
            // the ones that fall a semitone. The middle note strays least and
            // survives on its own; the outer two are just past the boundary.
            assertThat(pitches(MelodyEstimator.estimate(phrase())))
                    .containsExactly(68, 71, 68);
        }

        @Test
        @DisplayName("rounding on the recording's grid puts them back")
        void theRecordingsGridRecoversThem() {
            assertThat(pitches(MelodyEstimator.estimate(phrase(), silence(), FLAT)))
                    .containsExactly(69, 71, 69);
        }

        @Test
        @DisplayName("an offset the singing does not sit on is refused")
        void anOffsetTheSingingDoesNotSitOnIsRefused() {
            // Unaccompanied singing that has no tuning centre: the pitches are
            // spread evenly across the semitone, so no grid describes them and
            // estimateTuning still names one. Shifting by it would move the
            // second and fourth notes up and nothing towards the truth.
            PitchTrack spread = track(60.0, 40, 62.4, 40, 64.8, 40, 67.2, 40, 69.6, 40);

            assertThat(pitches(MelodyEstimator.estimate(spread, silence(), -0.4)))
                    .containsExactly(60, 62, 65, 67, 70)
                    .isEqualTo(pitches(MelodyEstimator.estimate(spread)));
        }

        @Test
        @DisplayName("only voiced frames vote on whether the grid holds")
        void unvoicedFramesDoNotVote() {
            // The decoder carries a pitch through a silence and its voicedness
            // there is low but not nothing, which is what the tracker emits.
            // Here the carried pitch is half a semitone above the note it
            // follows, which puts it far enough off the grid to vote against
            // it, and there is far more of it than there is singing -- so
            // counted, it would round the phrase on A440 again.
            PitchTrack withSilence = frames(
                    voiced(69 + FLAT - 0.15, 40),
                    unvoiced(69 + FLAT - 0.15 + 0.5, 400),
                    voiced(71 + FLAT - 0.05, 40));

            assertThat(pitches(MelodyEstimator.estimate(withSilence, silence(), FLAT)))
                    .containsExactly(69, 71);
        }

        @Test
        @DisplayName("a track just clear of the floor is rounded on the grid")
        void aTrackJustClearOfTheFloorIsHonoured() {
            assertThat(pitches(MelodyEstimator.estimate(atDistance(0.21), silence(), FLAT)))
                    .containsExactly(69);
        }

        @Test
        @DisplayName("a track just under it is not, though it is barely further off")
        void aTrackJustUnderTheFloorIsRefused() {
            // Barely further from the grid than the note above, and the whole
            // recording is named a semitone lower. The floor is a cliff, and
            // this pair is where it stands.
            assertThat(pitches(MelodyEstimator.estimate(atDistance(0.226), silence(), FLAT)))
                    .containsExactly(68);
        }

        @Test
        @DisplayName("an offset that reads as concert pitch is not used")
        void anOffsetReadingAsConcertPitchIsNotUsed() {
            // Which offsets those are is Chroma's to say and its own tests
            // pin; this one only has to be one of them. The long note sits on
            // that offset's grid, which is what makes the track corroborate
            // it, and the short one sits just off a rounding boundary, so a
            // shift this narrow would carry it over.
            double concertPitch = -Chroma.TUNING_RESOLUTION_SEMITONES / 2;
            assertThat(Chroma.readsAsConcertPitch(concertPitch)).isTrue();
            PitchTrack track = track(69 + concertPitch, 200, 71.49, 40);

            assertThat(pitches(MelodyEstimator.estimate(track, silence(), concertPitch)))
                    .containsExactly(69, 71)
                    .isEqualTo(pitches(MelodyEstimator.estimate(track)));
        }

        /** One held note the given distance under the flat transfer's own grid. */
        private PitchTrack atDistance(double belowTheGrid) {
            return track(69 + FLAT - belowTheGrid, 40);
        }

        private double[] voiced(double pitch, int count) {
            return new double[] {pitch, count, 0.9};
        }

        /**
         * Frames the decoder called silence: they still carry a pitch, and a
         * voicedness of their own that a fixture must not zero out if it wants
         * to say anything about whether that weight is read.
         */
        private double[] unvoiced(double pitch, int count) {
            return new double[] {pitch, count, -0.4};
        }

        /** A track from {@link #voiced} and {@link #unvoiced} runs. */
        private PitchTrack frames(double[]... runs) {
            int total = 0;
            for (double[] run : runs) {
                total += (int) run[1];
            }
            double[] hz = new double[total];
            boolean[] isVoiced = new boolean[total];
            double[] voicedness = new double[total];
            int at = 0;
            for (double[] run : runs) {
                for (int i = 0; i < (int) run[1]; i++, at++) {
                    hz[at] = 440 * Math.pow(2, (run[0] - 69) / 12);
                    isVoiced[at] = run[2] > 0;
                    voicedness[at] = Math.abs(run[2]);
                }
            }
            return new PitchTrack(hz, isVoiced, voicedness, RATE,
                    PitchTracker.WINDOW, PitchTracker.HOP);
        }

        @Test
        @DisplayName("a departure is confirmed at the same frame on either grid")
        void theGridDoesNotMoveADeparture() {
            // Only where nothing merges. Whether two spans are one note is
            // decided by the semitone they round to, so a grid does move that
            // -- see mergeEqualPitches. These three are far enough apart that
            // no grid can call any two of them the same note.
            List<Note> onA440 = MelodyEstimator.estimate(phrase(), silence()).notes();
            List<Note> onItsOwnGrid =
                    MelodyEstimator.estimate(phrase(), silence(), FLAT).notes();

            assertThat(onItsOwnGrid).hasSameSizeAs(onA440);
            for (int i = 0; i < onA440.size(); i++) {
                assertThat(onItsOwnGrid.get(i).onsetSeconds())
                        .isEqualTo(onA440.get(i).onsetSeconds());
                assertThat(onItsOwnGrid.get(i).durationSeconds())
                        .isEqualTo(onA440.get(i).durationSeconds());
            }
        }

        @Test
        @DisplayName("a non-finite offset is rejected")
        void aNonFiniteOffsetIsRejected() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> MelodyEstimator.estimate(phrase(), silence(), Double.NaN));
        }

        /** An envelope with no peak in it, so nothing is re-articulated. */
        private OnsetEnvelope silence() {
            return new OnsetEnvelope(new double[600], 172.0);
        }
    }

    @Nested
    @DisplayName("a note the tracker read an octave out")
    class OctaveErrors {

        /** Ten notes of singing, a bar apart in pitch, around D above middle C. */
        private static Object[] singing() {
            return new Object[] {
                62.0, 40, null, 4, 65.0, 40, null, 4, 62.0, 40, null, 4, 60.0, 40, null, 4,
                62.0, 40, null, 4, 67.0, 40, null, 4, 65.0, 40, null, 4, 62.0, 40, null, 4,
                60.0, 40, null, 4, 62.0, 40,
            };
        }

        /** Two frame sequences one after the other; a silence marker is null. */
        private static Object[] with(Object[] head, Object... tail) {
            List<Object> all = new ArrayList<>();
            Collections.addAll(all, head);
            Collections.addAll(all, tail);
            return all.toArray();
        }

        @Test
        @DisplayName("is folded back into the melody's own octave")
        void isFoldedBack() {
            NoteTrack melody = MelodyEstimator.estimate(
                    track(with(singing(), null, 4, 86.0, 20)));

            assertThat(pitches(melody).get(10))
                    .as("two octaves out, and its pitch class was already right")
                    .isEqualTo(62);
        }

        @Test
        @DisplayName("is moved rather than dropped")
        void isNotDropped() {
            PitchTrack pitches = track(with(singing(), null, 4, 86.0, 20));
            NoteTrack melody = MelodyEstimator.estimate(pitches);

            assertThat(melody.notes()).hasSize(11);
            assertThat(melody.notes().get(10).onsetSeconds())
                    .as("it keeps its place in time")
                    .isCloseTo(pitches.timeOf(440), within(1e-9));
        }

        @Test
        @DisplayName("goes to the octave nearest the melody, not the first one in range")
        void goesToTheNearestOctave() {
            // Landing it merely inside the band would leave it an octave out,
            // which is the population #596 was reported from.
            NoteTrack melody = MelodyEstimator.estimate(
                    track(with(singing(), null, 4, 86.0, 20)));

            assertThat(pitches(melody).get(10)).isNotEqualTo(74);
        }

        @Test
        @DisplayName("is read against the singing where the stem was empty before it")
        void leakageIsReadAgainstTheSinging() {
            // What the separator leaves behind before the singer enters: the
            // tracker follows it and reports it high (#575).
            NoteTrack melody = MelodyEstimator.estimate(
                    track(with(new Object[] {85.0, 10, null, 4, 86.0, 10, null, 4, 85.0, 10,
                            null, 8}, (Object[]) singing())));

            assertThat(pitches(melody).subList(0, 3)).containsExactly(61, 62, 61);
        }

        @Test
        @DisplayName("never moves the singing to meet an empty stretch")
        void anEmptyStretchIsNotEvidence() {
            // The same leakage lasting long enough to widen the band it is
            // judged against. It may then keep its own octave — what it must
            // not do is take the singing's with it, which is what an average
            // rather than a share of the sounding time would have let it do.
            NoteTrack melody = MelodyEstimator.estimate(
                    track(with(new Object[] {85.0, 30, null, 4, 86.0, 30, null, 4, 85.0, 30,
                            null, 8}, (Object[]) singing())));

            assertThat(pitches(melody).subList(3, 13))
                    .containsExactly(62, 65, 62, 60, 62, 67, 65, 62, 60, 62);
        }

        @Test
        @DisplayName("a line that really ranges that far is left alone")
        void aWideLineIsNotClamped() {
            // What --skip-separation gives the stage when the melody is played
            // rather than sung (#560): four octaves, wider than any voice, and
            // the band comes from this line's own spread rather than a voice's.
            NoteTrack melody = MelodyEstimator.estimate(track(
                    36.0, 40, null, 4, 48.0, 40, null, 4, 60.0, 40, null, 4, 72.0, 40,
                    null, 4, 84.0, 40, null, 4, 72.0, 40, null, 4, 60.0, 40, null, 4,
                    48.0, 40, null, 4, 36.0, 40));

            assertThat(pitches(melody))
                    .containsExactly(36, 48, 60, 72, 84, 72, 60, 48, 36);
        }

        @Test
        @DisplayName("is judged against a band the bench may choose")
        void theBandIsSweepable() {
            PitchTrack pitches = track(with(singing(), null, 4, 86.0, 20));
            OnsetEnvelope silence = new OnsetEnvelope(new double[1200], 172.0);

            assertThat(pitches(MelodyEstimator.estimate(pitches, silence, 0, 0.7, 0, 1)))
                    .as("a band reaching the whole melody folds nothing")
                    .endsWith(86);
            assertThatIllegalArgumentException().isThrownBy(
                    () -> MelodyEstimator.estimate(pitches, silence, 0, 0.7, -1, 0.9));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> MelodyEstimator.estimate(pitches, silence, 0, 0.7, 15, 1.5));
        }
    }

    @Nested
    @DisplayName("a pitch that never settles")
    class Glides {

        /** A glide, one frame per step, from one pitch to another inclusive. */
        private static List<Object> ramp(double from, double to, int frames) {
            List<Object> runs = new ArrayList<>();
            for (int frame = 0; frame < frames; frame++) {
                runs.add(from + (to - from) * frame / (frames - 1));
                runs.add(1);
            }
            return runs;
        }

        private static Object[] runs(Object... parts) {
            List<Object> flat = new ArrayList<>();
            for (Object part : parts) {
                if (part instanceof List<?> list) {
                    flat.addAll(list);
                } else {
                    flat.add(part);
                }
            }
            return flat.toArray();
        }

        @Test
        @DisplayName("becomes no note of its own between the two it joins")
        void aGlideIsNotANote() {
            // The scoop #566 was filed about: about eight semitones in half a
            // second, which the running mean leaves several times over.
            NoteTrack melody = MelodyEstimator.estimate(
                    track(runs(60.0, 40, ramp(60.2, 67.8, 41), 68.0, 40)));

            assertThat(pitches(melody)).containsExactly(60, 68);
        }

        @Test
        @DisplayName("is dropped however long it lasts")
        void lengthDoesNotRescueIt() {
            NoteTrack melody = MelodyEstimator.estimate(
                    track(runs(60.0, 40, ramp(60.2, 75.8, 84), 76.0, 40)));

            assertThat(pitches(melody)).containsExactly(60, 76);
        }

        @Test
        @DisplayName("does not take the notes around it with it")
        void aRunOfShortNotesSurvives() {
            // Each barely longer than the shortest note there can be, which is
            // what real singing is made of: a rule that read length rather than
            // stillness would remove these too.
            NoteTrack melody = MelodyEstimator.estimate(
                    track(60.0, 7, 62.0, 7, 64.0, 7, 66.0, 7, 68.0, 7));

            assertThat(pitches(melody)).containsExactly(60, 62, 64, 66, 68);
        }

        @Test
        @DisplayName("leaves its time to the note it left")
        void theNoteBeforeAbsorbsIt() {
            PitchTrack pitches = track(runs(60.0, 40, ramp(60.2, 67.8, 41), 68.0, 40));
            NoteTrack melody = MelodyEstimator.estimate(pitches);

            assertThat(melody.notes().get(0).offsetSeconds())
                    .as("no gap opens where the glide was")
                    .isCloseTo(melody.notes().get(1).onsetSeconds(), within(1e-9));
            assertThat(melody.notes().get(0).durationSeconds())
                    .as("the note it left runs on through it")
                    .isGreaterThan(40 * frameSeconds());
        }

        @Test
        @DisplayName("is decided at a steadiness the bench may choose")
        void theSteadinessIsSweepable() {
            PitchTrack pitches = track(runs(60.0, 40, ramp(60.2, 67.8, 41), 68.0, 40));
            OnsetEnvelope silence = new OnsetEnvelope(new double[600], 172.0);

            assertThat(pitches(MelodyEstimator.estimate(pitches, silence, 0, 4.0)))
                    .as("wide enough to hold the glide, and its pieces are notes again")
                    .hasSizeGreaterThan(2);
            assertThatIllegalArgumentException().isThrownBy(
                    () -> MelodyEstimator.estimate(pitches, silence, 0, 0));
        }
    }
}
