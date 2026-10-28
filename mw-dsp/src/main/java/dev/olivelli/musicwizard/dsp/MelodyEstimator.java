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
import dev.olivelli.musicwizard.core.workspace.MelodyTrace;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Turns a frame-by-frame pitch track into notes.
 *
 * <p>A run of voiced frames is cut wherever the rounded pitch changes, and a
 * piece becomes a note only where the pitch settles in it: between two notes
 * the decoded path travels through the pitches in between, and those frames
 * would otherwise become a note of their own on every interval wider than a
 * semitone. What is left absorbs the gap, so a note runs up to the start of the
 * next one and a boundary is placed once rather than twice.
 *
 * <p><strong>A piece is a note when it holds a pitch, not when it is long
 * enough</strong> (#566). Sung notes are short, so a rule that dropped the
 * short pieces would drop real music — and a sung scoop into a note is not
 * short: the running mean leaves it again every time the singer has climbed
 * far enough, so one glide arrives here as a row of pieces whose medians sit
 * between semitones. Asking each piece for a stretch the pitch stays inside
 * {@link #STEADY_SEMITONES} separates the two.
 *
 * <p><strong>The onset envelope splits the re-articulations; the pitch track
 * decides everything else.</strong> Two notes of the same pitch with no gap
 * are indistinguishable in a pitch track outright — a re-articulation is an
 * amplitude event — so a strong envelope peak inside a note cuts it in two,
 * but only where the voice itself restarts (#495), and so does the level
 * falling and coming back with something struck, which is a tongued repeat
 * whose voice never stops, unless the level dips like a tremolo around it
 * (#882). Only the printed notes are cut there: the
 * beat is tracked from the line without them. The constants carry what
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
     * How far the pitch may wander inside a window as long as
     * {@link #MIN_NOTE_SECONDS} and still count as held.
     *
     * <p>The two together are a rate, which is what tells a sung glide from a
     * run of short notes; length alone does not (#566). Swept by
     * {@code tools/GlideSweep.java}: the real singing improves over a wide band
     * around this value and the rendered packages move nowhere in it.
     */
    private static final double STEADY_SEMITONES = 0.7;

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
     * How far from a peak the voicedness dip may sit, and how far either side
     * of a level dip the level must come back. Numerically the shortest note,
     * but a search radius rather than a length -- see {@link #leastVoicedness}
     * for why it is deliberately coarse.
     */
    private static final double DIP_REACH_SECONDS = 0.06;

    /**
     * How far the level must fall inside a note, and come back, for the note
     * to count as struck again with the voice never stopping (#882). Swept on
     * {@code tools/score-melody.py} and {@code tools/score-solo.py}; the
     * figures are in the pull request that set it.
     */
    private static final double STROKE_DECIBELS = 6.0;

    /**
     * How deep, as a share of a stroke, another dip within {@link
     * #TREMOLO_SECONDS} of it may be before the stroke reads as a tremolo,
     * which the synthetic flute renders carry (#882).
     */
    private static final double STROKE_ALONE_SHARE = 0.5;

    /**
     * How far apart two dips may lie and still be read as one tremolo. Strokes
     * repeated faster than this read as a tremolo too, and a slower tremolo as
     * strokes (#885).
     */
    private static final double TREMOLO_SECONDS = 0.2;

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

    /**
     * The narrowest half-band an octave may be judged against, in semitones.
     *
     * <p>The lowest setting at which no melody package has its own extremes
     * folded away, and the best on real singing among those. It is a trade and
     * not a plateau: a semitone lower is better on real singing and is where a
     * package whose melody spans two octaves starts having its top notes folded
     * away, which is the defect this whole rule exists to avoid rather than a
     * column to optimise. {@code tools/OctaveSweep.java} walks the ladder.
     */
    private static final double RANGE_FLOOR_SEMITONES = 14;

    /**
     * The furthest the fold may move a note, in octaves.
     *
     * <p>It bounds the correction, not how far out the note was: a note beyond
     * this from the centre is still moved if this much brings it inside the
     * band. What it rules out is the larger correction, which is the one that
     * relocates a phrase rather than recovering it -- unbounded, the fold moves
     * whole correct phrases across the page wherever the tracker spent most of
     * a recording in another register. Swept by {@code tools/OctaveSweep.java}.
     */
    private static final int MOST_OCTAVES_OUT = 2;

    /**
     * The share of the melody's sounding time the band is asked to reach.
     *
     * <p>This is the half that comes from the recording rather than from a
     * constant: a line that spends this share of its time over a wider compass
     * buys a wider band with it, which is what keeps a melody played rather
     * than sung from being held to a voice's. A quantile rather than a multiple
     * of the median deviation, because the median of a track with a handful of
     * notes in it is zero however far apart they are. Swept by
     * {@code tools/OctaveSweep.java}.
     */
    private static final double RANGE_SPREAD_QUANTILE = 0.9;

    /**
     * How far apart two notes following one another may be and still be one
     * gesture the fold decides once.
     *
     * <p>A whole tone is the widest step a scale takes, so this is the smallest
     * value that holds a run of neighbouring degrees together, and the sweep
     * holds flat above it. {@code tools/OctaveSweep.java} walks the ladder and
     * its {@code splits} mode counts the gestures a setting cuts in half.
     */
    private static final double ONE_GESTURE_SEMITONES = 2;

    /**
     * The longest an attack an octave under its note may last and still be
     * read as that note rather than as one of its own (#860).
     */
    static final double SUB_OCTAVE_ATTACK_SECONDS = 0.2;

    private MelodyEstimator() {
    }

    /**
     * A melody and what the segmentation did to arrive at it.
     *
     * @param trace a summary of the pass that ran, never a second segmentation
     */
    public record Segmented(NoteTrack melody, MelodyTrace trace) {

        public Segmented {
            Objects.requireNonNull(melody, "melody");
            Objects.requireNonNull(trace, "trace");
        }
    }

    /**
     * Segments a pitch track into a melody part.
     *
     * <p>The notes carry wall-clock timing only. Quantizing them onto the beat
     * grid is a separate decision made downstream, where the grid is known.
     */
    public static NoteTrack estimate(PitchTrack pitches) {
        Objects.requireNonNull(pitches, "pitches");
        return segment(pitches, null, null, 0, STEADY_SEMITONES,
                RANGE_FLOOR_SEMITONES, RANGE_SPREAD_QUANTILE, MOST_OCTAVES_OUT,
                ONE_GESTURE_SEMITONES, false).melody();
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
        return segment(pitches, envelope, null, 0, STEADY_SEMITONES,
                RANGE_FLOOR_SEMITONES, RANGE_SPREAD_QUANTILE, MOST_OCTAVES_OUT,
                ONE_GESTURE_SEMITONES, false).melody();
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
        return explain(pitches, envelope, tuningOffsetSemitones).melody();
    }

    /**
     * The same, with what the segmentation did on the way there (#679).
     *
     * <p>The trace names no signal: which one this track was tracked from is
     * the caller's fact, and {@link MelodyTrace#readFrom} is where it is added.
     */
    public static Segmented explain(PitchTrack pitches, OnsetEnvelope envelope,
                                    double tuningOffsetSemitones) {
        return explain(pitches, envelope, tuningOffsetSemitones, false);
    }

    /**
     * The same for a track read from a separated stem or from the whole signal.
     *
     * @param separated whether the track was read from a separated stem, where
     *                  a short note an octave under the next is not taken for
     *                  that note's attack (#860)
     */
    public static Segmented explain(PitchTrack pitches, OnsetEnvelope envelope,
                                    double tuningOffsetSemitones, boolean separated) {
        return explain(pitches, envelope, null, tuningOffsetSemitones, separated);
    }

    /**
     * The same, with the level of the signal the track was read from, which
     * splits a note its player struck again without the voice stopping
     * (#882). The pipeline calls this one.
     *
     * @param level that signal's level, or null to split on the envelope alone
     */
    public static Segmented explain(PitchTrack pitches, OnsetEnvelope envelope,
                                    LevelContour level, double tuningOffsetSemitones,
                                    boolean separated) {
        Objects.requireNonNull(pitches, "pitches");
        Objects.requireNonNull(envelope, "envelope");
        if (!Double.isFinite(tuningOffsetSemitones)) {
            throw new IllegalArgumentException("tuningOffsetSemitones must be finite, got: "
                    + tuningOffsetSemitones);
        }
        return segment(pitches, envelope, level, tuningOffsetSemitones, STEADY_SEMITONES,
                RANGE_FLOOR_SEMITONES, RANGE_SPREAD_QUANTILE, MOST_OCTAVES_OUT,
                ONE_GESTURE_SEMITONES, separated);
    }

    /**
     * The same at a chosen steadiness, which is what {@code tools/GlideSweep.java}
     * sweeps. The pipeline calls the overload above and gets
     * {@link #STEADY_SEMITONES}.
     */
    public static NoteTrack estimate(PitchTrack pitches, OnsetEnvelope envelope,
                                     double tuningOffsetSemitones, double steadySemitones) {
        return estimate(pitches, envelope, null, tuningOffsetSemitones, steadySemitones);
    }

    /**
     * The same, with the level {@link
     * #explain(PitchTrack, OnsetEnvelope, LevelContour, double, boolean)} takes.
     */
    public static NoteTrack estimate(PitchTrack pitches, OnsetEnvelope envelope,
                                     LevelContour level, double tuningOffsetSemitones,
                                     double steadySemitones) {
        Objects.requireNonNull(pitches, "pitches");
        Objects.requireNonNull(envelope, "envelope");
        if (!Double.isFinite(tuningOffsetSemitones)) {
            throw new IllegalArgumentException("tuningOffsetSemitones must be finite, got: "
                    + tuningOffsetSemitones);
        }
        if (!(steadySemitones > 0) || !Double.isFinite(steadySemitones)) {
            throw new IllegalArgumentException("steadySemitones must be finite and positive,"
                    + " got: " + steadySemitones);
        }
        return segment(pitches, envelope, level, tuningOffsetSemitones, steadySemitones,
                RANGE_FLOOR_SEMITONES, RANGE_SPREAD_QUANTILE, MOST_OCTAVES_OUT,
                ONE_GESTURE_SEMITONES, false).melody();
    }

    /**
     * The same with the octave fold's band and bound chosen too, which is what
     * {@code tools/OctaveSweep.java} sweeps. The pipeline calls
     * {@link #estimate(PitchTrack, OnsetEnvelope, double)} and gets
     * {@link #RANGE_FLOOR_SEMITONES}, {@link #RANGE_SPREAD_QUANTILE},
     * {@link #MOST_OCTAVES_OUT} and {@link #ONE_GESTURE_SEMITONES}.
     *
     * <p>A band narrower than an octave folds nothing at all, since no pitch
     * then has a representative inside it; a gesture of zero decides every note
     * alone.
     */
    public static NoteTrack estimate(PitchTrack pitches, OnsetEnvelope envelope,
                                     double tuningOffsetSemitones, double steadySemitones,
                                     double rangeFloorSemitones, double rangeSpreadQuantile,
                                     int mostOctavesOut, double gestureSemitones) {
        return estimate(pitches, envelope, tuningOffsetSemitones, steadySemitones,
                rangeFloorSemitones, rangeSpreadQuantile, mostOctavesOut, gestureSemitones,
                false);
    }

    /**
     * The same for a track read from a separated stem or from the whole signal,
     * in the sense of {@link #explain(PitchTrack, OnsetEnvelope, double, boolean)}.
     */
    public static NoteTrack estimate(PitchTrack pitches, OnsetEnvelope envelope,
                                     double tuningOffsetSemitones, double steadySemitones,
                                     double rangeFloorSemitones, double rangeSpreadQuantile,
                                     int mostOctavesOut, double gestureSemitones,
                                     boolean separated) {
        return estimate(pitches, envelope, null, tuningOffsetSemitones, steadySemitones,
                rangeFloorSemitones, rangeSpreadQuantile, mostOctavesOut, gestureSemitones,
                separated);
    }

    /**
     * The same, with the level {@link
     * #explain(PitchTrack, OnsetEnvelope, LevelContour, double, boolean)} takes.
     */
    public static NoteTrack estimate(PitchTrack pitches, OnsetEnvelope envelope,
                                     LevelContour level, double tuningOffsetSemitones,
                                     double steadySemitones, double rangeFloorSemitones,
                                     double rangeSpreadQuantile, int mostOctavesOut,
                                     double gestureSemitones, boolean separated) {
        Objects.requireNonNull(pitches, "pitches");
        Objects.requireNonNull(envelope, "envelope");
        if (!Double.isFinite(tuningOffsetSemitones)) {
            throw new IllegalArgumentException("tuningOffsetSemitones must be finite, got: "
                    + tuningOffsetSemitones);
        }
        if (!(steadySemitones > 0) || !Double.isFinite(steadySemitones)) {
            throw new IllegalArgumentException("steadySemitones must be finite and positive,"
                    + " got: " + steadySemitones);
        }
        if (!(rangeFloorSemitones >= 0) || !Double.isFinite(rangeFloorSemitones)) {
            throw new IllegalArgumentException("rangeFloorSemitones must be finite and"
                    + " non-negative, got: " + rangeFloorSemitones);
        }
        if (!(rangeSpreadQuantile >= 0) || !(rangeSpreadQuantile <= 1)) {
            throw new IllegalArgumentException("rangeSpreadQuantile must be within 0..1,"
                    + " got: " + rangeSpreadQuantile);
        }
        if (mostOctavesOut < 0) {
            throw new IllegalArgumentException("mostOctavesOut must be non-negative, got: "
                    + mostOctavesOut);
        }
        if (!(gestureSemitones >= 0) || !Double.isFinite(gestureSemitones)) {
            throw new IllegalArgumentException("gestureSemitones must be finite and"
                    + " non-negative, got: " + gestureSemitones);
        }
        return segment(pitches, envelope, level, tuningOffsetSemitones, steadySemitones,
                rangeFloorSemitones, rangeSpreadQuantile, mostOctavesOut, gestureSemitones,
                separated).melody();
    }

    private static Segmented segment(PitchTrack pitches, OnsetEnvelope envelope,
                                     LevelContour level, double tuningOffsetSemitones,
                                     double steadySemitones, double rangeFloorSemitones,
                                     double rangeSpreadQuantile, int mostOctavesOut,
                                     double gestureSemitones, boolean separated) {
        // Decided once for the whole track and before anything is cut: the
        // offset shifts where a semitone boundary falls, so a decision taken
        // per run would let one note of a phrase be rounded on a grid the next
        // one is not.
        MelodyTrace.Tuning tuning = tuning(pitches, tuningOffsetSemitones);
        double grid = tuning.appliedSemitones();
        List<Note> notes = new ArrayList<>();
        List<String> startedBy = new ArrayList<>();
        List<Integer> ofRun = new ArrayList<>();
        List<MelodyTrace.Run> runs = new ArrayList<>();
        List<MelodyTrace.Strike> strikes = new ArrayList<>();
        int voicedFrames = 0;
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
            voicedFrames += end - frame;
            Cut cut = notesOfRun(pitches, envelope, level, frame, end, grid, steadySemitones,
                    strikes);
            notes.addAll(cut.notes());
            startedBy.addAll(cut.startedBy());
            cut.notes().forEach(note -> ofRun.add(runs.size()));
            runs.add(cut.run());
            frame = end;
        }
        if (!separated) {
            absorbSubOctaveAttacks(pitches, notes, startedBy, ofRun, runs);
        }
        Folded folded = foldOctaves(notes, rangeFloorSemitones, rangeSpreadQuantile,
                mostOctavesOut, gestureSemitones);
        List<Note> voices = folded.notes();
        return new Segmented(
                new NoteTrack(PartRole.LEAD_VOCAL, NoteTrack.VOICE, voices, trackConfidence(voices)),
                new MelodyTrace(null, track(pitches, voicedFrames), tuning, folded.fold(),
                        runs, folded.gestures(), traced(voices, startedBy, ofRun, folded),
                        strikes));
    }

    /**
     * Joins a short note that is a run of its own to the note opening the next
     * run, where that note is an octave above it and the voice carries on
     * between them (#860).
     *
     * <p>The shape is the tracker reading the sub-octave of a note while it
     * speaks, and the decoder leaving the voiced state to make the leap; the
     * octave fold cannot remove it, because the octave under a line's lower
     * notes is still inside the line's band. A voice that pauses between the
     * two is a note sung again, read an octave low, and is left to the fold.
     * The note takes the attack's start, and the two runs are traced as one.
     *
     * <p>Not applied to a separated stem: through the separator the same
     * shape was more often the attack read right and the note read an octave
     * high.
     */
    private static void absorbSubOctaveAttacks(PitchTrack pitches, List<Note> notes,
                                               List<String> startedBy, List<Integer> ofRun,
                                               List<MelodyTrace.Run> runs) {
        for (int i = 0; i + 1 < notes.size(); i++) {
            Note attack = notes.get(i);
            Note note = notes.get(i + 1);
            int first = ofRun.get(i);
            int last = ofRun.get(i + 1);
            if (!MelodyTrace.Note.RUN.equals(startedBy.get(i))
                    || first == last
                    || note.midiPitch() - attack.midiPitch() != 12
                    || attack.durationSeconds() > SUB_OCTAVE_ATTACK_SECONDS
                    || note.onsetSeconds() - attack.offsetSeconds() >= MIN_NOTE_SECONDS
                    || voicePausesBetween(pitches, attack, note)) {
                continue;
            }
            notes.set(i, Note.ofSeconds(attack.onsetSeconds(),
                    note.offsetSeconds() - attack.onsetSeconds(), note.midiPitch(),
                    note.confidence()));
            notes.remove(i + 1);
            startedBy.remove(i + 1);
            ofRun.remove(i + 1);
            joinRuns(runs, first, last);
            for (int later = i + 1; later < ofRun.size(); later++) {
                ofRun.set(later, ofRun.get(later) - (last - first));
            }
        }
    }

    /** Folds runs {@code first} to {@code last} into one, the attack among its joined pieces. */
    private static void joinRuns(List<MelodyTrace.Run> runs, int first, int last) {
        List<MelodyTrace.Run> joined = runs.subList(first, last + 1);
        int pieces = 0;
        int held = 0;
        int merged = 1;
        int rearticulations = 0;
        int count = -1;
        for (MelodyTrace.Run run : joined) {
            pieces += run.pieces();
            held += run.held();
            merged += run.joined();
            rearticulations += run.rearticulations();
            count += run.notes();
        }
        MelodyTrace.Run one = new MelodyTrace.Run(joined.get(0).fromSeconds(),
                joined.get(joined.size() - 1).toSeconds(), pieces, held, merged,
                rearticulations, count, joined.get(0).unassignedSeconds());
        joined.clear();
        runs.add(first, one);
    }

    /**
     * Whether the voicedness between two notes dips the way {@link
     * #leastVoicedness} asks of a re-articulation, against the first note's.
     */
    private static boolean voicePausesBetween(PitchTrack pitches, Note first, Note second) {
        int[] span = {frameAt(pitches, first.onsetSeconds()),
                      frameAt(pitches, first.offsetSeconds())};
        double floor = REARTICULATION_DIP_SHARE * medianVoicedness(pitches, span);
        for (int frame = span[1]; frame < frameAt(pitches, second.onsetSeconds()); frame++) {
            if (pitches.voicedness()[frame] < floor) {
                return true;
            }
        }
        return false;
    }

    /** Inverts {@link PitchTrack#timeOf}, which is where every note boundary was read. */
    private static int frameAt(PitchTrack pitches, double seconds) {
        return (int) Math.round((seconds * pitches.sampleRate() - pitches.windowSize() / 2.0)
                / pitches.hopSize());
    }

    private static MelodyTrace.Track track(PitchTrack pitches, int voicedFrames) {
        return new MelodyTrace.Track(pitches.sampleRate(), pitches.windowSize(),
                pitches.hopSize(), pitches.frameRate(), pitches.frameCount(), voicedFrames,
                pitches.frameCount() == 0
                        ? 0 : pitches.timeOf(pitches.frameCount() - 1) + 1 / pitches.frameRate());
    }

    /** One entry per printed note, joined to the run and the gesture that shaped it. */
    private static List<MelodyTrace.Note> traced(List<Note> notes, List<String> startedBy,
                                                 List<Integer> ofRun, Folded folded) {
        List<MelodyTrace.Note> traced = new ArrayList<>(notes.size());
        for (int i = 0; i < notes.size(); i++) {
            Note note = notes.get(i);
            traced.add(new MelodyTrace.Note(note.onsetSeconds(), note.offsetSeconds(),
                    note.midiPitch(), startedBy.get(i), ofRun.get(i),
                    folded.gestureOf(i), folded.shiftOf(i)));
        }
        return traced;
    }

    /**
     * Brings notes the tracker read an octave or two out back to the octave the
     * melody is in (#596).
     *
     * <p>A monophonic tracker that locks onto a harmonic instead of the
     * fundamental reports a multiple of the true frequency, and a multiple of
     * two or four is exactly an octave or two — so the error is a factor and
     * not a random pitch, and the note is recovered by moving it rather than
     * lost by dropping it. Which octave the melody is in is asked of the
     * recording itself, because no fixed bound separates one singer from
     * another or a voice from a played line.
     *
     * <p>The band decides <em>whether</em> a note is out; the centre decides
     * <em>where</em> it goes. Landing a note merely inside the band instead
     * would leave one read two octaves out still an octave out.
     *
     * <p>The decision is taken over a gesture rather than a note (#614): notes
     * following one another within {@link #ONE_GESTURE_SEMITONES} are one line
     * moving, and which side of the band's edge each of them falls on is a
     * semitone of tracker noise.
     *
     * <p>A band narrower than an octave is refused rather than applied: there
     * would be pitch classes with no representative in it at all, so every note
     * of one would be moved on no evidence.
     */
    private static Folded foldOctaves(List<Note> notes, double rangeFloorSemitones,
                                      double rangeSpreadQuantile, int mostOctavesOut,
                                      double gestureSemitones) {
        if (notes.isEmpty()) {
            return new Folded(notes, null, List.of(), new int[0], new int[0]);
        }
        double[] pitches = new double[notes.size()];
        double[] weights = new double[notes.size()];
        for (int i = 0; i < notes.size(); i++) {
            pitches[i] = notes.get(i).midiPitch();
            weights[i] = notes.get(i).durationSeconds();
        }
        double centre = weightedQuantile(pitches, weights, 0.5);
        double[] deviations = new double[pitches.length];
        for (int i = 0; i < pitches.length; i++) {
            deviations[i] = Math.abs(pitches[i] - centre);
        }
        double half = Math.max(rangeFloorSemitones,
                weightedQuantile(deviations, weights, rangeSpreadQuantile));
        if (2 * half < 12) {
            return new Folded(notes,
                    new MelodyTrace.Fold(centre, half, MelodyTrace.Fold.REFUSED),
                    List.of(), new int[notes.size()], new int[notes.size()]);
        }
        List<Note> folded = new ArrayList<>(notes.size());
        List<MelodyTrace.Gesture> gestures = new ArrayList<>();
        int[] gestureOfNote = new int[notes.size()];
        int[] shiftOfNote = new int[notes.size()];
        int from = 0;
        while (from < notes.size()) {
            int to = from + 1;
            while (to < notes.size() && oneGesture(notes.get(to - 1), notes.get(to),
                    gestureSemitones)) {
                to++;
            }
            List<Note> gesture = notes.subList(from, to);
            Shift shift = gestureShift(gesture, centre, half, mostOctavesOut);
            int semitones = shift.semitones();
            for (int i = from; i < to; i++) {
                gestureOfNote[i] = gestures.size();
                shiftOfNote[i] = semitones;
            }
            gestures.add(describe(gesture, shift));
            for (Note note : gesture) {
                folded.add(semitones == 0 ? note : note.transposedBy(semitones));
            }
            from = to;
        }
        return new Folded(folded,
                new MelodyTrace.Fold(centre, half, MelodyTrace.Fold.APPLIED),
                gestures, gestureOfNote, shiftOfNote);
    }

    /** What the fold judged one gesture on, as it stood before any move. */
    private static MelodyTrace.Gesture describe(List<Note> gesture, Shift shift) {
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        double end = 0;
        for (Note note : gesture) {
            lowest = Math.min(lowest, note.midiPitch());
            highest = Math.max(highest, note.midiPitch());
            end = Math.max(end, note.offsetSeconds());
        }
        return new MelodyTrace.Gesture(gesture.get(0).onsetSeconds(), end, gesture.size(),
                lowest, highest, shift.heldMidi(), shift.semitones(), shift.read());
    }

    /**
     * The notes as the fold left them, and what it decided over each gesture.
     *
     * <p>{@code gestureOfNote} and {@code shiftOfNote} are per note of the list
     * handed in, whose order the fold keeps.
     */
    private record Folded(List<Note> notes, MelodyTrace.Fold fold,
                          List<MelodyTrace.Gesture> gestures,
                          int[] gestureOfNote, int[] shiftOfNote) {

        /** Which gesture decided a note's octave, or null where none did. */
        Integer gestureOf(int note) {
            return gestures.isEmpty() ? null : gestureOfNote[note];
        }

        int shiftOf(int note) {
            return gestures.isEmpty() ? 0 : shiftOfNote[note];
        }
    }

    /** What the fold makes of one gesture: how far to move it, and on what reading. */
    private record Shift(int semitones, Integer heldMidi, String read) {
    }

    /**
     * Whether two notes following one another are near enough to be one
     * gesture: nearness in pitch and nothing else, so a gesture bridges a
     * silence of any length. What a condition on that silence would cost is
     * measured on #664.
     */
    private static boolean oneGesture(Note first, Note second, double gestureSemitones) {
        return Math.abs(second.midiPitch() - first.midiPitch()) <= gestureSemitones;
    }

    /**
     * What to move a gesture by: nothing at all where any of it is inside the
     * band, and otherwise whatever the fold makes of the pitch it spends most
     * of its time at, applied to every note of it.
     *
     * <p>One shift for the gesture rather than one per note, so its own
     * intervals survive the move; a gesture that reaches into the band is in
     * the melody's octave whatever the rest of it does.
     */
    private static Shift gestureShift(List<Note> gesture, double centre, double half,
                                      int mostOctavesOut) {
        double[] pitches = new double[gesture.size()];
        double[] weights = new double[gesture.size()];
        for (int i = 0; i < gesture.size(); i++) {
            if (Math.abs(gesture.get(i).midiPitch() - centre) <= half) {
                return new Shift(0, null, MelodyTrace.Gesture.INSIDE);
            }
            pitches[i] = gesture.get(i).midiPitch();
            weights[i] = gesture.get(i).durationSeconds();
        }
        int held = (int) weightedQuantile(pitches, weights, 0.5);
        int semitones = foldedPitch(held, centre, half, mostOctavesOut) - held;
        for (Note note : gesture) {
            if (note.midiPitch() + semitones < 0 || note.midiPitch() + semitones > 127) {
                return new Shift(0, held, MelodyTrace.Gesture.OUT_OF_RANGE);
            }
        }
        return new Shift(semitones, held, semitones == 0
                ? MelodyTrace.Gesture.OUT_OF_REACH : MelodyTrace.Gesture.MOVED);
    }

    /**
     * A pitch outside the band, moved to whichever octave of itself is nearest
     * the centre <em>and</em> inside the band, within {@code mostOctavesOut}.
     *
     * <p>All three conditions bind at once, and the search is over the moves
     * the bound allows rather than over every octave: the nearest octave of all
     * may be one the bound forbids, and settling for the nearest allowed one
     * still recovers the note. Requiring the landing to be inside the band is
     * what refuses a note no allowed move brings home, rather than moving it
     * to the least bad octave of several that are all still out.
     *
     * <p>Searched outwards from no move at all, so a note an octave and a half
     * out — equally far from the centre either way — takes the smaller
     * displacement.
     */
    private static int foldedPitch(int pitch, double centre, double half, int mostOctavesOut) {
        if (Math.abs(pitch - centre) <= half) {
            return pitch;
        }
        int folded = pitch;
        double nearest = Double.POSITIVE_INFINITY;
        for (int octaves = 1; octaves <= mostOctavesOut; octaves++) {
            for (int candidate : new int[] {pitch - 12 * octaves, pitch + 12 * octaves}) {
                if (candidate < 0 || candidate > 127) {
                    continue;
                }
                double distance = Math.abs(candidate - centre);
                if (distance <= half && distance < nearest) {
                    folded = candidate;
                    nearest = distance;
                }
            }
        }
        return folded;
    }

    /**
     * A weighted quantile of a sample: the first value at which that share of
     * the weight has been passed.
     *
     * <p>A quantile rather than a mean because of what it has to survive: a
     * stretch the separator left empty still yields notes (#575), and those
     * carry no evidence about the singer's tessitura. Where they are the
     * shorter part of the recording a share of the weight passes over them,
     * where an average would be pulled towards them.
     */
    private static double weightedQuantile(double[] values, double[] weights, double share) {
        Integer[] order = new Integer[values.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (left, right) -> Double.compare(values[left], values[right]));
        double total = 0;
        for (double weight : weights) {
            total += weight;
        }
        double passed = 0;
        for (int index : order) {
            passed += weights[index];
            if (passed >= share * total) {
                return values[index];
            }
        }
        return values[order[order.length - 1]];
    }

    /**
     * Whether an offset is worth rounding on, and what that was read from: it
     * has to say something {@link Chroma#estimateTuning} can resolve, and the
     * track's own pitches have to sit on the grid it names.
     *
     * <p>An offset that reads as concert pitch is not rounded on at all: a
     * shift that narrow decides nothing but notes already on a rounding
     * boundary, in whichever direction they happened to lie. It is not
     * corroborated either, because there is nothing to corroborate.
     */
    private static MelodyTrace.Tuning tuning(PitchTrack pitches, double tuningOffsetSemitones) {
        // A track with nothing voiced in it disagrees with nothing, so the
        // readings below are kept apart rather than collapsed into whether the
        // rounding took the offer.
        if (Chroma.readsAsConcertPitch(tuningOffsetSemitones)) {
            return refused(tuningOffsetSemitones, MelodyTrace.Tuning.CONCERT_PITCH);
        }
        Double agreement = corroboration(pitches, tuningOffsetSemitones);
        if (agreement == null) {
            return refused(tuningOffsetSemitones, MelodyTrace.Tuning.NOT_WEIGHED);
        }
        boolean honoured = agreement >= TUNING_CORROBORATION_FLOOR;
        return new MelodyTrace.Tuning(tuningOffsetSemitones, agreement,
                TUNING_CORROBORATION_FLOOR, honoured ? tuningOffsetSemitones : 0,
                honoured ? MelodyTrace.Tuning.CORROBORATED
                        : MelodyTrace.Tuning.UNCORROBORATED);
    }

    private static MelodyTrace.Tuning refused(double tuningOffsetSemitones, String read) {
        return new MelodyTrace.Tuning(tuningOffsetSemitones, null,
                TUNING_CORROBORATION_FLOOR, 0, read);
    }

    /**
     * How strongly the track's own pitches sit on the grid an offset names,
     * against {@link #TUNING_CORROBORATION_FLOOR}, or null where no voiced frame
     * carried a reading to measure it from.
     *
     * <p>Read frame by frame rather than from the notes, so that the decision
     * does not depend on the cuts it goes on to move, and weighted by
     * voicedness so a frame the decoder was unsure was singing does not vote
     * like one it was sure of. Unvoiced frames carry the decoder's memory
     * rather than a measurement ({@link PitchTrack}) and are left out.
     */
    private static Double corroboration(PitchTrack pitches, double tuningOffsetSemitones) {
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
        return weight > 0 ? agreement / weight : null;
    }

    /**
     * One unbroken voiced run, cut into notes, and what the cutting did to it.
     *
     * @param startedBy one entry per note, naming the rule that placed its start
     */
    private record Cut(List<Note> notes, List<String> startedBy, MelodyTrace.Run run) {
    }

    private static Cut notesOfRun(PitchTrack pitches, OnsetEnvelope envelope, LevelContour level,
                                  int from, int to, double grid, double steadySemitones,
                                  List<MelodyTrace.Strike> strikes) {
        double frameSeconds = 1 / pitches.frameRate();
        int confirmFrames = Math.max(1, (int) Math.ceil(MIN_NOTE_SECONDS / frameSeconds));
        List<int[]> spans = cut(pitches, from, to, confirmFrames);
        double runEnd = pitches.timeOf(to - 1) + frameSeconds;
        List<int[]> kept = new ArrayList<>();
        for (int[] span : spans) {
            if (holdsAPitch(pitches, span, confirmFrames, steadySemitones)) {
                kept.add(span);
            }
        }

        List<int[]> merged = mergeEqualPitches(pitches, kept, grid);

        double[] onsets = new double[merged.size()];
        for (int i = 0; i < merged.size(); i++) {
            onsets[i] = pitches.timeOf(merged.get(i)[0]);
        }
        // Across the note boundaries too, since a tremolo does not stop for
        // them; each marked when it lies where the pitch moved -- at a note's
        // onset, or in a piece crossing between two notes that held no pitch --
        // since there it is the next note's articulation.
        List<double[]> runDips = new ArrayList<>();
        if (level != null) {
            for (double[] dip : levelDips(level, pitches.timeOf(from), runEnd)) {
                boolean atBoundary = false;
                for (double onset : onsets) {
                    atBoundary |= Math.abs(dip[0] - onset) <= DIP_REACH_SECONDS;
                }
                for (int[] piece : spans) {
                    atBoundary |= !kept.contains(piece)
                            && dip[0] >= pitches.timeOf(piece[0]) - DIP_REACH_SECONDS
                            && dip[0] <= pitches.timeOf(piece[1] - 1) + DIP_REACH_SECONDS;
                }
                runDips.add(new double[] {dip[0], dip[1], atBoundary ? 1 : 0});
            }
        }
        List<Note> notes = new ArrayList<>(merged.size());
        List<String> startedBy = new ArrayList<>(merged.size());
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
            List<String> by = new ArrayList<>();
            starts.add(onset);
            by.add(i == 0 ? MelodyTrace.Note.RUN : MelodyTrace.Note.PITCH);
            if (envelope != null) {
                rearticulations(envelope, level, runDips, pitches, span, onset, end, starts, by,
                        strikes);
            }
            for (int piece = 0; piece < starts.size(); piece++) {
                double pieceStart = starts.get(piece);
                double pieceEnd = piece + 1 < starts.size() ? starts.get(piece + 1) : end;
                notes.add(Note.ofSeconds(pieceStart, pieceEnd - pieceStart, pitch, confidence));
                startedBy.add(by.get(piece));
            }
        }
        double runStart = pitches.timeOf(from);
        return new Cut(notes, startedBy, new MelodyTrace.Run(runStart, runEnd,
                spans.size(), kept.size(), kept.size() - merged.size(),
                notes.size() - merged.size(), notes.size(),
                (merged.isEmpty() ? runEnd : onsets[0]) - runStart));
    }

    /**
     * Whether a span holds a pitch: some window of it {@code frames} long stays
     * inside {@code steadySemitones}.
     *
     * <p>A span shorter than that window holds no pitch either, so this is the
     * only test a piece faces.
     */
    private static boolean holdsAPitch(PitchTrack pitches, int[] span, int frames,
                                       double steadySemitones) {
        for (int start = span[0]; start + frames <= span[1]; start++) {
            double low = Double.POSITIVE_INFINITY;
            double high = Double.NEGATIVE_INFINITY;
            for (int frame = start; frame < start + frames; frame++) {
                double pitch = pitches.midiPitchAt(frame);
                low = Math.min(low, pitch);
                high = Math.max(high, pitch);
            }
            if (high - low <= steadySemitones) {
                return true;
            }
        }
        return false;
    }

    /** Where a note may have been struck again, and which reading proposed it. */
    private enum Proposed { PEAK, STROKE, SWELL, UNSTRUCK }

    private record Candidate(double seconds, Proposed by) {
    }

    /**
     * Adds the boundaries of a note's re-articulations to {@code starts}, the
     * rule that placed each to {@code by}, and what was read at each place one
     * was considered to {@code strikes}.
     *
     * <p>Two things strike a note again: an envelope peak where the voice
     * restarts, and a stroke -- the level falling and coming back with the
     * envelope above its mean -- which is a tongued repeat on a wind instrument
     * whose voice never stops (#882), unless the level dips like a tremolo
     * around it (#885). A cut lands only strictly inside the note, at least {@link
     * #MIN_NOTE_SECONDS} from either end and from the previous cut: nearer the
     * start it is the note's own attack, nearer the end it is the next note's,
     * and either way the piece it would leave is shorter than anything this
     * class calls a note.
     *
     * @param runDips the dips of the run this note is in, each marked where the pitch moved
     */
    private static void rearticulations(OnsetEnvelope envelope, LevelContour level,
                                        List<double[]> runDips, PitchTrack pitches, int[] span,
                                        double onset, double end, List<Double> starts,
                                        List<String> by, List<MelodyTrace.Strike> strikes) {
        double medianVoicedness = medianVoicedness(pitches, span);
        double last = end - MIN_NOTE_SECONDS;
        List<Candidate> candidates = new ArrayList<>();
        for (int frame = envelope.frameOf(onset + MIN_NOTE_SECONDS);
                frame <= envelope.frameOf(last); frame++) {
            // The loop bound is a rounded frame index, so a peak can land a
            // few milliseconds past the boundary it was computed from; the
            // start side needs no twin, because the spacing guard below
            // already holds every cut at least MIN_NOTE_SECONDS after the
            // onset the list opens with.
            if (envelope.timeOf(frame) < last && isPeak(envelope, frame, REARTICULATION_FLOOR)) {
                candidates.add(new Candidate(envelope.timeOf(frame), Proposed.PEAK));
            }
        }
        List<double[]> dips = level == null ? List.of() : levelDips(level, onset, end);
        if (level != null) {
            for (double[] dip : dips) {
                if (dip[0] <= onset || dip[0] >= last || dip[1] < STROKE_DECIBELS) {
                    continue;
                }
                boolean atBoundary = false;
                boolean alone = true;
                for (double[] other : runDips) {
                    // Where the pitch crossed unsteadily to the next note.
                    atBoundary |= other[0] == dip[0] && other[2] == 1;
                    alone &= !echoes(other, dip)
                            || other[2] == 1 && !hasEchoBeyond(runDips, other, dip);
                }
                if (atBoundary) {
                    continue;
                }
                // The envelope is mean-zero: above it, something was struck.
                boolean attacked = strongestNear(envelope, dip[0] - level.offsetSeconds()) > 0;
                candidates.add(new Candidate(dip[0], !alone ? Proposed.SWELL
                        : attacked ? Proposed.STROKE : Proposed.UNSTRUCK));
            }
            candidates.sort(Comparator.comparingDouble(Candidate::seconds));
        }
        for (Candidate candidate : candidates) {
            double time = candidate.seconds();
            boolean peak = candidate.by() == Proposed.PEAK;
            double voicedness = leastVoicedness(pitches, span, time) / medianVoicedness;
            boolean struck = switch (candidate.by()) {
                case PEAK -> voicedness < REARTICULATION_DIP_SHARE;
                case STROKE -> true;
                case SWELL, UNSTRUCK -> false;
            };
            boolean room = time >= starts.get(starts.size() - 1) + MIN_NOTE_SECONDS;
            if (struck && room) {
                starts.add(time);
                by.add(peak ? MelodyTrace.Note.REARTICULATION : MelodyTrace.Note.STROKE);
            }
            // Both readings on the level's axis, whose frames are centred
            // where the envelope's start.
            double centred = peak && level != null ? time + level.offsetSeconds() : time;
            strikes.add(new MelodyTrace.Strike(time,
                    strongestNear(envelope, peak || level == null
                            ? time : time - level.offsetSeconds()),
                    voicedness,
                    level == null ? 0 : deepestDipNear(dips, centred),
                    candidate.by() == Proposed.SWELL ? MelodyTrace.Strike.SWELL
                            : candidate.by() == Proposed.UNSTRUCK ? MelodyTrace.Strike.UNSTRUCK
                            : !struck ? MelodyTrace.Strike.HELD
                            : !room ? MelodyTrace.Strike.CROWDED
                            : peak ? MelodyTrace.Strike.ENVELOPE
                            : MelodyTrace.Strike.LEVEL));
        }
    }

    /**
     * Whether {@code other} is as deep a dip as a tremolo would repeat {@code
     * dip} with: further than {@link #DIP_REACH_SECONDS}, which would be this
     * dip's own bottom, and no further than {@link #TREMOLO_SECONDS}.
     */
    private static boolean echoes(double[] other, double[] dip) {
        double apart = Math.abs(other[0] - dip[0]);
        return apart > DIP_REACH_SECONDS && apart <= TREMOLO_SECONDS
                && other[1] >= STROKE_ALONE_SHARE * dip[1];
    }

    /**
     * Whether a dip where the pitch moved is echoed on its far side from
     * {@code dip} by one where it did not: a tremolo's dips run on through a
     * change of pitch, and an articulation's do not. Two repeats either side of
     * a change at a tremolo's spacing read as a tremolo too (#885).
     */
    private static boolean hasEchoBeyond(List<double[]> dips, double[] boundary, double[] dip) {
        for (double[] beyond : dips) {
            if (beyond[2] == 0
                    && Math.signum(beyond[0] - boundary[0]) == Math.signum(boundary[0] - dip[0])
                    && echoes(beyond, boundary)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The dips inside a note: each local minimum of the level, at least {@link
     * #MIN_NOTE_SECONDS} from either end, as its time and how far it lies under
     * the lower of the loudest frames within {@link #DIP_REACH_SECONDS} either
     * side of it inside the note. Both sides are asked so that a note fading
     * out, or one arriving, is not a dip: the level has to come back.
     */
    private static List<double[]> levelDips(LevelContour level, double onset, double end) {
        double[] db = level.decibels();
        int reach = (int) Math.ceil(DIP_REACH_SECONDS * level.frameRate());
        int first = level.frameOf(onset);
        int last = level.frameOf(end) - 1;
        List<double[]> dips = new ArrayList<>();
        for (int frame = Math.max(1, level.frameOf(onset + MIN_NOTE_SECONDS));
                frame <= Math.min(db.length - 2, level.frameOf(end - MIN_NOTE_SECONDS));
                frame++) {
            if (db[frame] > db[frame - 1] || db[frame] >= db[frame + 1]) {
                continue;
            }
            double before = Double.NEGATIVE_INFINITY;
            for (int f = Math.max(first, frame - reach); f < frame; f++) {
                before = Math.max(before, db[f]);
            }
            double after = Double.NEGATIVE_INFINITY;
            for (int f = frame + 1; f <= Math.min(last, frame + reach); f++) {
                after = Math.max(after, db[f]);
            }
            double depth = Math.min(before, after) - db[frame];
            if (depth > 0) {
                dips.add(new double[] {level.timeOf(frame), depth});
            }
        }
        return dips;
    }

    private static double deepestDipNear(List<double[]> dips, double time) {
        double deepest = 0;
        for (double[] dip : dips) {
            if (Math.abs(dip[0] - time) <= DIP_REACH_SECONDS) {
                deepest = Math.max(deepest, dip[1]);
            }
        }
        return deepest;
    }

    private static double strongestNear(OnsetEnvelope envelope, double time) {
        int centre = envelope.frameOf(time);
        int reach = (int) Math.ceil(DIP_REACH_SECONDS * envelope.frameRate());
        double strongest = Double.NEGATIVE_INFINITY;
        for (int f = Math.max(0, centre - reach);
                f <= Math.min(envelope.length() - 1, centre + reach); f++) {
            strongest = Math.max(strongest, envelope.strength()[f]);
        }
        return strongest;
    }

    /**
     * The least voicedness within {@link #DIP_REACH_SECONDS} of a time, inside
     * the span: the voice itself restarting there dips it against the note's
     * own median.
     *
     * <p>The localisation is deliberately coarse -- the pitch window smears
     * any dip by its own width -- but it is not free to widen: on the real
     * singing a reach of this size and one spanning the whole note answer
     * identically, and on the accompanied package the note-wide reach admits
     * more pad cuts. A single-frame reach loses real splits on both corpora.
     * It keeps a note's far end out of the question; it does not place the
     * restart precisely.
     */
    private static double leastVoicedness(PitchTrack pitches, int[] span, double time) {
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
        return dip;
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
