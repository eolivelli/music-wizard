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

package dev.olivelli.musicwizard.it;

import static dev.olivelli.musicwizard.it.LilyPondComplaints.assertEngravedCleanly;
import static dev.olivelli.musicwizard.it.LilyPondComplaints.failedBarChecksIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.notation.LilyPondRenderer;
import dev.olivelli.musicwizard.notation.StaffNotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the golden-file tests cannot check: that LilyPond accepts the emitted
 * source and engraves a page from it.
 *
 * <p>The assertion that matters is the absence of warnings. Every bar carries a
 * bar check, so a bar that does not fill its meter — the commonest way an
 * emitter goes wrong, and the one LilyPond will otherwise engrave without
 * complaint — shows up as a failed bar check and fails here. The last test in
 * the file proves that check has teeth by engraving a bar that is a beat short
 * on purpose; {@link LilyPondComplaints} is how that complaint is recognised
 * without depending on which of its two spellings the installed LilyPond uses.
 */
class StaffNotationIT {

    @TempDir
    Path tempDirectory;

    private static Note note(double onsetBeat, double beats, String spelling) {
        PitchSpelling pitch = PitchSpelling.parse(spelling);
        return Note.ofSeconds(onsetBeat / 2 + 0.5, beats / 2, pitch.midiPitch(), Confidence.CERTAIN)
                .quantizedTo(onsetBeat, beats)
                .spelledAs(pitch);
    }

    /** A melody with a pickup, ties over bar lines and a rest, in common time. */
    private static Score commonTimeSong() {
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(3, 1, "G4"),
                note(4, 1.5, "C5"), note(5.5, 0.5, "B4"), note(6, 3, "A4"),
                note(9, 1, "G4"), note(11, 1, "F4"),
                note(12, 4, "E4")), Confidence.CERTAIN);
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 60)
                .withTrack(voice)
                .withKeys(List.of(Key.ofSeconds(PitchSpelling.parse("G3"), Mode.MAJOR,
                        0, 60, Confidence.CERTAIN)))
                .withMetadata("Engraving Check", "Music Wizard");
    }

    /** A tune in compound time, whose bars must beam in two groups of three. */
    private static Score compoundTimeSong() {
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(0, 0.5, "C4"), note(0.5, 0.5, "D4"), note(1, 0.5, "E4"),
                note(1.5, 1, "F4"), note(2.5, 0.5, "G4"),
                note(3, 2, "A4"), note(5, 1, "G4")), Confidence.CERTAIN);
        return Score.empty(TempoMap.constant(180, TimeSignature.SIX_EIGHT), 60).withTrack(voice);
    }

    /** A bass line, which is engraved an octave above where it sounds. */
    private static Score bassSong() {
        NoteTrack bass = new NoteTrack(PartRole.BASS, "Bass", List.of(
                note(0, 1, "Eb2"), note(1, 1, "Eb2"), note(2, 1, "Ab2"), note(3, 1, "Bb2"),
                note(4, 4, "Ab1")), Confidence.CERTAIN);
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 60)
                .withTrack(bass)
                .withKeys(List.of(Key.ofSeconds(PitchSpelling.parse("Eb3"), Mode.MAJOR,
                        0, 60, Confidence.CERTAIN)));
    }

    /**
     * The meters whose beats do not come in a power-of-two count, where the
     * splitter has repeatedly hidden beats. The defects themselves
     * were invisible here — a bar that hides a beat still sums, so LilyPond
     * engraves it silently — but the tied output they now produce is the most
     * heavily split this emitter writes, and that it engraves at all is worth
     * knowing.
     */
    private static Score oddMeterSong(TimeSignature meter) {
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(0, 0.75, "C4"), note(0.75, 1.5, "D4"), note(2.25, 0.75, "E4"),
                note(3, meter.quarterBeatsPerBar() - 3 + 0.0625, "F4")), Confidence.CERTAIN);
        return Score.empty(TempoMap.constant(120, meter), 60).withTrack(voice);
    }

    @Test
    @DisplayName("LilyPond engraves every kind of staff this emits, without a single warning")
    void engravesWithoutWarnings() throws Exception {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();
        LilyPondRenderer renderer = new LilyPondRenderer(lilypond);

        record Case(String name, Score score, PartRole role) {
        }
        List<Case> cases = List.of(
                new Case("melody", commonTimeSong(), PartRole.LEAD_VOCAL),
                new Case("compound", compoundTimeSong(), PartRole.LEAD_VOCAL),
                new Case("bass", bassSong(), PartRole.BASS),
                new Case("triple", oddMeterSong(TimeSignature.THREE_FOUR), PartRole.LEAD_VOCAL),
                new Case("five", oddMeterSong(new TimeSignature(5, 4)), PartRole.LEAD_VOCAL),
                new Case("seven", oddMeterSong(new TimeSignature(7, 8)), PartRole.LEAD_VOCAL));

        for (Case engraved : cases) {
            NoteTrack track = engraved.score().track(engraved.role()).orElseThrow();
            LilyPondRenderer.Result result = renderer.renderSource(
                    tempDirectory.resolve(engraved.name() + "/part.ly"),
                    StaffNotation.toLilyPond(engraved.score(), track));

            // A failed bar check is a warning, not an error: LilyPond engraves
            // the wrong bar and carries on. Treating any warning as a failure is
            // what makes this test worth running.
            //
            // Through the shared helper since #164, having been the third copy
            // of the same two lines -- and by the project's own rule, the place
            // the third edit would go is the place the structure should change
            // instead. Nothing tolerated: this file's staves are the plain ones,
            // and the tuplet suite's carve-out has nothing here to apply to.
            assertEngravedCleanly(engraved.name(), result);

            Path pdf = result.pdf().orElseThrow();
            assertThat(pageCount(pdf)).as("%s page count", engraved.name()).isEqualTo(1);
            // Non-trivial: an engraved staff is tens of kilobytes of glyph
            // drawing, where a page with nothing on it is a couple.
            assertThat(Files.size(pdf)).as("%s is an empty page", engraved.name())
                    .isGreaterThan(10_000);
        }
    }

    @Test
    @DisplayName("a bar that does not fill its meter is caught, so the check above means something")
    void barChecksActuallyFail() {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        // The same preamble the emitter produces, with a bar three beats long
        // under a 4/4 signature. LilyPond engraves it and warns; nothing else
        // about the file is wrong.
        String source = """
                \\version "2.24.0"
                \\score {
                  \\new Staff {
                    \\time #'(1 1 1 1) 4/4
                    c'4 d'4 e'4 |
                    c'1 |
                    \\bar "|."
                  }
                  \\layout { }
                }
                """;
        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve("short/bar.ly"), source);

        assertThat(result.succeeded()).isTrue();
        // The moment rather than the wording: LilyPond spells the complaint
        // "barcheck" up to 2.25.5 and "bar check" after it, and #145 was this
        // test pinning the second spelling while the integration job installs a
        // version that uses the first. What it is really asserting is that the
        // bar was counted and came to three quarters instead of four.
        assertThat(failedBarChecksIn(result.output()))
                .as("%s", result.output())
                .contains("3/4");
        // And the gate the test above rests on fails on it. This was once an
        // inconsistency rather than a gap (#164) -- the same fact
        // is pinned synthetically in LilyPondComplaintsTest -- but the two
        // teeth-tests in this module now read the same way, and this one says it
        // against a real binary on whichever spelling it uses.
        assertThatThrownBy(() -> assertEngravedCleanly("the short bar", result))
                .as("%s", result.output())
                .isInstanceOf(AssertionError.class);
    }

    /** Pages in a PDF, read from the page objects rather than from the trailer. */
    private static int pageCount(Path pdf) throws Exception {
        // Latin-1 so every byte maps to one character and no byte is lost to a
        // decoding error; the structure being matched is ASCII.
        String text = new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1);
        Matcher matcher = Pattern.compile("/Type\\s*/Page[^s]").matcher(text);
        int pages = 0;
        while (matcher.find()) {
            pages++;
        }
        return pages;
    }
}
