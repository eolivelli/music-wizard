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
 * <p><strong>Two notes of the same pitch with no gap between them are one note
 * here.</strong> Nothing in a pitch track distinguishes them — the pitch does
 * not change, and a re-articulation is an amplitude event. Splitting them needs
 * the onset envelope, which this stage deliberately does not read yet: an onset
 * detector that can also cut a note that was merely accented is a second way to
 * be wrong, and the first version is better judged with one. See #495.
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
            notes.addAll(notesOfRun(pitches, frame, end));
            frame = end;
        }
        return new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, trackConfidence(notes));
    }

    /** One unbroken voiced run, cut into notes. */
    private static List<Note> notesOfRun(PitchTrack pitches, int from, int to) {
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

        List<int[]> merged = mergeEqualPitches(pitches, kept);

        List<Note> notes = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            int[] span = merged.get(i);
            // Up to the next surviving note rather than to its own last frame,
            // so the frames dropped between them belong to the note being left
            // rather than to no note at all.
            double onset = pitches.timeOf(span[0]);
            double end = i + 1 < merged.size() ? pitches.timeOf(merged.get(i + 1)[0]) : runEnd;
            notes.add(Note.ofSeconds(onset, end - onset, medianPitch(pitches, span),
                    Confidence.clamped(meanVoicedness(pitches, span))));
        }
        return notes;
    }

    /**
     * Joins neighbouring spans that land on the same semitone.
     *
     * <p>Two adjacent spans of one pitch are one note that wandered far enough
     * to be cut and came back — and the class already cannot tell a note from
     * the same note sung twice with no gap (#495), so joining them takes away a
     * false note without taking away any true one it could otherwise have kept.
     */
    private static List<int[]> mergeEqualPitches(PitchTrack pitches, List<int[]> spans) {
        List<int[]> merged = new ArrayList<>(spans.size());
        for (int[] span : spans) {
            if (!merged.isEmpty()) {
                int[] previous = merged.get(merged.size() - 1);
                if (previous[1] == span[0]
                        && medianPitch(pitches, previous) == medianPitch(pitches, span)) {
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
     * A span's pitch: the median of its frames, rounded.
     *
     * <p>The median rather than the mean, because a span's first frames are the
     * ones that crossed into it and sit between this note and the one before.
     *
     * <p>Both middle values on an even span, rather than the upper one. A note
     * wavering across a semitone boundary spends half its frames either side,
     * and taking the upper middle rounds every such note up — which is a bias
     * that shows only on the notes least able to afford one.
     */
    private static int medianPitch(PitchTrack pitches, int[] span) {
        double[] sorted = new double[span[1] - span[0]];
        for (int frame = span[0]; frame < span[1]; frame++) {
            sorted[frame - span[0]] = pitches.midiPitchAt(frame);
        }
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        double median = sorted.length % 2 == 1
                ? sorted[middle]
                : (sorted[middle - 1] + sorted[middle]) / 2;
        return (int) Math.round(median);
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
