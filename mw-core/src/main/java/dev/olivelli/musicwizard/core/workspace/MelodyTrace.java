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

package dev.olivelli.musicwizard.core.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;

/**
 * What the melody stage did between hearing a signal and printing notes (#679).
 *
 * <p>A record and never an input: nothing reads it back into an analysis, so a
 * run that writes none cuts the same notes.
 *
 * <p>The per-frame pitch and voicedness the segmentation read are deliberately
 * not here. They are the largest thing the stage holds and the least readable,
 * and every decision taken on them is a note, a gesture or a run — so what is
 * kept is one entry per decision and a summary per run.
 *
 * @param signal   what the tracker was handed, or null where nothing recorded it
 * @param track    the pitch track it returned, or null where none was recorded
 * @param tuning   the offset the rounding was asked to honour, or null
 * @param fold     what the octave fold judged the melody's own octave to be, or
 *                 null where no note reached it
 * @param runs     one entry per voiced run, in time order
 * @param gestures one entry per gesture the fold decided over, in time order,
 *                 and empty where the fold decided nothing
 * @param notes    one entry per note the stage printed, in time order
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MelodyTrace(
        String signal,
        Track track,
        Tuning tuning,
        Fold fold,
        List<Run> runs,
        List<Gesture> gestures,
        List<Note> notes) {

    /** The stage this trace belongs to, which is also its report phase. */
    public static final String STAGE = "melody";

    /** A separator ran and the tracker read its vocal stem. */
    public static final String SEPARATED_VOCAL = "separated vocal";

    /** No stem was to be had, so the tracker read the recording itself. */
    public static final String FULL_MIX = "full mix";

    public MelodyTrace {
        runs = runs == null ? List.of() : List.copyOf(runs);
        gestures = gestures == null ? List.of() : List.copyOf(gestures);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /**
     * The same trace, naming the signal the tracker was handed.
     *
     * <p>Which signal that was is the caller's fact and not the segmenter's:
     * there is no separation in the module that cuts the notes.
     */
    public MelodyTrace readFrom(String signal) {
        return new MelodyTrace(signal, track, tuning, fold, runs, gestures, notes);
    }

    /**
     * The pitch track as it reached the segmentation.
     *
     * <p>{@code voicedFrames} is what makes a signal with nothing in it legible:
     * a stem the separator emptied still yields a track of the right length, and
     * only this says that none of it was singing.
     *
     * @param sampleRate   the rate the tracker ran at
     * @param windowSize   analysis window in samples
     * @param hopSize      samples between frames
     * @param frameRate    frames a second
     * @param frames       how many the signal gave
     * @param voicedFrames how many of them the decoder called singing
     * @param spanSeconds  how long the track runs for
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Track(
            int sampleRate,
            int windowSize,
            int hopSize,
            double frameRate,
            int frames,
            int voicedFrames,
            double spanSeconds) {
    }

    /**
     * The tuning offset the rounding was offered, and what became of it (#567).
     *
     * <p>{@code read} is the comparison rather than what follows from it. The
     * offset is measured on the mix by the chroma front end, so a melody read
     * from a stem is asked whether its own pitches sit on the grid that offset
     * names before it is rounded on one.
     *
     * @param offsetSemitones   how far the recording was said to sit above A440,
     *                          or zero where nothing measured one
     * @param agreement         how strongly this track's own voiced frames sit on
     *                          that grid, or null wherever the comparison was
     *                          never made — which {@code read} distinguishes
     * @param required          what the agreement had to reach
     * @param appliedSemitones  the grid the notes were rounded on
     * @param read              why the offer did or did not reach the rounding,
     *                          as one of the constants below
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tuning(
            double offsetSemitones,
            Double agreement,
            double required,
            double appliedSemitones,
            String read) {

        /** The offset says nothing the tuning estimator can resolve. */
        public static final String CONCERT_PITCH = "concert-pitch";

        /**
         * The offset named a grid, and this signal carried no voiced frame to
         * weigh against it — which is what an emptied stem leaves, and is not a
         * reading about the singing.
         */
        public static final String NOT_WEIGHED = "not-weighed";

        /** The track's own pitches sit on the grid it names. */
        public static final String CORROBORATED = "corroborated";

        /** They do not, so the notes were rounded on A440 instead. */
        public static final String UNCORROBORATED = "uncorroborated";

        public Tuning {
            Objects.requireNonNull(read, "read");
        }

        /**
         * Whether the front end measured a tuning to offer at all — zero being
         * the estimator's answer for no evidence.
         */
        public boolean measured() {
            return offsetSemitones != 0;
        }
    }

    /**
     * The band the octave fold judged the melody against (#596, #614).
     *
     * <p>Both figures are readings of the notes rather than settings: the centre
     * is where the line spends its time, and the half-band is the wider of a
     * floor and what this line's own spread asks for.
     *
     * @param centreMidi          the pitch the melody sits around
     * @param halfBandSemitones   how far either side of it counts as in the octave
     * @param read                {@code applied} where the band was wide enough to
     *                            judge a gesture by, {@code refused} where it was
     *                            narrower than an octave and nothing was moved
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fold(double centreMidi, double halfBandSemitones, String read) {

        /** The band was judged against. */
        public static final String APPLIED = "applied";

        /** It was narrower than an octave, which no pitch has a representative in. */
        public static final String REFUSED = "refused";

        public Fold {
            Objects.requireNonNull(read, "read");
        }
    }

    /**
     * One unbroken run of voiced frames, and what the cutting made of it.
     *
     * <p>{@code unassignedSeconds} is the run's own head, before the first piece
     * that held a pitch: a singer arriving at a note from below spends it
     * gliding, and those frames belong to no note (#580). Every later gap is
     * absorbed by the note before it, so this is all of the run that no note
     * covers.
     *
     * @param fromSeconds        where the voiced run starts
     * @param toSeconds          where it ends
     * @param pieces             pieces the pitch's own departures cut it into
     * @param held               how many of them held a pitch long enough to
     *                           become a note
     * @param joined             how many held pieces were joined to their
     *                           neighbour for rounding to the same semitone
     * @param rearticulations    boundaries the onset envelope added inside a note
     * @param notes              notes the run produced
     * @param unassignedSeconds  how much of the run no note covers
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Run(
            double fromSeconds,
            double toSeconds,
            int pieces,
            int held,
            int joined,
            int rearticulations,
            int notes,
            double unassignedSeconds) {
    }

    /**
     * One run of notes the fold decided together, because they are one line
     * moving rather than independent readings (#614).
     *
     * @param fromSeconds      where the gesture's first note starts
     * @param toSeconds        where its last one ends
     * @param notes            how many notes it carries
     * @param lowestMidi       its lowest note as the tracker read it
     * @param highestMidi      its highest
     * @param heldMidi         the pitch it spends most of its time at, which is
     *                         what the fold judges, or null where some note of it
     *                         was inside the band and none was needed
     * @param shiftSemitones   what every note of it was moved by
     * @param read             {@code inside} where part of it lay in the band,
     *                         {@code moved} where the fold brought it home,
     *                         {@code out-of-reach} where no allowed octave landed
     *                         inside the band, {@code out-of-range} where the move
     *                         would have left the pitch range
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gesture(
            double fromSeconds,
            double toSeconds,
            int notes,
            int lowestMidi,
            int highestMidi,
            Integer heldMidi,
            int shiftSemitones,
            String read) {

        /** Some note of the gesture lay in the band, so the fold left it alone. */
        public static final String INSIDE = "inside";

        /** The fold moved it into the melody's own octave. */
        public static final String MOVED = "moved";

        /** No octave the bound allows lands inside the band. */
        public static final String OUT_OF_REACH = "out-of-reach";

        /** The move would have carried a note off the pitch range. */
        public static final String OUT_OF_RANGE = "out-of-range";

        public Gesture {
            Objects.requireNonNull(read, "read");
        }
    }

    /**
     * One note the stage printed, and the rule that placed its start.
     *
     * @param fromSeconds     where it starts
     * @param toSeconds       where it ends
     * @param midiPitch       the pitch it prints, after any fold
     * @param startedBy       {@code run} where it opens a voiced run,
     *                        {@code pitch} where the pitch left the note before
     *                        it, {@code re-articulation} where the onset envelope
     *                        cut a held note in two
     * @param run             which run of {@link MelodyTrace#runs} it came from
     * @param gesture         which gesture of {@link MelodyTrace#gestures} decided
     *                        its octave, or null where the fold decided nothing
     * @param shiftSemitones  what that gesture moved it by
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Note(
            double fromSeconds,
            double toSeconds,
            int midiPitch,
            String startedBy,
            int run,
            Integer gesture,
            int shiftSemitones) {

        /** It is the first note of a voiced run. */
        public static final String RUN = "run";

        /** The pitch left the note before it and stayed away. */
        public static final String PITCH = "pitch";

        /** The onset envelope struck the same pitch again (#495). */
        public static final String REARTICULATION = "re-articulation";

        public Note {
            // The page prints this, so a note without one is a note this build
            // cannot draw.
            Objects.requireNonNull(startedBy, "startedBy");
        }
    }
}
