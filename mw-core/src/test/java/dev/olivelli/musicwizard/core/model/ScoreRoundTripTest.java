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

package dev.olivelli.musicwizard.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The workspace persists a {@code Score} between runs, so serialization is a
 * hard requirement rather than a nicety. These tests exist because derived
 * accessors on a record are silently picked up by Jackson as extra properties,
 * which breaks reading a score back with an error that names an unrelated field.
 */
class ScoreRoundTripTest {

    /** A score exercising every field, so nothing escapes the round trip. */
    private static Score fullyPopulated() {
        PitchSpelling c4 = new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 4);
        PitchSpelling e3 = new PitchSpelling(NoteLetter.E, Accidental.NATURAL, 3);

        Note plain = Note.ofSeconds(0.0, 0.5, 60, Confidence.of(0.9));
        Note quantized = Note.ofSeconds(0.5, 0.5, 64, Confidence.of(0.8))
                .quantizedTo(1.0, 1.0)
                .spelledAs(new PitchSpelling(NoteLetter.E, Accidental.NATURAL, 4));

        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice",
                List.of(plain, quantized), Confidence.of(0.7));
        NoteTrack bass = new NoteTrack(PartRole.BASS, "Bass",
                List.of(Note.ofSeconds(0.0, 1.0, 40, Confidence.of(0.85))), Confidence.of(0.85));

        Chord chord = Chord.ofSeconds(c4, ChordQuality.MAJOR_SEVENTH, 0.0, 2.0, Confidence.of(0.75))
                .withBass(e3)
                .quantizedTo(0.0, 4.0);
        Chord silence = Chord.noChord(2.0, 3.0, Confidence.of(0.6));

        LyricWord hello = new LyricWord("hello", 0.0, 0.4, Optional.of(0.0), Confidence.of(0.5));
        LyricWord world = new LyricWord("world", 0.5, 0.9, Optional.empty(), Confidence.of(0.5));
        Lyrics lyrics = new Lyrics(
                List.of(new LyricLine(List.of(hello, world), Confidence.of(0.5))),
                "en", Confidence.of(0.5));

        BeatGrid grid = BeatGrid.ofTimes(List.of(0.0, 0.5, 1.0, 1.5, 2.0), 4, Confidence.of(0.9));

        return new Score(
                Optional.of("Test Piece"),
                Optional.of("A Composer"),
                TempoMap.constant(120, TimeSignature.FOUR_FOUR)
                        .withMeterChange(2, TimeSignature.THREE_FOUR),
                Optional.of(grid),
                List.of(new Key(c4, Mode.MAJOR, 0.0, 3.0, Confidence.of(0.8))),
                List.of(new Section(SectionKind.VERSE, "Verse 1", 0.0, 3.0,
                        Optional.of("A"), Confidence.of(0.7))),
                List.of(voice, bass),
                new ChordProgression(List.of(chord, silence), Confidence.of(0.7)),
                lyrics,
                3.0);
    }

    @Nested
    @DisplayName("JSON persistence")
    class Persistence {

        @Test
        @DisplayName("a fully populated score survives a round trip")
        void roundTripsEverything() {
            Score original = fullyPopulated();

            Score restored = ScoreJson.fromJson(ScoreJson.toJson(original));

            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("an empty score survives a round trip")
        void roundTripsEmpty() {
            Score original = Score.empty(TempoMap.constant(100), 12.5);

            assertThat(ScoreJson.fromJson(ScoreJson.toJson(original))).isEqualTo(original);
        }

        @Test
        @DisplayName("no derived accessor is written as a property")
        void noDerivedPropertiesLeak() {
            // Each of these is an isXxx() on a record. Jackson treats them as bean
            // getters, and reading the score back then fails on a field that is not
            // a record component. Guarding by name because the failure is silent
            // on write and only surfaces on read.
            String json = ScoreJson.toJson(fullyPopulated());

            assertThat(json)
                    .doesNotContain("\"compound\"")
                    .doesNotContain("\"flatKey\"")
                    .doesNotContain("\"quantized\"")
                    .doesNotContain("\"empty\"")
                    .doesNotContain("\"monophonic\"")
                    .doesNotContain("\"noChord\"")
                    .doesNotContain("\"slashChord\"")
                    .doesNotContain("\"downbeat\":true,\"positionInBar\"".substring(0, 0) + "\"renderableContent\"");
        }

        @Test
        @DisplayName("optional musical timing survives as present or absent")
        void preservesOptionalTiming() {
            Score restored = ScoreJson.fromJson(ScoreJson.toJson(fullyPopulated()));
            NoteTrack voice = restored.track(PartRole.LEAD_VOCAL).orElseThrow();

            assertThat(voice.notes().get(0).isQuantized()).isFalse();
            assertThat(voice.notes().get(1).isQuantized()).isTrue();
            assertThat(voice.notes().get(1).onsetBeat()).contains(1.0);
            assertThat(voice.notes().get(1).spelling()).isPresent();
        }

        @Test
        @DisplayName("a score written by a newer build still opens")
        void toleratesUnknownFields() {
            String json = ScoreJson.toJson(Score.empty(TempoMap.constant(120), 5.0))
                    .replaceFirst("\\{", "{\"someFutureField\": 42,");

            assertThat(ScoreJson.fromJson(json).durationSeconds()).isEqualTo(5.0);
        }
    }

    @Nested
    @DisplayName("invariants that deserialization must not be able to break")
    class Invariants {

        @Test
        @DisplayName("a note cannot be half-quantized")
        void rejectsHalfQuantizedNote() {
            assertThatThrownBy(() -> new Note(0, 1, 60, 80,
                    Optional.empty(), Optional.of(1.0), Optional.empty(), Confidence.CERTAIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("both onsetBeat and durationBeats");
        }

        @Test
        @DisplayName("a note cannot have a negative quantized onset")
        void rejectsNegativeOnsetBeat() {
            assertThatThrownBy(() -> new Note(0, 1, 60, 80,
                    Optional.empty(), Optional.of(-3.0), Optional.of(1.0), Confidence.CERTAIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("onsetBeat");
        }

        @Test
        @DisplayName("a chord cannot end before it starts in beat terms")
        void rejectsInvertedChordBeats() {
            assertThatThrownBy(() -> new Chord(
                    new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 4),
                    ChordQuality.MAJOR, Optional.empty(), 0, 1,
                    Optional.of(10.0), Optional.of(2.0), Confidence.CERTAIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endBeat");
        }

        @Test
        @DisplayName("keys are ordered and may not overlap")
        void rejectsOverlappingKeys() {
            PitchSpelling c = new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 4);
            Score score = Score.empty(TempoMap.constant(120), 100);

            assertThatThrownBy(() -> score.withKeys(List.of(
                    new Key(c, Mode.MAJOR, 0, 60, Confidence.CERTAIN),
                    new Key(c, Mode.MINOR, 50, 80, Confidence.CERTAIN))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not overlap");
        }

        @Test
        @DisplayName("keyAt answers by position, not by insertion order")
        void keyAtIsPositional() {
            PitchSpelling c = new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 4);
            Key later = new Key(c, Mode.MINOR, 50, 60, Confidence.CERTAIN);
            Key earlier = new Key(c, Mode.MAJOR, 0, 50, Confidence.CERTAIN);

            // Deliberately supplied out of order.
            Score score = Score.empty(TempoMap.constant(120), 100)
                    .withKeys(List.of(later, earlier));

            assertThat(score.keyAt(10).orElseThrow().mode()).isEqualTo(Mode.MAJOR);
            assertThat(score.keyAt(55).orElseThrow().mode()).isEqualTo(Mode.MINOR);
        }

        @Test
        @DisplayName("replacing a track keeps the staff order stable")
        void withTrackPreservesOrder() {
            NoteTrack voice = NoteTrack.empty(PartRole.LEAD_VOCAL, "Voice");
            NoteTrack bass = NoteTrack.empty(PartRole.BASS, "Bass");
            NoteTrack betterVoice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice v2",
                    List.of(), Confidence.of(0.9));

            Score score = Score.empty(TempoMap.constant(120), 10)
                    .withTrack(voice).withTrack(bass).withTrack(betterVoice);

            // Voice must stay first; otherwise the printed staff order would depend
            // on which analysis stage happened to finish last.
            assertThat(score.tracks()).extracting(NoteTrack::role)
                    .containsExactly(PartRole.LEAD_VOCAL, PartRole.BASS);
            assertThat(score.tracks().get(0).name()).isEqualTo("Voice v2");
        }

        @Test
        @DisplayName("a beat cannot claim to be a downbeat at a non-zero bar position")
        void rejectsContradictoryBeat() {
            assertThatThrownBy(() -> new BeatGrid.Beat(0.0, true, 3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contradicts");
        }

        @Test
        @DisplayName("a downbeat cannot have an unknown bar position")
        void rejectsDownbeatWithUnknownPosition() {
            assertThatThrownBy(() -> new BeatGrid.Beat(0.0, true, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be unknown");
        }

        @Test
        @DisplayName("a score may not hold two tracks with the same role")
        void rejectsDuplicateRoles() {
            // Duplicates make track(role) ambiguous, and previously made
            // withTrack replace every match at once.
            List<NoteTrack> twoBassParts = List.of(
                    NoteTrack.empty(PartRole.BASS, "a"),
                    NoteTrack.empty(PartRole.BASS, "b"));

            assertThatThrownBy(() -> new Score(
                    Optional.empty(), Optional.empty(), TempoMap.constant(120),
                    Optional.empty(), List.of(), List.of(), twoBassParts,
                    ChordProgression.empty(), Lyrics.empty(), 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at most one track in the BASS role");
        }

        @Test
        @DisplayName("all four separated stems can coexist in one score")
        void holdsEveryStem() {
            // Source separation emits vocals, bass, drums and everything else. If
            // only one unclassified track were allowed, two of those four could not
            // be represented at all.
            Score score = Score.empty(TempoMap.constant(120), 10)
                    .withTrack(NoteTrack.empty(PartRole.LEAD_VOCAL, "Vocals"))
                    .withTrack(NoteTrack.empty(PartRole.BASS, "Bass"))
                    .withTrack(NoteTrack.empty(PartRole.DRUMS, "Drums"))
                    .withTrack(NoteTrack.empty(PartRole.ACCOMPANIMENT, "Other"));

            assertThat(score.tracks()).hasSize(4);
            assertThat(score.track(PartRole.DRUMS)).isPresent();
        }

        @Test
        @DisplayName("several unclassified tracks may coexist, distinguished by name")
        void allowsSeveralOtherTracks() {
            Score score = Score.empty(TempoMap.constant(120), 10)
                    .withTrack(NoteTrack.empty(PartRole.OTHER, "Guitar"))
                    .withTrack(NoteTrack.empty(PartRole.OTHER, "Strings"));

            assertThat(score.tracks()).hasSize(2);
            assertThat(score.tracks()).extracting(NoteTrack::name)
                    .containsExactly("Guitar", "Strings");
        }

        @Test
        @DisplayName("tracks(role) returns every unclassified track, not just the first")
        void tracksByRoleReturnsAll() {
            Score score = Score.empty(TempoMap.constant(120), 10)
                    .withTrack(NoteTrack.empty(PartRole.OTHER, "Guitar"))
                    .withTrack(NoteTrack.empty(PartRole.OTHER, "Strings"))
                    .withTrack(NoteTrack.empty(PartRole.BASS, "Bass"));

            assertThat(score.tracks(PartRole.OTHER)).hasSize(2);
            assertThat(score.tracks(PartRole.BASS)).hasSize(1);
            assertThat(score.tracks(PartRole.DRUMS)).isEmpty();
        }

        @Test
        @DisplayName("two unclassified tracks may not share a name")
        void rejectsAmbiguousOtherTracks() {
            List<NoteTrack> ambiguous = List.of(
                    NoteTrack.empty(PartRole.OTHER, "Guitar"),
                    NoteTrack.empty(PartRole.OTHER, "Guitar"));

            assertThatThrownBy(() -> new Score(
                    Optional.empty(), Optional.empty(), TempoMap.constant(120),
                    Optional.empty(), List.of(), List.of(), ambiguous,
                    ChordProgression.empty(), Lyrics.empty(), 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("distinct names");
        }

        @Test
        @DisplayName("a lyric line ends when its last word ends, even if spans overlap")
        void lyricLineEndsAtTheMaximum() {
            // Recognition spans on sung speech overlap, so the word that starts
            // last need not be the one that finishes last.
            LyricLine line = new LyricLine(List.of(
                    new LyricWord("looong", 1.0, 99.0, Optional.empty(), Confidence.CERTAIN),
                    new LyricWord("short", 2.0, 3.0, Optional.empty(), Confidence.CERTAIN)),
                    Confidence.CERTAIN);

            assertThat(line.endSeconds()).isEqualTo(99.0);
        }

        @Test
        @DisplayName("a lyric line reports a sane span even if words arrive unordered")
        void lyricLineOrdersWords() {
            LyricLine line = new LyricLine(List.of(
                    new LyricWord("late", 5, 6, Optional.empty(), Confidence.CERTAIN),
                    new LyricWord("early", 1, 2, Optional.empty(), Confidence.CERTAIN)),
                    Confidence.CERTAIN);

            assertThat(line.startSeconds()).isLessThan(line.endSeconds());
            assertThat(line.text()).isEqualTo("early late");
        }
    }

    @Nested
    @DisplayName("tempo map consistency")
    class TempoConsistency {

        @Test
        @DisplayName("rejects a segment whose tempo contradicts the next segment")
        void rejectsInconsistentSegments() {
            // 60 BPM from beat 0 reaches beat 10 at t=10s, not t=1s. Accepting this
            // would make time appear to jump backwards at the boundary.
            assertThatThrownBy(() -> new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0, 60),
                            new TempoMap.TempoSegment(10, 1.0, 60)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("inconsistent");
        }

        @Test
        @DisplayName("requires the map to be anchored on both axes")
        void requiresAnchor() {
            assertThatThrownBy(() -> new TempoMap(
                    List.of(new TempoMap.TempoSegment(8, 10.0, 120)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("anchored");

            // Anchoring only the beat axis still lets secondsToBeats(0) go negative,
            // which is the failure the anchor exists to prevent.
            assertThatThrownBy(() -> new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 10.0, 120)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("anchored");
        }

        @Test
        @DisplayName("accepts a map whose boundaries were stored at millisecond precision")
        void acceptsMillisecondPrecision() {
            // Rounding boundary times to milliseconds is normal for an imported
            // map and must not be mistaken for an inconsistent tempo.
            double secondsPerBeat = 60.0 / 137.0;
            double boundary = Math.round(200 * secondsPerBeat * 1000.0) / 1000.0;

            assertThatCode(() -> new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0, 137),
                            new TempoMap.TempoSegment(200, boundary, 141.5)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("tracked beats stay on integer positions for any lead-in")
        void leadInKeepsTrackedBeatsOnIntegers() {
            // The regression this guards: rounding the lead-in to zero and then
            // shifting the seconds axis puts every tracked beat up to half a beat
            // off, and silently misaligns the whole map from the audio. It is also
            // discontinuous -- one millisecond of difference in the tracker's first
            // onset flipped the behaviour.
            for (double firstBeat : new double[] {
                    0.001, 0.01, 0.05, 0.2499, 0.25, 0.2501, 0.4, 0.5, 1.0, 7.3, 30.0}) {
                List<Double> beats = new java.util.ArrayList<>();
                for (int i = 0; i < 40; i++) {
                    beats.add(firstBeat + i * 0.5);
                }
                TempoMap map = TempoMap.fromBeatTimes(beats, TimeSignature.FOUR_FOUR);

                assertThat(map.beatsToSeconds(0.0))
                        .as("map anchored at second 0 for firstBeat=%s", firstBeat)
                        .isCloseTo(0.0, within(1e-9));

                for (int i = 0; i < beats.size(); i++) {
                    double beat = map.secondsToBeats(beats.get(i));
                    assertThat(beat - Math.rint(beat))
                            .as("tracked beat %d on an integer position for firstBeat=%s", i, firstBeat)
                            .isCloseTo(0.0, within(1e-6));
                }
            }
        }

        @Test
        @DisplayName("tracked beats starting mid-recording still yield non-negative beats")
        void handlesLeadIn() {
            // A real beat tracker never reports a beat at t=0. Everything before the
            // first tracked beat must still be convertible, or any note in the intro
            // breaks the notation stage.
            TempoMap map = TempoMap.fromBeatTimes(
                    List.of(2.0, 2.5, 3.0, 3.5, 4.0), TimeSignature.FOUR_FOUR);

            assertThat(map.secondsToBeats(0.0)).isGreaterThanOrEqualTo(0.0);
            assertThat(map.beatsToSeconds(0.0)).isCloseTo(0.0, within(1e-9));
            // The first tracked beat lands on a whole beat, not a fractional one.
            double firstTracked = map.secondsToBeats(2.0);
            assertThat(firstTracked).isCloseTo(Math.rint(firstTracked), within(1e-9));
            assertThat(map.toMusicalTime(map.secondsToBeats(0.5))).isNotNull();
        }

        @Test
        @DisplayName("average tempo accounts for the final segment when duration is known")
        void averageTempoUsesDuration() {
            // 120 BPM for 2s, then 60 BPM for 8s. Ignoring the final segment would
            // report 120; weighting by duration gives something much closer to 60.
            TempoMap map = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0, 120),
                            new TempoMap.TempoSegment(4, 2.0, 60)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));

            assertThat(map.averageTempo(10.0)).isCloseTo(72.0, within(1e-6));
        }
    }

    @Nested
    @DisplayName("chord quality")
    class Qualities {

        @Test
        @DisplayName("a sixth chord is not a seventh chord")
        void sixthIsNotSeventh() {
            // Interval 9 is the diminished seventh of dim7 and the major sixth of a
            // 6 chord, so this cannot be derived from the interval set.
            assertThat(ChordQuality.SIXTH.hasSeventh()).isFalse();
            assertThat(ChordQuality.MINOR_SIXTH.hasSeventh()).isFalse();
            assertThat(ChordQuality.DIMINISHED_SEVENTH.hasSeventh()).isTrue();
            assertThat(ChordQuality.DOMINANT_SEVENTH.hasSeventh()).isTrue();
            assertThat(ChordQuality.MAJOR.hasSeventh()).isFalse();
        }
    }
}
