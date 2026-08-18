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
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
 * note; everything else is grouped by the weaker rule that an ornament belongs
 * to the note it leads into. The printed pitch is the one the group
 * <em>settles</em> on rather than the one it sounds longest — over a scoop
 * those differ, and it is the arrival that was sung.
 *
 * <p>Chord tones only separate candidates the group's own evidence leaves
 * level, and only under a chart span this trusts. Rounding a melody to the
 * harmony was built, swept and rejected (#571): the errors are not marginal,
 * so a snap yields a confidently wrong note rather than a recovered one.
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
     * decoration.
     */
    private static final double ORNAMENT_BEATS = 1.0 / 3;

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

    /** No syllable claims this note. */
    private static final long UNSUNG = Long.MIN_VALUE;

    private static final double EPSILON = 1e-9;

    private PlayableMelody() {
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
        Objects.requireNonNull(score, "score");
        NoteTrack melody = score.track(PartRole.LEAD_VOCAL).orElseThrow(
                () -> new IllegalArgumentException("the score holds no melody to reduce"));
        TempoMap map = score.tempoMap();
        List<Piece> pieces = new ArrayList<>(melody.size());
        for (Note note : melody.notes()) {
            pieces.add(Piece.of(note, map));
        }
        List<Note> reduced = new ArrayList<>(pieces.size());
        for (List<Piece> group : groups(pieces, score.lyrics(), map)) {
            reduced.add(collapse(group, score.chords(), map));
        }
        return new NoteTrack(PartRole.LEAD_VOCAL, TRACK_NAME, reduced, melody.confidence());
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

    private static List<List<Piece>> groups(List<Piece> pieces, Lyrics lyrics, TempoMap map) {
        long[] syllable = syllableOf(pieces, lyrics, map);
        List<List<Piece>> groups = new ArrayList<>();
        int from = 0;
        while (from < pieces.size()) {
            int to = from;
            if (syllable[from] != UNSUNG) {
                while (to + 1 < pieces.size() && syllable[to + 1] == syllable[from]) {
                    to++;
                }
            } else {
                while (to + 1 < pieces.size() && syllable[to + 1] == UNSUNG
                        && leadsInto(pieces.get(to), pieces.get(to + 1))) {
                    to++;
                }
            }
            groups.add(pieces.subList(from, to + 1));
            from = to + 1;
        }
        return groups;
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
    private static boolean leadsInto(Piece ornament, Piece target) {
        return ornament.durationBeats() < ORNAMENT_BEATS
                && ornament.durationBeats() <= ORNAMENT_RATIO * target.durationBeats()
                && target.startBeat() <= ornament.endBeat() + EPSILON;
    }

    /**
     * Which syllable claims each note, or {@link #UNSUNG}.
     *
     * <p>A note is claimed by the line its own span overlaps most, and inside
     * that line by the syllable whose start is nearest its onset. Nearest
     * rather than containing: the two measurements are independent, so a scoop
     * that begins a breath before the aligner's word start, or an onset the
     * analysis window placed late (#497), still belongs to the syllable it is
     * sung on.
     *
     * <p>A syllable some stage has marked as a melisma claims nothing, so its
     * notes fall to the ornament rule rather than collapsing to one. Nothing
     * marks one today, which is #597.
     */
    private static long[] syllableOf(List<Piece> pieces, Lyrics lyrics, TempoMap map) {
        long[] claimed = new long[pieces.size()];
        Arrays.fill(claimed, UNSUNG);
        List<LyricLine> lines = lyrics.lines();
        if (lines.isEmpty()) {
            return claimed;
        }
        double[][] syllableStart = new double[lines.size()][];
        double[] lineStart = new double[lines.size()];
        double[] lineEnd = new double[lines.size()];
        for (int l = 0; l < lines.size(); l++) {
            List<LyricWord> words = lines.get(l).words();
            syllableStart[l] = new double[words.size()];
            lineStart[l] = Double.POSITIVE_INFINITY;
            lineEnd[l] = Double.NEGATIVE_INFINITY;
            for (int w = 0; w < words.size(); w++) {
                LyricWord word = words.get(w);
                double start = word.startBeat()
                        .orElseGet(() -> map.secondsToBeats(word.startSeconds()));
                double end = word.endBeat().orElseGet(() -> map.secondsToBeats(word.endSeconds()));
                syllableStart[l][w] = start;
                lineStart[l] = Math.min(lineStart[l], start);
                lineEnd[l] = Math.max(lineEnd[l], end);
            }
        }
        for (int i = 0; i < pieces.size(); i++) {
            Piece piece = pieces.get(i);
            int line = -1;
            double widest = 0;
            for (int l = 0; l < lines.size(); l++) {
                double overlap = Math.min(piece.endBeat(), lineEnd[l])
                        - Math.max(piece.startBeat(), lineStart[l]);
                if (overlap > widest) {
                    widest = overlap;
                    line = l;
                }
            }
            if (line < 0) {
                continue;
            }
            int word = nearest(syllableStart[line], piece.startBeat());
            if (!lines.get(line).words().get(word).melisma()) {
                claimed[i] = ((long) line << 32) | word;
            }
        }
        return claimed;
    }

    private static int nearest(double[] starts, double beat) {
        int best = 0;
        double distance = Math.abs(starts[0] - beat);
        for (int i = 1; i < starts.length; i++) {
            double candidate = Math.abs(starts[i] - beat);
            if (candidate < distance) {
                distance = candidate;
                best = i;
            }
        }
        return best;
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

    /** The chord span covering most of the group, if any covers it at all. */
    private static Optional<Chord> chordOver(List<Piece> group, ChordProgression chords,
                                             TempoMap map) {
        double from = group.get(0).startBeat();
        double to = group.get(group.size() - 1).endBeat();
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
