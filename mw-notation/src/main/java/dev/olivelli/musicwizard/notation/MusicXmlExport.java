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

import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exports a {@link Score} as MusicXML 4.0.
 *
 * <p><b>A sibling of {@link StaffNotation}, never a stage before it.</b>
 * LilyPond source is emitted straight from the domain model precisely because
 * the route through MusicXML and {@code musicxml2ly} is lossy, so this class is
 * not on the path to a PDF and nothing on that path may reach it. That holds by
 * construction rather than by intention: both this and {@link StaffNotation}
 * are readers of {@link StaffLayout} rather than of each other, and nothing on
 * the route to a PDF names either export. {@code ExportsAreSiblingsTest}
 * checks that mechanically — the same claim made in prose was once wrong.
 *
 * <p>Which means the two exports cannot disagree about the music. Where a held
 * note is cut by a bar line, which bars carry a tuplet bracket, which notes
 * gather into a chord and where the pickup falls are all decided once, by
 * {@link StaffLayout}; this class only spells them. See {@link StaffWriter}.
 * The chord symbols are {@link ChartLayout}'s cells for the same reason, and
 * the chart's first bar is cut to a pickup by {@link ChordChart#intoPickup},
 * the one place that decides it.
 *
 * <p><b>Written as text, with no binding library.</b> The phone links this
 * module, and Android has neither JAXB nor StAX; the text is also what a golden
 * file can describe byte for byte on every platform, which a serializer's
 * indentation is not.
 *
 * <p><b>Pitch is spelled, never computed.</b> MusicXML carries {@code <step>}
 * and {@code <alter>} explicitly, which is exactly the distinction a MIDI number
 * cannot make — 61 is both C sharp and D flat — and a spelling dropped at this
 * boundary is a spelling lost to every editor downstream. So
 * {@link PitchSpelling} is written through and the MIDI number is never
 * consulted, for chord roots and basses as for notes.
 *
 * <p>What is <em>not</em> written, and why:
 *
 * <ul>
 *   <li><b>{@code <accidental>}.</b> The alteration is written as
 *       {@code <alter>}, which says what the note sounds; the accidental a
 *       reader prints follows from that and the key signature, and is what
 *       LilyPond derives from exactly the same two facts. Emitting a notated
 *       accidental as well would need this class to track which accidentals are
 *       already in force in the bar, and a second implementation of that rule
 *       is a second chance to disagree with the first.
 *   <li><b>Beams, stems, slurs and dynamics.</b> The domain model carries
 *       none of them for a note track. A reader lays out beams from the time
 *       signature and the note values, which are both here.
 *   <li><b>Voices.</b> One voice per part, as on the LilyPond side: overlapping
 *       notes become block chords rather than separate voices. #93.
 *   <li><b>Tempo changes.</b> One metronome mark for the whole part, averaged
 *       across the tempo map, because that is what {@link StaffLayout} publishes
 *       — so a MIDI export of the same score, which writes every segment, plays
 *       a tempo this file does not print. #154.
 *   <li><b>{@code <encoding>}.</b> It carries the date the file was written,
 *       which makes a golden file fail on a day nobody changed anything.
 * </ul>
 */
public final class MusicXmlExport {

    /**
     * MusicXML divisions per quarter note, which is
     * {@link ExportGrid#PER_QUARTER} because a MIDI tick is the same figure for
     * the same reason. The derivation lives there.
     */
    static final int DIVISIONS_PER_QUARTER = ExportGrid.PER_QUARTER;

    /**
     * The MusicXML version this claims to be, in the {@code version} attribute
     * and in the DOCTYPE. A version of the format, which a reader resolving the
     * public identifier through a catalogue looks up by name.
     */
    private static final String MUSICXML_VERSION = "4.0";

    /** The name of the one part a chord chart has. */
    private static final String CHORDS_PART = "Chords";

    private MusicXmlExport() {
    }

    /**
     * The export declining to write a document that would put a symbol in the
     * wrong bar, which a caller may report and carry on from — unlike any
     * other failure here, which is a defect.
     */
    public static final class Refused extends IllegalStateException {

        Refused(String message) {
            super(message);
        }
    }

    /**
     * One part, as a complete MusicXML document.
     *
     * <p>Tuplets are not represented, for the reason
     * {@link StaffNotation#toLilyPond(Score, NoteTrack)} gives: a plain
     * {@link Score} does not carry the grid its notes were snapped to, so a
     * triplet arrives as three onsets a third of a beat apart. Pass the
     * {@link QuantizedScore} instead where there is one.
     *
     * @throws IllegalArgumentException if the track is percussion, or holds a
     *         note that has not been quantized
     */
    public static String toMusicXml(Score score, NoteTrack track) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(track, "track");
        return document(score, List.of(track), TupletPlan.none(), List.of());
    }

    /** The same, with the quantizer's per-bar grid honoured. */
    public static String toMusicXml(QuantizedScore quantized, NoteTrack track) {
        Objects.requireNonNull(quantized, "quantized");
        Objects.requireNonNull(track, "track");
        return document(quantized.score(), List.of(track), TupletPlan.of(quantized), List.of());
    }

    /**
     * Every part of the score that can go on a staff, as one document.
     *
     * <p>MusicXML holds a whole score where a LilyPond file in this project
     * holds one part, so this is the export a scorewriter is actually opened
     * with. The parts share a bar grid because {@link StaffLayout} takes the
     * span across the score rather than across a track — see its {@code
     * musicSpan} — so the measures line up without this class arranging it.
     *
     * <p>Percussion is left out rather than refused. It cannot be engraved on a
     * pitched staff (#95), and dropping the whole export because a drum track is
     * present would make the useful parts unreachable.
     *
     * @throws IllegalArgumentException if the score holds no engravable part, or
     *         one of them holds a note that has not been quantized
     */
    public static String toMusicXml(Score score) {
        Objects.requireNonNull(score, "score");
        return document(score, engravableTracks(score), TupletPlan.none(), List.of());
    }

    /** The same, with the quantizer's per-bar grid honoured. */
    public static String toMusicXml(QuantizedScore quantized) {
        Objects.requireNonNull(quantized, "quantized");
        return document(quantized.score(), engravableTracks(quantized.score()),
                TupletPlan.of(quantized), List.of());
    }

    /**
     * The lead sheet: the melody staff with the chart's chord symbols over it
     * and the words under it, which is what {@link LeadSheet} engraves.
     *
     * <p>The symbols and the words are placed by the assumption the LilyPond
     * page makes too: chart bar {@code k} is staff bar {@code k}, the first cut
     * to the pickup (#501). A chord whose bar lies past the staff's last is not
     * written, where the page prints it in its running chord context (#778).
     *
     * <p>A syllable rides the note sounding when it is sung, which is where a
     * singer reads it. Where that note already carries one of the lane's
     * syllables, or is a rest, or is the far end of a tie the syllable does not
     * begin on, the syllable is written as text at its own moment instead —
     * placed where the page places it. A syllable past the staff's last bar
     * goes with the chords there (#778).
     *
     * @throws IllegalArgumentException if the track is percussion, or holds a
     *         note that has not been quantized
     * @throws Refused if a chart bar runs past the staff bar of the same
     *         index, which the two axes allow when the meter changes and the
     *         first chord falls after bar 0: the page then misaligns where this
     *         refuses to write a symbol in the wrong bar (#787)
     */
    public static String leadSheet(QuantizedScore quantized, NoteTrack melody) {
        Objects.requireNonNull(quantized, "quantized");
        Objects.requireNonNull(melody, "melody");
        Score score = quantized.score();
        return document(score, List.of(melody), TupletPlan.of(quantized), ChartLayout.of(score));
    }

    /**
     * The chord chart: one part of rests under the chart's chord symbols, bar
     * for bar what {@link ChordChart#toLilyPond(Score)} engraves.
     *
     * <p>Rests rather than nothing, because a reader attaches a chord symbol to
     * the note or rest that follows it, and one rest per cell so that every
     * symbol has its own. The rests carry their note values like any other: a
     * reader that knows no measure rest would otherwise size the bar wrong.
     *
     * @throws IllegalArgumentException if the score holds no chords to chart
     */
    public static String chordChart(Score score) {
        Objects.requireNonNull(score, "score");
        return chart(score, Optional.empty(), false);
    }

    /**
     * The chords-over-lyrics sheet: the chart with the words under its chords,
     * which is what {@link LyricSheet#toLilyPond(Score)} engraves.
     *
     * <p>MusicXML hangs a syllable on a note, and this page has none. So each
     * syllable rides the rest that begins on its unit — the rests being cut
     * there for the purpose — and the rests and the staff are marked not to
     * print, which leaves the chords and the words on the page, as the LilyPond
     * sheet has them.
     *
     * @throws IllegalArgumentException if the score holds no chords to chart
     */
    public static String lyricSheet(Score score) {
        Objects.requireNonNull(score, "score");
        List<ChartLayout.Bar> bars = ChartLayout.of(score);
        return chart(score, LyricEngraving.place(score, bars, Optional.empty(), false), true);
    }

    private static String chart(Score score, Optional<LyricEngraving.Placement> lyrics,
                                boolean hidden) {
        List<ChartLayout.Bar> bars = ChartLayout.of(score);
        if (bars.isEmpty()) {
            throw new IllegalArgumentException(
                    "this score holds no chords, so there is no chart to write");
        }
        XmlWriter xml = open(score);
        partList(xml, List.of(CHORDS_PART));
        xml.open("part", "id", partId(0));
        writeChart(xml, score, bars, lyrics, hidden);
        xml.close("part");
        return close(xml);
    }

    private static List<NoteTrack> engravableTracks(Score score) {
        List<NoteTrack> tracks = score.tracks().stream()
                .filter(track -> track.role() != PartRole.DRUMS)
                .toList();
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException(
                    "this score holds nothing that can go on a pitched staff, so there is no"
                            + " MusicXML to write; percussion needs a drum staff, see #95");
        }
        return tracks;
    }

    // -------------------------------------------------------------- document

    private static String document(Score score, List<NoteTrack> tracks, TupletPlan tuplets,
                                   List<ChartLayout.Bar> chart) {
        XmlWriter xml = open(score);
        partList(xml, tracks.stream().map(NoteTrack::name).toList());
        for (int i = 0; i < tracks.size(); i++) {
            xml.open("part", "id", partId(i));
            StaffLayout.write(score, tracks.get(i), tuplets,
                    new MusicXmlStaffWriter(xml, score, chart));
            xml.close("part");
        }
        return close(xml);
    }

    /** The document up to and including its header. */
    private static XmlWriter open(Score score) {
        XmlWriter xml = new XmlWriter();
        xml.prolog("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.prolog("<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML "
                + MUSICXML_VERSION + " Partwise//EN\""
                + " \"http://www.musicxml.org/dtds/partwise.dtd\">");
        xml.open("score-partwise", "version", MUSICXML_VERSION);
        score.title().ifPresent(title ->
                xml.open("work").element("work-title", title).close("work"));
        // "composer" rather than "artist": MusicXML's creator types are the
        // ones a score credits, and every reader shows composer at the top
        // right of the first page. The model's "artist" is whoever the
        // recording is by, which for a transcription is the closest thing to
        // a composer credit it has.
        score.artist().ifPresent(artist -> xml.open("identification")
                .element("creator", artist, "type", "composer")
                .close("identification"));
        return xml;
    }

    private static void partList(XmlWriter xml, List<String> names) {
        xml.open("part-list");
        for (int i = 0; i < names.size(); i++) {
            xml.open("score-part", "id", partId(i))
                    .element("part-name", names.get(i))
                    .close("score-part");
        }
        xml.close("part-list");
    }

    private static String close(XmlWriter xml) {
        xml.close("score-partwise");
        return xml.toString();
    }

    /** P1, P2, ...: an XML ID has to start with a letter, so the index alone would not be one. */
    private static String partId(int index) {
        return "P" + (index + 1);
    }

    /**
     * A length in quarter-note beats as whole MusicXML divisions.
     *
     * <p>Exact, with no tolerance, for the reason {@link ExportGrid#unitsOf}
     * gives: rounding a length the layout could not hold would put the measure
     * out by exactly as much as the length was wrong, and a measure that does
     * not fill its meter is imported without complaint.
     *
     * @throws IllegalStateException if the length is not a whole number of
     *         divisions
     */
    static int divisionsOf(double quarters) {
        return ExportGrid.unitsOf(quarters);
    }

    /** A length counted in {@code perWhole}ths of a whole note, as divisions. */
    private static int divisionsOf(long length, long perWhole) {
        return ExportGrid.unitsOf(length, perWhole);
    }

    // ----------------------------------------------------------------- chart

    private static void writeChart(XmlWriter xml, Score score, List<ChartLayout.Bar> bars,
                                   Optional<LyricEngraving.Placement> lyrics, boolean hidden) {
        TimeSignature counted = ChordChart.countedIn(score, bars);
        double opensAt = ChordChart.opensAt(bars);
        List<List<Sung>> sungByBar = lyrics.map(placement -> byBar(placement, bars.size()))
                .orElse(List.of());
        for (int i = 0; i < bars.size(); i++) {
            ChartLayout.Bar bar = bars.get(i);
            // The chart writes its first bar full and leads in with a rest, as
            // its text and page do; there is no pickup to number from.
            xml.open("measure", "number", String.valueOf(i + 1));
            if (i == 0 || bar.meterChanged()) {
                xml.open("attributes");
                if (i == 0) {
                    xml.element("divisions", String.valueOf(DIVISIONS_PER_QUARTER));
                    keySignature(xml, score.primaryKey());
                }
                timeSignature(xml, bar.meter());
                if (i == 0 && hidden) {
                    xml.empty("staff-details", "print-object", "no");
                }
                xml.close("attributes");
            }
            if (i == 0) {
                TempoMark.of(score, counted, opensAt).ifPresent(mark ->
                        tempo(xml, mark.unit(), mark.perMinute(),
                                TempoMark.headline(score, opensAt)));
            }
            // This bar's syllables, at their position in it in quarter beats.
            List<Sung> words = sungByBar.isEmpty() ? List.of() : sungByBar.get(i);
            List<Double> positions = new ArrayList<>();
            for (Sung syllable : words) {
                positions.add(syllable.into() * ChartGrid.UNIT);
            }
            double at = 0;
            for (ChartLayout.Cell cell : bar.cells()) {
                if (cell.named()) {
                    harmony(xml, cell.chord(), 0);
                }
                double end = at + cell.lengthQuarters();
                // Cut at every syllable inside the cell, so each has a rest
                // beginning on its own moment to ride.
                List<Double> cuts = new ArrayList<>();
                cuts.add(at);
                for (double position : positions) {
                    if (position > at && position < end) {
                        cuts.add(position);
                    }
                }
                cuts.add(end);
                for (int c = 0; c + 1 < cuts.size(); c++) {
                    List<Sung> riding = new ArrayList<>();
                    for (int w = 0; w < words.size(); w++) {
                        if (positions.get(w).doubleValue() == cuts.get(c).doubleValue()) {
                            riding.add(words.get(w));
                        }
                    }
                    boolean first = true;
                    for (NoteValue value : MetricSplitter.split(
                            bar.meter(), cuts.get(c), cuts.get(c + 1))) {
                        rest(xml, value, hidden, first ? riding : List.of());
                        first = false;
                    }
                }
                at = end;
            }
            if (i == bars.size() - 1) {
                finalBarLine(xml);
            }
            xml.close("measure");
        }
    }

    /**
     * Every syllable the page places, by chart bar and in unit order within it,
     * with what the format says about it: which lane, how it joins its
     * neighbours, whether it opens an extender — or closes one, which is a
     * syllable of empty text. Bars past {@code bars} are not represented.
     */
    private static List<List<Sung>> byBar(LyricEngraving.Placement placement, int bars) {
        List<List<Sung>> byBar = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            byBar.add(new ArrayList<>());
        }
        long[] barStart = placement.barStart();
        for (int lane = 0; lane < placement.lanes().size(); lane++) {
            List<LyricEngraving.Syllable> syllables = placement.lanes().get(lane);
            int bar = 0;
            for (int i = 0; i < syllables.size(); i++) {
                LyricEngraving.Syllable syllable = syllables.get(i);
                while (bar < bars && syllable.unit() >= barStart[bar + 1]) {
                    bar++;
                }
                if (bar >= bars) {
                    break;
                }
                boolean joined = i > 0 && syllables.get(i - 1).hyphenated();
                String syllabic = joined ? (syllable.hyphenated() ? "middle" : "end")
                        : (syllable.hyphenated() ? "begin" : "single");
                byBar.get(bar).add(new Sung(lane + 1, syllable.unit() - barStart[bar],
                        syllable.text(), syllabic, syllable.melisma()));
            }
        }
        for (List<Sung> inBar : byBar) {
            inBar.sort(Comparator.comparingLong(Sung::into).thenComparingInt(Sung::lane));
        }
        return byBar;
    }

    /**
     * One placed syllable, {@code into} its chart bar in grid units; an empty
     * text closes an extender rather than saying anything.
     */
    private record Sung(int lane, long into, String text, String syllabic, boolean extendStart) {

        boolean closesExtender() {
            return text.isEmpty();
        }
    }

    /** Divisions in one unit of the chart's grid. */
    private static final int UNIT_DIVISIONS = divisionsOf(ChartGrid.UNIT);

    // ---------------------------------------------------------------- pieces

    /**
     * A chord symbol, before the note or rest it sounds over.
     *
     * <p>The quality is written twice on purpose: as MusicXML's own kind, which
     * is what a reader plays, and as the chart's symbol in {@code text}, which
     * is what it prints — so the page and {@code chords.txt} agree letter for
     * letter. Every quality has its own case and there is no {@code default},
     * for the reason {@code ChordChart.lilyPondQuality} gives.
     *
     * <p>No chord — the lead-in gap and an estimated silence alike — is the
     * format's own idiom for it, with a root that displays nothing.
     */
    private static void harmony(XmlWriter out, Optional<Chord> chord, int offsetDivisions) {
        out.open("harmony");
        if (chord.isEmpty() || chord.get().isNoChord()) {
            out.open("root").element("root-step", "C", "text", "").close("root");
            out.element("kind", "none", "text", ChordQuality.NONE.symbol());
        } else {
            Chord named = chord.get();
            out.open("root");
            step(out, "root-step", "root-alter", named.root());
            out.close("root");
            out.element("kind", kindOf(named.quality()), "text", named.quality().symbol());
            if (named.isSlashChord()) {
                out.open("bass");
                step(out, "bass-step", "bass-alter", named.bass().orElseThrow());
                out.close("bass");
            }
        }
        if (offsetDivisions != 0) {
            out.element("offset", String.valueOf(offsetDivisions));
        }
        out.close("harmony");
    }

    private static String kindOf(ChordQuality quality) {
        return switch (quality) {
            case MAJOR -> "major";
            case MINOR -> "minor";
            case DIMINISHED -> "diminished";
            case AUGMENTED -> "augmented";
            case SUSPENDED_SECOND -> "suspended-second";
            case SUSPENDED_FOURTH -> "suspended-fourth";
            case DOMINANT_SEVENTH -> "dominant";
            case MAJOR_SEVENTH -> "major-seventh";
            case MINOR_SEVENTH -> "minor-seventh";
            case MINOR_MAJOR_SEVENTH -> "major-minor";
            case HALF_DIMINISHED_SEVENTH -> "half-diminished";
            case DIMINISHED_SEVENTH -> "diminished-seventh";
            case SIXTH -> "major-sixth";
            case MINOR_SIXTH -> "minor-sixth";
            case NONE -> "none";
        };
    }

    /** A step and its alteration, from the spelling and nothing else. */
    private static void step(XmlWriter out, String stepTag, String alterTag,
                             PitchSpelling spelling) {
        out.element(stepTag, spelling.letter().name());
        if (spelling.accidental().alteration() != 0) {
            out.element(alterTag, String.valueOf(spelling.accidental().alteration()));
        }
    }

    private static void rest(XmlWriter out, NoteValue value, boolean hidden, List<Sung> riding) {
        // A note that does not print takes its lyrics with it unless told otherwise.
        out.open("note", "print-object", hidden ? "no" : null,
                        "print-lyric", hidden ? "yes" : null)
                .empty("rest")
                .element("duration", String.valueOf(divisionsOf(value.quarters())))
                .element("voice", MusicXmlStaffWriter.VOICE)
                .element("type", value.musicXmlType());
        for (int dot = 0; dot < value.dots(); dot++) {
            out.empty("dot");
        }
        for (Sung syllable : riding) {
            lyric(out, syllable);
        }
        out.close("note");
    }

    private static void lyric(XmlWriter out, Sung syllable) {
        out.open("lyric", "number", String.valueOf(syllable.lane()));
        if (syllable.closesExtender()) {
            out.empty("extend", "type", "stop");
        } else {
            out.element("syllabic", syllable.syllabic());
            out.element("text", syllable.text());
            if (syllable.extendStart()) {
                out.empty("extend", "type", "start");
            }
        }
        out.close("lyric");
    }

    /** A syllable written as text at its own moment, when no note can carry it. */
    private static void spoken(XmlWriter out, Sung syllable, int offsetDivisions) {
        out.open("direction", "placement", "below")
                .open("direction-type")
                .element("words", syllable.text())
                .close("direction-type");
        if (offsetDivisions != 0) {
            out.element("offset", String.valueOf(offsetDivisions));
        }
        out.close("direction");
    }

    /**
     * The key signature.
     *
     * <p>Zero sharps when the score claims no key, which is what the
     * LilyPond side writes as {@code c \major}: no accidental is put on the
     * page that the pipeline never decided on. The {@code <mode>} is left
     * off in that case rather than guessed, since "no key was decided" and
     * "the key is C major" are different claims and only the second one
     * names a mode.
     */
    private static void keySignature(XmlWriter out, Optional<Key> key) {
        out.open("key")
                .element("fifths", String.valueOf(
                        key.map(Key::keySignatureAccidentals).orElse(0)));
        key.ifPresent(k -> out.element("mode", k.mode().lilyPondName()));
        out.close("key");
    }

    /**
     * The time signature.
     *
     * <p>The numerator and denominator as the model holds them.
     * {@link TimeSignature#beatStructure()} has no MusicXML equivalent that
     * a reader acts on — beam grouping there is expressed per note, by
     * {@code <beam>} elements this does not write — so a reader beams the
     * bar from the meter, which is where the grouping came from anyway.
     */
    private static void timeSignature(XmlWriter out, TimeSignature signature) {
        out.open("time")
                .element("beats", String.valueOf(signature.numerator()))
                .element("beat-type", String.valueOf(signature.denominator()))
                .close("time");
    }

    private static void tempo(XmlWriter out, NoteValue unit, long perMinute,
                              double quarterBeatsPerMinute) {
        out.open("direction", "placement", "above");
        // The qualifier first, as its own direction-type -- the same word
        // LilyPond prints, from the same constant: this file and the .ly
        // are two spellings of one page, and a reader engraving this one
        // would otherwise state as exact the figure the PDF marks as an
        // estimate.
        out.open("direction-type")
                .element("words", TempoMark.ESTIMATE)
                .close("direction-type");
        out.open("direction-type").open("metronome")
                .element("beat-unit", unit.musicXmlType());
        for (int i = 0; i < unit.dots(); i++) {
            out.empty("beat-unit-dot");
        }
        out.element("per-minute", String.valueOf(perMinute))
                .close("metronome").close("direction-type");
        // <sound tempo> is quarter notes a minute by definition, whatever
        // unit the mark is printed in -- which is the trap the printed mark
        // exists to avoid in the other direction. Taken from the model's own
        // figure rather than reconstructed from the rounded mark, so that a
        // 6/8 score plays at the tempo it was transcribed at rather than at
        // whatever three times a rounded dotted-quarter count comes to.
        out.empty("sound", "tempo", String.valueOf(Math.round(quarterBeatsPerMinute)));
        out.close("direction");
    }

    /** The double bar line at the end of the piece, which is what LilyPond's {@code \bar "|."} draws. */
    private static void finalBarLine(XmlWriter out) {
        out.open("barline", "location", "right")
                .element("bar-style", "light-heavy")
                .close("barline");
    }

    // ---------------------------------------------------------------- writer

    /**
     * Spells one laid-out staff as a MusicXML {@code <part>}.
     *
     * <p>Every decision has already been made; this writes measures. What it
     * keeps back is what the format needs later than the layout says it: a
     * measure is held until the next one starts, because its opening tag needs
     * the pickup that arrives after {@link #startBar}, and each note is held
     * until the next callback, because a tuplet bracket is stopped on its last
     * note and the layout says the bracket has closed only after that note has
     * gone by.
     *
     * <p>The chord symbols and words of a lead sheet ride along: each staff bar
     * takes the chart bar of the same index and writes every named cell before
     * the note sounding when it begins, offset into that note where the change
     * falls inside it, and hangs each syllable on the note sounding when it is
     * sung. The words are placed only once the pickup is known, since the page
     * opens its lanes on it.
     */
    private static final class MusicXmlStaffWriter implements StaffWriter {

        /** The voice every note is in. One part, one voice: #93. */
        static final String VOICE = "1";

        /** The bracket number every tuplet carries. Brackets never nest here. */
        private static final String TUPLET_NUMBER = "1";

        private final XmlWriter part;
        private final Score score;
        private final double quarterBeatsPerMinute;
        private final List<ChartLayout.Bar> chart;

        private StaffClef clef;
        private Optional<Key> key = Optional.empty();

        /** The body of the measure being written, or {@code null} before the first. */
        private XmlWriter measure;
        private int index;
        private int bars;
        private TimeSignature meter;

        /**
         * Whether bar 0 turned out to be a pickup, which shifts every measure
         * number down by one.
         *
         * <p>A pickup is measure 0 and the first full bar is measure 1, which is
         * how a musician counts and how MusicXML says to write it.
         */
        private boolean pickupBar;

        /** Divisions written into the current measure so far. */
        private int written;

        /** Divisions the current measure must hold, from its meter and pickup. */
        private int expected;

        /** The bracket in force as actual and normal note counts, or {@code null}. */
        private int[] tuplet;
        private boolean tupletHasNote;

        private PendingNote pending;

        /** Whether the note written next is the far end of a tie. */
        private boolean tiedFromPrevious;

        /** The chord symbols of this bar not yet written, in order. */
        private List<Placed> harmonies = new ArrayList<>();

        /** The page's syllables by chart bar, once the pickup is known; {@code null} before. */
        private List<List<Sung>> sungByBar;

        /** This bar's syllables not yet written, at their offsets into the bar. */
        private List<SungAt> lyrics = new ArrayList<>();

        /**
         * The lanes with an extender open. One ends where the lane's next
         * syllable rides a note, or where a closing stops it; a closing with
         * nothing open says nothing, and a syllable said as text ends none
         * (#789).
         */
        private final List<Integer> extending = new ArrayList<>();

        /** How far the bar's own start lies before its first written division: the pickup. */
        private int barShift;

        MusicXmlStaffWriter(XmlWriter part, Score score, List<ChartLayout.Bar> chart) {
            this.part = part;
            this.score = score;
            this.quarterBeatsPerMinute = TempoMark.headline(score, 0);
            this.chart = chart;
        }

        @Override
        public void startStaff(String name, StaffClef staffClef, Optional<Key> staffKey) {
            this.clef = staffClef;
            this.key = staffKey;
        }

        @Override
        public void startBar(int barIndex, TimeSignature barMeter, boolean meterChanged) {
            emitMeasure();
            measure = part.child();
            index = barIndex;
            written = 0;
            expected = divisionsOf(barMeter.quarterBeatsPerBar());
            harmonies = barIndex < chart.size() ? placed(chart.get(barIndex)) : new ArrayList<>();
            barShift = 0;
            lyrics = sungByBar == null ? new ArrayList<>() : lyricsOf(barIndex);
            boolean first = bars == 0;
            bars++;
            if (first || meterChanged) {
                measure.open("attributes");
                if (first) {
                    measure.element("divisions", String.valueOf(DIVISIONS_PER_QUARTER));
                    keySignature(measure, key);
                }
                timeSignature(measure, barMeter);
                if (first) {
                    clefElement();
                }
                measure.close("attributes");
            }
            meter = barMeter;
        }

        @Override
        public void tempo(NoteValue unit, long perMinute) {
            MusicXmlExport.tempo(measure, unit, perMinute, quarterBeatsPerMinute);
        }

        @Override
        public void unreadMelody() {
            measure.open("direction", "placement", "above")
                    .open("direction-type")
                    .element("words", UNREAD_MELODY)
                    .close("direction-type")
                    .close("direction");
        }

        @Override
        public void pickup(long wholeNotesNumerator, long wholeNotesDenominator) {
            pickupBar = true;
            // Four quarters to a whole note. The pickup is a fraction of one
            // because a pickup inside a triplet is two thirds of a quarter and
            // no double holds that; the multiplication below is exact for every
            // fraction that reaches here, since the division is the last step.
            expected = divisionsOf(4.0 * wholeNotesNumerator / wholeNotesDenominator);
            barShift = divisionsOf(meter.quarterBeatsPerBar()) - expected;
            if (!chart.isEmpty()) {
                StaffNotation.Pickup pickup =
                        new StaffNotation.Pickup(wholeNotesNumerator, wholeNotesDenominator);
                harmonies = placed(ChordChart.intoPickup(chart.get(0), pickup));
                placeLyrics(Optional.of(pickup));
            }
        }

        @Override
        public void openTuplet(int actual, int normal) {
            flush();
            tuplet = new int[] {actual, normal};
            tupletHasNote = false;
        }

        @Override
        public void closeTuplet() {
            if (pending != null && tuplet != null) {
                pending.tupletStop = true;
            }
            flush();
            tuplet = null;
        }

        @Override
        public void symbol(List<PitchSpelling> pitches, NoteValue value, double soundingQuarters,
                           boolean tied) {
            flush();
            placeLyrics(Optional.empty());
            int duration = divisionsOf(soundingQuarters);
            pending = new PendingNote(pitches, value, duration, tiedFromPrevious, tied,
                    tuplet, tuplet != null && !tupletHasNote, due(written, written + duration),
                    lyricsDue(written, written + duration));
            tupletHasNote = true;
            // A chord sounds once, however many note heads it has: only the
            // first carries the measure forward, and MusicXML says the same by
            // marking the rest with <chord/>.
            written += duration;
            tiedFromPrevious = tied;
        }

        @Override
        public void wholeBarRest(long wholeNotesNumerator, long wholeNotesDenominator) {
            flush();
            placeLyrics(Optional.empty());
            int duration = divisionsOf(4.0 * wholeNotesNumerator / wholeNotesDenominator);
            for (Placed placed : due(written, written + duration)) {
                harmony(measure, placed.chord(), placed.offset());
            }
            // A rest carries no syllable: each is said at its own moment. It
            // does close an extender, as the page's empty syllable does.
            List<Sung> closing = new ArrayList<>();
            for (SungAt at : lyricsDue(written, written + duration)) {
                if (!at.sung().closesExtender()) {
                    spoken(measure, at.sung(), at.offset());
                } else if (extending.remove(Integer.valueOf(at.sung().lane()))) {
                    closing.add(at.sung());
                }
            }
            // measure="yes" is the whole-bar rest: one symbol centred in the bar
            // whatever the meter, which is what LilyPond's R means too. No
            // <type>: a measure rest has no note value, and naming one would
            // claim a 5/4 bar of silence is a symbol that does not exist.
            measure.open("note")
                    .empty("rest", "measure", "yes")
                    .element("duration", String.valueOf(duration))
                    .element("voice", VOICE);
            for (Sung syllable : closing) {
                lyric(measure, syllable);
            }
            measure.close("note");
            written += duration;
        }

        @Override
        public void endBar() {
            flush();
            if (written != expected) {
                // The failure this catches is the one MusicXML readers do not:
                // a measure that does not fill its meter is imported without
                // complaint and everything after it is in the wrong place.
                throw new IllegalStateException(
                        "measure " + number() + " of " + meter + " holds " + written
                                + " divisions where it should hold " + expected
                                + "; the layout and this export disagree about its length");
            }
            if (!harmonies.isEmpty()) {
                throw new Refused(
                        "measure " + number() + " of " + meter + " has a chord change at division "
                                + harmonies.getFirst().offset() + ", outside its span;"
                                + " the chart and the staff disagree about this bar");
            }
            if (!lyrics.isEmpty()) {
                throw new Refused(
                        "measure " + number() + " of " + meter + " has a syllable at division "
                                + lyrics.getFirst().offset() + ", outside its span;"
                                + " the chart and the staff disagree about this bar");
            }
        }

        @Override
        public void endStaff() {
            if (tiedFromPrevious) {
                throw new IllegalStateException(
                        "the last note of this part ties into a note that was never written;"
                                + " the layout ended mid-tie");
            }
            if (measure != null) {
                finalBarLine(measure);
            }
            emitMeasure();
        }

        // ------------------------------------------------------------ pieces

        /** Writes the held measure into the part, with the number the pickup decided. */
        private void emitMeasure() {
            if (measure == null) {
                return;
            }
            // "implicit" is MusicXML's word for a bar that is not counted, which
            // is what a pickup is: the reader must not print a 0 over it.
            part.open("measure", "number", number(),
                    "implicit", pickupBar && index == 0 ? "yes" : null);
            part.append(measure);
            part.close("measure");
            measure = null;
        }

        private String number() {
            return String.valueOf(pickupBar ? index : index + 1);
        }

        /** The named cells of a chart bar, at their offsets into it. */
        private static List<Placed> placed(ChartLayout.Bar bar) {
            List<Placed> placed = new ArrayList<>();
            int at = 0;
            for (ChartLayout.Cell cell : bar.cells()) {
                if (cell.named()) {
                    placed.add(new Placed(at, cell.chord()));
                }
                at += divisionsOf(cell.lengthQuarters());
            }
            return placed;
        }

        /** The same, for the first bar as the pickup cut it. */
        private static List<Placed> placed(List<ChordChart.Cut> cuts) {
            List<Placed> placed = new ArrayList<>();
            int at = 0;
            for (ChordChart.Cut cut : cuts) {
                if (cut.named()) {
                    placed.add(new Placed(at, cut.chord()));
                }
                at += divisionsOf(cut.length(), cut.perWhole());
            }
            return placed;
        }

        /**
         * Places the page's syllables once, on the pickup the staff opens with
         * — which the layout says after the bar has started, so this is asked
         * at the pickup and again at the first note.
         */
        private void placeLyrics(Optional<StaffNotation.Pickup> pickup) {
            if (sungByBar != null) {
                return;
            }
            sungByBar = chart.isEmpty() ? List.of()
                    : LyricEngraving.place(score, chart, pickup, true)
                            .map(placement -> byBar(placement, chart.size())).orElse(List.of());
            lyrics = lyricsOf(index);
        }

        /** This bar's syllables, offset from its first written division. */
        private List<SungAt> lyricsOf(int barIndex) {
            List<SungAt> at = new ArrayList<>();
            if (barIndex >= sungByBar.size()) {
                return at;
            }
            for (Sung syllable : sungByBar.get(barIndex)) {
                at.add(new SungAt(
                        Math.toIntExact(syllable.into() * UNIT_DIVISIONS - barShift), syllable));
            }
            return at;
        }

        /** The syllables sung within {@code [from, to)}, taken out and offset from {@code from}. */
        private List<SungAt> lyricsDue(int from, int to) {
            List<SungAt> due = new ArrayList<>();
            Iterator<SungAt> remaining = lyrics.iterator();
            while (remaining.hasNext()) {
                SungAt at = remaining.next();
                if (at.offset() >= from && at.offset() < to) {
                    due.add(new SungAt(at.offset() - from, at.sung()));
                    remaining.remove();
                }
            }
            return due;
        }

        /**
         * The chord symbols beginning within {@code [from, to)}, taken out of
         * the bar's list and offset from {@code from}.
         */
        private List<Placed> due(int from, int to) {
            List<Placed> due = new ArrayList<>();
            Iterator<Placed> remaining = harmonies.iterator();
            while (remaining.hasNext()) {
                Placed placed = remaining.next();
                if (placed.offset() >= from && placed.offset() < to) {
                    due.add(new Placed(placed.offset() - from, placed.chord()));
                    remaining.remove();
                }
            }
            return due;
        }

        /** Writes the held note, one {@code <note>} per note head. */
        private void flush() {
            if (pending == null) {
                return;
            }
            PendingNote note = pending;
            pending = null;
            for (Placed placed : note.harmonies) {
                harmony(measure, placed.chord(), placed.offset());
            }
            // Which syllables this note carries: per lane the first sung on it,
            // on its own onset if it is the far end of a tie, and never on a
            // rest. The rest are said at their moments.
            List<Sung> carried = new ArrayList<>();
            List<Integer> lanesTaken = new ArrayList<>();
            List<SungAt> said = new ArrayList<>();
            for (SungAt at : note.lyrics) {
                if (at.sung().closesExtender()) {
                    continue;
                }
                boolean carries = !note.pitches.isEmpty() && !lanesTaken.contains(at.sung().lane())
                        && (at.offset() == 0 || !note.tiedIn);
                if (carries) {
                    carried.add(at.sung());
                    lanesTaken.add(at.sung().lane());
                } else {
                    said.add(at);
                }
            }
            // A syllable riding this note ends whatever extender its lane had open.
            for (Sung sung : carried) {
                extending.remove(Integer.valueOf(sung.lane()));
            }
            // A closing on the note that carries its lane's syllable closes
            // nothing the format can draw: after that syllable, it cancels the
            // extender the syllable would open; before it, it is an earlier
            // melisma the syllable ends. Elsewhere it stops the lane's open
            // extender, a rest included, and says nothing where none is open.
            for (SungAt at : note.lyrics) {
                if (!at.sung().closesExtender()) {
                    continue;
                }
                int lane = at.sung().lane();
                Sung own = carried.stream()
                        .filter(sung -> sung.lane() == lane && !sung.closesExtender())
                        .findFirst().orElse(null);
                if (own != null) {
                    if (at.sung().into() > own.into()) {
                        carried.replaceAll(sung -> sung == own
                                ? new Sung(own.lane(), own.into(), own.text(), own.syllabic(), false)
                                : sung);
                    }
                } else if (extending.remove(Integer.valueOf(lane))) {
                    carried.add(at.sung());
                    lanesTaken.add(lane);
                }
            }
            for (Sung sung : carried) {
                if (sung.extendStart()) {
                    extending.add(sung.lane());
                }
            }
            for (SungAt at : said) {
                spoken(measure, at.sung(), at.offset());
            }
            for (int i = 0; i < Math.max(1, note.pitches.size()); i++) {
                measure.open("note");
                if (i > 0) {
                    measure.empty("chord");
                }
                if (note.pitches.isEmpty()) {
                    measure.empty("rest");
                } else {
                    pitch(note.pitches.get(i));
                }
                measure.element("duration", String.valueOf(note.duration));
                // Both spellings of a tie, because MusicXML has two and they
                // mean different things: <tie> is what a playback engine reads
                // and <tied> is the slur a reader draws. Stop before start,
                // which is the order the schema fixes.
                if (note.tiedIn) {
                    measure.empty("tie", "type", "stop");
                }
                if (note.tiedOut) {
                    measure.empty("tie", "type", "start");
                }
                measure.element("voice", VOICE);
                measure.element("type", note.value.musicXmlType());
                for (int dot = 0; dot < note.value.dots(); dot++) {
                    measure.empty("dot");
                }
                // Every note head inside a bracket carries the modification: it
                // is what tells a reader the written value is played shorter,
                // and a reader that saw it on only the first note of a chord
                // would play the rest of the chord long.
                if (note.tuplet != null) {
                    measure.open("time-modification")
                            .element("actual-notes", String.valueOf(note.tuplet[0]))
                            .element("normal-notes", String.valueOf(note.tuplet[1]))
                            .close("time-modification");
                }
                boolean bracket = i == 0 && (note.tupletStart || note.tupletStop);
                if (note.tiedIn || note.tiedOut || bracket) {
                    measure.open("notations");
                    if (note.tiedIn) {
                        measure.empty("tied", "type", "stop");
                    }
                    if (note.tiedOut) {
                        measure.empty("tied", "type", "start");
                    }
                    if (i == 0 && note.tupletStart) {
                        measure.empty("tuplet", "type", "start", "number", TUPLET_NUMBER);
                    }
                    if (i == 0 && note.tupletStop) {
                        measure.empty("tuplet", "type", "stop", "number", TUPLET_NUMBER);
                    }
                    measure.close("notations");
                }
                if (i == 0) {
                    for (Sung syllable : carried) {
                        lyric(measure, syllable);
                    }
                }
                measure.close("note");
            }
        }

        private void clefElement() {
            measure.open("clef")
                    .element("sign", clef.sign() == 'F' ? "F" : "G")
                    .element("line", String.valueOf(clef.line()));
            if (clef.octaveChange() != 0) {
                measure.element("clef-octave-change", String.valueOf(clef.octaveChange()));
            }
            measure.close("clef");
        }

        /**
         * A pitch, from the spelling and nothing else.
         *
         * <p>{@code <alter>} is the spelling's alteration rather than the
         * difference between the sounding pitch and the natural, which are the
         * same number and are arrived at differently: the first cannot lose a
         * double flat and the second cannot tell one from a natural a whole tone
         * down. The octave is the model's, which counts middle C as 4 — the same
         * convention MusicXML uses, so it passes straight through.
         */
        private void pitch(PitchSpelling spelling) {
            measure.open("pitch");
            step(measure, "step", "alter", spelling);
            measure.element("octave", String.valueOf(spelling.octave())).close("pitch");
        }

        /** A chord symbol and where it begins, in divisions. */
        private record Placed(int offset, Optional<Chord> chord) {
        }

        /** A syllable and where it is sung, in divisions. */
        private record SungAt(int offset, Sung sung) {
        }

        /** A note held back until the callback after it, which may stop its bracket. */
        private static final class PendingNote {

            final List<PitchSpelling> pitches;
            final NoteValue value;
            final int duration;
            final boolean tiedIn;
            final boolean tiedOut;
            final int[] tuplet;
            final boolean tupletStart;
            final List<Placed> harmonies;
            final List<SungAt> lyrics;
            boolean tupletStop;

            PendingNote(List<PitchSpelling> pitches, NoteValue value, int duration,
                        boolean tiedIn, boolean tiedOut, int[] tuplet, boolean tupletStart,
                        List<Placed> harmonies, List<SungAt> lyrics) {
                this.pitches = pitches;
                this.value = value;
                this.duration = duration;
                this.tiedIn = tiedIn;
                this.tiedOut = tiedOut;
                this.tuplet = tuplet;
                this.tupletStart = tupletStart;
                this.harmonies = harmonies;
                this.lyrics = lyrics;
            }
        }
    }
}
