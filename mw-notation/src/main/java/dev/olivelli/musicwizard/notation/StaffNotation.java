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

import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Engraves a {@link NoteTrack} as staff notation in LilyPond source.
 *
 * <p>Emitted straight from the domain model. The alternative — export MusicXML
 * and run {@code musicxml2ly} — loses information on the way through, and it
 * loses precisely the things this class exists to get right: how a note is
 * spelled, where a tie falls and how a bar groups.
 *
 * <p>Three decisions are worth knowing before reading the output.
 *
 * <p><b>Pitch is spelled, never computed.</b> A note's {@link PitchSpelling} is
 * used as written. MIDI 61 is both C sharp and D flat and the difference is
 * visible on the page, so the spelling the pipeline decided on is carried here
 * rather than re-derived. A note that carries none is spelled from the score's
 * key — flats in a flat key, sharps otherwise — which is a documented guess and
 * not a claim that the information was there.
 *
 * <p><b>Everything is in quarter-note beats.</b> Positions come from
 * {@link Note#onsetBeat()} and lengths from {@link Note#durationBeats()}; no
 * second is read. A track that has not been quantized is rejected rather than
 * converted, because inventing a rhythm for it is exactly what the model's
 * optional musical timing exists to prevent.
 *
 * <p><b>Bars are filled exactly.</b> Every bar is emitted with a {@code |} bar
 * check, so if the arithmetic here were ever wrong LilyPond would say so instead
 * of engraving a bar of the wrong length, which it will otherwise do without
 * complaint.
 *
 * <p>Known limitations, each with an issue: tuplets are snapped to the 64th-note
 * grid rather than represented (#92), overlapping notes are reduced to block
 * chords rather than split into voices (#93), a key change part-way through is
 * not engraved (#94), and percussion parts are refused (#95).
 */
public final class StaffNotation {

    /** The LilyPond language version the emitted source declares. */
    static final String LILYPOND_VERSION = "2.24.0";

    /** The grid all positions and lengths are snapped onto. */
    private static final double GRID = LilyPondDuration.SHORTEST_QUARTERS;

    /**
     * A ceiling on how many bars are emitted, so that a score whose beat axis is
     * wrong produces a complaint rather than a file that fills the disk.
     *
     * <p>Generous enough that reaching it is unusual rather than impossible: a
     * 4/4 piece at 120 BPM runs out at about three and a half hours, which a live
     * set or a DJ mix can exceed. So the message says what was seen and offers
     * the likely cause, rather than telling a user with a long recording that
     * their data is corrupt.
     */
    private static final int MAX_BARS = 100_000;

    private StaffNotation() {
    }

    /**
     * A complete LilyPond document engraving one part.
     *
     * @throws IllegalArgumentException if the track is percussion, or holds a
     *         note that has not been quantized
     */
    public static String toLilyPond(Score score, NoteTrack track) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(track, "track");
        StringBuilder out = new StringBuilder();
        out.append("\\version \"").append(LILYPOND_VERSION).append("\"\n\n");
        out.append("\\header {\n");
        out.append("  title = \"").append(escape(score.title().orElse("Untitled"))).append("\"\n");
        score.artist().ifPresent(artist ->
                out.append("  composer = \"").append(escape(artist)).append("\"\n"));
        out.append("  tagline = ##f\n");
        out.append("}\n\n");
        out.append("\\score {\n");
        out.append(staffBlock(score, track));
        out.append("  \\layout { }\n");
        out.append("}\n");
        return out.toString();
    }

    /**
     * The {@code \new Staff} expression alone, indented two spaces.
     *
     * <p>Separate from {@link #toLilyPond} so that a multi-staff emitter — a
     * piano reduction, a full score — can place several of these in one
     * {@code \score} block without re-deriving any of this.
     */
    static String staffBlock(Score score, NoteTrack track) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(track, "track");
        if (track.role() == PartRole.DRUMS) {
            throw new IllegalArgumentException(
                    "percussion parts need a DrumStaff, which this does not emit; see #95");
        }
        requireQuantized(track);

        List<Event> events = eventsOf(score, track);
        double pickupStart = pickupStart(score, track, events);

        StringBuilder out = new StringBuilder();
        out.append("  \\new Staff \\with { instrumentName = \"")
                .append(escape(track.name())).append("\" } {\n");
        out.append("    \\clef \"").append(clefOf(track.role())).append("\"\n");
        out.append("    \\key ").append(keyOf(score)).append('\n');
        appendBars(out, score, events, pickupStart);
        out.append("    \\bar \"|.\"\n");
        out.append("  }\n");
        return out.toString();
    }

    /** Rejects a track the notation layer cannot honestly engrave. */
    private static void requireQuantized(NoteTrack track) {
        for (Note note : track.notes()) {
            if (!note.isQuantized()) {
                throw new IllegalArgumentException(
                        "track \"" + track.name() + "\" holds a note at " + note.onsetSeconds()
                                + "s with no musical timing; quantize before engraving");
            }
        }
    }

    // ---------------------------------------------------------------- events

    /**
     * One written event: a pitch or a chord sounding over a span of beats, or a
     * rest when {@code pitches} is empty.
     */
    private record Event(double startBeat, double endBeat, List<String> pitches) {
    }

    /**
     * The track as a sequence of non-overlapping written events.
     *
     * <p>Notes sharing an onset become one chord, which is what a reduction of a
     * polyphonic part looks like on one staff. One staff holds one rhythm, so
     * two things are lost getting there and it is worth naming both.
     *
     * <p>A chord is written as long as its <em>longest</em> member, so a note
     * that stopped early is printed still sounding. Taking the shortest instead
     * would be the other error — it would cut the chord off and open a rest in
     * the middle of a held harmony, which reads worse and is no more true.
     *
     * <p>A note that begins while another is still sounding truncates that one.
     * The alternative, dropping the later note, loses a pitch the listener heard.
     *
     * <p>Both are what voices exist to avoid: #93.
     */
    private static List<Event> eventsOf(Score score, NoteTrack track) {
        boolean flatKey = score.primaryKey().map(Key::isFlatKey).orElse(false);

        // Keyed by onset so simultaneous notes gather, ordered so the sweep that
        // resolves overlaps below runs in time order.
        Map<Double, List<Note>> byOnset = new TreeMap<>();
        for (Note note : track.notes()) {
            if (!note.isQuantized()) {
                // The track being engraved has already been rejected if it holds
                // one of these. The other tracks reach here only through
                // pickupStart, which asks where the score's music begins and can
                // answer that from the tracks that have a position on the beat
                // axis, rather than refusing to engrave a finished part because
                // an unrelated one is still in seconds.
                continue;
            }
            double onset = snap(note.onsetBeat().orElseThrow());
            double offset = snap(note.offsetBeat().orElseThrow());
            if (offset <= onset) {
                // Shorter than a 64th once snapped. Dropped rather than lengthened
                // to the grid, which would push everything after it out of place.
                continue;
            }
            byOnset.computeIfAbsent(onset, key -> new ArrayList<>()).add(note);
        }

        List<Event> events = new ArrayList<>(byOnset.size());
        for (Map.Entry<Double, List<Note>> entry : byOnset.entrySet()) {
            double onset = entry.getKey();
            double end = onset;
            // Deduplicated by written name: two transcribed notes at the same
            // pitch would otherwise print as a unison chord, which LilyPond warns
            // about and no reader wants to see.
            Map<String, PitchSpelling> pitches = new LinkedHashMap<>();
            for (Note note : entry.getValue()) {
                end = Math.max(end, snap(note.offsetBeat().orElseThrow()));
                // Sounding pitch, not written pitch. An octave-transposing part
                // is transposed by its clef -- see clefOf -- and moving the
                // pitches as well would print it an octave high and, worse, make
                // LilyPond's own MIDI output play it there.
                PitchSpelling written = writtenSpelling(note, flatKey);
                pitches.putIfAbsent(written.lilyPondAbsoluteName(), written);
            }
            List<String> names = pitches.values().stream()
                    .sorted(Comparator.comparingInt(PitchSpelling::diatonicPosition)
                            .thenComparingInt(PitchSpelling::midiPitch))
                    .map(PitchSpelling::lilyPondAbsoluteName)
                    .toList();
            events.add(new Event(onset, end, names));
        }

        for (int i = 0; i < events.size() - 1; i++) {
            Event current = events.get(i);
            double next = events.get(i + 1).startBeat();
            if (current.endBeat() > next) {
                events.set(i, new Event(current.startBeat(), next, current.pitches()));
            }
        }
        return events;
    }

    /**
     * How a note is written.
     *
     * <p>The spelling the pipeline chose, when it chose one. Otherwise a default
     * that at least agrees with the key signature: an A flat bass line in E flat
     * major printed as G sharps is arithmetically correct and the first thing a
     * musician would complain about, and {@link Note#spellingOrDefault()} alone
     * prefers sharps everywhere.
     */
    private static PitchSpelling writtenSpelling(Note note, boolean flatKey) {
        Optional<PitchSpelling> chosen = note.spelling();
        if (chosen.isPresent()) {
            return chosen.get();
        }
        return flatKey
                ? PitchSpelling.ofMidiPitchFlat(note.midiPitch())
                : PitchSpelling.ofMidiPitchSharp(note.midiPitch());
    }

    // ------------------------------------------------------------------ bars

    /**
     * Where the music starts within bar zero, or zero when it starts on the
     * downbeat.
     *
     * <p>A piece whose first note is not on beat one has a pickup, and a pickup
     * is a short bar rather than a bar of rests: written as rests, every bar line
     * after it lands in the wrong place relative to what a reader counts. This is
     * the normal case for song melodies, not an edge case.
     *
     * <p>Taken from the earliest note in the <em>score</em> rather than in this
     * track, so that parts engraved separately still share a bar grid. A bass
     * that enters in bar four does not get its own pickup. The track being
     * engraved is included whether or not the score holds it, so that engraving
     * a track the caller built by hand does not silently lose its pickup.
     *
     * <p>Measured on the <em>events</em>, not on the notes. Those differ: a note
     * shorter than a 64th disappears when its onset and offset snap to the same
     * point, and reading the notes here let one of those open a pickup bar full
     * of rests for music that was never written — {@code \partial 1*63/64}
     * followed by seven rest symbols. A stray sub-64th onset at the head of a
     * transcription is ordinary input, not a corrupt score, so this asks the
     * question of the thing that actually reaches the page.
     *
     * <p>One consequence is worth knowing: a part engraves differently depending
     * on what the <em>rest</em> of the score has had done to it. Quantize the
     * bass after engraving the vocal and the vocal staff can gain a pickup and
     * move every bar line. That is the price of parts that agree with each other
     * rather than each choosing its own grid, and it argues for engraving a score
     * once its stages have all finished rather than part by part as they land.
     */
    private static double pickupStart(Score score, NoteTrack engraved, List<Event> events) {
        double earliest = events.isEmpty()
                ? Double.POSITIVE_INFINITY : events.getFirst().startBeat();
        for (NoteTrack track : score.tracks()) {
            if (track.equals(engraved)) {
                continue;
            }
            List<Event> other = eventsOf(score, track);
            if (!other.isEmpty()) {
                earliest = Math.min(earliest, other.getFirst().startBeat());
            }
        }
        double firstBar = score.tempoMap().timeSignatureAtBar(0).quarterBeatsPerBar();
        // Only a gap inside the opening bar is a pickup. Music that starts in bar
        // two starts after a bar of rest, and saying otherwise would move every
        // bar line in the piece.
        return Double.isFinite(earliest) && earliest > 0 && earliest < firstBar ? earliest : 0;
    }

    private static void appendBars(StringBuilder out, Score score, List<Event> events,
                                   double pickupStart) {
        // The last event ends last: overlaps were resolved by truncating the
        // earlier of the pair, so ends increase with onsets.
        double musicEnd = events.isEmpty() ? 0 : events.getLast().endBeat();

        TimeSignature previousMeter = null;
        double barStart = 0;
        int bar = 0;
        int eventIndex = 0;
        boolean tempoWritten = false;
        while (bar < MAX_BARS) {
            TimeSignature meter = score.tempoMap().timeSignatureAtBar(bar);
            double barEnd = barStart + meter.quarterBeatsPerBar();
            if (!meter.equals(previousMeter)) {
                appendMeter(out, meter);
                previousMeter = meter;
            }
            if (!tempoWritten) {
                appendTempo(out, score, meter);
                tempoWritten = true;
            }
            double contentStart = Math.max(barStart, bar == 0 ? pickupStart : 0);
            if (bar == 0 && pickupStart > 0) {
                out.append("    \\partial ")
                        .append(LilyPondDuration.scaled(barEnd - pickupStart)).append('\n');
            }
            eventIndex = appendBar(out, meter, barStart, barEnd, contentStart, events, eventIndex);
            barStart = barEnd;
            bar++;
            // One bar minimum, so an empty part still engraves as a staff rather
            // than as a syntax error.
            if (barStart >= musicEnd) {
                return;
            }
        }
        throw new IllegalStateException(
                "this part runs past " + MAX_BARS + " bars, which is more music than a staff is"
                        + " engraved for; if the recording is not that long, the beat axis is"
                        + " wrong -- a note quantized to a beat far past the end will do it");
    }

    /**
     * Fills one bar and returns the index of the first event it did not finish.
     *
     * <p>The bar is filled from {@code contentStart} to {@code barEnd} with no
     * gaps by construction: every step either writes an event or writes the rest
     * that precedes it.
     */
    private static int appendBar(StringBuilder out, TimeSignature meter, double barStart,
                                 double barEnd, double contentStart, List<Event> events,
                                 int firstEvent) {
        List<String> tokens = new ArrayList<>();
        double position = contentStart;
        int index = firstEvent;
        while (index < events.size() && events.get(index).startBeat() < barEnd) {
            Event event = events.get(index);
            if (event.endBeat() <= position) {
                index++;
                continue;
            }
            double from = Math.max(event.startBeat(), position);
            if (from > position) {
                appendValues(tokens, meter, barStart, position, from, List.of(), false);
            }
            double to = Math.min(event.endBeat(), barEnd);
            appendValues(tokens, meter, barStart, from, to, event.pitches(),
                    event.endBeat() > barEnd);
            position = to;
            if (event.endBeat() > barEnd) {
                break;
            }
            index++;
        }
        if (position < barEnd) {
            if (tokens.isEmpty() && contentStart == barStart) {
                // A bar nobody plays in shows one rest covering it, whatever the
                // meter. That is what a whole rest means, and a 3/4 bar of silence
                // is not a dotted half rest.
                tokens.add("R" + LilyPondDuration.scaled(barEnd - barStart));
            } else {
                appendValues(tokens, meter, barStart, position, barEnd, List.of(), false);
            }
        }
        out.append("    ").append(String.join(" ", tokens)).append(" |\n");
        return index;
    }

    /**
     * Writes one span as note values, tying the pieces together.
     *
     * <p>The tie between pieces is not a musical decision — it is one note the
     * meter forced into several symbols — so it is added here rather than left to
     * the caller. {@code tieOut} adds the further tie that carries the note over
     * the bar line.
     */
    private static void appendValues(List<String> tokens, TimeSignature meter, double barStart,
                                     double from, double to, List<String> pitches,
                                     boolean tieOut) {
        List<String> values = MetricSplitter.split(meter, from - barStart, to - barStart);
        String head = pitches.isEmpty() ? "r"
                : pitches.size() == 1 ? pitches.get(0)
                : "<" + String.join(" ", pitches) + ">";
        for (int i = 0; i < values.size(); i++) {
            boolean last = i == values.size() - 1;
            boolean tie = !pitches.isEmpty() && (!last || tieOut);
            tokens.add(head + values.get(i) + (tie ? "~" : ""));
        }
    }

    // ------------------------------------------------------------ preamble

    /**
     * The time signature, carrying the model's beat grouping where it has one.
     *
     * <p>{@code \time #'(3 3) 6/8} rather than a pair of {@code \set Timing}
     * lines: the list form takes the grouping in denominator units, which is
     * exactly what {@link TimeSignature#beatStructure()} returns, and it does not
     * name {@code baseMoment}, which LilyPond renamed in 2.26 and now warns
     * about. One line, no deprecation, same grouping on both versions.
     */
    private static void appendMeter(StringBuilder out, TimeSignature meter) {
        out.append("    \\time ");
        if (statesItsOwnGrouping(meter)) {
            out.append("#'(")
                    .append(meter.beatStructure().stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining(" ")))
                    .append(") ");
        }
        out.append(meter.numerator()).append('/').append(meter.denominator()).append('\n');
    }

    /**
     * Whether the model's beat structure is worth emitting for this meter.
     *
     * <p>{@link TimeSignature#beatStructure()} is authoritative for compound time
     * — it is what beams a 6/8 bar as two groups of three — and for meters
     * counted in quarters or halves, where one beat per denominator unit is how
     * the bar is actually counted.
     *
     * <p>It is not authoritative for an irregular meter in eighths. There it
     * declines to guess and returns one beat per eighth, which as a beam grouping
     * means no beams at all: 7/8 would come out as seven separate eighth notes.
     * LilyPond's own table has a conventional grouping for those, so it is left
     * to make the guess this model deliberately does not make. See #62.
     */
    private static boolean statesItsOwnGrouping(TimeSignature meter) {
        return meter.isCompound() || meter.denominator() <= 4;
    }

    /**
     * The metronome mark, in the beat the reader counts.
     *
     * <p>The model stores quarter notes per minute. Printed unqualified over a
     * 6/8 staff that is a marking 50% fast, because a 6/8 bar is counted in
     * dotted quarters — the same trap {@link ChordChart} documents, and the
     * reason the unit is written out rather than assumed to be a quarter.
     */
    private static void appendTempo(StringBuilder out, Score score, TimeSignature meter) {
        Optional<String> unit = LilyPondDuration.of(meter.beatUnitQuarters());
        if (unit.isEmpty()) {
            return;
        }
        double counted = meter.countedTempo(score.estimatedTempo());
        if (!Double.isFinite(counted) || counted < 1) {
            return;
        }
        // Locale.ROOT: a LilyPond integer is not a localised number, and under
        // fr_FR this would emit a decimal comma LilyPond rejects.
        out.append(String.format(Locale.ROOT, "    \\tempo %s = %d%n", unit.get(),
                Math.round(counted)).replace(System.lineSeparator(), "\n"));
    }

    /**
     * The clef the part is written in, carrying its octave transposition.
     *
     * <p>{@link PartRole#BASS} sounds an octave below where it is written, and
     * {@code \clef "bass_8"} is how a staff says so: LilyPond draws a sounding
     * pitch an octave higher under that clef than under a plain one, which is
     * the written position, and prints the small 8 that tells the reader why.
     *
     * <p>So the transposition is applied <em>here</em> and not to the pitches.
     * Doing both — which is the obvious reading of
     * {@link PartRole#writtenTranspositionSemitones()} — puts a bass line two
     * octaves above where it sounds, on ledger lines, and a plain bass clef with
     * moved pitches would instead leave nothing on the page saying the part
     * sounds lower than it reads.
     */
    private static String clefOf(PartRole role) {
        String base = role.prefersBassClef() ? "bass" : "treble";
        int octaves = role.writtenTranspositionSemitones() / 12;
        if (octaves == 0 || role.writtenTranspositionSemitones() % 12 != 0) {
            // A non-octave transposition needs a transposing instrument, not a
            // clef modifier. No role has one; if one appears, it is engraved at
            // sounding pitch rather than silently at the wrong one.
            return base;
        }
        return base + (octaves > 0 ? "_" : "^") + (Math.abs(octaves) * 7 + 1);
    }

    /**
     * The key signature.
     *
     * <p>{@code c \major} when the score has no key, which prints every
     * accidental explicitly. That is cluttered and it is honest: a key signature
     * is a claim about the music, and guessing one puts accidentals on the page
     * that the pipeline never decided on.
     */
    private static String keyOf(Score score) {
        return score.primaryKey()
                .map(key -> key.tonic().lilyPondName() + " \\" + key.mode().lilyPondName())
                .orElse("c \\major");
    }

    /** Rounds onto the shortest value named, which is what makes every span writable. */
    private static double snap(double beats) {
        return Math.round(beats / GRID) * GRID;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
