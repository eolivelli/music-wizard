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
import dev.olivelli.musicwizard.arrange.QuantizationSettings;
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
import java.util.ArrayList;
import java.util.List;
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

    private static final double QUARTER_BPM = 120;

    @Test
    @DisplayName("the estimate's page, for the reduction to be read against")
    void theEstimate() {
        QuantizedScore quantized = Quantizer.quantize(scooped());

        Goldens.assertGolden("lead-sheet-estimate",
                LeadSheet.toLilyPond(quantized, melodyOf(quantized)));
    }

    @Test
    @DisplayName("one note-head per syllable, and the rest of the page untouched")
    void theReduction() {
        Score score = scooped();
        QuantizedScore quantized = Quantizer.quantize(
                score.withTrack(PlayableMelody.reduce(score)), QuantizationSettings.READING);

        Goldens.assertGolden("lead-sheet-playable",
                LeadSheet.toLilyPond(quantized, melodyOf(quantized)));
    }

    @Test
    @DisplayName("the triplets are the reduction's doing: the estimate of the same line is duple")
    void theOffGridEstimateIsDuple() {
        QuantizedScore estimate = Quantizer.quantize(offGrid());
        Score score = offGrid();
        QuantizedScore reduced =
                Quantizer.quantize(score.withTrack(PlayableMelody.reduce(score)));

        assertThat(LeadSheet.toLilyPond(estimate, melodyOf(estimate)))
                .doesNotContain("\\tuplet");
        assertThat(LeadSheet.toLilyPond(reduced, melodyOf(reduced))).contains("\\tuplet");
    }

    @Test
    @DisplayName("no triplet bracket in a song in straight time")
    void theOffGridReduction() {
        Score score = offGrid();
        QuantizedScore quantized = Quantizer.quantize(
                score.withTrack(PlayableMelody.reduce(score)), QuantizationSettings.READING);

        String source = LeadSheet.toLilyPond(quantized, melodyOf(quantized));

        assertThat(source).doesNotContain("\\tuplet");
        Goldens.assertGolden("lead-sheet-off-grid-playable", source);
    }

    @Test
    @DisplayName("touches the staff and nothing else on the page")
    void onlyTheStaffMoves() {
        Score score = scooped();
        String estimate = LeadSheet.toLilyPond(
                Quantizer.quantize(score), melodyOf(Quantizer.quantize(score)));
        QuantizedScore reduced = Quantizer.quantize(
                score.withTrack(PlayableMelody.reduce(score)), QuantizationSettings.READING);

        String playable = LeadSheet.toLilyPond(reduced, melodyOf(reduced));

        assertThat(context(playable, "\\new ChordNames"))
                .isEqualTo(context(estimate, "\\new ChordNames"));
        assertThat(context(playable, "\\new Lyrics"))
                .isEqualTo(context(estimate, "\\new Lyrics"));
        // Compared with the staff's name taken out, since the two differ by
        // that line whatever the reduction did, and what is being asserted here
        // is that the music differs.
        assertThat(music(context(playable, "\\new Staff")))
                .isNotEqualTo(music(context(estimate, "\\new Staff")));
    }

    @Test
    @DisplayName("the staff says which of the two pages it is")
    void theStaffIsNamed() {
        Score score = scooped();
        QuantizedScore quantized = Quantizer.quantize(
                score.withTrack(PlayableMelody.reduce(score)), QuantizationSettings.READING);

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

    @Test
    @DisplayName("a compound-meter page keeps its plain eighths and gains no bracket")
    void compoundTimeIsNotBracketed() {
        // The restriction is on the divisions the meter does not subdivide by,
        // and which those are inverts here: a rule fixed in simple time would
        // withhold the plain eighth and bracket every beat of this page.
        Score score = inSixEight();
        QuantizedScore quantized = Quantizer.quantize(
                score.withTrack(PlayableMelody.reduce(score)), QuantizationSettings.READING);

        String source = LeadSheet.toLilyPond(quantized, melodyOf(quantized));

        assertThat(source).doesNotContain("\\tuplet");
        List<Note> printed = melodyOf(quantized).notes();
        // Counted as well as placed: a reduction that collapsed the line would
        // leave the loop below asserting nothing at all.
        assertThat(printed).hasSize(12);
        for (int i = 0; i < printed.size(); i++) {
            assertThat(printed.get(i).onsetBeat().orElseThrow())
                    .describedAs("note %d", i)
                    .isCloseTo(i * 0.5, org.assertj.core.api.Assertions.within(1e-9));
        }
    }

    /**
     * Four bars of a sung line whose syllables do not begin on the beat, the
     * shape #594 was reported on: every group is scooped into and every scoop
     * starts a little off the duple grid, so the bar the reduction leaves has
     * few enough note-heads that a triplet subdivision fits them.
     *
     * <p>The offsets are the same in every bar, which is what makes the pair of
     * goldens legible — the page is one figure repeated, so a difference between
     * them is the subdivision and nothing else.
     */
    private static Score offGrid() {
        TempoMap map = TempoMap.constant(QUARTER_BPM, TimeSignature.FOUR_FOUR);
        double[] entries = {0.08, 1.28, 2.72};
        String[] scoops = {"D4", "F4", "A4"};
        String[] targets = {"E4", "G4", "B4"};
        List<Note> voice = new ArrayList<>();
        List<LyricWord> words = new ArrayList<>();
        String[] sung = {"la", "di", "da"};
        for (int bar = 0; bar < 4; bar++) {
            for (int i = 0; i < entries.length; i++) {
                double at = 4 * bar + entries[i];
                voice.add(note(map, at, 0.2, scoops[i]));
                voice.add(note(map, at + 0.2, 0.9, targets[i]));
                words.add(LyricWord.ofSeconds(sung[i], map.beatsToSeconds(at),
                        map.beatsToSeconds(at + 1.1), Confidence.CERTAIN));
            }
        }
        return Score.empty(map, 16 / (QUARTER_BPM / 60))
                .withTrack(new NoteTrack(PartRole.LEAD_VOCAL, "Voice", voice, Confidence.CERTAIN))
                .withChords(new ChordProgression(List.of(
                        chord(map, "C4", ChordQuality.MAJOR, 0, 8),
                        chord(map, "G4", ChordQuality.MAJOR, 8, 16)),
                        Confidence.of(0.9)))
                .withLyrics(new Lyrics(List.of(new LyricLine(words, Confidence.CERTAIN)),
                        "en", Confidence.CERTAIN));
    }

    /**
     * A 6/8 tune of plain eighths, one syllable to each, played exactly.
     *
     * <p>Exact rather than performed: what is being asserted is that the
     * division survives, so a jittered onset would leave the test unable to say
     * whether a moved note was the meter or the dice.
     */
    private static Score inSixEight() {
        TempoMap map = TempoMap.constantPulse(QUARTER_BPM, TimeSignature.SIX_EIGHT);
        String[] sung = {"one", "two", "three", "four", "five", "six"};
        List<Note> voice = new ArrayList<>();
        List<LyricWord> words = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double at = i * 0.5;
            voice.add(note(map, at, 0.5, i % 2 == 0 ? "E4" : "G4"));
            words.add(LyricWord.ofSeconds(sung[i % sung.length], map.beatsToSeconds(at),
                    map.beatsToSeconds(at + 0.5), Confidence.CERTAIN));
        }
        return Score.empty(map, map.beatsToSeconds(12))
                .withTrack(new NoteTrack(PartRole.LEAD_VOCAL, "Voice", voice, Confidence.CERTAIN))
                .withChords(new ChordProgression(List.of(
                        chord(map, "C4", ChordQuality.MAJOR, 0, 6)), Confidence.of(0.9)))
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

    /** A staff context without the line that names it. */
    private static String music(String staff) {
        return staff.lines()
                .filter(line -> !line.contains("instrumentName"))
                .reduce("", (a, b) -> a + b + "\n");
    }

    /** One context of the score, from its opening line to the next one's. */
    private static String context(String source, String opening) {
        int from = source.indexOf(opening);
        assertThat(from).as("no %s in the source", opening).isNotNegative();
        // Anchored to the line so the staff's own \new Voice, indented deeper,
        // does not end the context early.
        int next = source.indexOf("\n  \\new ", from + opening.length());
        int end = next >= 0 ? next : source.indexOf("  >>", from);
        return source.substring(from, end < 0 ? source.length() : end);
    }
}
