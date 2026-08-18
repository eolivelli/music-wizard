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

package dev.olivelli.musicwizard.notation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.arrange.PlayableMelody;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.arrange.Quantizer;
import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lead sheet engraved from the reduced melody (#592), against the same
 * fixture engraved from the estimate.
 *
 * <p>Two goldens rather than one, because the reduction is only legible as a
 * difference: the pair says which note-heads a player is spared and what the
 * chord names and the words do while that happens, which is nothing.
 *
 * <p>The fixture is an un-quantized score, the state the reduction runs in —
 * {@code render} reduces before it quantizes, so that a group's span is snapped
 * once, as a note, rather than assembled out of snapped pieces.
 */
class PlayableLeadSheetTest {

    private static final String UPDATE_PROPERTY = "mw.golden.update";

    private static final double QUARTER_BPM = 120;

    @Test
    @DisplayName("the estimate's page, for the reduction to be read against")
    void theEstimate() {
        QuantizedScore quantized = Quantizer.quantize(scooped());

        assertGolden("lead-sheet-estimate",
                LeadSheet.toLilyPond(quantized, melodyOf(quantized)));
    }

    @Test
    @DisplayName("one note-head per syllable, and the rest of the page untouched")
    void theReduction() {
        Score score = scooped();
        QuantizedScore quantized =
                Quantizer.quantize(score.withTrack(PlayableMelody.reduce(score)));

        assertGolden("lead-sheet-playable",
                LeadSheet.toLilyPond(quantized, melodyOf(quantized)));
    }

    @Test
    @DisplayName("the staff says which of the two pages it is")
    void theStaffIsNamed() {
        Score score = scooped();
        QuantizedScore quantized =
                Quantizer.quantize(score.withTrack(PlayableMelody.reduce(score)));

        assertThat(LeadSheet.toLilyPond(quantized, melodyOf(quantized)))
                .contains("instrumentName = \"" + PlayableMelody.TRACK_NAME + "\"");
    }

    /**
     * Four bars whose first two syllables are scooped into, as a sung entry is.
     *
     * <p>Spelled here rather than by {@code PitchSpeller}, so that the goldens
     * also say that the settled note's own written spelling rides along with the
     * pitch it printed.
     */
    private static Score scooped() {
        TempoMap map = TempoMap.constant(QUARTER_BPM, TimeSignature.FOUR_FOUR);
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(map, 0, 0.5, "E4"), note(map, 0.5, 0.5, "F4"), note(map, 1, 1, "G4"),
                note(map, 2, 0.5, "A4"), note(map, 2.5, 1.5, "C5"),
                note(map, 4, 4, "E5"),
                note(map, 8, 4, "G4"),
                note(map, 12, 1, "B4"), note(map, 13, 3, "C5")), Confidence.CERTAIN);
        double[] from = {0, 2, 4, 8, 12};
        double[] to = {2, 4, 8, 12, 16};
        String[] sung = {"one", "two", "three", "four", "five"};
        List<LyricWord> words = new ArrayList<>();
        for (int i = 0; i < sung.length; i++) {
            words.add(LyricWord.ofSeconds(sung[i], map.beatsToSeconds(from[i]),
                    map.beatsToSeconds(to[i]), Confidence.CERTAIN));
        }
        return Score.empty(map, 16 / (QUARTER_BPM / 60))
                .withTrack(voice)
                .withChords(new ChordProgression(List.of(
                        chord(map, "C4", ChordQuality.MAJOR, 0, 4),
                        chord(map, "A4", ChordQuality.MINOR, 4, 8),
                        chord(map, "F4", ChordQuality.MAJOR, 8, 12),
                        chord(map, "G4", ChordQuality.DOMINANT_SEVENTH, 12, 16)),
                        Confidence.of(0.9)))
                .withLyrics(new Lyrics(List.of(new LyricLine(words, Confidence.CERTAIN)),
                        "en", Confidence.CERTAIN));
    }

    private static Note note(TempoMap map, double onsetBeat, double beats, String spelling) {
        PitchSpelling written = pitch(spelling);
        return Note.ofSeconds(map.beatsToSeconds(onsetBeat),
                        map.beatsToSeconds(onsetBeat + beats) - map.beatsToSeconds(onsetBeat),
                        written.midiPitch(), Confidence.CERTAIN)
                .spelledAs(written);
    }

    private static Chord chord(TempoMap map, String root, ChordQuality quality,
                               double fromBeat, double toBeat) {
        return Chord.ofSeconds(pitch(root), quality,
                map.beatsToSeconds(fromBeat), map.beatsToSeconds(toBeat), Confidence.of(0.9));
    }

    private static PitchSpelling pitch(String name) {
        NoteLetter letter = NoteLetter.valueOf(name.substring(0, 1));
        Accidental accidental = name.contains("#") ? Accidental.SHARP
                : name.contains("b") ? Accidental.FLAT : Accidental.NATURAL;
        return new PitchSpelling(letter, accidental,
                Integer.parseInt(name.substring(name.length() - 1)));
    }

    private static NoteTrack melodyOf(QuantizedScore quantized) {
        return quantized.score().track(PartRole.LEAD_VOCAL).orElseThrow();
    }

    /**
     * Compares generated LilyPond against its golden file.
     *
     * <p>The same contract as {@code StaffNotationTest}'s: {@code
     * -Dmw.golden.update=true} rewrites the file from the source tree Maven
     * passes as {@code -Dbasedir}, and the run that does it is expected to read
     * the diff, because the comparison it would have failed is the one the flag
     * suppressed.
     */
    private static void assertGolden(String name, String actual) {
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Path target = goldenDirectory()
                    .orElseThrow(() -> new AssertionError(UPDATE_PROPERTY
                            + " needs the module directory, which Maven passes as -Dbasedir"))
                    .resolve(name + ".ly");
            try {
                Files.writeString(target, actual);
            } catch (IOException e) {
                throw new UncheckedIOException("could not update golden " + name, e);
            }
            System.err.println("updated golden file " + target);
        }
        assertThat(actual).isEqualTo(readGolden(name));
    }

    private static String readGolden(String name) {
        Optional<Path> onDisk = goldenDirectory().map(dir -> dir.resolve(name + ".ly"))
                .filter(Files::isRegularFile);
        if (onDisk.isPresent()) {
            try {
                return Files.readString(onDisk.get());
            } catch (IOException e) {
                throw new UncheckedIOException("could not read golden " + name, e);
            }
        }
        try (InputStream in = PlayableLeadSheetTest.class
                .getResourceAsStream("/golden/" + name + ".ly")) {
            if (in == null) {
                throw new AssertionError("no golden file for " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read golden " + name, e);
        }
    }

    private static Optional<Path> goldenDirectory() {
        String basedir = System.getProperty("basedir", System.getProperty("user.dir"));
        if (basedir == null) {
            return Optional.empty();
        }
        Path directory = Path.of(basedir, "src", "test", "resources", "golden");
        return Files.isDirectory(directory) ? Optional.of(directory) : Optional.empty();
    }
}
