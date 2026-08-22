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

package dev.olivelli.musicwizard.arrange;

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reduces an estimated melody to a part a player can read.
 *
 * <p><b>Synthesis, not transcription</b> (#592), the frame #10 sets for the
 * piano reduction. The melody staff answers what the singer did and this
 * answers what a pianist should read, so the result is a part <em>beside</em>
 * the estimate and never a replacement for it: the melody baselines score the
 * estimate, and a simplification that reached them would leave them measuring
 * an arrangement of MW's own output.
 *
 * <p>Notes are grouped and each group prints as one note. A sung syllable is
 * the grouping evidence wherever there is one, since a syllable carries one
 * note — unless some stage has marked it a melisma (#597), which says the
 * melody moves under it and its run is to be printed. Everything else is
 * grouped by the weaker rule that an ornament belongs to the note it leads
 * into, and so is a melisma's own run. The printed pitch is the one the group
 * <em>settles</em> on rather than the one it sounds longest — over a scoop
 * those differ, and it is the arrival that was sung.
 *
 * <p>Chord tones only separate candidates the group's own evidence leaves
 * level, and only under a chart span this trusts. Rounding a melody to the
 * harmony was built, swept and rejected (#571): the errors are not marginal,
 * so a snap yields a confidently wrong note rather than a recovered one. What
 * the harmony does decide, once the heads are chosen, is where the line went
 * briefly somewhere neither the chord nor the key signature admits and came
 * straight back (#670) — an excursion rather than a note, printed as the pitch
 * it returned to.
 */
public final class PlayableMelody {

    /** The staff name the reduced part is engraved under. */
    public static final String TRACK_NAME = "Voice (playable)";

    /**
     * How long a note may be, in quarter-note beats, and still be read as an
     * ornament of the note after it rather than as a note of its own.
     *
     * <p>Strictly below the triplet eighth, which is the shortest value this
     * expects a reader to want: anything finer inside a single beat is
     * decoration. Public because {@code tools/PlayablePartCheck.java} sweeps
     * around it, so the shipped bound has one writer.
     */
    public static final double ORNAMENT_BEATS = 1.0 / 3;

    /** What fraction of the note it leads into an ornament may last. */
    private static final double ORNAMENT_RATIO = 0.5;

    /**
     * The share of the group's dominant pitch that the pitch it ends on must
     * itself sound for the group to count as having settled unaided.
     *
     * <p>The pitch's total across the group, not the last note's own length: a
     * group that leaves a pitch and returns to it has settled on it twice.
     */
    private static final double SETTLED_SHARE = 0.75;

    /** How far the chart has to be trusted before a chord tone may break a tie. */
    private static final double CHART_CONFIDENCE_FLOOR = 0.5;

    /**
     * How much silence there may be between a note and a syllable, in
     * quarter-note beats, for the note to have been sung on it.
     *
     * <p>Silence between the two spans rather than distance between their
     * starts: a note sounding under a syllable is at no distance from it,
     * however late in it the note begins (#598). Swept by
     * {@code tools/PlayablePartCheck.java}, which also reads this value so
     * the shipped bound has one writer.
     */
    public static final double CLAIM_BEATS = 2.0;

    /**
     * What {@link #reduce(Score)} decides an excursion by;
     * {@code tools/PlayablePartCheck.java} sweeps around it, so the shipped
     * bounds have one writer.
     */
    public static final Excursion EXCURSION = new Excursion(1.0, 2, 4);

    /** The degrees of each mode's key signature, in semitones above the tonic. */
    private static final int[] MAJOR_DEGREES = {0, 2, 4, 5, 7, 9, 11};

    private static final int[] MINOR_DEGREES = {0, 2, 3, 5, 7, 8, 10};

    /** No syllable claims this note. */
    private static final long UNSUNG = Long.MIN_VALUE;

    private static final double EPSILON = 1e-9;

    private PlayableMelody() {
    }

    /**
     * The three bounds {@link #reduce(Score)} reads a head as an excursion by
     * (#670).
     *
     * @param beats how long a head nothing in the harmony accounts for may
     *              last and still be an excursion rather than a note held on
     *              purpose, in quarter-note beats
     * @param returnSemitones how near the supported heads on either side must
     *              be to each other for the melody to have returned to where
     *              it left
     * @param departureSemitones how far the head may be from the pitch it
     *              would take. Past this the line did not wobble, it went
     *              somewhere, and printing what was heard is the honest answer
     *              even where the harmony refuses it
     */
    public record Excursion(double beats, int returnSemitones, int departureSemitones) {

        /**
         * Bounds that refuse nothing, so the part prints as its heads were
         * chosen.
         *
         * <p>Named once because which of the three zeros does it is not
         * guessable: a return bound of zero is the rule's strictest setting,
         * asking that the line come back to the very pitch it left.
         */
        public static final Excursion NONE = new Excursion(0, 0, 0);

        public Excursion {
            if (!Double.isFinite(beats)) {
                throw new IllegalArgumentException("beats must be finite, got: " + beats);
            }
        }
    }

    /**
     * The score's melody, reduced to one note per sung syllable.
     *
     * <p>The returned track carries the melody role, so a caller engraving it
     * hands it to the staff writer in place of the estimate. Nothing here
     * touches the score it was read from.
     *
     * @throws IllegalArgumentException if the score holds no melody part
     */
    public static NoteTrack reduce(Score score) {
        return reduce(score, CLAIM_BEATS);
    }

    /**
     * The same at a chosen claim bound, which is what
     * {@code tools/PlayablePartCheck.java} sweeps.
     *
     * @param claimBeats how much silence there may be between a note and a
     *                   syllable for the note to be sung on it, in quarter-note
     *                   beats
     * @throws IllegalArgumentException if the score holds no melody part, or
     *                                  the bound is not a finite number
     */
    public static NoteTrack reduce(Score score, double claimBeats) {
        return reduce(score, claimBeats, ORNAMENT_BEATS);
    }

    /**
     * The same at a chosen ornament bound too, for the sweep the double-rate
     * question needs (#595): the bound is stated in beats, and on a grid
     * tracked at double rate (#378) only moving it can say what the rate
     * costs.
     *
     * @param ornamentBeats how long a note may be, in quarter-note beats, and
     *                      still be absorbed as an ornament of the note after
     *                      it
     * @throws IllegalArgumentException if the score holds no melody part, or
     *                                  either bound is not a finite number
     */
    public static NoteTrack reduce(Score score, double claimBeats, double ornamentBeats) {
        return reduce(score, claimBeats, ornamentBeats, EXCURSION);
    }

    /**
     * The same at chosen excursion bounds too, which is the other thing
     * {@code tools/PlayablePartCheck.java} sweeps (#670).
     *
     * @param excursion what a head is read as an excursion by
     * @throws IllegalArgumentException if the score holds no melody part, or
     *                                  either bound is not a finite number
     */
    public static NoteTrack reduce(Score score, double claimBeats, double ornamentBeats,
                                   Excursion excursion) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(excursion, "excursion");
        if (!Double.isFinite(claimBeats) || !Double.isFinite(ornamentBeats)) {
            throw new IllegalArgumentException("the bounds must be finite, got: "
                    + claimBeats + " and " + ornamentBeats);
        }
        NoteTrack melody = score.track(PartRole.LEAD_VOCAL).orElseThrow(
                () -> new IllegalArgumentException("the score holds no melody to reduce"));
        TempoMap map = score.tempoMap();
        List<Piece> pieces = new ArrayList<>(melody.size());
        for (Note note : melody.notes()) {
            pieces.add(Piece.of(note, map));
        }
        List<Note> reduced = new ArrayList<>(pieces.size());
        Set<Long> opened = new HashSet<>();
        for (Sung group : groups(pieces, score.lyrics(), map, claimBeats, ornamentBeats)) {
            boolean opens = group.syllable() != UNSUNG && opened.add(group.syllable());
            reduced.add(printed(group.pieces(),
                    opens ? wordOf(score.lyrics(), group.syllable()) : null,
                    score.chords(), map));
        }
        return new NoteTrack(PartRole.LEAD_VOCAL, TRACK_NAME,
                returned(reduced, score, excursion), melody.confidence());
    }

    /** One syllable of the lyrics and the note-heads it prints when it is a melisma. */
    public record SungSyllable(int line, int word, List<Note> heads) {
    }

    /**
     * Every syllable some note is claimed for, with the note-heads
     * {@link #reduce} prints for it once it is marked a melisma.
     *
     * <p>Built by the machinery the reduction uses, so a stage deciding what
     * a syllable holds (#597) reads the heads marking it produces rather than
     * a prediction of them. Public for {@code tools/PlayablePartCheck.java},
     * which reads the claims off it the way the melisma sweep reads the
     * marks.
     */
    public static List<SungSyllable> sungSyllables(Score score) {
        Objects.requireNonNull(score, "score");
        Optional<NoteTrack> melody = score.track(PartRole.LEAD_VOCAL);
        if (melody.isEmpty()) {
            return List.of();
        }
        TempoMap map = score.tempoMap();
        List<Piece> pieces = new ArrayList<>(melody.get().size());
        for (Note note : melody.get().notes()) {
            pieces.add(Piece.of(note, map));
        }
        long[] claimed = syllableOf(pieces, score.lyrics(), map, CLAIM_BEATS);
        Map<Long, List<Note>> bySyllable = new LinkedHashMap<>();
        int from = 0;
        while (from < pieces.size()) {
            int to = from;
            while (to + 1 < pieces.size() && claimed[to + 1] == claimed[from]) {
                to++;
            }
            if (claimed[from] != UNSUNG) {
                List<Note> heads = bySyllable.computeIfAbsent(claimed[from],
                        key -> new ArrayList<>());
                for (List<Piece> group : ornamentGroups(pieces.subList(from, to + 1),
                        ORNAMENT_BEATS)) {
                    heads.add(printed(group, heads.isEmpty()
                                    ? wordOf(score.lyrics(), claimed[from]) : null,
                            score.chords(), map));
                }
            }
            from = to + 1;
        }
        List<SungSyllable> out = new ArrayList<>(bySyllable.size());
        for (var entry : bySyllable.entrySet()) {
            out.add(new SungSyllable((int) (entry.getKey() >> 32), (int) (long) entry.getKey(),
                    List.copyOf(entry.getValue())));
        }
        return List.copyOf(out);
    }

    /**
     * One note on the beat axis.
     *
     * <p>Every comparison this class makes is in quarter-note beats, so a note
     * that has not been quantized is converted once, here, through the tempo
     * map — which is the only sanctioned conversion — rather than compared in
     * seconds against a syllable or a chord that was converted separately.
     */
    private record Piece(Note note, double startBeat, double endBeat) {

        static Piece of(Note note, TempoMap map) {
            return new Piece(note,
                    note.onsetBeat().orElseGet(() -> map.secondsToBeats(note.onsetSeconds())),
                    note.offsetBeat().orElseGet(() -> map.secondsToBeats(note.offsetSeconds())));
        }

        double durationBeats() {
            return endBeat - startBeat;
        }

        int pitch() {
            return note.midiPitch();
        }
    }

    /** One group of pieces and the syllable that claims it, {@link #UNSUNG} for none. */
    private record Sung(List<Piece> pieces, long syllable) {
    }

    private static List<Sung> groups(List<Piece> pieces, Lyrics lyrics, TempoMap map,
                                     double claimBeats, double ornamentBeats) {
        long[] syllable = syllableOf(pieces, lyrics, map, claimBeats);
        List<Sung> groups = new ArrayList<>();
        int from = 0;
        while (from < pieces.size()) {
            int to = from;
            while (to + 1 < pieces.size() && syllable[to + 1] == syllable[from]) {
                to++;
            }
            List<Piece> run = pieces.subList(from, to + 1);
            if (syllable[from] != UNSUNG && !isMelisma(lyrics, syllable[from])) {
                groups.add(new Sung(run, syllable[from]));
            } else {
                for (List<Piece> group : ornamentGroups(run, ornamentBeats)) {
                    groups.add(new Sung(group, syllable[from]));
                }
            }
            from = to + 1;
        }
        return groups;
    }

    /**
     * The run split into the note-heads the ornament rule leaves: an ornament
     * joins the note it leads into, and anything else stands on its own.
     */
    private static List<List<Piece>> ornamentGroups(List<Piece> run, double ornamentBeats) {
        List<List<Piece>> groups = new ArrayList<>();
        int from = 0;
        while (from < run.size()) {
            int to = from;
            while (to + 1 < run.size() && leadsInto(run.get(to), run.get(to + 1), ornamentBeats)) {
                to++;
            }
            groups.add(run.subList(from, to + 1));
            from = to + 1;
        }
        return groups;
    }

    private static boolean isMelisma(Lyrics lyrics, long syllable) {
        return wordOf(lyrics, syllable).melisma();
    }

    private static LyricWord wordOf(Lyrics lyrics, long syllable) {
        return lyrics.lines().get((int) (syllable >> 32)).words().get((int) syllable);
    }

    /**
     * The group's printed note, its onset taken from the syllable it opens
     * (#616).
     *
     * <p>The group's first piece is usually the start of a scoop, so a head
     * printed from it sits where the approach began; the aligner's syllable
     * start is a measurement, on the voice itself, of where the same event is
     * felt. Groups that do not open their syllable — later heads of a
     * melisma, notes no syllable claims — keep the melody's own onsets, and
     * {@link #feltInside} bounds when an opening group's is taken.
     */
    private static Note printed(List<Piece> group, LyricWord sungOn,
                                ChordProgression chords, TempoMap map) {
        Note note = collapse(group, chords, map);
        if (sungOn == null) {
            return note;
        }
        double startSeconds = sungOn.startSeconds();
        // Both ends of the moved head go through the one sanctioned
        // conversion: a word's startBeat and a quantized note's offsetBeat
        // are snapped values, and pairing either with the raw seconds would
        // give a head, or a stub guard, whose two axes name different spans.
        double startBeat = map.secondsToBeats(startSeconds);
        double endBeat = map.secondsToBeats(note.offsetSeconds());
        if (!feltInside(group, note, startSeconds, startBeat, endBeat)) {
            return note;
        }
        Note moved = new Note(startSeconds, note.offsetSeconds() - startSeconds,
                note.midiPitch(), note.velocity(), note.spelling(),
                Optional.empty(), Optional.empty(), note.confidence());
        return note.isQuantized()
                ? moved.quantizedTo(startBeat, endBeat - startBeat)
                : moved;
    }

    /**
     * Whether the syllable's measured start is a moment the printed head can
     * open on.
     *
     * <p>Three refusals, each a way the two measurements can disagree rather
     * than re-measure one event. A start at or before the group's onset has
     * nothing to move. A start leaving less of the head than what this class
     * already discards as decoration is not the felt beginning of the group —
     * it is a claim from an onset placed late (#497) or from a syllable
     * apportioned rather than measured, and taking it would manufacture a
     * stub the reading quantizer prints on the next head's beat. And a start
     * in the silence between the group's pieces, or outside them, would print
     * the head where the melody holds nothing.
     *
     * <p>Bounds rather than a gate on measured lines, because which lines the
     * aligner measured is known only to the run that aligned them — the same
     * missing fact as #623 — so an apportioned start cannot be told from a
     * measured one here. What these bounds leave it able to do is shift a
     * head within the notes its own syllable sounds.
     */
    private static boolean feltInside(List<Piece> group, Note printed,
                                      double startSeconds, double startBeat, double endBeat) {
        if (startSeconds <= printed.onsetSeconds() + EPSILON) {
            return false;
        }
        if (endBeat - startBeat <= ORNAMENT_BEATS) {
            return false;
        }
        for (Piece piece : group) {
            if (startSeconds >= piece.note().onsetSeconds() - EPSILON
                    && startSeconds < piece.note().offsetSeconds() - EPSILON) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the first note is an ornament of the second.
     *
     * <p>The grouping evidence left when there are no words, and deliberately
     * the weaker one: what says where a sung gesture begins on a recording
     * nobody has written words for is the onset envelope, which is audio and
     * cannot be read here. So this claims only what a reader could not have
     * played separately — a note too short to be worth a note-head, glued to a
     * much longer one — and leaves every legible rhythm alone.
     */
    private static boolean leadsInto(Piece ornament, Piece target, double ornamentBeats) {
        return ornament.durationBeats() < ornamentBeats
                && ornament.durationBeats() <= ORNAMENT_RATIO * target.durationBeats()
                && target.startBeat() <= ornament.endBeat() + EPSILON;
    }

    /**
     * Which syllable claims each note, or {@link #UNSUNG}.
     *
     * <p>A note is claimed, over every line its own span overlaps (#621), by
     * the syllable the least silence separates it from — the quantity the
     * claim bound reads (#620) — with ties broken by the nearest start. A
     * line whose hull misses the note offers nothing, however near its words
     * sit: that gate is geometric rather than the bound (#648). The
     * tie-break is a decision, not a leftover: it governs the
     * words a note sounds under, where silence says nothing, and there a note
     * beginning near a word's start is usually that word's own approach — a
     * scoop begins a breath before the aligner's word start, and an onset the
     * analysis window placed late (#497) lands just after it. Ordering that
     * regime by overlap instead was measured and costs the scored recording's
     * reduced part; {@code tools/PlayablePartCheck.java} is the instrument.
     *
     * <p>A line's hull covers whatever instrumental gap sits inside it, so
     * without the claim bound both a note in such a gap and a note simply far
     * from every syllable of its line would still be claimed by some word and
     * welded to whatever it sang (#598). Both present here as the same
     * silence, so one bound answers both.
     *
     * <p>A syllable marked as a melisma claims its notes like any other; what
     * changes is that {@link #groups} then splits its run by the ornament rule
     * instead of collapsing it.
     */
    private static long[] syllableOf(List<Piece> pieces, Lyrics lyrics, TempoMap map,
                                     double claimBeats) {
        long[] claimed = new long[pieces.size()];
        Arrays.fill(claimed, UNSUNG);
        List<LyricLine> lines = lyrics.lines();
        if (lines.isEmpty()) {
            return claimed;
        }
        double[][] syllableStart = new double[lines.size()][];
        double[][] syllableEnd = new double[lines.size()][];
        double[] lineStart = new double[lines.size()];
        double[] lineEnd = new double[lines.size()];
        for (int l = 0; l < lines.size(); l++) {
            List<LyricWord> words = lines.get(l).words();
            syllableStart[l] = new double[words.size()];
            syllableEnd[l] = new double[words.size()];
            lineStart[l] = Double.POSITIVE_INFINITY;
            lineEnd[l] = Double.NEGATIVE_INFINITY;
            for (int w = 0; w < words.size(); w++) {
                LyricWord word = words.get(w);
                double start = word.startBeat()
                        .orElseGet(() -> map.secondsToBeats(word.startSeconds()));
                double end = word.endBeat().orElseGet(() -> map.secondsToBeats(word.endSeconds()));
                syllableStart[l][w] = start;
                syllableEnd[l][w] = end;
                lineStart[l] = Math.min(lineStart[l], start);
                lineEnd[l] = Math.max(lineEnd[l], end);
            }
        }
        for (int i = 0; i < pieces.size(); i++) {
            Piece piece = pieces.get(i);
            int line = -1;
            int word = -1;
            double leastSilence = Double.POSITIVE_INFINITY;
            double nearestStart = Double.POSITIVE_INFINITY;
            for (int l = 0; l < lines.size(); l++) {
                if (Math.min(piece.endBeat(), lineEnd[l])
                        - Math.max(piece.startBeat(), lineStart[l]) <= 0) {
                    continue;
                }
                for (int w = 0; w < syllableStart[l].length; w++) {
                    double silence = Math.max(0,
                            Math.max(piece.startBeat(), syllableStart[l][w])
                                    - Math.min(piece.endBeat(), syllableEnd[l][w]));
                    double distance = Math.abs(syllableStart[l][w] - piece.startBeat());
                    if (silence < leastSilence - EPSILON
                            || (silence < leastSilence + EPSILON && distance < nearestStart)) {
                        leastSilence = silence;
                        nearestStart = distance;
                        line = l;
                        word = w;
                    }
                }
            }
            if (line >= 0 && leastSilence <= claimBeats) {
                claimed[i] = ((long) line << 32) | word;
            }
        }
        return claimed;
    }

    /** The group as the one note it prints, running from its first onset to its last release. */
    private static Note collapse(List<Piece> group, ChordProgression chords, TempoMap map) {
        Piece first = group.get(0);
        if (group.size() == 1) {
            return first.note();
        }
        // The furthest release rather than the last note's, since nothing
        // promises a note track is monophonic and the group has to cover what
        // it replaced.
        double endSeconds = first.note().offsetSeconds();
        double endBeat = first.endBeat();
        boolean quantized = first.note().isQuantized();
        for (Piece piece : group) {
            endSeconds = Math.max(endSeconds, piece.note().offsetSeconds());
            endBeat = Math.max(endBeat, piece.endBeat());
            quantized &= piece.note().isQuantized();
        }
        Note settled = settledOn(group, chords, map).note();
        Note printed = new Note(first.note().onsetSeconds(),
                endSeconds - first.note().onsetSeconds(),
                settled.midiPitch(), settled.velocity(), settled.spelling(),
                Optional.empty(), Optional.empty(), settled.confidence());
        // A group whose notes were all quantized has a quantized span too, and
        // dropping it would make a reduced part of a quantized score look
        // un-analysed to everything downstream.
        return quantized
                ? printed.quantizedTo(first.startBeat(), endBeat - first.startBeat())
                : printed;
    }

    /**
     * The note in the group whose pitch the group settles on.
     *
     * <p>The last one, because a syllable's target is where its pitch arrives:
     * a scoop passes through everything below the note it is aiming at, so any
     * rule that weighs the whole group reports the approach as well as the
     * arrival. Where an earlier pitch sounds much longer than the last one the
     * group has arguably not settled at all, and only there does the chart get
     * a say — a chord tone in place of a passing tone, and only under a span
     * more likely right than wrong.
     */
    private static Piece settledOn(List<Piece> group, ChordProgression chords, TempoMap map) {
        Map<Integer, Double> sounding = new HashMap<>();
        for (Piece piece : group) {
            sounding.merge(piece.pitch(), piece.durationBeats(), Double::sum);
        }
        int settled = group.get(group.size() - 1).pitch();
        int dominant = settled;
        double dominantBeats = -1;
        // Backwards, so that among equal totals the later pitch is the dominant
        // one and this cannot depend on how the map happened to order its keys.
        for (int i = group.size() - 1; i >= 0; i--) {
            double beats = sounding.get(group.get(i).pitch());
            if (beats > dominantBeats) {
                dominantBeats = beats;
                dominant = group.get(i).pitch();
            }
        }
        if (dominant != settled && sounding.get(settled) < SETTLED_SHARE * dominantBeats) {
            Optional<Chord> chord = chordOver(group, chords, map)
                    .filter(c -> !c.isNoChord())
                    .filter(c -> c.confidence().isAtLeast(CHART_CONFIDENCE_FLOOR));
            if (chord.isPresent() && sounds(chord.get(), dominant) && !sounds(chord.get(), settled)) {
                settled = dominant;
            }
        }
        for (int i = group.size() - 1; i >= 0; i--) {
            if (group.get(i).pitch() == settled) {
                return group.get(i);
            }
        }
        throw new IllegalStateException("the settled pitch is not in the group it came from");
    }

    private static boolean sounds(Chord chord, int midiPitch) {
        int pitchClass = Math.floorMod(midiPitch, 12);
        for (int tone : chord.pitchClasses()) {
            if (tone == pitchClass) {
                return true;
            }
        }
        return false;
    }

    /**
     * The printed heads with each unsupported excursion moved to the pitch the
     * melody returned to (#670).
     *
     * <p>A head neither the chord under it nor the key signature accounts for
     * is not a note a reader should meet as an accidental unless the melody
     * really went there. Four things have to hold before this decides it did
     * not: nothing in the harmony admits the pitch, the head is too short to
     * be a note held on purpose, the nearest supported heads on either side
     * are near enough each other that the line came back to where it left, and
     * the head is near enough to that place for the move to have been a wobble
     * rather than somewhere the line went. A head <em>between</em> its two
     * neighbours is left alone whatever the harmony says, because that is what
     * a chromatic passing tone looks like.
     *
     * <p>The pass reads a monophonic sequence of heads, and a head sounding at
     * the same time as another stands aside rather than being read as the side
     * the line returned to. Nothing here changes how many heads are printed or
     * when: a syllable keeps the head it was given, at its own onset and
     * length.
     */
    private static List<Note> returned(List<Note> heads, Score score, Excursion excursion) {
        TempoMap map = score.tempoMap();
        boolean[] supported = new boolean[heads.size()];
        boolean[] alone = new boolean[heads.size()];
        for (int i = 0; i < heads.size(); i++) {
            supported[i] = accountedFor(score, heads.get(i));
            alone[i] = alone(heads, i);
        }
        List<Note> out = new ArrayList<>(heads);
        for (int i = 0; i < heads.size(); i++) {
            Note head = heads.get(i);
            if (supported[i] || !alone[i] || beatsOf(head, map) > excursion.beats()) {
                continue;
            }
            int before = i - 1;
            while (before >= 0 && !(supported[before] && alone[before])) {
                before--;
            }
            int after = i + 1;
            while (after < heads.size() && !(supported[after] && alone[after])) {
                after++;
            }
            if (before < 0 || after >= heads.size()) {
                continue;
            }
            int left = heads.get(before).midiPitch();
            int right = heads.get(after).midiPitch();
            if (Math.abs(left - right) > excursion.returnSemitones()
                    || (head.midiPitch() > Math.min(left, right)
                        && head.midiPitch() < Math.max(left, right))) {
                continue;
            }
            Note home = Math.abs(left - head.midiPitch()) <= Math.abs(right - head.midiPitch())
                    ? heads.get(before) : heads.get(after);
            if (Math.abs(home.midiPitch() - head.midiPitch()) > excursion.departureSemitones()) {
                continue;
            }
            out.set(i, new Note(head.onsetSeconds(), head.durationSeconds(), home.midiPitch(),
                    head.velocity(), home.spelling(), head.onsetBeat(), head.durationBeats(),
                    head.confidence()));
        }
        return out;
    }

    /** Whether no other head sounds while this one does. */
    private static boolean alone(List<Note> heads, int index) {
        Note head = heads.get(index);
        for (int i = 0; i < heads.size(); i++) {
            if (i != index && heads.get(i).onsetSeconds() < head.offsetSeconds() - EPSILON
                    && head.onsetSeconds() < heads.get(i).offsetSeconds() - EPSILON) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether anything in the harmony admits a note's pitch, which is what
     * {@link #reduce(Score)} refuses an excursion by (#670).
     *
     * <p>Both references have to be there and both have to refuse it. The
     * chord is stated per span, so it survives a piece that changes key; the
     * key layer holds one span (#441), so it is the coarser of the two and
     * cannot answer alone.
     *
     * <p>Public so that {@code tools/PlayablePartCheck.java} measures the
     * decision the reduction makes rather than a second transcription of it.
     */
    public static boolean accountedFor(Score score, Note head) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(head, "head");
        TempoMap map = score.tempoMap();
        Optional<Chord> chord = chordOver(startBeat(head, map), endBeat(head, map),
                score.chords(), map).filter(c -> !c.isNoChord());
        Optional<Key> key = score.keyAt((head.onsetSeconds() + head.offsetSeconds()) / 2);
        if (chord.isEmpty() || key.isEmpty()) {
            return true;
        }
        return sounds(chord.get(), head.midiPitch()) || inSignature(key.get(), head.midiPitch());
    }

    private static boolean inSignature(Key key, int midiPitch) {
        int[] degrees = key.mode() == Mode.MINOR ? MINOR_DEGREES : MAJOR_DEGREES;
        for (int degree : degrees) {
            if (Math.floorMod(key.tonic().pitchClass() + degree, 12)
                    == Math.floorMod(midiPitch, 12)) {
                return true;
            }
        }
        return false;
    }

    private static double beatsOf(Note note, TempoMap map) {
        return endBeat(note, map) - startBeat(note, map);
    }

    // A quantized note's own beats, like Piece's, so that the length this
    // measures and the span it looks the chord up over are the same one.
    private static double startBeat(Note note, TempoMap map) {
        return note.onsetBeat().orElseGet(() -> map.secondsToBeats(note.onsetSeconds()));
    }

    private static double endBeat(Note note, TempoMap map) {
        return note.offsetBeat().orElseGet(() -> map.secondsToBeats(note.offsetSeconds()));
    }

    /** The chord span covering most of the group, if any covers it at all. */
    private static Optional<Chord> chordOver(List<Piece> group, ChordProgression chords,
                                             TempoMap map) {
        return chordOver(group.get(0).startBeat(), group.get(group.size() - 1).endBeat(),
                chords, map);
    }

    private static Optional<Chord> chordOver(double from, double to, ChordProgression chords,
                                             TempoMap map) {
        Chord best = null;
        double widest = 0;
        for (Chord chord : chords.chords()) {
            double start = chord.startBeat()
                    .orElseGet(() -> map.secondsToBeats(chord.startSeconds()));
            double end = chord.endBeat().orElseGet(() -> map.secondsToBeats(chord.endSeconds()));
            double overlap = Math.min(to, end) - Math.max(from, start);
            if (overlap > widest) {
                widest = overlap;
                best = chord;
            }
        }
        return Optional.ofNullable(best);
    }
}
