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
 * <p><b>Tuplets are carried, not inferred.</b> {@link #toLilyPond(Score,
 * NoteTrack)} engraves what a plain {@code Score} can say, and a {@code Score}
 * cannot say that a bar is in triplets — three onsets a third of a beat apart
 * and three a half beat apart are both legal on the sixth-of-a-beat grid.
 * {@link #toLilyPond(QuantizedScore, NoteTrack)} takes the quantizer's own
 * per-bar decision and brackets those bars; the {@code Score} overload snaps
 * them onto the 64th-note grid as it always did, because guessing is worse. See
 * #92 and {@link TupletBar}.
 *
 * <p>Known limitations, each with an issue: overlapping notes are reduced to
 * block chords rather than split into voices (#93), a key change part-way
 * through is not engraved (#94), and percussion parts are refused (#95).
 */
public final class StaffNotation {

    /** The LilyPond language version the emitted source declares. */
    static final String LILYPOND_VERSION = "2.24.0";

    /** The grid all positions and lengths are snapped onto. */
    private static final double GRID = LilyPondDuration.SHORTEST_QUARTERS;

    /**
     * A ceiling on how many bars are emitted, so that a score whose beat axis is
     * wrong produces a complaint rather than a file nobody can use.
     *
     * <p>Set by what can be <em>engraved</em> rather than by what can be
     * recorded, because that is the smaller number by a long way. Emitting is
     * cheap; engraving is not, and it is not linear either. Measured with
     * LilyPond 2.26 on a 16-core machine, on a four-minute song plus one note
     * quantized to the far end — the shape of #113, which is the case that
     * actually reaches this ceiling:
     *
     * <pre>
     *   1,000 bars     3.4 s    0.4 GB
     *   2,000 bars    10.2 s    1.4 GB
     *   4,000 bars  37-43   s    5.0 GB
     *   8,000 bars   380   s   19.3 GB
     * </pre>
     *
     * <p>Superlinear throughout and steeply so at the top: each doubling costs
     * between two and four times the last, and the final one costs nine. The
     * memory is what decides the number rather than the seconds: 19 GB is not a slow run on an ordinary laptop, it is a swap
     * storm or an OOM kill, which is worse than the hang the ceiling exists to
     * bound and does not get better on a faster machine. {@value} bars is about a
     * minute and five gigabytes.
     *
     * <p>These are measurements rather than an extrapolation, which is worth
     * saying because the figure here was an extrapolation twice: once wrong by
     * sixteen times and once by six, each written as though it had been run.
     *
     * <p>In musical terms: {@value} bars of 4/4 at 120 BPM is about two and a
     * quarter hours, far past anything this tool is aimed at.
     * {@code theBarCeilingIsTheLengthItClaims} checks that arithmetic — the
     * engraving table above needs LilyPond and so cannot be checked by a unit
     * test, but the musical figure can be, and an unchecked figure that is the
     * argument for a number is a number nobody can evaluate.
     *
     * <p>It is deliberately not a defence against the outlier of #113: a note
     * quantized a hundred bars late is nowhere near this, and clamping the score
     * to catch it was tried twice and was wrong both times — see
     * {@link #musicSpan}.
     */
    static final int MAX_BARS = 4_000;

    private StaffNotation() {
    }

    /**
     * A complete LilyPond document engraving one part.
     *
     * <p>Tuplets are not represented: a plain {@link Score} does not carry the
     * grid its notes were snapped to, so a triplet arrives here as three onsets
     * a third of a beat apart and is written on the 64th-note grid, which fills
     * the bar and is not the rhythm that was played. Pass the {@link
     * QuantizedScore} instead where there is one.
     *
     * @throws IllegalArgumentException if the track is percussion, or holds a
     *         note that has not been quantized
     */
    public static String toLilyPond(Score score, NoteTrack track) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(track, "track");
        return document(score, track, TupletPlan.none());
    }

    /**
     * The same, with the quantizer's per-bar grid honoured.
     *
     * <p>The one difference is the bars the quantizer put on a tuplet grid:
     * those are engraved as {@code \tuplet} brackets rather than snapped onto
     * the 64th-note grid. Everything else — bar lines, pickup, ties, spelling,
     * the score-wide span — is decided exactly as the {@link #toLilyPond(Score,
     * NoteTrack)} overload decides it, on the same {@link
     * QuantizedScore#score()}.
     *
     * @throws IllegalArgumentException if the track is percussion, or holds a
     *         note that has not been quantized
     */
    public static String toLilyPond(QuantizedScore quantized, NoteTrack track) {
        Objects.requireNonNull(quantized, "quantized");
        Objects.requireNonNull(track, "track");
        return document(quantized.score(), track, TupletPlan.of(quantized));
    }

    private static String document(Score score, NoteTrack track, TupletPlan tuplets) {
        StringBuilder out = new StringBuilder();
        out.append("\\version \"").append(LILYPOND_VERSION).append("\"\n\n");
        out.append("\\header {\n");
        out.append("  title = \"").append(escape(score.title().orElse("Untitled"))).append("\"\n");
        score.artist().ifPresent(artist ->
                out.append("  composer = \"").append(escape(artist)).append("\"\n"));
        out.append("  tagline = ##f\n");
        out.append("}\n\n");
        out.append("\\score {\n");
        out.append(staffBlock(score, track, tuplets));
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
        return staffBlock(score, track, TupletPlan.none());
    }

    /** The same, with the quantizer's per-bar grid honoured. */
    static String staffBlock(QuantizedScore quantized, NoteTrack track) {
        Objects.requireNonNull(quantized, "quantized");
        Objects.requireNonNull(track, "track");
        return staffBlock(quantized.score(), track, TupletPlan.of(quantized));
    }

    private static String staffBlock(Score score, NoteTrack track, TupletPlan tuplets) {
        if (!canEngrave(track.role())) {
            throw new IllegalArgumentException(
                    "percussion parts need a DrumStaff, which this does not emit; see #95");
        }
        List<Event> events = eventsOf(score, track, tuplets);
        Span music = musicSpan(score, track, events, tuplets);

        StringBuilder out = new StringBuilder();
        out.append("  \\new Staff \\with { instrumentName = \"")
                .append(escape(track.name())).append("\" } {\n");
        out.append("    \\clef \"").append(clefOf(track.role())).append("\"\n");
        out.append("    \\key ").append(keyOf(score)).append('\n');
        appendBars(out, score, track.name(), events, music, tuplets);
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
    private static List<Event> eventsOf(Score score, NoteTrack track, TupletPlan tuplets) {
        // Validated here rather than at the call site so that the rule holds for
        // every caller: musicSpan reaches this for the score's other parts too,
        // and it filters them with isEngravable rather than relying on this.
        requireQuantized(track);
        boolean flatKey = score.primaryKey().map(Key::isFlatKey).orElse(false);

        // Keyed by onset so simultaneous notes gather, ordered so the sweep that
        // resolves overlaps below runs in time order.
        Map<Double, List<Note>> byOnset = new TreeMap<>();
        for (Note note : track.notes()) {
            double onset = snap(tuplets, note.onsetBeat().orElseThrow());
            double offset = snap(tuplets, note.offsetBeat().orElseThrow());
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
            // Deduplicated by written name. Two transcribed notes at the same
            // pitch print as a unison chord, which is meaningless on a staff and
            // which LilyPond will engrave without complaint -- I checked, on
            // 2.26; there is no warning to lean on. Notes at the same sounding
            // pitch spelled differently still survive this and come out as
            // <cis' des'>, which is a spelling disagreement the pipeline should
            // have resolved upstream rather than something to hide here.
            Map<String, PitchSpelling> pitches = new LinkedHashMap<>();
            for (Note note : entry.getValue()) {
                end = Math.max(end, snap(tuplets, note.offsetBeat().orElseThrow()));
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
     * Where the music begins and ends, in quarter-note beats.
     *
     * @param startBeat where the music begins, which is a pickup when it falls
     *                  inside the opening bar and zero otherwise
     * @param endBeat   where the last engravable part stops sounding
     * @param endedBy   the part that stops last, named so that a complaint about
     *                  the length points at the part responsible for it
     * @param endedByAnotherPart whether that part is one other than the one being
     *                  engraved — carried rather than re-derived by comparing
     *                  names, because two tracks in different roles may share a
     *                  name and a piano's two hands routinely do
     */
    private record Span(double startBeat, double endBeat, String endedBy,
                        boolean endedByAnotherPart) {
    }

    /**
     * The stretch of beats every staff of this score is barred across.
     *
     * <p>A piece whose first note is not on beat one has a pickup, and a pickup
     * is a short bar rather than a bar of rests: written as rests, every bar line
     * after it lands in the wrong place relative to what a reader counts. This is
     * the normal case for song melodies, not an edge case.
     *
     * <p>Both ends are taken across the whole <em>score</em> rather than across
     * this track, so that parts engraved separately share a bar grid. A bass that
     * enters in bar four does not get its own pickup, and one that stops playing
     * in bar four does not get its own final bar line — it rests to the end like
     * a real part, instead of the staff simply stopping. The track being engraved
     * is included whether or not the score holds it, so that engraving a track
     * the caller built by hand does not silently lose either end.
     *
     * <p><b>This is a function of the score and not of which part is being
     * engraved</b>, and that is the property to preserve if it is ever changed.
     * Round 7 found the start score-wide and the end track-local, so a part that
     * dropped out early ended its staff mid-system. Round 8 clamped other parts'
     * ends to the length of the recording, to stop one badly quantized note
     * padding every staff — and round 9 found that clamp both inert at ordinary
     * song lengths, where a stray note at bar 101 is still inside a four-minute
     * recording, and harmful, because clamping other parts while never clamping
     * this one made the answer depend on which part you asked, which is round 7's
     * defect again. The clamp is gone.
     *
     * <p>So a note quantized a hundred bars late does lengthen every staff in the
     * score, and that is deliberate: the score says the piece is a hundred bars
     * long, every part agrees about it, and the page shows plainly that something
     * is wrong. Deciding that such a note is spurious needs the evidence that it
     * is a lone event separated from its own part's music — which is a
     * quantization judgement, made where the note is placed rather than where it
     * is drawn. See #113.
     *
     * <p>Parts this emitter cannot engrave do not get a vote. A drum count-in
     * before beat one is the most ordinary thing a percussion track contains, and
     * it gave every visible staff a pickup that nothing on the page justified.
     * Nor does a part that is only half quantized: {@link #staffBlock} refuses to
     * engrave one, and a part that cannot be on the page must not move the bar
     * lines of the parts that are.
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
    private static Span musicSpan(Score score, NoteTrack engraved, List<Event> events,
                                  TupletPlan tuplets) {
        // The last event ends last: overlaps were resolved by truncating the
        // earlier of the pair, so ends increase with onsets.
        double earliest = events.isEmpty()
                ? Double.POSITIVE_INFINITY : events.getFirst().startBeat();
        double latest = events.isEmpty() ? 0 : events.getLast().endBeat();
        String endedBy = engraved.name();
        boolean endedByAnotherPart = false;
        for (NoteTrack track : score.tracks()) {
            if (track.equals(engraved) || !isEngravable(track)) {
                continue;
            }
            List<Event> other = eventsOf(score, track, tuplets);
            if (!other.isEmpty()) {
                earliest = Math.min(earliest, other.getFirst().startBeat());
                if (other.getLast().endBeat() > latest) {
                    latest = other.getLast().endBeat();
                    endedBy = track.name();
                    endedByAnotherPart = true;
                }
            }
        }
        double firstBar = score.tempoMap().timeSignatureAtBar(0).quarterBeatsPerBar();
        // Only a gap inside the opening bar is a pickup. Music that starts in bar
        // two starts after a bar of rest, and saying otherwise would move every
        // bar line in the piece.
        boolean pickup = Double.isFinite(earliest) && earliest > 0 && earliest < firstBar;
        return new Span(pickup ? earliest : 0, latest, endedBy, endedByAnotherPart);
    }

    /**
     * Whether a part of the score may set the bar grid.
     *
     * <p>Exactly the parts {@link #staffBlock} will engrave, which is the point:
     * it refuses on two grounds and only the role half was shared at first, so a
     * half-quantized track it would have rejected outright was still voting on
     * where the bar lines fall, with whichever of its notes happened to carry a
     * position.
     */
    private static boolean isEngravable(NoteTrack track) {
        return canEngrave(track.role()) && track.isQuantized();
    }

    /**
     * Whether this emitter can put a part in this role on a staff.
     *
     * <p>One predicate rather than two, because {@link #staffBlock} refusing a
     * role while {@link #musicSpan} counts it is how a part that can never be on
     * the page came to move the bar lines of the parts that are.
     */
    private static boolean canEngrave(PartRole role) {
        return role != PartRole.DRUMS;
    }

    private static void appendBars(StringBuilder out, Score score, String name,
                                   List<Event> events, Span music, TupletPlan tuplets) {
        double pickupStart = music.startBeat();
        double musicEnd = music.endBeat();

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
            TupletBar tupletBar = tuplets.atBar(bar).orElse(null);
            double contentStart = Math.max(barStart, bar == 0 ? pickupStart : 0);
            if (bar == 0 && pickupStart > 0) {
                // Through the grid when there is one. A pickup entering two
                // triplet eighths before the bar line is two thirds of a quarter
                // beat, which is not a whole number of 64ths and which the
                // length-only form therefore refuses -- so the emitter would
                // throw on exactly the bar it had just learned to write.
                out.append("    \\partial ")
                        .append(tupletBar == null
                                ? LilyPondDuration.scaled(barEnd - pickupStart)
                                : tupletBar.scaledLengthToBarLine(tupletBar.stepAt(pickupStart)))
                        .append('\n');
            }
            eventIndex = appendBar(out, meter, barStart, barEnd, contentStart, events, eventIndex,
                    tupletBar);
            barStart = barEnd;
            bar++;
            // One bar minimum, so an empty part still engraves as a staff rather
            // than as a syntax error.
            if (barStart >= musicEnd) {
                return;
            }
        }
        // Named rather than diagnosed: which part runs that far is a fact, and
        // where to look for the cause follows from it only when that part is not
        // this one. Saying "it is in that part rather than this one" of a
        // single-part score, which is what the pipeline produces today, sent the
        // reader somewhere else entirely.
        String culprit = music.endedByAnotherPart()
                ? "\"" + music.endedBy() + "\" runs that far, so look there rather than here"
                : "\"" + music.endedBy() + "\" itself runs that far";
        throw new IllegalStateException(
                "engraving \"" + name + "\" needs more than " + MAX_BARS + " bars, because "
                        + culprit + "; if the recording is not that long, the beat axis is"
                        + " wrong -- one note quantized to a beat far past the end will do it");
    }

    /**
     * A stretch of one bar carrying one thing: a chord, a note, or a rest when
     * {@code pitches} is empty.
     *
     * <p>An {@link Event} cut to the bar. The two differ in exactly one way and
     * it is the reason this exists: an event may run past the bar line, and
     * {@code tiedOut} is what remembers that after the cut, so a piece can be
     * cut again — at a tuplet bracket — without losing the fact that the note
     * continues.
     */
    private record Piece(double from, double to, List<String> pitches, boolean tiedOut) {
    }

    /**
     * Fills one bar and returns the index of the first event it did not finish.
     *
     * <p>The bar is filled from {@code contentStart} to {@code barEnd} with no
     * gaps by construction: every step either writes an event or writes the rest
     * that precedes it.
     *
     * @param tuplets the bar's tuplet grid, or {@code null} when it has none,
     *                which is every bar of a score engraved from a plain
     *                {@link Score}
     */
    private static int appendBar(StringBuilder out, TimeSignature meter, double barStart,
                                 double barEnd, double contentStart, List<Event> events,
                                 int firstEvent, TupletBar tuplets) {
        List<Piece> pieces = new ArrayList<>();
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
                pieces.add(new Piece(position, from, List.of(), false));
            }
            double to = Math.min(event.endBeat(), barEnd);
            pieces.add(new Piece(from, to, event.pitches(), event.endBeat() > barEnd));
            position = to;
            if (event.endBeat() > barEnd) {
                break;
            }
            index++;
        }

        List<String> tokens = new ArrayList<>();
        if (position < barEnd) {
            if (pieces.isEmpty() && contentStart == barStart) {
                // A bar nobody plays in shows one rest covering it, whatever the
                // meter. That is what a whole rest means, and a 3/4 bar of silence
                // is not a dotted half rest. No bracket either: a bar of silence
                // has no rhythm to bracket, and the quantizer does not publish a
                // grid for one.
                out.append("    ").append("R")
                        .append(LilyPondDuration.scaled(barEnd - barStart)).append(" |\n");
                return index;
            }
            pieces.add(new Piece(position, barEnd, List.of(), false));
        }

        if (tuplets == null) {
            for (Piece piece : pieces) {
                appendValues(tokens, meter, barStart, piece.from(), piece.to(), piece.pitches(),
                        piece.tiedOut());
            }
        } else {
            appendTupletBar(tokens, meter, barStart, tuplets, pieces);
        }
        out.append("    ").append(String.join(" ", tokens)).append(" |\n");
        return index;
    }

    // ---------------------------------------------------------------- tuplets

    /**
     * Fills a bar the quantizer divided into tuplets.
     *
     * <p><b>A bracket is written only where something is happening inside it.</b>
     * A triplet bracket exists to say that a beat was divided in three, so a beat
     * held by one note is a plain quarter and two such beats are a plain half —
     * not {@code \tuplet 3/2 { c4. } \tuplet 3/2 { c4.~ }}, which is the same
     * music, correct arithmetic, and unreadable. So a bracket whose span carries
     * no boundary of its own is left off, and the run of bar it belongs to goes
     * to {@link MetricSplitter} exactly as an untuplet-ed bar would. This is
     * what keeps a mostly-held triplet bar looking like the music rather than
     * like the grid it was measured against.
     *
     * <p>Everything is decided in whole grid steps. That is not tidiness: a
     * third of a beat is not a representable double and the positions arriving
     * here were built by adding lengths to onsets, so comparing them against
     * bracket boundaries computed by multiplication would miss by an ulp exactly
     * where it matters most. See {@link TupletBar}.
     */
    private static void appendTupletBar(List<String> tokens, TimeSignature meter, double barStart,
                                        TupletBar tuplets, List<Piece> pieces) {
        // Starts only. The pieces cover the bar end to end -- each one begins
        // where the last stopped, the first at the content start and the last at
        // the bar line -- so every boundary in the bar is some piece's start,
        // except the bar line itself, which is a bracket boundary and marks
        // nothing. Marking the ends as well was byte-for-byte inert.
        boolean[] bracketed = new boolean[tuplets.brackets()];
        for (Piece piece : pieces) {
            markBracket(bracketed, tuplets, piece.from());
        }

        int open = -1;
        for (Piece piece : pieces) {
            List<int[]> fragments = fragmentsOf(tuplets, bracketed,
                    tuplets.stepAt(piece.from()), tuplets.stepAt(piece.to()));
            for (int i = 0; i < fragments.size(); i++) {
                int[] fragment = fragments.get(i);
                // Every fragment but the last continues into the next one, and
                // the last continues if the piece itself did.
                boolean tie = i + 1 < fragments.size() || piece.tiedOut();
                int bracket = tuplets.bracketOf(fragment[0]);
                if (bracketed[bracket]) {
                    if (open != bracket) {
                        closeBracket(tokens, open);
                        tokens.add("\\tuplet " + tuplets.ratio() + " {");
                        open = bracket;
                    }
                    tokens.add(head(piece.pitches())
                            + value(tuplets.writtenLength(fragment[1] - fragment[0]))
                            + (!piece.pitches().isEmpty() && tie ? "~" : ""));
                } else {
                    closeBracket(tokens, open);
                    open = -1;
                    appendValues(tokens, meter, barStart, tuplets.beatOfStep(fragment[0]),
                            tuplets.beatOfStep(fragment[1]), piece.pitches(), tie);
                }
            }
        }
        closeBracket(tokens, open);
    }

    /**
     * Marks the bracket a position falls strictly inside, which is what makes it
     * a bracket worth printing.
     *
     * <p>A position on a bracket boundary marks nothing: it is where a bracket
     * would begin or end anyway, so it is no evidence that the bracket is
     * subdivided.
     */
    private static void markBracket(boolean[] bracketed, TupletBar tuplets, double beat) {
        int step = tuplets.stepAt(beat);
        if (step % tuplets.stepsPerBracket() != 0) {
            bracketed[tuplets.bracketOf(step)] = true;
        }
    }

    /**
     * A span of grid steps cut where a printed bracket begins or ends, as
     * {@code [from, to)} step pairs.
     *
     * <p>Cut at a bracket boundary only when a bracket is actually printed on
     * one side of it. A note held across two plain beats of a triplet bar is
     * therefore one fragment and comes out as a half note; cutting it at every
     * boundary would give two tied quarters and say nothing extra.
     */
    private static List<int[]> fragmentsOf(TupletBar tuplets, boolean[] bracketed,
                                           int fromStep, int toStep) {
        List<int[]> fragments = new ArrayList<>();
        int start = fromStep;
        for (int step = fromStep + 1; step < toStep; step++) {
            if (step % tuplets.stepsPerBracket() != 0) {
                continue;
            }
            // A boundary strictly inside the span, so there is a bracket on both
            // sides of it and neither index needs bounding.
            int after = tuplets.bracketOf(step);
            if (bracketed[after - 1] || bracketed[after]) {
                fragments.add(new int[] {start, step});
                start = step;
            }
        }
        fragments.add(new int[] {start, toStep});
        return fragments;
    }

    private static void closeBracket(List<String> tokens, int open) {
        if (open >= 0) {
            tokens.add("}");
        }
    }

    /**
     * Writes one span as note values, tying the pieces together.
     *
     * <p>The tie between pieces is not a musical decision — it is one note the
     * meter forced into several symbols — so it is added here rather than left to
     * the caller. {@code tieOut} adds the further tie that carries the note over
     * the bar line, or on to the next tuplet bracket.
     */
    private static void appendValues(List<String> tokens, TimeSignature meter, double barStart,
                                     double from, double to, List<String> pitches,
                                     boolean tieOut) {
        List<String> values = MetricSplitter.split(meter, from - barStart, to - barStart);
        String head = head(pitches);
        for (int i = 0; i < values.size(); i++) {
            boolean last = i == values.size() - 1;
            boolean tie = !pitches.isEmpty() && (!last || tieOut);
            tokens.add(head + values.get(i) + (tie ? "~" : ""));
        }
    }

    /** What a token is written on: a rest, a pitch, or a chord. */
    private static String head(List<String> pitches) {
        return pitches.isEmpty() ? "r"
                : pitches.size() == 1 ? pitches.get(0)
                : "<" + String.join(" ", pitches) + ">";
    }

    /**
     * The note value of a written length inside a bracket.
     *
     * <p>There is always one — see {@link TupletBar#writtenLength}. The throw is
     * a claim about that arithmetic rather than a case to handle, and
     * {@code TupletBarTest} checks the claim over every meter and grid.
     */
    private static String value(double writtenQuarters) {
        return LilyPondDuration.of(writtenQuarters).orElseThrow(() -> new IllegalStateException(
                "no note value for " + writtenQuarters + " quarter beats inside a tuplet"));
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

    /**
     * Rounds onto the grid the bar is written on, which is what makes every span
     * writable.
     *
     * <p>The 64th-note grid everywhere except in a bar the quantizer divided
     * into tuplets, where a third of a beat is not a whole number of 64ths and
     * rounding it onto them is the whole of #92. There the bar's own grid is
     * used, which is idempotent on the positions the quantizer produced and puts
     * a position built by addition back onto the position the same grid would
     * have reached by multiplication — see {@link TupletBar}.
     */
    private static double snap(TupletPlan tuplets, double beats) {
        Optional<TupletBar> bar = tuplets.covering(beats);
        if (bar.isPresent()) {
            return bar.get().beatOfStep(bar.get().stepAt(beats));
        }
        return Math.round(beats / GRID) * GRID;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
