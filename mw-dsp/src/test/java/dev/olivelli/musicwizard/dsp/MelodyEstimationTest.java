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
            // as a note must — which makes this the one shape that reaches the
            // filter.
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
            // The decoder carries a pitch through a silence, and here it sits
            // where the grid is not. Counted, it would outvote the singing and
            // the phrase would be rounded on A440 again.
            PitchTrack withSilence = track(69 + FLAT - 0.15, 40,
                                           null, 400,
                                           71 + FLAT - 0.05, 40);

            assertThat(pitches(MelodyEstimator.estimate(withSilence, silence(), FLAT)))
                    .containsExactly(69, 71);
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
}
