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

import static dev.olivelli.musicwizard.notation.MusicXmlChecks.assertMeasuresFillTheirMeter;
import static dev.olivelli.musicwizard.notation.MusicXmlChecks.assertValidMusicXml;
import static dev.olivelli.musicwizard.notation.MusicXmlChecks.child;
import static dev.olivelli.musicwizard.notation.MusicXmlChecks.childElements;
import static dev.olivelli.musicwizard.notation.MusicXmlChecks.elements;
import static dev.olivelli.musicwizard.notation.MusicXmlChecks.one;
import static dev.olivelli.musicwizard.notation.MusicXmlChecks.parse;
import static dev.olivelli.musicwizard.notation.MusicXmlChecks.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The chord chart and the lead sheet as MusicXML: the exports that carry chord
 * symbols, read against the LilyPond pages of the same scores.
 */
class MusicXmlSheetsTest {

    // ----------------------------------------------------------------- chart

    @Test
    @DisplayName("the chart, valid and paired with its LilyPond page")
    void chartPage() {
        Score score = Fixtures.chordChart();
        String xml = MusicXmlExport.chordChart(score);
        String lilyPond = ChordChart.toLilyPond(score);

        assertValidMusicXml("chord-chart", xml);
        Document document = parse(xml);
        assertMeasuresFillTheirMeter("chord-chart", document);
        // The same fixture as ChordChartTest's golden of that name, so the two
        // pages can be read side by side and cannot drift apart.
        assertThat(lilyPond).isEqualTo(Goldens.read("chord-chart", ".ly"));
        assertThat(elements(document, "measure"))
                .as("the two pages disagree about how many bars this is")
                .hasSize(barCount(lilyPond));
        Goldens.assertGolden("chord-chart", ".musicxml", xml);
    }

    @Test
    @DisplayName("one symbol per named cell, and none where the chart writes %")
    void oneSymbolPerNamedCell() {
        Document document = parse(MusicXmlExport.chordChart(Fixtures.chordChart()));

        List<Element> measures = elements(document, "measure");
        assertThat(symbolsOf(measures.get(0))).containsExactly("C major", "G major");
        assertThat(symbolsOf(measures.get(1))).containsExactly("A minor-seventh");
        assertThat(symbolsOf(measures.get(2))).as("a chord held across the bar line").isEmpty();
        assertThat(symbolsOf(measures.get(3))).containsExactly("C none", "D major");
    }

    @Test
    @DisplayName("every rest under the symbols carries its note value")
    void restsCarryTheirValues() {
        Document document = parse(MusicXmlExport.chordChart(Fixtures.chordChart()));

        List<Element> notes = elements(document, "note");
        assertThat(notes).isNotEmpty();
        for (Element note : notes) {
            assertThat(child(note, "rest")).hasSize(1);
            assertThat(one(note, "rest").getAttribute("measure")).isEmpty();
            assertThat(child(note, "type")).hasSize(1);
        }
    }

    @Test
    @DisplayName("the symbol is written as the format's kind and as the chart's text")
    void everyQualityHasAKind() {
        Map<ChordQuality, String> kinds = Map.ofEntries(
                Map.entry(ChordQuality.MAJOR, "major"),
                Map.entry(ChordQuality.MINOR, "minor"),
                Map.entry(ChordQuality.DIMINISHED, "diminished"),
                Map.entry(ChordQuality.AUGMENTED, "augmented"),
                Map.entry(ChordQuality.SUSPENDED_SECOND, "suspended-second"),
                Map.entry(ChordQuality.SUSPENDED_FOURTH, "suspended-fourth"),
                Map.entry(ChordQuality.DOMINANT_SEVENTH, "dominant"),
                Map.entry(ChordQuality.MAJOR_SEVENTH, "major-seventh"),
                Map.entry(ChordQuality.MINOR_SEVENTH, "minor-seventh"),
                Map.entry(ChordQuality.MINOR_MAJOR_SEVENTH, "major-minor"),
                Map.entry(ChordQuality.HALF_DIMINISHED_SEVENTH, "half-diminished"),
                Map.entry(ChordQuality.DIMINISHED_SEVENTH, "diminished-seventh"),
                Map.entry(ChordQuality.SIXTH, "major-sixth"),
                Map.entry(ChordQuality.MINOR_SIXTH, "minor-sixth"),
                Map.entry(ChordQuality.NONE, "none"));
        // A quality added to the model must be given a kind here as well as in
        // the exporter, or this fails to name it.
        assertThat(kinds).containsOnlyKeys(ChordQuality.values());

        for (ChordQuality quality : ChordQuality.values()) {
            Chord chord = quality == ChordQuality.NONE
                    ? Chord.noChord(0, 2, Confidence.CERTAIN)
                    : Chord.ofSeconds(PitchSpelling.parse("E4"), quality, 0, 2,
                            Confidence.CERTAIN);
            Document document = parse(MusicXmlExport.chordChart(oneChord(chord)));
            Element kind = one(elements(document, "harmony").getFirst(), "kind");
            assertThat(text(kind)).as(quality.name()).isEqualTo(kinds.get(quality));
            assertThat(kind.getAttribute("text")).as(quality.name()).isEqualTo(quality.symbol());
        }
    }

    @Test
    @DisplayName("roots and basses are spelled, never derived from a pitch")
    void rootsAreSpelled() {
        Document flat = parse(MusicXmlExport.chordChart(oneChord(
                Chord.ofSeconds(PitchSpelling.parse("Db4"), ChordQuality.MAJOR, 0, 2,
                        Confidence.CERTAIN))));
        Element root = one(elements(flat, "harmony").getFirst(), "root");
        assertThat(text(one(root, "root-step"))).isEqualTo("D");
        assertThat(text(one(root, "root-alter"))).isEqualTo("-1");

        Document slash = parse(MusicXmlExport.chordChart(oneChord(
                Chord.ofSeconds(PitchSpelling.parse("C#4"), ChordQuality.MINOR, 0, 2,
                        Confidence.CERTAIN).withBass(PitchSpelling.parse("E3")))));
        Element harmony = elements(slash, "harmony").getFirst();
        assertThat(text(one(one(harmony, "root"), "root-alter"))).isEqualTo("1");
        assertThat(text(one(one(harmony, "bass"), "bass-step"))).isEqualTo("E");
        assertThat(child(one(harmony, "bass"), "bass-alter")).isEmpty();
    }

    @Test
    @DisplayName("no chord displays as the chart's N.C. and nothing for a root")
    void noChordIsTheFormatsOwn() {
        Document document = parse(MusicXmlExport.chordChart(oneChord(
                Chord.noChord(0, 2, Confidence.CERTAIN))));
        Element harmony = elements(document, "harmony").getFirst();
        assertThat(one(one(harmony, "root"), "root-step").getAttribute("text")).isEmpty();
        assertThat(one(harmony, "kind").getAttribute("text")).isEqualTo("N.C.");
    }

    @Test
    @DisplayName("a score with no chords has no chart to write")
    void noChordsIsRefused() {
        Score silent = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 8);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MusicXmlExport.chordChart(silent))
                .withMessageContaining("no chords");
    }

    // ------------------------------------------------------------ lead sheet

    @Test
    @DisplayName("the lead sheet, valid, with the symbols on the staff's bars")
    void leadSheetPage() {
        QuantizedScore quantized = leadSheet();
        String xml = MusicXmlExport.leadSheet(quantized, melodyOf(quantized));

        assertValidMusicXml("lead sheet", xml);
        assertMeasuresFillTheirMeter("lead sheet", parse(xml));
    }

    @Test
    @DisplayName("opens the symbols on the pickup, cut to it")
    void symbolsOpenOnThePickup() {
        Document document = parse(leadSheetXml());

        Element pickup = elements(document, "measure").getFirst();
        assertThat(pickup.getAttribute("implicit")).isEqualTo("yes");
        assertThat(symbolsOf(pickup)).containsExactly("C major");
        assertThat(order(pickup)).containsExactly("harmony", "note");
    }

    @Test
    @DisplayName("a change inside a held note is offset into it")
    void aChangeInsideAHeldNoteIsOffset() {
        Document document = parse(leadSheetXml());

        Element bar = elements(document, "measure").get(1);
        assertThat(symbolsOf(bar)).containsExactly("G major", "F major");
        List<Element> harmonies = childElements(bar, "harmony");
        assertThat(child(harmonies.get(0), "offset")).isEmpty();
        assertThat(text(one(harmonies.get(1), "offset")))
                .isEqualTo(String.valueOf(2 * MusicXmlExport.DIVISIONS_PER_QUARTER));
        assertThat(order(bar)).containsExactly("harmony", "harmony", "note");
    }

    @Test
    @DisplayName("a chord held across the bar line is named once")
    void aHeldChordIsNamedOnce() {
        Document document = parse(leadSheetXml());

        assertThat(symbolsOf(elements(document, "measure").get(2))).isEmpty();
    }

    @Test
    @DisplayName("a chord past the staff's last bar is not written")
    void symbolsPastTheStaffAreDropped() {
        Document document = parse(leadSheetXml());

        // The chords run a bar past the melody; the staff decides the length.
        assertThat(elements(document, "measure")).hasSize(3);
        assertThat(elements(document, "harmony").stream().map(MusicXmlSheetsTest::symbolOf))
                .doesNotContain("A minor");
    }

    @Test
    @DisplayName("a triplet pickup in a meter with three beats is cut exactly")
    void aTripletPickupInThreeFourIsCut() {
        // A pickup a third of a beat long is counted in a unit no division
        // divides, in a bar whose length adds a factor of three of its own;
        // the length is exact all the same, and the LilyPond page writes it.
        TempoMap map = TempoMap.constant(120, TimeSignature.THREE_FOUR);
        NoteTrack melody = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(3 - thirds(1), thirds(1), "G4"), note(3, 3, "C5")), Confidence.CERTAIN);
        Score score = Score.empty(map, 60)
                .withTrack(melody)
                .withChords(new ChordProgression(List.of(
                        chord("C4", ChordQuality.MAJOR, 0, 3),
                        chord("G4", ChordQuality.MAJOR, 3, 6)), Confidence.CERTAIN));
        QuantizedScore quantized = Fixtures.quantized(score,
                GridResolution.THIRD_BEAT, GridResolution.THIRD_BEAT);

        String xml = MusicXmlExport.leadSheet(quantized, melodyOf(quantized));

        assertValidMusicXml("triplet pickup", xml);
        Document document = parse(xml);
        assertMeasuresFillTheirMeter("triplet pickup", document);
        Element pickup = elements(document, "measure").getFirst();
        assertThat(pickup.getAttribute("implicit")).isEqualTo("yes");
        assertThat(symbolsOf(pickup)).containsExactly("C major");
        assertThat(LeadSheet.toLilyPond(quantized, melodyOf(quantized))).contains("\\partial");
    }

    @Test
    @DisplayName("the first symbol kept by the pickup cut is named, whatever the chart said")
    void theFirstSymbolKeptIsNamed() {
        // Bar 0 of the chart: a lead-in gap, then C twice over, so the chart
        // names the second C nowhere. A quarter pickup keeps only that second
        // C, and a page opening on an unnamed chord would open on no chord.
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);
        NoteTrack melody = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(3, 1, "G4"), note(4, 4, "C5")), Confidence.CERTAIN);
        Score score = Score.empty(map, 60)
                .withTrack(melody)
                .withChords(new ChordProgression(List.of(
                        chord("C4", ChordQuality.MAJOR, 1, 2),
                        chord("C4", ChordQuality.MAJOR, 2, 4),
                        chord("F4", ChordQuality.MAJOR, 4, 8)), Confidence.CERTAIN));
        QuantizedScore quantized = Fixtures.quantized(score,
                GridResolution.HALF_BEAT, GridResolution.HALF_BEAT);

        Document document = parse(MusicXmlExport.leadSheet(quantized, melodyOf(quantized)));

        List<Element> measures = elements(document, "measure");
        assertThat(symbolsOf(measures.get(0))).containsExactly("C major");
        assertThat(symbolsOf(measures.get(1))).containsExactly("F major");
    }

    // ----------------------------------------------------------------- words

    @Test
    @DisplayName("the lead sheet with words, valid and paired with its LilyPond page")
    void leadSheetWithLyricsPage() {
        QuantizedScore quantized = Fixtures.leadSheetWithPickupAndLyrics(3);
        NoteTrack melody = melodyOf(quantized);
        String xml = MusicXmlExport.leadSheet(quantized, melody);

        assertValidMusicXml("lead-sheet-pickup-lyrics", xml);
        assertMeasuresFillTheirMeter("lead-sheet-pickup-lyrics", parse(xml));
        // The same score as LeadSheetTest's golden of that name.
        assertThat(LeadSheet.toLilyPond(quantized, melody))
                .isEqualTo(Goldens.read("lead-sheet-pickup-lyrics", ".ly"));
        Goldens.assertGolden("lead-sheet-pickup-lyrics", ".musicxml", xml);
    }

    @Test
    @DisplayName("the chart with words, valid and paired with its LilyPond page")
    void chordsOverLyricsPage() {
        Score score = Fixtures.chordsOverLyrics();
        String xml = MusicXmlExport.lyricSheet(score);
        String lilyPond = LyricSheet.toLilyPond(score);

        assertValidMusicXml("chords-over-lyrics", xml);
        Document document = parse(xml);
        assertMeasuresFillTheirMeter("chords-over-lyrics", document);
        assertThat(lilyPond).isEqualTo(Goldens.read("chords-over-lyrics", ".ly"));
        assertThat(elements(document, "measure")).hasSize(barCount(lilyPond) / 2);
        Goldens.assertGolden("chords-over-lyrics", ".musicxml", xml);
    }

    @Test
    @DisplayName("a syllable rides the note sounding when it is sung, joined as the page joins it")
    void syllablesRideTheirNotes() {
        Document document = parse(sungLeadSheetXml());

        List<Element> measures = elements(document, "measure");
        assertThat(lyricsOf(measures.get(0))).containsExactly("1 single one");
        assertThat(lyricsOf(measures.get(1))).containsExactly("1 begin go", "1 end ing");
    }

    @Test
    @DisplayName("a syllable no note can carry is said at its own moment")
    void aSyllableOnARestIsSaid() {
        Document document = parse(sungLeadSheetXml());

        Element bar = elements(document, "measure").get(2);
        List<Element> said = childElements(bar, "direction").stream()
                .filter(direction -> "below".equals(direction.getAttribute("placement")))
                .toList();
        assertThat(said).hasSize(1);
        assertThat(text(one(one(said.getFirst(), "direction-type"), "words"))).isEqualTo("rest");
        assertThat(child(said.getFirst(), "offset")).isEmpty();
        // Before the rest it is sung over, and on no note.
        assertThat(order(bar, "direction", "note").getFirst()).isEqualTo("direction");
        assertThat(lyricsOf(bar)).noneMatch(lyric -> lyric.endsWith(" rest"));
    }

    @Test
    @DisplayName("a melisma opens an extender on its note and closes it where it ends")
    void aMelismaIsExtended() {
        Document document = parse(sungLeadSheetXml());

        Element bar = elements(document, "measure").get(2);
        List<Element> notes = childElements(bar, "note");
        Element held = notes.get(notes.size() - 2);
        Element after = notes.getLast();
        assertThat(text(one(one(held, "lyric"), "text"))).isEqualTo("hold");
        assertThat(one(one(held, "lyric"), "extend").getAttribute("type")).isEqualTo("start");
        assertThat(child(one(after, "lyric"), "text")).isEmpty();
        assertThat(one(one(after, "lyric"), "extend").getAttribute("type")).isEqualTo("stop");
    }

    @Test
    @DisplayName("a second syllable sung inside one note is said, not stacked")
    void aSecondSyllableOnOneNoteIsSaid() {
        // Two words over the one whole note of bar 1: the first rides it, the
        // second is said inside it.
        QuantizedScore quantized = withWords(leadSheet(),
                LyricWord.ofSeconds("first", 2, 3, Confidence.CERTAIN),
                LyricWord.ofSeconds("next", 3, 4, Confidence.CERTAIN));
        Document document = parse(MusicXmlExport.leadSheet(quantized, melodyOf(quantized)));

        Element bar = elements(document, "measure").get(1);
        assertThat(lyricsOf(bar)).containsExactly("1 single first");
        List<Element> said = childElements(bar, "direction");
        assertThat(text(one(one(said.getFirst(), "direction-type"), "words"))).isEqualTo("next");
        assertThat(text(one(said.getFirst(), "offset")))
                .isEqualTo(String.valueOf(2 * MusicXmlExport.DIVISIONS_PER_QUARTER));
    }

    @Test
    @DisplayName("the chart with words prints the words and hides the rests")
    void theLyricSheetHidesItsRests() {
        Document document = parse(MusicXmlExport.lyricSheet(Fixtures.chordsOverLyrics()));

        assertThat(one(one(document.getDocumentElement(), "part")
                .getElementsByTagName("attributes").item(0) instanceof Element attributes
                ? attributes : null, "staff-details").getAttribute("print-object"))
                .isEqualTo("no");
        for (Element note : elements(document, "note")) {
            assertThat(note.getAttribute("print-object")).isEqualTo("no");
        }
        List<Element> measures = elements(document, "measure");
        // The cell holding "hap" and "py" is cut in two, one rest for each.
        assertThat(lyricsOf(measures.get(0)))
                .containsExactly("1 single Sing", "1 begin hap", "1 end py");
        // A word sung inside the silence cuts the silence, too.
        assertThat(lyricsOf(measures.get(3))).containsExactly("1 single now");
        assertThat(childElements(measures.get(3), "note")).hasSize(3);
    }

    @Test
    @DisplayName("the chart itself carries no words, whatever the score sings")
    void theChartCarriesNoWords() {
        Document document = parse(MusicXmlExport.chordChart(Fixtures.chordsOverLyrics()));
        assertThat(elements(document, "lyric")).isEmpty();
        for (Element note : elements(document, "note")) {
            assertThat(note.getAttribute("print-object")).isEmpty();
        }
    }

    // -------------------------------------------------------------- fixtures

    /**
     * A melody with a pickup, over chords, singing a word on the pickup, a
     * hyphenated word over two notes, a word over a rest, and a melisma that
     * ends on the next note's onset.
     */
    private static QuantizedScore sungLeadSheet() {
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);
        NoteTrack melody = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(3, 1, "G4"), note(4, 2, "C5"), note(6, 2, "D5"),
                note(10, 1, "E5"), note(11, 1, "F5")), Confidence.CERTAIN);
        List<Chord> chords = List.of(
                chord("C4", ChordQuality.MAJOR, 0, 4),
                chord("F4", ChordQuality.MAJOR, 4, 8),
                chord("G4", ChordQuality.MAJOR, 8, 12));
        Score score = Score.empty(map, 60)
                .withTrack(melody)
                .withChords(new ChordProgression(chords, Confidence.CERTAIN));
        return withWords(Fixtures.quantized(score, GridResolution.HALF_BEAT,
                        GridResolution.HALF_BEAT, GridResolution.HALF_BEAT),
                sung("one", 3, 4), joined("go", 4, 6), sung("ing", 6, 8), sung("rest", 8, 10),
                new LyricWord("hold", 5, 5.5, java.util.Optional.empty(),
                        java.util.Optional.empty(), false, true, Confidence.CERTAIN));
    }

    private static String sungLeadSheetXml() {
        QuantizedScore quantized = sungLeadSheet();
        return MusicXmlExport.leadSheet(quantized, melodyOf(quantized));
    }

    private static QuantizedScore withWords(QuantizedScore quantized, LyricWord... words) {
        Score sung = quantized.score().withLyrics(new Lyrics(
                List.of(new LyricLine(List.of(words), Confidence.CERTAIN)), "en",
                Confidence.CERTAIN));
        return new QuantizedScore(sung, quantized.grids(), quantized.swing());
    }

    /** A word sung from one beat to another, at the fixtures' tempo. */
    private static LyricWord sung(String text, double fromBeat, double toBeat) {
        return LyricWord.ofSeconds(text, fromBeat / 2, toBeat / 2, Confidence.CERTAIN);
    }

    /** The same, continuing into the next word as one word's syllables do. */
    private static LyricWord joined(String text, double fromBeat, double toBeat) {
        return new LyricWord(text, fromBeat / 2, toBeat / 2, java.util.Optional.empty(),
                java.util.Optional.empty(), true, false, Confidence.CERTAIN);
    }

    private static double thirds(double steps) {
        return steps / 3.0;
    }

    private static Score oneChord(Chord chord) {
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 2)
                .withChords(new ChordProgression(List.of(chord), Confidence.CERTAIN));
    }

    /**
     * A melody entering a quarter before the bar line, over chords that change
     * on a bar line, inside a held note, hold across a bar line, and run one
     * bar past the melody's end.
     */
    private static QuantizedScore leadSheet() {
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);
        NoteTrack melody = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(3, 1, "G4"), note(4, 4, "C5"), note(8, 4, "D5")), Confidence.CERTAIN);
        List<Chord> chords = List.of(
                chord("C4", ChordQuality.MAJOR, 0, 4),
                chord("G4", ChordQuality.MAJOR, 4, 6),
                chord("F4", ChordQuality.MAJOR, 6, 12),
                chord("A4", ChordQuality.MINOR, 12, 16));
        Score score = Score.empty(map, 60)
                .withTrack(melody)
                .withChords(new ChordProgression(chords, Confidence.CERTAIN));
        return Fixtures.quantized(score, GridResolution.HALF_BEAT, GridResolution.HALF_BEAT,
                GridResolution.HALF_BEAT, GridResolution.HALF_BEAT);
    }

    private static String leadSheetXml() {
        QuantizedScore quantized = leadSheet();
        return MusicXmlExport.leadSheet(quantized, melodyOf(quantized));
    }

    private static NoteTrack melodyOf(QuantizedScore quantized) {
        return quantized.score().tracks().getFirst();
    }

    private static Note note(double onsetBeat, double beats, String spelling) {
        PitchSpelling written = PitchSpelling.parse(spelling);
        return Note.ofSeconds(onsetBeat / 2, beats / 2, written.midiPitch(), Confidence.CERTAIN)
                .quantizedTo(onsetBeat, beats)
                .spelledAs(written);
    }

    private static Chord chord(String root, ChordQuality quality, double fromBeat, double toBeat) {
        return Chord.ofSeconds(PitchSpelling.parse(root), quality, fromBeat / 2, toBeat / 2,
                        Confidence.CERTAIN)
                .quantizedTo(fromBeat, toBeat);
    }

    // --------------------------------------------------------------- reading

    /** Each symbol of a measure as "root kind", in document order. */
    private static List<String> symbolsOf(Element measure) {
        return childElements(measure, "harmony").stream().map(MusicXmlSheetsTest::symbolOf).toList();
    }

    private static String symbolOf(Element harmony) {
        return text(one(one(harmony, "root"), "root-step")) + " " + text(one(harmony, "kind"));
    }

    /** Every lyric of a measure as "lane syllabic text", in document order. */
    private static List<String> lyricsOf(Element measure) {
        List<String> lyrics = new ArrayList<>();
        for (Element note : childElements(measure, "note")) {
            for (Element lyric : childElements(note, "lyric")) {
                List<Element> text = child(lyric, "text");
                lyrics.add(lyric.getAttribute("number") + " "
                        + (text.isEmpty() ? "-" : text(one(lyric, "syllabic")) + " " + text(text)));
            }
        }
        return lyrics;
    }

    /** The element names a measure holds after its attributes and directions. */
    private static List<String> order(Element measure) {
        return order(measure, "harmony", "note");
    }

    private static List<String> order(Element measure, String... names) {
        List<String> found = new ArrayList<>();
        NodeList children = measure.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child
                    && List.of(names).contains(child.getTagName())) {
                found.add(child.getTagName());
            }
        }
        return found;
    }

    private static int barCount(String lilyPond) {
        int bars = 0;
        for (String rawLine : lilyPond.split("\n")) {
            String line = rawLine.trim();
            if (line.endsWith("|") && !line.startsWith("\\bar")) {
                bars++;
            }
        }
        return bars;
    }
}
