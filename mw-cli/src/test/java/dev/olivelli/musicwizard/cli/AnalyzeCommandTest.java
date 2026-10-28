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

package dev.olivelli.musicwizard.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.core.workspace.BeatTrace;
import dev.olivelli.musicwizard.core.workspace.RunManifest;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The one line of this command that is not plumbing.
 *
 * <p>What it prints has to be a number the user can hand straight back to
 * {@code --tempo}, which is the correction the README calls the highest-value
 * thing they can do. That has failed twice: once by printing quarter notes where
 * the option takes counted beats, and once by printing the map's figure where
 * the engraved chart printed the grid's.
 */
class AnalyzeCommandTest {

    /**
     * Pulses starting a little after t=0, which is where a beat tracker actually
     * puts them and what makes this fixture discriminate.
     *
     * <p>A grid starting at exactly 0.0 is the trap pr-reviewer.md records: with no
     * lead-in, the tempo map's average equals the grid's own rate to the bit, so
     * every source of a tempo agrees and a test cannot tell which one was read.
     * So is one starting within the fraction of a pulse the map leaves out
     * (#824). The lead-in here is past that fraction, so a whole pulse is
     * crammed into it and the map's average is pulled above the grid's.
     */
    private static List<Double> pulses(int count, double interval) {
        List<Double> times = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            times.add(LEAD_IN + i * interval);
        }
        return times;
    }

    private static final double LEAD_IN = 0.13;

    private static Score trackedAt(double interval, TimeSignature meter) {
        List<Double> times = pulses(24, interval);
        return Score.empty(TempoMap.fromBeatTimes(times, meter), LEAD_IN + 24 * interval)
                .withBeatGrid(BeatGrid.ofTimes(times, meter, Confidence.of(0.9)));
    }

    @Nested
    @DisplayName("the tempo line")
    class TempoLine {

        @Test
        @DisplayName("prints quarter notes only where they are what the user counts")
        void printsTheCountedBeat() {
            assertThat(AnalyzeCommand.tempoLine(trackedAt(0.5, TimeSignature.FOUR_FOUR)))
                    .isEqualTo("Tempo   120.0 BPM");
            // 120 dotted quarters a minute, which the map stores as 180 quarters.
            assertThat(AnalyzeCommand.tempoLine(trackedAt(0.5, TimeSignature.SIX_EIGHT)))
                    .isEqualTo("Tempo   120.0 BPM (180.0 quarter notes/min)");
        }

        @Test
        @DisplayName("prints the tracked beats' tempo, not the map's inflated average")
        void prefersTheTrackedBeatsOverTheMap() {
            // fromBeatTimes forces a whole pulse into the audio before the first
            // tracked beat, so the map's average runs above the real tempo, by
            // less on a longer fixture, where the same one pulse is spread
            // thinner. Guarded here because the two agree exactly whenever the
            // grid starts at t=0, or within the fraction of a pulse the map
            // leaves out, and a fixture that starts there cannot tell the two
            // sources apart.
            Score tracked = trackedAt(0.5, TimeSignature.FOUR_FOUR);

            assertThat(tracked.tempoMap().averageTempo(tracked.durationSeconds()))
                    .as("the map is inflated, so this fixture discriminates")
                    .isGreaterThan(123.0);
            assertThat(AnalyzeCommand.tempoLine(tracked)).isEqualTo("Tempo   120.0 BPM");
        }

        @Test
        @DisplayName("prints the tempo the user forced, not the one that was tracked")
        void honoursAForcedTempo() {
            Score corrected = trackedAt(0.5, TimeSignature.FOUR_FOUR)
                    .withTempoMap(TempoMap.constantPulse(60, TimeSignature.FOUR_FOUR));

            assertThat(AnalyzeCommand.tempoLine(corrected)).isEqualTo("Tempo   60.0 BPM");
        }

        @Test
        @DisplayName("is typeable back into --tempo in any locale")
        void isLocaleIndependent() {
            // picocli parses --tempo with Double.valueOf, which rejects "120,0".
            Locale original = Locale.getDefault();
            try {
                for (Locale locale : List.of(Locale.forLanguageTag("fr-FR"),
                        Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN"))) {
                    Locale.setDefault(locale);
                    String line = AnalyzeCommand.tempoLine(trackedAt(0.5, TimeSignature.FOUR_FOUR));

                    assertThat(line).as("under %s", locale).isEqualTo("Tempo   120.0 BPM");
                    assertThat(Double.valueOf(line.replace("Tempo   ", "").replace(" BPM", "")))
                            .as("parseable under %s", locale)
                            .isEqualTo(120.0);
                }
            } finally {
                Locale.setDefault(original);
            }
        }
    }

    @Nested
    @DisplayName("the meter line")
    class MeterLine {

        @Test
        @DisplayName("is followed by the change the run proposed, as it would be typed (#895)")
        void showsTheProposal() {
            String proposal = "--time-signature 6/8,3/4@31.20=8,6/8@33.94=8";
            List<RunManifest.StageRun> stages = List.of(new RunManifest.StageRun(BeatTrace.STAGE,
                    RunManifest.Outcome.COMPUTED, null,
                    Map.of(AudioTranscriber.PROPOSED_METER, proposal)));

            assertThat(AnalyzeCommand.summary(SourceKind.AUDIO,
                    trackedAt(0.5, TimeSignature.SIX_EIGHT), stages))
                    .containsSubsequence("Meter   6/8", "        proposed, not applied: " + proposal);
        }

        @Test
        @DisplayName("is alone where nothing was proposed")
        void saysNothingElse() {
            assertThat(AnalyzeCommand.summary(SourceKind.AUDIO,
                    trackedAt(0.5, TimeSignature.SIX_EIGHT), List.of()))
                    .noneSatisfy(line -> assertThat(line).contains("proposed"));
        }
    }

    @Nested
    @DisplayName("the key line")
    class KeyLine {

        @Test
        @DisplayName("names the key and how much it is trusted")
        void namesTheKeyWithItsConfidence() {
            // With the number, because the row's failure mode is invisible: a
            // key and its relative minor share every note, so a wrong answer
            // reads exactly as well as a right one.
            Score score = trackedAt(0.5, TimeSignature.FOUR_FOUR)
                    .withKeys(List.of(Key.ofSeconds(PitchSpelling.parse("A4"), Mode.MINOR,
                            0, 12.0, Confidence.of(0.25))));

            assertThat(AnalyzeCommand.summary(SourceKind.AUDIO, score))
                    .contains("Key     A minor (25% confidence)");
        }

        @Test
        @DisplayName("names both of the estimator's decisions when the key carries them")
        void namesBothComponentsWhenRecorded() {
            // The row a user acts on: the signature and the tonic over its
            // relative fail differently, and their product says only that one
            // of them did.
            Score score = trackedAt(0.5, TimeSignature.FOUR_FOUR)
                    .withKeys(List.of(Key.estimated(PitchSpelling.parse("A4"), Mode.MINOR,
                            0, 12.0, Confidence.of(0.5), Confidence.of(0.97))));

            assertThat(AnalyzeCommand.summary(SourceKind.AUDIO, score))
                    .contains("Key     A minor (signature 50%, tonic over its relative 97%)");
        }

        @Test
        @DisplayName("says nothing at all when no key was estimated")
        void omitsTheRowWithoutAKey() {
            assertThat(AnalyzeCommand.summary(SourceKind.AUDIO,
                    trackedAt(0.5, TimeSignature.FOUR_FOUR)))
                    .noneSatisfy(line -> assertThat(line).startsWith("Key"));
        }

        @Test
        @DisplayName("prints a percentage a non-English locale can still read back")
        void isLocaleIndependent() {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("ar-EG"));
                Score score = trackedAt(0.5, TimeSignature.FOUR_FOUR)
                        .withKeys(List.of(Key.ofSeconds(PitchSpelling.parse("A4"), Mode.MINOR,
                                0, 12.0, Confidence.of(0.25))));

                assertThat(AnalyzeCommand.summary(SourceKind.AUDIO, score))
                        .contains("Key     A minor (25% confidence)");
            } finally {
                Locale.setDefault(original);
            }
        }
    }

    /**
     * That the unit is stated where the option is reached for (#705).
     *
     * <p>Rendered rather than read off picocli's option model, because a
     * description that never reaches the usage says nothing. Each assertion is
     * the shortest phrase that carries its claim, so rewording stays cheap and
     * dropping the claim does not.
     */
    @Nested
    @DisplayName("the corrections' help")
    class CorrectionHelp {

        /** The usage as one line: picocli wraps, and a claim can straddle a wrap. */
        private String usage() {
            return CliRunner.run("analyze", "--help").all().replaceAll("\\s+", " ");
        }

        @Test
        @DisplayName("--tempo says whose beat it is counted in, and that MW may have picked it")
        void tempoNamesTheMeterThatCountsIt() {
            assertThat(usage())
                    .contains("Which meter counts it is what --time-signature says")
                    .contains("what MW read off the recording");
        }

        @Test
        @DisplayName("--time-signature says it pins that unit")
        void meterSaysItPinsTheUnit() {
            assertThat(usage()).contains("pins the beat --tempo is counted in");
        }
    }

    /** What the melody's staff is called (#862). */
    @Nested
    @DisplayName("the melody's name")
    class MelodyName {

        private Score led(String name, boolean sung) {
            Score score = Score.empty(TempoMap.constant(120), 4.0).withTrack(new NoteTrack(
                    PartRole.LEAD_VOCAL, name, List.of(Note.ofSeconds(0.0, 1.0, 72, Confidence.of(0.8))),
                    Confidence.of(0.7)));
            return sung ? score.withLyrics(new Lyrics(List.of(new LyricLine(
                    List.of(LyricWord.ofSeconds("la", 0.0, 1.0, Confidence.of(0.8))),
                    Confidence.of(0.8))), "it", Confidence.of(0.8))) : score;
        }

        private static String nameOf(Score score) {
            return score.track(PartRole.LEAD_VOCAL).orElseThrow().name();
        }

        @Test
        @DisplayName("is the instrument the user named, whatever the transcription called it")
        void theUserNamesIt() {
            for (String name : List.of(NoteTrack.VOICE, NoteTrack.LEAD)) {
                assertThat(nameOf(AnalyzeCommand.named(led(name, true), SourceKind.AUDIO,
                        Optional.of("Flauto")))).isEqualTo("Flauto");
            }
        }

        @Test
        @DisplayName("a line alone with words under it was sung")
        void aSungLineAloneIsAVoice() {
            assertThat(nameOf(AnalyzeCommand.named(led(NoteTrack.LEAD, true), SourceKind.AUDIO,
                    Optional.empty()))).isEqualTo(NoteTrack.VOICE);
            assertThat(nameOf(AnalyzeCommand.named(led(NoteTrack.LEAD, false), SourceKind.AUDIO,
                    Optional.empty()))).isEqualTo(NoteTrack.LEAD);
        }

        @Test
        @DisplayName("a MIDI file's own track name is left as the file wrote it")
        void aMidiNameIsTheFiles() {
            assertThat(nameOf(AnalyzeCommand.named(led(NoteTrack.LEAD, true), SourceKind.MIDI,
                    Optional.empty()))).isEqualTo(NoteTrack.LEAD);
        }

        @Test
        @DisplayName("a score without a melody is left alone")
        void noMelodyNoName() {
            Score score = Score.empty(TempoMap.constant(120), 4.0);

            assertThat(AnalyzeCommand.named(score, SourceKind.AUDIO, Optional.of("Flauto")))
                    .isSameAs(score);
        }
    }
}
