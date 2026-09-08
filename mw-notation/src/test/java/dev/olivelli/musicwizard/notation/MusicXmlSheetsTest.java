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

    // -------------------------------------------------------------- fixtures

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

    /** The element names a measure holds after its attributes and directions. */
    private static List<String> order(Element measure) {
        List<String> names = new ArrayList<>();
        NodeList children = measure.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child
                    && (child.getTagName().equals("harmony") || child.getTagName().equals("note"))) {
                names.add(child.getTagName());
            }
        }
        return names;
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
