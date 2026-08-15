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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.olivelli.musicwizard.arrange.BarGrid;
import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.arrange.SwingFeel;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Source;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * Golden files over the generated MusicXML, and three checks that keep proving
 * something after the goldens have been regenerated.
 *
 * <p>A golden file generated from the code under test asserts only that the code
 * is unchanged. So every document these tests produce is also
 *
 * <ul>
 *   <li><b>validated against the MusicXML 4.0 XSD</b> that proxymusic ships in
 *       its own jar. That schema is the format's definition and the one
 *       authority here that this project did not write, so it is what "correct
 *       against the specification" means mechanically rather than by my reading
 *       of it. See {@code src/test/resources/xsd/README.md} for why it needs two
 *       companion namespaces to load;
 *   <li><b>re-parsed and re-added up</b>, measure by measure, from the emitted
 *       {@code <duration>} elements. That is deliberately not the check the
 *       exporter runs on itself: this one reads the bytes that were written,
 *       so an exporter that counted correctly and then wrote something else
 *       still fails it. A measure that does not fill its meter is imported
 *       without complaint by every scorewriter and everything after it lands in
 *       the wrong bar;
 *   <li><b>compared against the LilyPond emitter</b> on bar count, so that the
 *       two exports of one score cannot quietly disagree about how long the
 *       piece is.
 * </ul>
 *
 * <p>The fixtures are the same music {@code StaffNotationTest} engraves, on
 * purpose: the two golden trees can then be read side by side, and a change that
 * moves one and not the other is visible as a diff rather than as a discrepancy
 * somebody has to notice.
 */
class MusicXmlExportTest {

    /**
     * Set {@code -Dmw.golden.update=true} to rewrite the golden files from the
     * current output. Read the diff before committing it: a golden file
     * regenerated without being read asserts nothing at all.
     */
    private static final String UPDATE_PROPERTY = "mw.golden.update";

    /**
     * MusicXML goldens with no LilyPond twin. None today: the whole-score
     * export, which cannot have one — {@link StaffNotation} writes one part per
     * document — currently has no golden at all (#511).
     *
     * <p>A list rather than a condition, so that a fixture whose golden simply
     * was not written fails the pairing check instead of skipping it.
     */
    private static final List<String> UNPAIRED = List.of();

    /** The MusicXML schema, inside the proxymusic jar. */
    private static final String MUSICXML_XSD = "META-INF/jaxb/xsd/musicxml.xsd";

    /** Loaded once: parsing a 380 KB schema per test dominates the run. */
    private static final Schema SCHEMA = loadSchema();

    // ------------------------------------------------------------- fixtures

    private static PitchSpelling pitch(String name) {
        return PitchSpelling.parse(name);
    }

    /** A note with musical timing, as the quantizer would leave it. */
    private static Note note(double onsetBeat, double beats, String spelling) {
        PitchSpelling written = pitch(spelling);
        // The seconds are what a 120 BPM reading of the beats would give. They
        // are deliberately not what the exporter reads; if it ever did, these
        // tests would still pass and the beat axis would have stopped mattering.
        return Note.ofSeconds(onsetBeat / 2 + 0.5, beats / 2, written.midiPitch(),
                        Confidence.CERTAIN)
                .quantizedTo(onsetBeat, beats)
                .spelledAs(written);
    }

    /** A note the pipeline never chose a spelling for. */
    private static Note unspelled(double onsetBeat, double beats, int midiPitch) {
        return Note.ofSeconds(onsetBeat / 2 + 0.5, beats / 2, midiPitch, Confidence.CERTAIN)
                .quantizedTo(onsetBeat, beats);
    }

    private static NoteTrack track(PartRole role, String name, Note... notes) {
        return new NoteTrack(role, name, List.of(notes), Confidence.CERTAIN);
    }

    private static Score score(TimeSignature meter, double quarterBpm, NoteTrack... tracks) {
        Score built = Score.empty(TempoMap.constant(quarterBpm, meter), 60);
        for (NoteTrack track : tracks) {
            built = built.withTrack(track);
        }
        return built;
    }

    private static Key key(String tonic, Mode mode) {
        return Key.ofSeconds(pitch(tonic), mode, 0, 60, Confidence.CERTAIN);
    }

    /** A quantizer verdict for a score: one grid per bar, in bar order. */
    private static QuantizedScore quantized(Score score, GridResolution... perBar) {
        List<BarGrid> grids = new ArrayList<>(perBar.length);
        double startBeat = 0;
        for (int bar = 0; bar < perBar.length; bar++) {
            TimeSignature meter = score.tempoMap().timeSignatureAtBar(bar);
            grids.add(new BarGrid(bar, startBeat, perBar[bar], meter));
            startBeat += meter.quarterBeatsPerBar();
        }
        return new QuantizedScore(score, grids, SwingFeel.STRAIGHT);
    }

    /** A position or length of {@code steps} triplet eighths, in quarter beats. */
    private static double thirds(double steps) {
        return steps / 3.0;
    }

    // --------------------------------------------------------------- golden

    @Test
    @DisplayName("a melody in common time")
    void melodyInCommonTime() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 1, "C4"), note(1, 1, "D4"), note(2, 1, "E4"), note(3, 1, "F4"),
                note(4, 2, "G4"), note(6, 2, "A4"),
                note(8, 0.5, "C5"), note(8.5, 0.5, "B4"), note(9, 0.5, "A4"),
                note(9.5, 0.5, "G4"), note(10, 2, "F4"),
                note(13, 1, "E4"), note(14, 2, "C4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice)
                .withKeys(List.of(key("C4", Mode.MAJOR)))
                .withMetadata("Scale Practice", "Anonymous");

        assertGolden("melody-common-time", MusicXmlExport.toMusicXml(score, voice),
                StaffNotation.toLilyPond(score, voice));
    }

    @Test
    @DisplayName("a note held across a bar line becomes two tied notes")
    void tieAcrossBarLine() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 2, "C4"), note(2, 3, "G4"), note(5, 1, "E4"),
                note(6, 4.5, "C4"), note(10.5, 1.5, "D4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        assertGolden("tie-across-barline", MusicXmlExport.toMusicXml(score, voice),
                StaffNotation.toLilyPond(score, voice));
    }

    @Test
    @DisplayName("a melody in 6/8 is marked in dotted quarters and sounds in quarters")
    void melodyInSixEight() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 0.5, "C4"), note(0.5, 0.5, "D4"), note(1, 0.5, "E4"),
                note(1.5, 1, "F4"), note(2.5, 0.5, "G4"),
                note(3, 2, "A4"), note(5, 1, "G4"),
                note(6, 3, "F4"));
        Score score = score(TimeSignature.SIX_EIGHT, 180, voice);

        assertGolden("melody-six-eight", MusicXmlExport.toMusicXml(score, voice),
                StaffNotation.toLilyPond(score, voice));
    }

    @Test
    @DisplayName("a bass part gets an octave-transposing bass clef")
    void bassLine() {
        NoteTrack bass = track(PartRole.BASS, "Bass",
                unspelled(0, 1, 39), unspelled(1, 1, 39), unspelled(2, 1, 44),
                unspelled(3, 1, 46), unspelled(4, 4, 32));
        Score score = score(TimeSignature.FOUR_FOUR, 120, bass)
                .withKeys(List.of(key("Eb3", Mode.MAJOR)));

        assertGolden("bass-line", MusicXmlExport.toMusicXml(score, bass),
                StaffNotation.toLilyPond(score, bass));
    }

    @Test
    @DisplayName("a melody starting before the first downbeat gets an implicit measure 0")
    void pickupBar() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(3, 1, "G4"),
                note(4, 1.5, "C5"), note(5.5, 0.5, "B4"), note(6, 2, "A4"),
                note(8, 4, "G4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        assertGolden("pickup-bar", MusicXmlExport.toMusicXml(score, voice),
                StaffNotation.toLilyPond(score, voice));
    }

    @Test
    @DisplayName("triplet eighths are a tuplet, not tied 64ths")
    void tripletEighths() {
        // Not a copy of StaffNotationTest's four bars but the same object: round
        // 2 of review reproduced round 1's finding against a copy in one edit.
        Fixtures.Quantized fixture = Fixtures.tripletPractice();

        assertGolden("triplet-eighths",
                MusicXmlExport.toMusicXml(fixture.plan(), fixture.voice()),
                StaffNotation.toLilyPond(fixture.plan(), fixture.voice()));
    }

    // ------------------------------------------------------ what the spec says

    @Test
    @DisplayName("a spelling is written as step and alter, never derived from the pitch")
    void spellingSurvives() {
        // Same sounding pitch, spelled two ways. A MIDI number cannot tell them
        // apart and MusicXML must: this is the whole reason the model carries
        // pitch twice.
        NoteTrack sharps = track(PartRole.LEAD_VOCAL, "Sharp", note(0, 4, "C#4"));
        NoteTrack flats = track(PartRole.LEAD_VOCAL, "Flat", note(0, 4, "Db4"));
        assertThat(sharps.notes().getFirst().midiPitch())
                .isEqualTo(flats.notes().getFirst().midiPitch());

        Document sharp = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, sharps), sharps));
        Document flat = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, flats), flats));

        assertThat(text(first(sharp, "step"))).isEqualTo("C");
        assertThat(text(first(sharp, "alter"))).isEqualTo("1");
        assertThat(text(first(flat, "step"))).isEqualTo("D");
        assertThat(text(first(flat, "alter"))).isEqualTo("-1");
        // Octave 4 is middle C's octave in MusicXML and in the model alike, so
        // it passes through rather than being renumbered.
        assertThat(text(first(sharp, "octave"))).isEqualTo("4");
    }

    @Test
    @DisplayName("a natural carries no alter element at all")
    void naturalsHaveNoAlter() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        Document document = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, voice), voice));

        // MusicXML's alter defaults to 0 when absent, so writing <alter>0</alter>
        // would be legal and noisy. Absent is the conventional spelling and the
        // one every scorewriter emits.
        assertThat(elements(document, "alter")).isEmpty();
        assertThat(text(first(document, "step"))).isEqualTo("C");
    }

    @Test
    @DisplayName("a triplet carries time-modification on every note and a bracket on the ends")
    void tripletsAreTuplets() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, thirds(1), "C4"), note(thirds(1), thirds(1), "D4"),
                note(thirds(2), thirds(1), "E4"),
                note(1, 3, "F4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);
        Document document = parse(MusicXmlExport.toMusicXml(
                quantized(score, GridResolution.THIRD_BEAT), voice));

        List<Element> notes = elements(document, "note");
        // Three bracketed eighths and then the held F: four notes in the bar.
        assertThat(notes).hasSize(4);
        for (int i = 0; i < 3; i++) {
            Element note = notes.get(i);
            assertThat(text(child(note, "type")))
                    .as("the written value inside a 3:2 bracket is the plain eighth")
                    .isEqualTo("eighth");
            Element modification = one(note, "time-modification");
            assertThat(text(child(modification, "actual-notes"))).isEqualTo("3");
            assertThat(text(child(modification, "normal-notes"))).isEqualTo("2");
            // 768 divisions to a quarter, a third of a quarter each.
            assertThat(text(child(note, "duration"))).isEqualTo("256");
        }
        // The bracket is marked on its first and last notes and nowhere else,
        // which is what MusicXML's start/stop pair means.
        assertThat(tupletTypes(notes.get(0))).containsExactly("start");
        assertThat(tupletTypes(notes.get(1))).isEmpty();
        assertThat(tupletTypes(notes.get(2))).containsExactly("stop");
        assertThat(elements(document, "time-modification")).hasSize(3);
    }

    @Test
    @DisplayName("a tie is written as sound and as notation, stop before start")
    void tiesAreWrittenTwice() {
        // Three beats from beat three of a 4/4 bar: a half tied over the bar
        // line to a quarter. The second note both stops that tie and starts
        // nothing, so exactly one tie element each.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 2, "C4"), note(2, 3, "G4"), note(5, 3, "E4"));
        Document document = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, voice), voice));

        List<Element> notes = elements(document, "note");
        Element start = notes.get(1);
        Element stop = notes.get(2);
        assertThat(tieTypes(start)).containsExactly("start");
        assertThat(tiedTypes(start)).containsExactly("start");
        assertThat(tieTypes(stop)).containsExactly("stop");
        assertThat(tiedTypes(stop)).containsExactly("stop");
        // A rest is never tied, however the meter cut it.
        for (Element note : notes) {
            if (!child(note, "rest").isEmpty()) {
                assertThat(tieTypes(note)).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("a chord is one sounding note plus chord-marked note heads")
    void chordsMarkEveryNoteButTheFirst() {
        NoteTrack piano = track(PartRole.PIANO_RIGHT_HAND, "Piano",
                note(0, 4, "C4"), note(0, 4, "E4"), note(0, 4, "G4"));
        Document document = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, piano), piano));

        List<Element> notes = elements(document, "note");
        assertThat(notes).hasSize(3);
        assertThat(child(notes.get(0), "chord")).isEmpty();
        assertThat(child(notes.get(1), "chord")).isNotEmpty();
        assertThat(child(notes.get(2), "chord")).isNotEmpty();
        // Low to high on the staff, which is the order a reader expects and the
        // order the LilyPond chord is written in.
        assertThat(elements(document, "step").stream().map(MusicXmlExportTest::text).toList())
                .containsExactly("C", "E", "G");
    }

    @Test
    @DisplayName("a pickup measure is numbered 0 and marked implicit")
    void pickupMeasureIsImplicit() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(3, 1, "G4"), note(4, 4, "C5"));
        Document document = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, voice), voice));

        List<Element> measures = elements(document, "measure");
        assertThat(measures.get(0).getAttribute("number")).isEqualTo("0");
        assertThat(measures.get(0).getAttribute("implicit")).isEqualTo("yes");
        // And the first full bar is 1, which is the bar a musician counts as
        // one. Getting this wrong shifts every rehearsal mark in the piece.
        assertThat(measures.get(1).getAttribute("number")).isEqualTo("1");
        assertThat(measures.get(1).getAttribute("implicit")).isEmpty();
    }

    @Test
    @DisplayName("a score with no pickup starts at measure 1")
    void firstMeasureIsOneWithoutAPickup() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        Document document = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, voice), voice));

        assertThat(elements(document, "measure").getFirst().getAttribute("number"))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("an octave-transposing part says so in the clef, not in its pitches")
    void bassClefCarriesTheOctave() {
        NoteTrack bass = track(PartRole.BASS, "Bass", unspelled(0, 4, 40));
        Document document = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, bass), bass));

        assertThat(text(first(document, "sign"))).isEqualTo("F");
        assertThat(text(first(document, "line"))).isEqualTo("4");
        assertThat(text(first(document, "clef-octave-change"))).isEqualTo("-1");
        // MIDI 40 is E2, and it is written at sounding pitch: the clef moves it,
        // not the export. Writing E3 as well would put the line two octaves up.
        assertThat(text(first(document, "step"))).isEqualTo("E");
        assertThat(text(first(document, "octave"))).isEqualTo("2");
    }

    @Test
    @DisplayName("a treble part on the staff has no octave change at all")
    void trebleClefHasNoOctaveChange() {
        // C5 needs no ledger line where it sounds and two an octave up, so
        // the plain clef wins the vote.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C5"));
        Document document = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, voice), voice));

        assertThat(text(first(document, "sign"))).isEqualTo("G");
        assertThat(text(first(document, "line"))).isEqualTo("2");
        assertThat(elements(document, "clef-octave-change")).isEmpty();
    }

    @Test
    @DisplayName("a low vocal part gets the octave treble clef, still at sounding pitch")
    void lowVocalClefCarriesTheOctave() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", unspelled(0, 4, 55));
        Document document = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, voice), voice));

        assertThat(text(first(document, "sign"))).isEqualTo("G");
        assertThat(text(first(document, "line"))).isEqualTo("2");
        assertThat(text(first(document, "clef-octave-change"))).isEqualTo("-1");
        // MIDI 55 is G3, and it is written at sounding pitch: the clef moves it,
        // exactly as the bass clef moves the bass.
        assertThat(text(first(document, "step"))).isEqualTo("G");
        assertThat(text(first(document, "octave"))).isEqualTo("3");
    }

    @Test
    @DisplayName("the metronome mark counts the beat the meter counts")
    void metronomeIsInTheCountedBeat() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 3, "C4"));
        // A dotted quarter is a beat and a half, so 180 quarter notes a minute
        // is 120 dotted quarters -- which is what a 6/8 staff is counted in.
        // Printing 180 over it would be a marking half again too fast.
        Document document = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.SIX_EIGHT, 180, voice), voice));

        assertThat(text(first(document, "beat-unit"))).isEqualTo("quarter");
        assertThat(elements(document, "beat-unit-dot")).hasSize(1);
        assertThat(text(first(document, "per-minute"))).isEqualTo("120");
        // <sound tempo> is quarter notes a minute by definition, whatever the
        // printed mark counts in. The two say the same tempo in different units.
        assertThat(first(document, "sound").getAttribute("tempo")).isEqualTo("180");
    }

    @Test
    @DisplayName("the printed mark and the playback tempo are one figure, over a stated change")
    void theMarkAndTheSoundTempoAgreeAcrossATempoChange() {
        // One file states the tempo twice, once for a reader and once for a
        // player, and a score stating 120 and then 60 makes estimatedTempo()'s
        // duration-weighted average neither. Both now come from TempoMark, so
        // the page cannot say one thing and the playback another.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        TempoMap map = new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120, Provenance.DECLARED),
                        new TempoMap.TempoSegment(8, 4, 60, Provenance.DECLARED)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        Score changing = Score.empty(map, 60).withTrack(voice);
        assertThat(changing.estimatedTempo()).isNotEqualTo(120.0).isNotEqualTo(60.0);

        Document document = parse(MusicXmlExport.toMusicXml(changing, voice));

        assertThat(text(first(document, "per-minute"))).isEqualTo("120");
        assertThat(first(document, "sound").getAttribute("tempo")).isEqualTo("120");
    }

    @Test
    @DisplayName("the metronome mark says the figure is an estimate, as the engraving does")
    void theMetronomeMarkIsQualified() {
        // A reader of this file draws the metronome as a note and a number, so
        // an unqualified one states as exact what the PDF of the same score
        // marks as approximate. Round 1 of review on #216 found the two goldens
        // of one fixture disagreeing about exactly that, because the qualifier
        // had reached the LilyPond writer and not this one.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));

        Document document = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, voice), voice));

        assertThat(text(first(document, "words"))).isEqualTo("ca.");
        // In the same <direction> as the mark it qualifies, or it is a word
        // floating somewhere else on the page.
        Element direction = first(document, "direction");
        assertThat(elements(direction, "words")).hasSize(1);
        assertThat(elements(direction, "metronome")).hasSize(1);
    }

    @Test
    @DisplayName("a key signature is written as fifths, and an absent one as none")
    void keySignature() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        Score inEFlat = score(TimeSignature.FOUR_FOUR, 120, voice)
                .withKeys(List.of(key("Eb3", Mode.MAJOR)));
        Document flat = parse(MusicXmlExport.toMusicXml(inEFlat, voice));
        assertThat(text(first(flat, "fifths"))).isEqualTo("-3");
        assertThat(text(first(flat, "mode"))).isEqualTo("major");

        // No key is not C major. Zero accidentals is the only honest signature
        // to print, and the mode is left unsaid rather than guessed.
        Document none = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, voice), voice));
        assertThat(text(first(none, "fifths"))).isEqualTo("0");
        assertThat(elements(none, "mode")).isEmpty();
    }

    @Test
    @DisplayName("a bar nobody plays in is one measure rest, whatever the meter")
    void emptyBarsAreMeasureRests() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 3, "C4"), note(6, 3, "D4"));
        Document document = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.THREE_FOUR, 120, voice), voice));

        List<Element> measures = elements(document, "measure");
        assertThat(measures).hasSize(3);
        Element silent = measures.get(1);
        List<Element> rests = elements(silent, "rest");
        assertThat(rests).hasSize(1);
        assertThat(rests.getFirst().getAttribute("measure")).isEqualTo("yes");
        // Three quarters, not a dotted half symbol: a measure rest has no note
        // value, and claiming one would name a symbol for a 5/4 bar that does
        // not exist.
        assertThat(text(child(elements(silent, "note").getFirst(), "duration")))
                .isEqualTo(String.valueOf(3 * MusicXmlExport.DIVISIONS_PER_QUARTER));
        assertThat(child(elements(silent, "note").getFirst(), "type")).isEmpty();
    }

    @Test
    @DisplayName("a meter change is written where it happens and not before")
    void meterChange() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 4, "C4"), note(4, 3, "D4"), note(7, 3, "E4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);
        score = score.withTempoMap(score.tempoMap().withMeterChange(1, TimeSignature.THREE_FOUR));
        Document document = parse(MusicXmlExport.toMusicXml(score, voice));

        List<Element> measures = elements(document, "measure");
        assertThat(beats(measures.get(0))).containsExactly("4", "4");
        assertThat(beats(measures.get(1))).containsExactly("3", "4");
        // Unchanged bars carry no time element, which is what says the meter did
        // not change: a reader that saw one would print a redundant signature.
        assertThat(elements(measures.get(2), "time")).isEmpty();
    }

    @Test
    @DisplayName("divisions are declared once, in the first measure")
    void divisionsAreDeclaredOnce() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 4, "C4"), note(4, 4, "D4"));
        Document document = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, voice), voice));

        List<Element> divisions = elements(document, "divisions");
        assertThat(divisions).hasSize(1);
        assertThat(text(divisions.getFirst()))
                .isEqualTo(String.valueOf(MusicXmlExport.DIVISIONS_PER_QUARTER));
        assertThat(elements(document, "clef")).hasSize(1);
    }

    @Test
    @DisplayName("the piece ends on a double bar line")
    void finalBarline() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 4, "C4"), note(4, 4, "D4"));
        Document document = parse(MusicXmlExport.toMusicXml(
                score(TimeSignature.FOUR_FOUR, 120, voice), voice));

        List<Element> barlines = elements(document, "barline");
        assertThat(barlines).hasSize(1);
        assertThat(barlines.getFirst().getAttribute("location")).isEqualTo("right");
        assertThat(text(child(barlines.getFirst(), "bar-style"))).isEqualTo("light-heavy");
        // On the last measure, which is where a final bar line goes.
        assertThat(elements(elements(document, "measure").getLast(), "barline")).hasSize(1);
    }

    @Test
    @DisplayName("percussion is refused as a part and skipped in a whole score")
    void percussion() {
        NoteTrack drums = track(PartRole.DRUMS, "Drums", unspelled(0, 1, 36));
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        Score both = score(TimeSignature.FOUR_FOUR, 120, voice, drums);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> MusicXmlExport.toMusicXml(both, drums))
                .withMessageContaining("#95");
        // But asking for the whole score gets the parts that can be written
        // rather than nothing: refusing everything makes the useful parts
        // unreachable because of a track nobody asked about.
        Document document = parse(MusicXmlExport.toMusicXml(both));
        assertThat(elements(document, "score-part")).hasSize(1);
        assertThat(text(first(document, "part-name"))).isEqualTo("Voice");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> MusicXmlExport.toMusicXml(
                        score(TimeSignature.FOUR_FOUR, 120, drums)))
                .withMessageContaining("no MusicXML to write");
    }

    @Test
    @DisplayName("an unquantized note is refused rather than placed by its seconds")
    void unquantizedNotesAreRefused() {
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice",
                List.of(Note.ofSeconds(0, 1, 60, Confidence.CERTAIN)), Confidence.CERTAIN);
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> MusicXmlExport.toMusicXml(score, voice))
                .withMessageContaining("quantize before engraving");
    }

    @Test
    @DisplayName("every emitted length is a whole number of divisions")
    void divisionsRefuseALengthTheyCannotHold() {
        // 768 divisions to a quarter holds a 64th (48) and a triplet 64th (16),
        // so this is a claim about the constant rather than about any score. A
        // length between two divisions is a bug upstream, and rounding it would
        // put the measure out by exactly as much as it was wrong.
        assertThat(MusicXmlExport.divisionsOf(1.0 / 16)).isEqualTo(48);
        assertThat(MusicXmlExport.divisionsOf(1.0 / 24)).isEqualTo(32);
        assertThat(MusicXmlExport.divisionsOf(1.0 / 6)).isEqualTo(128);
        assertThat(MusicXmlExport.divisionsOf(3.0 / 32)).isEqualTo(72);
        assertThat(MusicXmlExport.divisionsOf(4)).isEqualTo(3072);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> MusicXmlExport.divisionsOf(1.0 / 1000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a positive whole number");
    }

    @Test
    @DisplayName("the document says which format it is, and says a version of that format")
    void headerIdentifiesTheFormat() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        String xml = MusicXmlExport.toMusicXml(score(TimeSignature.FOUR_FOUR, 120, voice), voice);

        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE score-partwise PUBLIC"
                + " \"-//Recordare//DTD MusicXML 4.0 Partwise//EN\""
                + " \"http://www.musicxml.org/dtds/partwise.dtd\">\n");
        // 4.0 and not proxymusic's 4.0.3, which is its own artifact version.
        // MusicXML's versions are 1.0, 1.1, 2.0, 3.0, 3.1 and 4.0, and a reader
        // that resolves the public identifier through a catalogue finds nothing
        // under a DTD name that has never existed.
        assertThat(parse(xml).getDocumentElement().getAttribute("version")).isEqualTo("4.0");
        assertThat(xml).endsWith("\n");
    }

    @Test
    @DisplayName("the schema check is alive, not a validator that quietly loaded nothing")
    void theSchemaCheckCanFail() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        String valid = MusicXmlExport.toMusicXml(score(TimeSignature.FOUR_FOUR, 120, voice), voice);
        assertValidMusicXml("valid", valid);

        // "crotchet" is a quarter note everywhere except in MusicXML, whose
        // note-type-value enumeration lists "quarter". Only the schema knows
        // that -- so if this passed, the validator above would be checking
        // nothing, which is exactly the failure a check nobody has seen fail
        // hides. The schema not loading at all is the realistic way in: its
        // xml and xlink references are unresolvable without the two stubs in
        // src/test/resources/xsd.
        String broken = valid.replace("<type>whole</type>", "<type>crotchet</type>");
        assertThat(broken).isNotEqualTo(valid);
        assertThatThrownBy(() -> assertValidMusicXml("broken", broken))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("not valid MusicXML 4.0");
    }

    // ---------------------------------------------------------------- checks

    /**
     * Every measure, re-added from the {@code <duration>} elements that were
     * actually written.
     *
     * <p>A note marked {@code <chord/>} sounds with the note before it rather
     * than after it, so it does not advance the measure — which is the one place
     * a naive sum goes wrong, and it goes wrong by exactly a chord's worth per
     * chord.
     *
     * <p>Read from the parsed document rather than from the exporter's own
     * running total, on purpose. The exporter checks itself too, and a check
     * that shares its arithmetic with the thing it is checking proves that the
     * arithmetic is consistent rather than that it is right.
     */
    private static void assertMeasuresFillTheirMeter(String label, Document document) {
        int divisions = 0;
        int beats = 0;
        int beatType = 0;
        int measureNumber = 0;
        for (Element measure : elements(document, "measure")) {
            measureNumber++;
            List<Element> declared = elements(measure, "divisions");
            if (!declared.isEmpty()) {
                divisions = Integer.parseInt(text(declared.getFirst()));
            }
            List<Element> time = elements(measure, "time");
            if (!time.isEmpty()) {
                beats = Integer.parseInt(text(child(time.getFirst(), "beats")));
                beatType = Integer.parseInt(text(child(time.getFirst(), "beat-type")));
            }
            assertThat(divisions).as("%s: measure %d has no divisions in force",
                    label, measureNumber).isPositive();
            assertThat(beatType).as("%s: measure %d has no meter in force",
                    label, measureNumber).isPositive();

            int sounded = 0;
            for (Element note : elements(measure, "note")) {
                if (!child(note, "chord").isEmpty()) {
                    continue;
                }
                sounded += Integer.parseInt(text(child(note, "duration")));
            }
            int full = divisions * 4 * beats / beatType;
            boolean implicit = "yes".equals(measure.getAttribute("implicit"));
            if (implicit) {
                // A pickup is short by construction; what must not happen is
                // that it is longer than the bar it opens.
                assertThat(sounded).as("%s: pickup measure %s overflows its %d/%d bar",
                                label, measure.getAttribute("number"), beats, beatType)
                        .isPositive().isLessThan(full);
            } else {
                assertThat(sounded).as("%s: measure %s of %d/%d holds %d divisions",
                                label, measure.getAttribute("number"), beats, beatType, sounded)
                        .isEqualTo(full);
            }
        }
        assertThat(measureNumber).as("%s: no measures were written", label).isPositive();
    }

    /**
     * The document, against the MusicXML 4.0 schema.
     *
     * <p>The parser is told not to fetch the external DTD the {@code <!DOCTYPE>}
     * line names. Without that this test would reach out to musicxml.org, which
     * makes it slow where there is a network and a failure where there is not —
     * and {@code mvn verify} has to run offline.
     */
    private static void assertValidMusicXml(String label, String xml) {
        try {
            Validator validator = SCHEMA.newValidator();
            SAXParserFactory parsers = SAXParserFactory.newInstance();
            parsers.setNamespaceAware(true);
            parsers.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            XMLReader reader = parsers.newSAXParser().getXMLReader();
            validator.validate(new SAXSource(reader,
                    new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
            ));
        } catch (SAXException e) {
            throw new AssertionError(label + " is not valid MusicXML 4.0: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Schema loadSchema() {
        URL musicXml = MusicXmlExportTest.class.getClassLoader().getResource(MUSICXML_XSD);
        if (musicXml == null) {
            throw new AssertionError(
                    "the MusicXML schema is not on the test classpath; it ships inside the"
                            + " proxymusic jar at " + MUSICXML_XSD);
        }
        try {
            SchemaFactory factory =
                    SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            return factory.newSchema(new Source[] {
                    namespaceStub("xsd/xml.xsd"),
                    namespaceStub("xsd/xlink.xsd"),
                    new StreamSource(musicXml.toString()),
            });
        } catch (SAXException e) {
            throw new AssertionError("could not load the MusicXML schema", e);
        }
    }

    private static Source namespaceStub(String resource) {
        URL url = MusicXmlExportTest.class.getClassLoader().getResource(resource);
        if (url == null) {
            throw new AssertionError("missing test resource " + resource);
        }
        return new StreamSource(url.toString());
    }

    // ------------------------------------------------------------------- XML

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        } catch (SAXException | ParserConfigurationException e) {
            throw new AssertionError("could not parse the generated MusicXML", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Element> elements(Document document, String name) {
        return elements(document.getDocumentElement(), name);
    }

    private static List<Element> elements(Element root, String name) {
        NodeList found = root.getElementsByTagName(name);
        List<Element> elements = new ArrayList<>(found.getLength());
        for (int i = 0; i < found.getLength(); i++) {
            elements.add((Element) found.item(i));
        }
        return elements;
    }

    private static Element first(Document document, String name) {
        List<Element> found = elements(document, name);
        assertThat(found).as("no <%s> in the generated MusicXML", name).isNotEmpty();
        return found.getFirst();
    }

    /**
     * The direct children of an element with a given name.
     *
     * <p>Direct children rather than descendants, which matters for
     * {@code <type>}: {@code <metronome>} has one too, and a descendant search
     * from a {@code <note>} would be fine but the same helper used from a
     * measure would not.
     */
    private static List<Element> childElements(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && element.getTagName().equals(name)) {
                found.add(element);
            }
        }
        return found;
    }

    /** The direct children with this name, as a list to assert emptiness on. */
    private static List<Element> child(Element parent, String name) {
        return childElements(parent, name);
    }

    /** The one direct child with this name, failing when there is not exactly one. */
    private static Element one(Element parent, String name) {
        List<Element> found = childElements(parent, name);
        assertThat(found).as("exactly one <%s>", name).hasSize(1);
        return found.getFirst();
    }

    private static String text(Element element) {
        return element.getTextContent().trim();
    }

    private static String text(List<Element> single) {
        assertThat(single).hasSize(1);
        return text(single.getFirst());
    }

    private static List<String> tieTypes(Element note) {
        return childElements(note, "tie").stream().map(e -> e.getAttribute("type")).toList();
    }

    private static List<String> tiedTypes(Element note) {
        return childElements(note, "notations").stream()
                .flatMap(n -> childElements(n, "tied").stream())
                .map(e -> e.getAttribute("type"))
                .toList();
    }

    private static List<String> tupletTypes(Element note) {
        return childElements(note, "notations").stream()
                .flatMap(n -> childElements(n, "tuplet").stream())
                .map(e -> e.getAttribute("type"))
                .toList();
    }

    private static List<String> beats(Element measure) {
        Element time = elements(measure, "time").getFirst();
        return List.of(text(child(time, "beats")), text(child(time, "beat-type")));
    }

    // ---------------------------------------------------------------- golden

    /**
     * Compares generated MusicXML against its golden file, having first checked
     * that it is valid MusicXML and valid music.
     *
     * <p>Both checks run on the text just generated rather than on the file, for
     * the reason {@code StaffNotationTest} documents: under {@code
     * -Dmw.golden.update} the file on disk is whatever this run has written so
     * far, and a check that reads it back can be satisfied by a golden nobody
     * ever looked at.
     *
     * @param lilyPond the same music through the other emitter, compared on bar
     *                 count so that the two cannot disagree about the length of
     *                 the piece. Only the count: the two formats differ in every
     *                 other respect and are supposed to.
     */
    private static void assertGolden(String name, String actual, String lilyPond) {
        assertValidMusicXml(name, actual);
        Document document = parse(actual);
        assertMeasuresFillTheirMeter(name, document);
        assertThat(elements(document, "measure").size() / partCount(document))
                .as("%s: the two emitters disagree about how many bars this is", name)
                .isEqualTo(barCount(lilyPond));
        assertPairedWithTheLilyPondGolden(name, lilyPond);

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Path target = goldenDirectory()
                    .orElseThrow(() -> new AssertionError(UPDATE_PROPERTY
                            + " needs the module directory, which Maven passes as -Dbasedir;"
                            + " run it under Maven rather than from an IDE working directory"))
                    .resolve(name + ".musicxml");
            try {
                Files.writeString(target, actual);
            } catch (IOException e) {
                throw new UncheckedIOException("could not update golden " + name, e);
            }
            System.err.println("updated golden file " + target);
        }
        assertThat(actual).isEqualTo(readGolden(name));
    }

    /**
     * The LilyPond golden of the same name is the same music.
     *
     * <p>This is what makes the pairing an invariant rather than a convention.
     * Round 1 of review found {@code triplet-eighths.ly} and
     * {@code triplet-eighths.musicxml} describing different music under one
     * name; round 2 found that copying the fixture across had not fixed it,
     * because one copy could still be edited. Comparing the LilyPond generated
     * <em>here</em> against the golden {@code StaffNotationTest} committed
     * closes it for every shared fixture at once, including any added later.
     *
     * <p>It also survives {@code -Dmw.golden.update}, which rewrites only the
     * {@code .musicxml} files: a fixture edited on this side then fails here
     * instead of quietly regenerating. It does <em>not</em> survive an update
     * run of both classes together in one JVM, where {@code StaffNotationTest}
     * may rewrite the {@code .ly} after this has read it — but the next
     * ordinary run fails, which is what CI does, and update mode's documented
     * contract is to read the diff.
     *
     * <p>{@link #UNPAIRED} is the escape, and it is a fixed list rather than
     * "skip when the file is missing" — a missing golden would otherwise
     * disable the check silently, which is the shape of the defect this exists
     * to stop.
     */
    private static void assertPairedWithTheLilyPondGolden(String name, String lilyPond) {
        if (UNPAIRED.contains(name)) {
            return;
        }
        Optional<Path> golden = goldenDirectory().map(dir -> dir.resolve(name + ".ly"))
                .filter(Files::isRegularFile);
        assertThat(golden)
                .as("%s has no LilyPond golden; either add one or list it in UNPAIRED"
                        + " with a reason", name)
                .isPresent();
        try {
            assertThat(lilyPond)
                    .as("%s: this fixture and StaffNotationTest's have drifted apart, so the"
                            + " two goldens of that name are different music", name)
                    .isEqualTo(Files.readString(golden.get()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int partCount(Document document) {
        return elements(document, "score-part").size();
    }

    /** Bars in an emitted LilyPond staff, counted by the bar checks it writes. */
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

    private static String readGolden(String name) {
        Optional<Path> onDisk = goldenDirectory().map(dir -> dir.resolve(name + ".musicxml"))
                .filter(Files::isRegularFile);
        if (onDisk.isPresent()) {
            try {
                return Files.readString(onDisk.get());
            } catch (IOException e) {
                throw new UncheckedIOException("could not read golden " + name, e);
            }
        }
        String resource = "/golden/" + name + ".musicxml";
        try (var stream = MusicXmlExportTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError("missing golden file " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read golden " + name, e);
        }
    }

    /** Where the golden files live in the source tree, when that can be known. */
    private static Optional<Path> goldenDirectory() {
        String basedir = System.getProperty("basedir", System.getProperty("user.dir"));
        if (basedir == null) {
            return Optional.empty();
        }
        Path directory = Path.of(basedir, "src", "test", "resources", "golden");
        return Files.isDirectory(directory) ? Optional.of(directory) : Optional.empty();
    }
}
