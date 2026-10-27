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
import dev.olivelli.musicwizard.core.workspace.MelodyTrace;
import java.util.ArrayList;
import java.util.Arrays;
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

        private static final double LEVEL_RATE = (double) RATE / OnsetEnvelope.ONSET_HOP;

        /**
         * A level steady over the note except for a fall of {@code depth}
         * decibels to its lowest at {@code at}, and a recovery as quick, or
         * none where {@code recovers} is false.
         */
        private LevelContour levelFalling(double totalSeconds, double at, double depth,
                                          boolean recovers) {
            double[] decibels = new double[(int) Math.ceil(totalSeconds * LEVEL_RATE)];
            int lowest = (int) Math.round(at * LEVEL_RATE);
            int edge = 5;
            for (int i = 0; i < decibels.length; i++) {
                int away = Math.abs(i - lowest);
                decibels[i] = !recovers && i > lowest ? -10 - depth
                        : away >= edge ? -10 : -10 - depth * (edge - away) / edge;
            }
            return new LevelContour(decibels, LEVEL_RATE, 0);
        }

        /** A steady level with a recovering dip of the given depth at each of the given times. */
        private LevelContour strokesAt(double totalSeconds, double depth, double... times) {
            double[] decibels = levelFalling(totalSeconds, times[0], depth, true).decibels();
            for (int t = 1; t < times.length; t++) {
                double[] next = levelFalling(totalSeconds, times[t], depth, true).decibels();
                for (int i = 0; i < decibels.length; i++) {
                    decibels[i] = Math.min(decibels[i], next[i]);
                }
            }
            return new LevelContour(decibels, LEVEL_RATE, 0);
        }

        private NoteTrack withLevel(PitchTrack pitches, OnsetEnvelope envelope,
                                    LevelContour level) {
            return MelodyEstimator.explain(pitches, envelope, level, 0, false).melody();
        }

        @Test
        @DisplayName("a stroke the voice never stops for cuts the note at the stroke")
        void aStrokeUnderAnUnbrokenVoiceCuts() {
            // The tongued repeat of #882: no voicedness dip and no envelope
            // peak to speak of, only the level falling and coming back.
            NoteTrack melody = withLevel(heldNote(100, -1, -1), peakAt(0.6, 1.0, 1.3),
                    levelFalling(1.3, 0.6, 14, true));

            assertThat(pitches(melody)).containsExactly(69, 69);
            assertThat(melody.notes().get(1).onsetSeconds()).isCloseTo(0.6, within(0.01));
        }

        @Test
        @DisplayName("a shallow swell of the level, as vibrato makes, does not cut")
        void aShallowDipDoesNotCut() {
            NoteTrack melody = withLevel(heldNote(100, -1, -1), peakAt(0.6, 1.0, 1.3),
                    levelFalling(1.3, 0.6, 3, true));

            assertThat(melody.notes()).hasSize(1);
        }

        @Test
        @DisplayName("a level that dips as deep again soon after is a tremolo")
        void aTremoloDoesNotCut() {
            NoteTrack melody = withLevel(heldNote(100, -1, -1),
                    peaksAt(1.3, 0.45, 1.0, 0.6, 1.0, 0.75, 1.0),
                    strokesAt(1.3, 14, 0.45, 0.6, 0.75));

            assertThat(melody.notes()).hasSize(1);
        }

        @Test
        @DisplayName("a note tongued three times prints as three")
        void everyStrokeOfANoteCuts() {
            NoteTrack melody = withLevel(heldNote(100, -1, -1),
                    peaksAt(1.3, 0.4, 1.0, 0.8, 1.0), strokesAt(1.3, 14, 0.4, 0.8));

            assertThat(pitches(melody)).containsExactly(69, 69, 69);
            assertThat(melody.notes().get(2).onsetSeconds()).isCloseTo(0.8, within(0.01));
        }

        @Test
        @DisplayName("a repeat soon after a tongued change of pitch still cuts")
        void aRepeatAfterATonguedChangeCuts() {
            // G, then A from about 0.45 s, tongued into and struck again soon
            // after: the dip at the change is the A's own articulation.
            double[] hz = new double[100];
            boolean[] voiced = new boolean[100];
            double[] voicedness = new double[100];
            for (int i = 0; i < hz.length; i++) {
                hz[i] = i < 38 ? 392 : 440;
                voiced[i] = true;
                voicedness[i] = 0.9;
            }
            NoteTrack melody = withLevel(
                    new PitchTrack(hz, voiced, voicedness, RATE,
                            PitchTracker.WINDOW, PitchTracker.HOP),
                    peaksAt(1.3, 0.45, 1.0, 0.6, 1.0), strokesAt(1.3, 14, 0.45, 0.6));

            assertThat(pitches(melody)).containsExactly(67, 69, 69);
            assertThat(melody.notes().get(2).onsetSeconds()).isCloseTo(0.6, within(0.01));
        }

        @Test
        @DisplayName("a dip with nothing struck under it does not cut")
        void aDipWithoutAnAttackDoesNotCut() {
            NoteTrack melody = withLevel(heldNote(100, -1, -1), peakAt(0.2, 1.0, 1.3),
                    levelFalling(1.3, 0.6, 14, true));

            assertThat(melody.notes()).hasSize(1);
        }

        @Test
        @DisplayName("a ripple at the bottom of a stroke leaves it one stroke")
        void aRippleAtTheBottomIsOneStroke() {
            double[] decibels = levelFalling(1.3, 0.6, 14, true).decibels();
            int lowest = (int) Math.round(0.6 * LEVEL_RATE);
            // Two minima a frame either side of a small bump.
            decibels[lowest - 1] = decibels[lowest];
            decibels[lowest + 1] = decibels[lowest];
            decibels[lowest] += 0.2;
            NoteTrack melody = withLevel(heldNote(100, -1, -1), peakAt(0.6, 1.0, 1.3),
                    new LevelContour(decibels, LEVEL_RATE, 0));

            assertThat(pitches(melody)).containsExactly(69, 69);
        }

        @Test
        @DisplayName("a level that falls and stays down is a note fading, not a stroke")
        void aFallWithoutRecoveryDoesNotCut() {
            // A ripple on the low side, so that its minima are dips the
            // recovery is asked of, rather than a plateau nothing is asked of.
            double[] decibels = levelFalling(1.3, 0.6, 14, false).decibels();
            for (int i = (int) Math.round(0.6 * LEVEL_RATE) + 1; i < decibels.length; i++) {
                decibels[i] += i % 2 == 0 ? 0.1 : -0.1;
            }
            NoteTrack melody = withLevel(heldNote(100, -1, -1), peakAt(0.6, 1.0, 1.3),
                    new LevelContour(decibels, LEVEL_RATE, 0));

            assertThat(melody.notes()).hasSize(1);
        }

        @Test
        @DisplayName("a stroke at the note's own attack does not cut")
        void aStrokeAtTheAttackDoesNotCut() {
            NoteTrack melody = withLevel(heldNote(100, -1, -1), peakAt(0.6, 1.0, 1.3),
                    levelFalling(1.3, 0.09, 14, true));

            assertThat(melody.notes()).hasSize(1);
        }

        @Test
        @DisplayName("a peak and the stroke under it cut the note once")
        void aPeakAndItsStrokeCutOnce() {
            NoteTrack melody = withLevel(heldNote(100, 44, 52), peakAt(0.6, 5.0, 1.3),
                    levelFalling(1.3, 0.62, 14, true));

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
        @DisplayName("wherever in the melody's range the true note sits")
        void theWholeBandIsRecovered() {
            // The octave nearest the centre is not always one the bound allows,
            // and where it is not, the allowed one still has to be taken: this
            // note's nearest is three octaves down, and folding by two is what
            // recovers it. Testing the chosen octave against the bound instead
            // of choosing within it left every true note more than a few
            // semitones from the centre unfolded -- most of a singer's range.
            NoteTrack melody = MelodyEstimator.estimate(
                    track(with(singing(), null, 4, 96.0, 40)));

            assertThat(pitches(melody).get(10)).isEqualTo(72);
        }

        @Test
        @DisplayName("and takes the smaller move when two octaves are equally near")
        void aTieTakesTheSmallerMove() {
            // An octave and a half above the centre: one octave down and two
            // are the same distance from it, and both are inside the band. The
            // smaller correction is the likelier error, so nothing here may
            // decide it by which candidate the search happens to reach last.
            NoteTrack melody = MelodyEstimator.estimate(
                    track(with(singing(), null, 4, 80.0, 40)));

            assertThat(pitches(melody).get(10)).isEqualTo(68);
        }

        @Test
        @DisplayName("but a line too far out to be a harmonic error is left alone")
        void aFurtherRegisterIsLeftAlone() {
            // The tracker on the accompaniment for most of a recording and on
            // the melody for the rest (#560). Folding the shorter line into the
            // longer one's octave relocates a correct phrase rather than
            // recovering it, so beyond the bound nothing moves. Enough low
            // notes that the band stays narrow, or they would simply be inside
            // it and the bound would not be what kept them.
            double[] line = {28, 30, 28, 31, 28, 30, 28, 31, 28, 30, 28, 31,
                             28, 30, 28, 31, 28, 30, 28, 31, 88, 90};
            List<Object> runs = new ArrayList<>();
            for (int note = 0; note < line.length; note++) {
                if (note > 0) {
                    Collections.addAll(runs, null, 4);
                }
                Collections.addAll(runs, line[note], 40);
            }

            NoteTrack melody = MelodyEstimator.estimate(track(runs.toArray()));

            assertThat(pitches(melody).subList(20, 22)).containsExactly(88, 90);
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
            // judged against. It may then keep its own octave -- what it must
            // not do is take the singing's with it, which is what an average
            // rather than a share of the sounding time would have let it do.
            // Asserted whole, so that what happens to the leakage is on the
            // page rather than hidden.
            NoteTrack melody = MelodyEstimator.estimate(
                    track(with(new Object[] {85.0, 30, null, 4, 86.0, 30, null, 4, 85.0, 30,
                            null, 8}, (Object[]) singing())));

            assertThat(pitches(melody))
                    .containsExactly(85, 86, 85, 62, 65, 62, 60, 62, 67, 65, 62, 60, 62);
        }

        @Test
        @DisplayName("and a run stepping past the edge is not cut in half by it")
        void aRunAcrossTheEdgeIsNotCut() {
            // The corpus shape behind #614 and #615 both: a phrase in another
            // register, too short a part of the recording to buy a band of its
            // own, that the edge lands in the middle of. Its notes are a step
            // or two apart, so which side of the edge each falls on is a
            // semitone of tracker noise -- and the same phrase then comes out
            // in two octaves.
            Object[] run = {76.0, 20, null, 4, 78.0, 20, null, 4, 79.0, 20, null, 4, 81.0, 20};
            PitchTrack pitches = track(with(singing(), with(new Object[] {null, 8}, run)));
            OnsetEnvelope silence = new OnsetEnvelope(new double[1600], 172.0);

            assertThat(pitches(MelodyEstimator.estimate(pitches, silence, 0, 0.7, 14, 0.9, 2, 0)))
                    .as("where the quantile leaves the edge, the run is split")
                    .endsWith(76, 78, 67, 57);
            assertThat(pitches(MelodyEstimator.estimate(pitches)))
                    .as("moved out to the gap the recording has, it is one gesture")
                    .endsWith(76, 78, 79, 81);
        }

        @Test
        @DisplayName("but a note alone past the edge is still folded")
        void aNoteWithNothingNearItIsStillFolded() {
            // The edge walks out along the notes it can reach, not out from
            // itself: a note a step or two beyond an edge that no note stands
            // near is reachable from nothing, and it is exactly the note this
            // rule exists to move. Real singing is where this is decided --
            // it is the whole of what the fold does for vocadito.
            NoteTrack melody = MelodyEstimator.estimate(
                    track(with(singing(), null, 4, 78.0, 20)));

            assertThat(pitches(melody)).endsWith(66);
        }

        @Test
        @DisplayName("a line that really ranges that far is left alone")
        void aWideLineIsNotClamped() {
            // What --skip-separation gives the stage when the melody is played
            // rather than sung (#560): four octaves, wider than any voice, and
            // the band comes from this line's own spread rather than a voice's.
            double[] line = {36, 48, 60, 72, 84, 72, 60, 48, 36, 48, 60,
                             72, 84, 72, 60, 48, 36, 60, 84, 60, 36};
            List<Object> runs = new ArrayList<>();
            for (int note = 0; note < line.length; note++) {
                if (note > 0) {
                    Collections.addAll(runs, null, 4);
                }
                Collections.addAll(runs, line[note], 40);
            }

            NoteTrack melody = MelodyEstimator.estimate(track(runs.toArray()));

            assertThat(pitches(melody))
                    .containsExactly(36, 48, 60, 72, 84, 72, 60, 48, 36, 48, 60,
                            72, 84, 72, 60, 48, 36, 60, 84, 60, 36);
        }

        @Test
        @DisplayName("but a lone leap in an otherwise narrow line is folded with it")
        void aLoneLeapIsFoldedWithTheRest() {
            // The limit of the rule above, pinned rather than desired (#615):
            // two genuine leaps too rare to widen the band come back inside it.
            double[] line = {60, 62, 64, 62, 60, 62, 64, 62, 60, 36, 60, 62, 64, 62,
                             60, 62, 64, 84, 60, 62, 64, 62, 60, 62, 64, 62, 60};
            List<Object> runs = new ArrayList<>();
            for (int note = 0; note < line.length; note++) {
                if (note > 0) {
                    Collections.addAll(runs, null, 4);
                }
                Collections.addAll(runs, line[note], 40);
            }

            NoteTrack melody = MelodyEstimator.estimate(track(runs.toArray()));

            assertThat(pitches(melody).get(9)).isEqualTo(60);
            assertThat(pitches(melody).get(17)).isEqualTo(60);
        }

        @Test
        @DisplayName("is judged against a band the bench may choose")
        void theBandIsSweepable() {
            PitchTrack pitches = track(with(singing(), null, 4, 86.0, 20));
            OnsetEnvelope silence = new OnsetEnvelope(new double[1200], 172.0);

            assertThat(pitches(MelodyEstimator.estimate(pitches, silence, 0, 0.7, 0, 1, 2, 0)))
                    .as("a band reaching the whole melody folds nothing")
                    .endsWith(86);
            assertThat(pitches(MelodyEstimator.estimate(pitches, silence, 0, 0.7, 15, 0.9, 0, 0)))
                    .as("and so does a bound admitting no octave at all")
                    .endsWith(86);
            assertThat(pitches(MelodyEstimator.estimate(pitches, silence, 0, 0.7, 15, 0.9, 2, 0)))
                    .as("a gesture of nothing decides every note alone")
                    .endsWith(62);
            assertThatIllegalArgumentException().isThrownBy(
                    () -> MelodyEstimator.estimate(pitches, silence, 0, 0.7, -1, 0.9, 2, 5));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> MelodyEstimator.estimate(pitches, silence, 0, 0.7, 15, 1.5, 2, 5));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> MelodyEstimator.estimate(pitches, silence, 0, 0.7, 15, 0.9, -1, 5));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> MelodyEstimator.estimate(pitches, silence, 0, 0.7, 15, 0.9, 2, -1));
        }
    }

    @Nested
    @DisplayName("an attack read an octave under its note")
    class SubOctaveAttacks {

        /**
         * The same track with the frames from {@code from} to {@code to} at the
         * voicedness given: the decoder leaves the voiced state to make a leap
         * even where the sound carries on.
         */
        private static PitchTrack voicedness(PitchTrack plain, int from, int to,
                                             double voicedness) {
            double[] changed = plain.voicedness().clone();
            for (int frame = from; frame < to; frame++) {
                changed[frame] = voicedness;
            }
            return new PitchTrack(plain.frequenciesHz(), plain.voiced(), changed, RATE,
                    PitchTracker.WINDOW, PitchTracker.HOP);
        }

        /** B5 held, a rest, then an attack of the given pitch and length before A5 (#860). */
        private static PitchTrack attack(Double pitch, int frames, int gapFrames,
                                         double gapVoicedness) {
            return voicedness(track(83.0, 40, null, 15, pitch, frames, null, gapFrames,
                    81.0, 56), 55 + frames, 55 + frames + gapFrames, gapVoicedness);
        }

        private static PitchTrack attack(Double pitch, int frames, int gapFrames) {
            return attack(pitch, frames, gapFrames, 0.9);
        }

        @Test
        @DisplayName("is joined to the note it leads into, which keeps its start")
        void isJoinedToItsNote() {
            PitchTrack pitches = attack(69.0, 12, 2);
            NoteTrack melody = MelodyEstimator.estimate(pitches);

            assertThat(pitches(melody)).containsExactly(83, 81);
            assertThat(melody.notes().get(1).onsetSeconds())
                    .isCloseTo(pitches.timeOf(55), within(1e-9));
            assertThat(melody.notes().get(1).offsetSeconds())
                    .isCloseTo(pitches.timeOf(pitches.frameCount() - 1) + frameSeconds(),
                            within(1e-9));
        }

        @Test
        @DisplayName("but a sub-octave note held longer is a leap and stays")
        void aLongerNoteIsALeap() {
            int frames = (int) Math.ceil(
                    MelodyEstimator.SUB_OCTAVE_ATTACK_SECONDS / frameSeconds()) + 1;

            assertThat(pitches(MelodyEstimator.estimate(attack(69.0, frames, 2))))
                    .containsExactly(83, 69, 81);
        }

        @Test
        @DisplayName("and so does one the voice pauses after, which is a note sung again")
        void aPauseAfterItKeepsIt() {
            assertThat(pitches(MelodyEstimator.estimate(attack(69.0, 12, 2, 0.1))))
                    .containsExactly(83, 69, 81);
        }

        @Test
        @DisplayName("and so does one with a rest after it")
        void aRestAfterItKeepsIt() {
            assertThat(pitches(MelodyEstimator.estimate(attack(69.0, 12, 8))))
                    .containsExactly(83, 69, 81);
        }

        @Test
        @DisplayName("and one that is not an octave under it")
        void anotherIntervalIsKept() {
            assertThat(pitches(MelodyEstimator.estimate(attack(70.0, 12, 2))))
                    .containsExactly(83, 70, 81);
            assertThat(pitches(MelodyEstimator.estimate(attack(93.0, 12, 2))))
                    .containsExactly(83, 93, 81);
        }

        @Test
        @DisplayName("and one the voice did not restart for")
        void aNoteInsideARunIsKept() {
            PitchTrack pitches = voicedness(track(83.0, 40, 69.0, 12, null, 2, 81.0, 56),
                    52, 54, 0.9);

            assertThat(pitches(MelodyEstimator.estimate(pitches)))
                    .containsExactly(83, 69, 81);
        }

        @Test
        @DisplayName("on a line's high notes as on its low ones")
        void aHighNoteIsJoinedToo() {
            PitchTrack pitches = voicedness(track(67.0, 80, null, 15, 63.0, 12, null, 2,
                    75.0, 20), 107, 109, 0.9);

            assertThat(pitches(MelodyEstimator.estimate(pitches))).containsExactly(67, 75);
        }

        @Test
        @DisplayName("but not on a separated stem")
        void aStemIsLeftAlone() {
            MelodyEstimator.Segmented segmented = MelodyEstimator.explain(attack(69.0, 12, 2),
                    new OnsetEnvelope(new double[400], 172.0), 0, true);

            assertThat(pitches(segmented.melody())).containsExactly(83, 69, 81);
        }

        @Test
        @DisplayName("and one that leads into its note within one run")
        void anAttackInsideTheRunIsKept() {
            assertThat(pitches(MelodyEstimator.estimate(
                    track(83.0, 40, null, 15, 69.0, 12, 81.0, 56))))
                    .containsExactly(83, 69, 81);
        }

        @Test
        @DisplayName("the two runs are traced as one, the attack among its joined pieces")
        void isTraced() {
            MelodyTrace trace = MelodyEstimator.explain(attack(69.0, 12, 2),
                    new OnsetEnvelope(new double[400], 172.0), 0).trace();

            assertThat(trace.notes()).extracting(MelodyTrace.Note::startedBy)
                    .containsExactly(MelodyTrace.Note.RUN, MelodyTrace.Note.RUN);
            assertThat(trace.notes()).extracting(MelodyTrace.Note::run)
                    .containsExactly(0, 1);
            assertThat(trace.runs()).hasSize(2);
            MelodyTrace.Run joined = trace.runs().get(1);
            assertThat(joined.fromSeconds()).isEqualTo(trace.notes().get(1).fromSeconds());
            assertThat(joined.toSeconds()).isEqualTo(trace.notes().get(1).toSeconds());
            assertThat(joined.joined()).isEqualTo(1);
            assertThat(joined.held() - joined.joined() + joined.rearticulations())
                    .isEqualTo(joined.notes())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("later notes keep pointing at their own runs")
        void laterRunsAreRenumbered() {
            MelodyTrace trace = MelodyEstimator.explain(
                    voicedness(track(83.0, 40, null, 15, 69.0, 12, null, 2, 81.0, 56,
                            null, 15, 79.0, 40), 67, 69, 0.9),
                    new OnsetEnvelope(new double[600], 172.0), 0).trace();

            assertThat(trace.notes()).extracting(MelodyTrace.Note::midiPitch)
                    .containsExactly(83, 81, 79);
            assertThat(trace.notes()).extracting(MelodyTrace.Note::run)
                    .containsExactly(0, 1, 2);
            assertThat(trace.runs()).hasSize(3);
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

    @Nested
    @DisplayName("what it writes down about the pass")
    class Tracing {

        private static final double ENVELOPE_RATE = 172.0;

        private static OnsetEnvelope silence() {
            return new OnsetEnvelope(new double[600], ENVELOPE_RATE);
        }

        private static MelodyTrace trace(PitchTrack pitches) {
            return MelodyEstimator.explain(pitches, silence(), 0).trace();
        }

        @Test
        @DisplayName("describes the very notes the stage returned")
        void describesTheNotesItReturned() {
            MelodyEstimator.Segmented segmented = MelodyEstimator.explain(
                    track(60.0, 40, 62.0, 40, null, 20, 64.0, 40), silence(), 0);

            List<Note> notes = segmented.melody().notes();
            assertThat(segmented.trace().notes()).hasSameSizeAs(notes);
            for (int i = 0; i < notes.size(); i++) {
                MelodyTrace.Note traced = segmented.trace().notes().get(i);
                assertThat(traced.midiPitch()).isEqualTo(notes.get(i).midiPitch());
                assertThat(traced.fromSeconds()).isEqualTo(notes.get(i).onsetSeconds());
                assertThat(traced.toSeconds()).isEqualTo(notes.get(i).offsetSeconds());
            }
            assertThat(segmented.melody()).isEqualTo(
                    MelodyEstimator.estimate(track(60.0, 40, 62.0, 40, null, 20, 64.0, 40),
                            silence(), 0));
        }

        @Test
        @DisplayName("names the rule that placed each note's start")
        void namesWhatBeganEachNote() {
            MelodyTrace trace = trace(track(60.0, 40, 62.0, 40, null, 20, 64.0, 40));

            assertThat(trace.notes()).extracting(MelodyTrace.Note::startedBy)
                    .containsExactly(MelodyTrace.Note.RUN, MelodyTrace.Note.PITCH,
                            MelodyTrace.Note.RUN);
            assertThat(trace.notes()).extracting(MelodyTrace.Note::run)
                    .containsExactly(0, 0, 1);
            assertThat(trace.runs()).hasSize(2);
        }

        @Test
        @DisplayName("counts the pieces a run held a pitch in, and the ones it did not")
        void countsThePiecesThatHeldAPitch() {
            // A glide between two notes: its pieces are cut like any others and
            // become no note, which is the only place the two counts differ.
            MelodyTrace trace = trace(track(Glides.runs(
                    60.0, 40, Glides.ramp(60.2, 67.8, 41), 68.0, 40)));

            assertThat(trace.runs()).hasSize(1);
            MelodyTrace.Run run = trace.runs().get(0);
            assertThat(run.held()).isEqualTo(2);
            assertThat(run.pieces()).isGreaterThan(run.held());
            assertThat(run.notes()).isEqualTo(2);
        }

        @Test
        @DisplayName("measures the head of a run that no note covers")
        void measuresTheGlideNoNoteTakes() {
            // A run that opens on a glide: the first note starts where the
            // pitch settles, and the frames before it belong to nothing.
            MelodyTrace trace = trace(track(Glides.runs(
                    Glides.ramp(60.0, 67.8, 41), 68.0, 40)));

            MelodyTrace.Run run = trace.runs().get(0);
            assertThat(run.unassignedSeconds()).isGreaterThan(0);
            assertThat(trace.notes().get(0).fromSeconds())
                    .isEqualTo(run.fromSeconds() + run.unassignedSeconds());
        }

        @Test
        @DisplayName("a run that yielded no note reports all of itself as unassigned")
        void aRunWithNoNoteIsAllUnassigned() {
            MelodyTrace trace = trace(track(Glides.runs(Glides.ramp(60.0, 79.0, 41))));

            assertThat(trace.notes()).isEmpty();
            MelodyTrace.Run run = trace.runs().get(0);
            assertThat(run.notes()).isZero();
            assertThat(run.unassignedSeconds())
                    .isEqualTo(run.toSeconds() - run.fromSeconds());
        }

        @Test
        @DisplayName("counts the boundaries the envelope added, and marks the notes")
        void countsRearticulations() {
            double[] strength = new double[(int) Math.ceil(1.3 * ENVELOPE_RATE)];
            strength[(int) Math.round(0.6 * ENVELOPE_RATE)] = 5.0;
            double[] hz = new double[100];
            boolean[] voiced = new boolean[100];
            double[] voicedness = new double[100];
            for (int i = 0; i < 100; i++) {
                hz[i] = 440;
                voiced[i] = true;
                voicedness[i] = i >= 44 && i <= 52 ? 0.3 : 0.9;
            }
            MelodyTrace trace = MelodyEstimator.explain(
                    new PitchTrack(hz, voiced, voicedness, RATE,
                            PitchTracker.WINDOW, PitchTracker.HOP),
                    new OnsetEnvelope(strength, ENVELOPE_RATE), 0).trace();

            assertThat(trace.runs().get(0).rearticulations()).isEqualTo(1);
            assertThat(trace.notes()).extracting(MelodyTrace.Note::startedBy)
                    .containsExactly(MelodyTrace.Note.RUN, MelodyTrace.Note.REARTICULATION);
        }

        @Test
        @DisplayName("records what each test read where a note may have been struck again")
        void recordsEveryStrikeConsidered() {
            double[] strength = new double[(int) Math.ceil(1.3 * ENVELOPE_RATE)];
            strength[(int) Math.round(0.3 * ENVELOPE_RATE)] = 5.0;
            strength[(int) Math.round(0.8 * ENVELOPE_RATE)] = 1.0;
            double[] hz = new double[100];
            boolean[] voiced = new boolean[100];
            double[] voicedness = new double[100];
            Arrays.fill(hz, 440);
            Arrays.fill(voiced, true);
            Arrays.fill(voicedness, 0.9);
            double rate = (double) RATE / OnsetEnvelope.ONSET_HOP;
            double[] decibels = new double[(int) Math.ceil(1.3 * rate)];
            Arrays.fill(decibels, -10);
            int lowest = (int) Math.round(0.8 * rate);
            for (int k = -4; k <= 4; k++) {
                decibels[lowest + k] = -24 + 14 * Math.abs(k) / 5.0;
            }
            MelodyTrace trace = MelodyEstimator.explain(
                    new PitchTrack(hz, voiced, voicedness, RATE,
                            PitchTracker.WINDOW, PitchTracker.HOP),
                    new OnsetEnvelope(strength, ENVELOPE_RATE),
                    new LevelContour(decibels, rate, 0), 0, false).trace();

            // The peak the voice held through, then the stroke that cut.
            assertThat(trace.strikes()).extracting(MelodyTrace.Strike::read)
                    .containsExactly(MelodyTrace.Strike.HELD, MelodyTrace.Strike.LEVEL);
            MelodyTrace.Strike peak = trace.strikes().get(0);
            assertThat(peak.seconds()).isCloseTo(0.3, within(0.01));
            assertThat(peak.strength()).isEqualTo(5.0);
            assertThat(peak.voicedness()).isEqualTo(1.0);
            assertThat(peak.dipDecibels()).isZero();
            MelodyTrace.Strike stroke = trace.strikes().get(1);
            assertThat(stroke.seconds()).isCloseTo(0.8, within(0.01));
            assertThat(stroke.dipDecibels()).isCloseTo(14, within(1e-9));
            assertThat(trace.runs().get(0).rearticulations()).isEqualTo(1);
            assertThat(trace.notes()).extracting(MelodyTrace.Note::startedBy)
                    .containsExactly(MelodyTrace.Note.RUN, MelodyTrace.Note.STROKE);
        }

        @Test
        @DisplayName("a signal with nothing in it says so rather than saying nothing")
        void aSignalWithNothingInItIsLegible() {
            MelodyTrace trace = trace(track(null, 200));

            assertThat(trace.track().frames()).isEqualTo(200);
            assertThat(trace.track().voicedFrames()).isZero();
            assertThat(trace.runs()).isEmpty();
            assertThat(trace.notes()).isEmpty();
            assertThat(trace.fold()).as("no note reached the fold").isNull();
        }

        @Test
        @DisplayName("a signal with nothing voiced in it disagrees with no offset")
        void anEmptySignalWeighsNoOffset() {
            // The emptied stem of #575: a track this long says nothing about
            // whether the singing sits on the mix's grid, and recording that as
            // a disagreement would put a reading where there was no comparison.
            MelodyTrace.Tuning tuning = MelodyEstimator.explain(
                    track(null, 200), silence(), -0.36).trace().tuning();

            assertThat(tuning.read()).isEqualTo(MelodyTrace.Tuning.NOT_WEIGHED);
            assertThat(tuning.agreement()).isNull();
            assertThat(tuning.appliedSemitones()).isZero();
        }

        @Test
        @DisplayName("an offset the front end never measured is recorded as no measurement")
        void anUnmeasuredOffsetIsNotAMeasurement() {
            MelodyTrace.Tuning tuning = trace(track(69.0, 40)).tuning();

            assertThat(tuning.measured()).isFalse();
            assertThat(tuning.agreement()).isNull();
            assertThat(tuning.appliedSemitones()).isZero();
        }

        @Test
        @DisplayName("records what each gesture was moved by, and which notes it carried")
        void recordsTheOctaveFold() {
            MelodyTrace trace = trace(track(OctaveErrors.with(
                    OctaveErrors.singing(), null, 4, 86.0, 20)));

            assertThat(trace.fold().read()).isEqualTo(MelodyTrace.Fold.APPLIED);
            MelodyTrace.Note last = trace.notes().get(trace.notes().size() - 1);
            assertThat(last.shiftSemitones()).isEqualTo(-24);
            MelodyTrace.Gesture moved = trace.gestures().get(last.gesture());
            assertThat(moved.read()).isEqualTo(MelodyTrace.Gesture.MOVED);
            assertThat(moved.lowestMidi()).as("the pitch as the tracker read it").isEqualTo(86);
            assertThat(moved.heldMidi()).isEqualTo(86);
            assertThat(trace.gestures()).extracting(MelodyTrace.Gesture::read)
                    .contains(MelodyTrace.Gesture.INSIDE);
        }

        @Test
        @DisplayName("an offset reading as concert pitch is recorded as one nothing tested")
        void anOffsetReadingAsConcertPitchIsNotTested() {
            MelodyTrace.Tuning tuning = MelodyEstimator.explain(track(69.0, 40), silence(),
                    -Chroma.TUNING_RESOLUTION_SEMITONES / 2).trace().tuning();

            assertThat(tuning.read()).isEqualTo(MelodyTrace.Tuning.CONCERT_PITCH);
            assertThat(tuning.agreement()).isNull();
            assertThat(tuning.appliedSemitones()).isZero();
        }

        @Test
        @DisplayName("an offset the track sits on is recorded with what it was weighed on")
        void aCorroboratedOffsetCarriesItsReading() {
            MelodyTrace.Tuning tuning = MelodyEstimator.explain(
                    track(69 - 0.36 - 0.15, 40, 71 - 0.36 - 0.05, 40),
                    silence(), -0.36).trace().tuning();

            assertThat(tuning.read()).isEqualTo(MelodyTrace.Tuning.CORROBORATED);
            assertThat(tuning.agreement()).isGreaterThanOrEqualTo(tuning.required());
            assertThat(tuning.appliedSemitones()).isEqualTo(-0.36);
        }

        @Test
        @DisplayName("an offset the track does not sit on is recorded as refused")
        void anUncorroboratedOffsetCarriesItsReading() {
            MelodyTrace.Tuning tuning = MelodyEstimator.explain(
                    track(60.0, 40, 62.4, 40, 64.8, 40, 67.2, 40, 69.6, 40),
                    silence(), -0.4).trace().tuning();

            assertThat(tuning.read()).isEqualTo(MelodyTrace.Tuning.UNCORROBORATED);
            assertThat(tuning.agreement()).isLessThan(tuning.required());
            assertThat(tuning.appliedSemitones())
                    .as("so the notes were rounded on A440 after all").isZero();
        }
    }
}
