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

package dev.olivelli.musicwizard.dsp;

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Turns a frame-by-frame pitch track into notes.
 *
 * <p>A run of voiced frames is cut wherever the rounded pitch changes, and the
 * pieces too short to be notes are removed rather than kept: between two notes
 * the decoded path travels through the pitches in between, and those frames
 * would otherwise become a note of their own on every interval wider than a
 * semitone. What is left absorbs the gap, so a note runs up to the start of the
 * next one and a boundary is placed once rather than twice.
 *
 * <p><strong>The onset envelope splits the re-articulations; the pitch track
 * decides everything else.</strong> Two notes of the same pitch with no gap
 * are indistinguishable in a pitch track outright — a re-articulation is an
 * amplitude event — so a strong envelope peak inside a note cuts it in two,
 * but only where the voice itself restarts (#495). The constants carry what
 * both melody corpora said about the floors.
 *
 * <p>What the envelope deliberately does <em>not</em> do here is move the
 * boundaries themselves. Measured on the packages with exact MIDI truth, that
 * closes their onset lateness completely — and on vocadito's real singing it
 * moves the same boundaries away from the annotations, because the envelope
 * marks the instrument's attack where a human marks the sung vowel, and the
 * two differ by the very interval the correction spans. The gap #497 names is
 * real against MIDI attacks and is not this stage's defect against sung
 * truth; the measurements are with that issue.
 *
 * <p><strong>Notes are rounded on the recording's grid, not on A440</strong>
 * (#566). A transfer that runs fast or slow puts every sung note off the
 * concert-pitch grid by one constant, which does not move a note by a semitone
 * on its own — it spends the rounding margin on one side, so the singer's own
 * scatter then crosses the boundary in one direction only. The offset is the
 * one the harmony is already analysed at; this stage is handed it rather than
 * measuring its own, so a chord and the melody over it cannot be named in
 * different frames. It is one offset for the whole recording, so a recording
 * whose tuning drifts is out of scope.
 */
public final class MelodyEstimator {

    /**
     * The shortest thing that may become a note.
     *
     * <p>Sixty milliseconds is around a thirty-second note at 120, and also
     * about the width of the transition between two notes at this window and
     * hop — which is what this constant is really sized against. Anything
     * shorter is far more likely to be the path crossing an interval than a
     * note that was sung.
     */
    private static final double MIN_NOTE_SECONDS = 0.06;

    /**
     * How far the pitch must leave a note before it can be a different note.
     *
     * <p>The cut is <em>not</em> made by rounding each frame to a semitone and
     * grouping equal ones. A note sung a little under the boundary between two
     * semitones rounds to one of them in some frames and the other in the rest,
     * and grouped that way it is a dozen spans of two or three frames, every one
     * of them too short to keep — so the note does not land on the wrong
     * semitone, it disappears. Measured against the note's own running mean
     * instead, that flicker never leaves the note at all.
     *
     * <p>Six tenths rather than half: half is the distance at which a frame
     * belongs to the neighbouring semitone, and a threshold there would make a
     * note sung slightly sharp its own boundary.
     */
    private static final double SPLIT_SEMITONES = 0.6;

    /**
     * The least envelope strength, in its standard-deviation units, that may
     * cut a note in two.
     *
     * <p>Inventing a boundary inside a held note is the
     * accent-versus-articulation mistake this stage once avoided by not
     * reading the envelope at all, so the floor sits just above the
     * interior-peak population the synthetic renders produce -- their
     * soundbank's vibrato runs to about 3.3 at the population's tail and
     * nearly reaches the floor at its extreme -- and sung re-strikes still
     * clear it. Below it the melody-only rows lose a few points each to false
     * cuts and the accompanied row loses double digits; at the floor the only
     * rows that move at all are the accompanied one and the real singing.
     */
    private static final double REARTICULATION_FLOOR = 4.0;

    /**
     * The share of a note's own median voicedness its dip must fall under for
     * the voice to count as restarted.
     *
     * <p>Swept both ways on vocadito against the accompanied package:
     * tightening it lost sung re-articulations without removing another false
     * cut, and loosening it admits accompaniment. It is a second gate, not a
     * cure -- accompaniment bleeding into the pitch window dips the measured
     * voicedness too, which is why the accompanied row still pays a little.
     */
    private static final double REARTICULATION_DIP_SHARE = 0.9;

    /**
     * How far from a peak the voicedness dip may sit. Numerically the shortest
     * note, but a search radius rather than a length -- see {@link
     * #voiceRestartsAt} for why it is deliberately coarse.
     */
    private static final double DIP_REACH_SECONDS = 0.06;

    /**
     * How strongly the track's own pitches must sit on the grid a tuning offset
     * names before that offset is used to round them.
     *
     * <p>The measure is the mean of {@code cos} over each voiced frame's
     * distance from that grid, so it is one for a track dead on it, zero for
     * one spread evenly across the semitone, and negative for one sitting
     * between its lines. An offset a recording does not sit on is not a
     * tuning, and shifting by it moves whatever share of the notes the shift
     * is wide across a boundary for nothing — which is what unaccompanied
     * singing costs, because {@link Chroma#estimateTuning} answers it as
     * confidently as it answers a band. Where the track sits on no grid the
     * rounding cannot be helped by a shift either, so refusing is also the
     * cheaper mistake.
     *
     * <p>It is a trade and not a separation. Swept against vocadito, the
     * value here is low enough to admit a band recording whose singing agrees
     * with its transfer, and admitting that also admits two clips of
     * unaccompanied singing, which pay — where the melody is the whole
     * recording the offset was measured on the signal it is applied to and
     * this test cannot be anything but circular (#568). No value both admits
     * the one and refuses the others; `tools/baselines/score-melody*.txt`
     * carry what each is worth.
     */
    private static final double TUNING_CORROBORATION_FLOOR = 0.2;

    private MelodyEstimator() {
    }

    /**
     * Segments a pitch track into a melody part.
     *
     * <p>The notes carry wall-clock timing only. Quantizing them onto the beat
     * grid is a separate decision made downstream, where the grid is known.
     */
    public static NoteTrack estimate(PitchTrack pitches) {
        Objects.requireNonNull(pitches, "pitches");
        return segment(pitches, null, 0);
    }

    /**
     * The same, with same-pitch re-articulations split by the onset envelope.
     *
     * <p>The envelope-less overload exists for callers with nothing to offer
     * it, and keeps two notes of one pitch with no gap as the one note a pitch
     * track can see. Neither of these two rounds anywhere but on A440; the
     * pipeline uses {@link #estimate(PitchTrack, OnsetEnvelope, double)}.
     */
    public static NoteTrack estimate(PitchTrack pitches, OnsetEnvelope envelope) {
        Objects.requireNonNull(pitches, "pitches");
        Objects.requireNonNull(envelope, "envelope");
        return segment(pitches, envelope, 0);
    }

    /**
     * The same, rounding notes on a recording whose tuning is known.
     *
     * @param tuningOffsetSemitones how far the recording sits above A440, in
     *                              the sense of {@link Chroma#estimateTuning}.
     *                              Honoured only where it says something that
     *                              estimator can resolve and where this track's
     *                              own pitches sit on the grid it names.
     */
    public static NoteTrack estimate(PitchTrack pitches, OnsetEnvelope envelope,
                                     double tuningOffsetSemitones) {
        Objects.requireNonNull(pitches, "pitches");
        Objects.requireNonNull(envelope, "envelope");
        if (!Double.isFinite(tuningOffsetSemitones)) {
            throw new IllegalArgumentException("tuningOffsetSemitones must be finite, got: "
                    + tuningOffsetSemitones);
        }
        return segment(pitches, envelope, tuningOffsetSemitones);
    }

    private static NoteTrack segment(PitchTrack pitches, OnsetEnvelope envelope,
                                     double tuningOffsetSemitones) {
        // Decided once for the whole track and before anything is cut: the
        // offset shifts where a semitone boundary falls, so a decision taken
        // per run would let one note of a phrase be rounded on a grid the next
        // one is not.
        double grid = honours(pitches, tuningOffsetSemitones) ? tuningOffsetSemitones : 0;
        List<Note> notes = new ArrayList<>();
        int frame = 0;
        while (frame < pitches.frameCount()) {
            if (!pitches.voiced()[frame]) {
                frame++;
                continue;
            }
            int end = frame;
            while (end < pitches.frameCount() && pitches.voiced()[end]) {
                end++;
            }
            notes.addAll(notesOfRun(pitches, envelope, frame, end, grid));
            frame = end;
        }
        return new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, trackConfidence(notes));
    }

    /**
     * Whether an offset is worth rounding on: it has to say something
     * {@link Chroma#estimateTuning} can resolve, and the track's own pitches
     * have to sit on the grid it names.
     *
     * <p>An offset that reads as concert pitch is not rounded on at all: a
     * shift that narrow decides nothing but notes already on a rounding
     * boundary, in whichever direction they happened to lie.
     */
    private static boolean honours(PitchTrack pitches, double tuningOffsetSemitones) {
        return !Chroma.readsAsConcertPitch(tuningOffsetSemitones)
                && corroborates(pitches, tuningOffsetSemitones);
    }

    /**
     * Whether the track's own pitches sit on the grid an offset names, against
     * {@link #TUNING_CORROBORATION_FLOOR}.
     *
     * <p>Read frame by frame rather than from the notes, so that the decision
     * does not depend on the cuts it goes on to move, and weighted by
     * voicedness so a frame the decoder was unsure was singing does not vote
     * like one it was sure of. Unvoiced frames carry the decoder's memory
     * rather than a measurement ({@link PitchTrack}) and are left out.
     */
    private static boolean corroborates(PitchTrack pitches, double tuningOffsetSemitones) {
        double agreement = 0;
        double weight = 0;
        for (int frame = 0; frame < pitches.frameCount(); frame++) {
            if (!pitches.voiced()[frame]) {
                continue;
            }
            double pitch = pitches.midiPitchAt(frame);
            double distance = pitch - Math.round(pitch) - tuningOffsetSemitones;
            agreement += pitches.voicedness()[frame] * Math.cos(2 * Math.PI * distance);
            weight += pitches.voicedness()[frame];
        }
        return weight > 0 && agreement / weight >= TUNING_CORROBORATION_FLOOR;
    }

    /** One unbroken voiced run, cut into notes. */
    private static List<Note> notesOfRun(PitchTrack pitches, OnsetEnvelope envelope,
                                         int from, int to, double grid) {
        double frameSeconds = 1 / pitches.frameRate();
        int confirmFrames = Math.max(1, (int) Math.ceil(MIN_NOTE_SECONDS / frameSeconds));
        List<int[]> spans = cut(pitches, from, to, confirmFrames);
        double runEnd = pitches.timeOf(to - 1) + frameSeconds;
        List<int[]> kept = new ArrayList<>();
        for (int[] span : spans) {
            if ((span[1] - span[0]) * frameSeconds >= MIN_NOTE_SECONDS) {
                kept.add(span);
            }
        }

        List<int[]> merged = mergeEqualPitches(pitches, kept, grid);

        double[] onsets = new double[merged.size()];
        for (int i = 0; i < merged.size(); i++) {
            onsets[i] = pitches.timeOf(merged.get(i)[0]);
        }
        List<Note> notes = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            int[] span = merged.get(i);
            // Up to the next surviving note rather than to its own last frame,
            // so the frames dropped between them belong to the note being left
            // rather than to no note at all.
            double onset = onsets[i];
            double end = i + 1 < merged.size() ? onsets[i + 1] : runEnd;
            int pitch = medianPitch(pitches, span, grid);
            // The span's own statistics for every piece of it: a
            // re-articulation is the same pitch by construction, and the
            // envelope says where it was struck, not what it sounded like.
            Confidence confidence = Confidence.clamped(meanVoicedness(pitches, span));
            List<Double> starts = new ArrayList<>();
            starts.add(onset);
            if (envelope != null) {
                rearticulations(envelope, pitches, span, onset, end, starts);
            }
            for (int piece = 0; piece < starts.size(); piece++) {
                double pieceStart = starts.get(piece);
                double pieceEnd = piece + 1 < starts.size() ? starts.get(piece + 1) : end;
                notes.add(Note.ofSeconds(pieceStart, pieceEnd - pieceStart, pitch, confidence));
            }
        }
        return notes;
    }

    /**
     * Adds the boundaries of a note's re-articulations to {@code starts}.
     *
     * <p>A peak may cut only strictly inside the note, at least
     * {@link #MIN_NOTE_SECONDS} from either end and from the previous cut:
     * nearer the start it is the note's own attack, nearer the end it is the
     * next note's, and either way the piece it would leave is shorter than
     * anything this class calls a note.
     */
    private static void rearticulations(OnsetEnvelope envelope, PitchTrack pitches, int[] span,
                                        double onset, double end, List<Double> starts) {
        double medianVoicedness = medianVoicedness(pitches, span);
        int fromFrame = envelope.frameOf(onset + MIN_NOTE_SECONDS);
        int toFrame = envelope.frameOf(end - MIN_NOTE_SECONDS);
        for (int frame = fromFrame; frame <= toFrame; frame++) {
            double time = envelope.timeOf(frame);
            // The loop bound is a rounded frame index, so a peak can land a
            // few milliseconds past the boundary it was computed from; the
            // start side needs no twin, because the spacing guard below
            // already holds every cut at least MIN_NOTE_SECONDS after the
            // onset the list opens with.
            if (time >= end - MIN_NOTE_SECONDS) {
                continue;
            }
            if (isPeak(envelope, frame, REARTICULATION_FLOOR)
                    && voiceRestartsAt(pitches, span, medianVoicedness, time)
                    && time >= starts.get(starts.size() - 1) + MIN_NOTE_SECONDS) {
                starts.add(time);
            }
        }
    }

    /**
     * True when the voice itself restarts around a time: its voicedness dips
     * against the note's own median, somewhere within {@link
     * #DIP_REACH_SECONDS} of the point asked about.
     *
     * <p>The localisation is deliberately coarse -- the pitch window smears
     * any dip by its own 93 ms -- but it is not free to widen: on the real
     * singing a reach of this size and one spanning the whole note answer
     * identically, and on the accompanied package the note-wide reach admits
     * more pad cuts. A single-frame reach loses real splits on both corpora.
     * It keeps a note's far end out of the question; it does not place the
     * restart precisely.
     */
    private static boolean voiceRestartsAt(PitchTrack pitches, int[] span, double medianVoicedness,
                                           double time) {
        // Inverts PitchTrack.timeOf: its frames are stamped at window centres,
        // where the envelope's are not.
        int centre = (int) Math.round(time * pitches.frameRate()
                - (double) pitches.windowSize() / 2 / pitches.hopSize());
        int reach = (int) Math.ceil(DIP_REACH_SECONDS * pitches.frameRate());
        double dip = Double.POSITIVE_INFINITY;
        for (int f = Math.max(span[0], centre - reach);
                f <= Math.min(span[1] - 1, centre + reach); f++) {
            dip = Math.min(dip, pitches.voicedness()[f]);
        }
        return dip < REARTICULATION_DIP_SHARE * medianVoicedness;
    }

    /**
     * A span's median voicedness, both middles averaged on an even span for
     * the reason {@link #medianPitch} averages them.
     */
    private static double medianVoicedness(PitchTrack pitches, int[] span) {
        double[] sorted = new double[span[1] - span[0]];
        for (int frame = span[0]; frame < span[1]; frame++) {
            sorted[frame - span[0]] = pitches.voicedness()[frame];
        }
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1
                ? sorted[middle]
                : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    /** True when a frame is a local maximum of the envelope clearing the floor. */
    private static boolean isPeak(OnsetEnvelope envelope, int frame, double floor) {
        double[] strength = envelope.strength();
        if (strength[frame] < floor) {
            return false;
        }
        double before = frame > 0 ? strength[frame - 1] : Double.NEGATIVE_INFINITY;
        double after = frame + 1 < strength.length ? strength[frame + 1] : Double.NEGATIVE_INFINITY;
        return strength[frame] >= before && strength[frame] > after;
    }

    /**
     * Joins neighbouring spans that land on the same semitone.
     *
     * <p>Two adjacent spans of one pitch are one note that wandered far enough
     * to be cut and came back — and the class already cannot tell a note from
     * the same note sung twice with no gap (#495), so joining them takes away a
     * false note without taking away any true one it could otherwise have kept.
     */
    private static List<int[]> mergeEqualPitches(PitchTrack pitches, List<int[]> spans,
                                                 double grid) {
        List<int[]> merged = new ArrayList<>(spans.size());
        for (int[] span : spans) {
            if (!merged.isEmpty()) {
                int[] previous = merged.get(merged.size() - 1);
                if (previous[1] == span[0]
                        && medianPitch(pitches, previous, grid)
                                == medianPitch(pitches, span, grid)) {
                    merged.set(merged.size() - 1, new int[] {previous[0], span[1]});
                    continue;
                }
            }
            merged.add(span);
        }
        return merged;
    }

    /**
     * Where a run's notes begin, by hysteresis against each note's running mean.
     *
     * <p>A frame that leaves the note by more than {@link #SPLIT_SEMITONES}
     * opens a candidate boundary, and the boundary is only taken once the
     * departure has lasted as long as the shortest note there can be — so a
     * frame or two of the decoded path crossing an interval does not cut
     * anything, and a departure that stays cuts at the frame it started, not at
     * the frame it was confirmed. While a departure is unconfirmed its frames do
     * not enter the mean: letting them would drag the reference towards the
     * pitch being tested against it, which is how a slow slide between two notes
     * becomes one note in the middle.
     */
    private static List<int[]> cut(PitchTrack pitches, int from, int to, int confirmFrames) {
        List<int[]> spans = new ArrayList<>();
        int start = from;
        double sum = 0;
        int count = 0;
        int departedAt = -1;
        for (int frame = from; frame < to; frame++) {
            double pitch = pitches.midiPitchAt(frame);
            if (count == 0) {
                sum = pitch;
                count = 1;
                continue;
            }
            if (Math.abs(pitch - sum / count) > SPLIT_SEMITONES) {
                if (departedAt < 0) {
                    departedAt = frame;
                }
                if (frame - departedAt + 1 >= confirmFrames) {
                    spans.add(new int[] {start, departedAt});
                    start = departedAt;
                    // Seeded from the frame that confirmed the departure, not
                    // from the whole departing stretch: those frames are the
                    // path still crossing the interval, and a mean seeded with
                    // them sits between the two notes — so the pitch the note
                    // actually settles on then departs from its own reference
                    // and splits the note again. Every leap became two notes.
                    sum = pitches.midiPitchAt(frame);
                    count = 1;
                    departedAt = -1;
                }
            } else {
                departedAt = -1;
                sum += pitch;
                count++;
            }
        }
        spans.add(new int[] {start, to});
        return spans;
    }

    /**
     * A span's pitch: the median of its frames, rounded on the recording's grid.
     *
     * <p>The median rather than the mean, because a span's first frames are the
     * ones that crossed into it and sit between this note and the one before.
     *
     * <p>Both middle values on an even span, rather than the upper one. A note
     * wavering across a semitone boundary spends half its frames either side,
     * and taking the upper middle rounds every such note up — which is a bias
     * that shows only on the notes least able to afford one.
     *
     * <p>The only place the grid is read, and {@link #cut} does not read it:
     * it measures each frame against the note's own running mean, which no
     * constant offset moves, so a departure is confirmed at the same frame on
     * any grid. {@link #mergeEqualPitches} does read it, through this method,
     * so a grid can still join or separate two spans that were cut the same
     * way — two spans a wide wobble apart round together on one grid and
     * apart on another.
     */
    private static int medianPitch(PitchTrack pitches, int[] span, double grid) {
        double[] sorted = new double[span[1] - span[0]];
        for (int frame = span[0]; frame < span[1]; frame++) {
            sorted[frame - span[0]] = pitches.midiPitchAt(frame);
        }
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        double median = sorted.length % 2 == 1
                ? sorted[middle]
                : (sorted[middle - 1] + sorted[middle]) / 2;
        return (int) Math.round(median - grid);
    }

    private static double meanVoicedness(PitchTrack pitches, int[] span) {
        double total = 0;
        for (int frame = span[0]; frame < span[1]; frame++) {
            total += pitches.voicedness()[frame];
        }
        return total / (span[1] - span[0]);
    }

    /**
     * How much the part as a whole is trusted: the mean of its notes' own
     * confidences, and nothing at all when there are no notes.
     */
    private static Confidence trackConfidence(List<Note> notes) {
        if (notes.isEmpty()) {
            return Confidence.UNKNOWN;
        }
        double total = 0;
        for (Note note : notes) {
            total += note.confidence().value();
        }
        return Confidence.clamped(total / notes.size());
    }
}
