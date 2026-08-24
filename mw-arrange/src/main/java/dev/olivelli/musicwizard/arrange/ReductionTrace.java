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

import dev.olivelli.musicwizard.core.model.NoteTrack;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What {@link PlayableMelody} did to every note of the estimate (#680).
 *
 * <p>The reduction runs when a part is rendered rather than when a recording is
 * analysed, so this is not persisted and is not a workspace trace: the report
 * runs the reduction itself and reads the records off that run.
 *
 * @param part  the reduced track, exactly as {@link PlayableMelody#reduce} would
 *              have returned it
 * @param notes one entry per note of the estimate, in the estimate's own order
 * @param heads one entry per note {@code part} prints, in the same order
 */
public record ReductionTrace(NoteTrack part, List<Source> notes, List<Head> heads) {

    public ReductionTrace {
        Objects.requireNonNull(part, "part");
        notes = notes == null ? List.of() : List.copyOf(notes);
        heads = heads == null ? List.of() : List.copyOf(heads);
    }

    /**
     * One note of the estimate, and what became of it.
     *
     * <p>Every note reaches exactly one head: the grouping partitions the
     * estimate, so nothing is discarded and {@code head} is always a head that
     * was printed.
     *
     * @param fromSeconds   where the note starts
     * @param toSeconds     where it ends
     * @param midiPitch     the pitch the estimate read
     * @param line          the lyric line whose syllable claimed it, or null
     * @param word          that syllable's index in the line, or null
     * @param silenceBeats  how much silence separates it from the syllable
     *                      nearest it by that measure, or null where no line's
     *                      hull covers the note at all
     * @param claimBeats    how much silence a claim was allowed
     * @param head          which of {@link ReductionTrace#heads} it reached
     * @param groupedBy     what put it in a head with its neighbours, as one of
     *                      the constants below
     * @param read          {@code chosen} where the head took its pitch from
     *                      this note, {@code absorbed} where it took another's.
     *                      What the head goes on to print is
     *                      {@link Head#returned}'s to say, since the pass
     *                      between the heads can replace it
     */
    public record Source(
            double fromSeconds,
            double toSeconds,
            int midiPitch,
            Integer line,
            Integer word,
            Double silenceBeats,
            double claimBeats,
            int head,
            String groupedBy,
            String read) {

        /** Nothing joined it to a neighbour: it is a head on its own. */
        public static final String ALONE = "alone";

        /** One syllable claimed it and its neighbours, and prints one note (#592). */
        public static final String SYLLABLE = "syllable";

        /** The ornament rule joined it to the note it leads into. */
        public static final String ORNAMENT = "ornament";

        /** Its head took its pitch from this note. */
        public static final String CHOSEN = "chosen";

        /** Its head took the pitch of another note of the group. */
        public static final String ABSORBED = "absorbed";

        public Source {
            Objects.requireNonNull(groupedBy, "groupedBy");
            Objects.requireNonNull(read, "read");
            requireSyllable(line, word);
        }

        /** Whether a syllable claimed the note, which is what the bound decides. */
        public boolean claimed() {
            return line != null;
        }
    }

    /**
     * One note the part prints, and where its pitch, onset and length came
     * from.
     *
     * @param fromSeconds where the head starts
     * @param toSeconds   where it ends
     * @param midiPitch   the pitch it prints
     * @param line        the lyric line of the syllable it was grouped by, or
     *                    null where no syllable claimed the group
     * @param word        that syllable's index in the line, or null
     * @param melisma     whether that syllable is marked as one (#597), which is
     *                    what leaves its run printed rather than collapsed
     * @param fromNote    the first note of the estimate the head covers
     * @param notes       how many it covers, which are consecutive
     * @param pitchNote   the note of the estimate whose pitch it prints
     * @param releaseNote the note whose release it ends on
     * @param pitch       how that pitch was chosen
     * @param onset       where its start came from
     * @param returned    what the pass between the heads made of it
     */
    public record Head(
            double fromSeconds,
            double toSeconds,
            int midiPitch,
            Integer line,
            Integer word,
            boolean melisma,
            int fromNote,
            int notes,
            int pitchNote,
            int releaseNote,
            Pitch pitch,
            Onset onset,
            Return returned) {

        public Head {
            Objects.requireNonNull(pitch, "pitch");
            Objects.requireNonNull(onset, "onset");
            Objects.requireNonNull(returned, "returned");
            requireSyllable(line, word);
        }
    }

    /** A syllable is a line and a word in it, so half of one is not a claim. */
    private static void requireSyllable(Integer line, Integer word) {
        if ((line == null) != (word == null)) {
            throw new IllegalArgumentException("a syllable needs both a line and a word, got "
                    + line + " and " + word);
        }
    }

    /**
     * Which of the group's pitches the head prints.
     *
     * <p>The arrival is what the group ends on and the dominant is what it
     * sounds longest; where they differ and the arrival is too slight to count
     * as an arrival, the chart is asked whether the dominant is a chord tone the
     * arrival is not.
     *
     * <p>{@code read} keeps apart the ways the chart can leave the arrival
     * standing: naming no chord under the group, naming one this does not trust,
     * naming one that admits both pitches or neither, and naming one that admits
     * the arrival itself. They are one outcome and four different facts about
     * the recording.
     *
     * @param arrivalMidi    the pitch the group ends on
     * @param arrivalBeats   how long that pitch sounds across the whole group
     * @param dominantMidi   the pitch that sounds longest
     * @param dominantBeats  how long that one sounds
     * @param requiredBeats  what the arrival had to reach to settle unaided
     * @param chord          the span the chart was asked about, whatever became
     *                       of it, or null where it was not asked
     * @param chordConfidence how far that span was trusted, under the same
     *                       condition
     * @param requiredConfidence how far it had to be trusted to break a tie
     * @param read           one of the constants below
     */
    public record Pitch(
            int arrivalMidi,
            double arrivalBeats,
            int dominantMidi,
            double dominantBeats,
            double requiredBeats,
            String chord,
            Double chordConfidence,
            double requiredConfidence,
            String read) {

        /** The group settled on what it arrived at, and nothing was asked. */
        public static final String SETTLED = "settled";

        /** Nothing covering the group names a chord, so the chart had no answer. */
        public static final String NO_CHORD = "no-chord";

        /** A chord covers it, below the confidence a tie-break needs. */
        public static final String UNTRUSTED = "untrusted";

        /** The chord was read, and it admits both pitches or neither. */
        public static final String UNAIDED = "unaided";

        /** The chord was read, and it admits the arrival and not the other pitch. */
        public static final String CONFIRMED = "confirmed";

        /** The chart put the pitch the group holds longest in place of the arrival. */
        public static final String CHART = "chart";

        public Pitch {
            Objects.requireNonNull(read, "read");
        }
    }

    /**
     * Where the head's start came from (#616, #641).
     *
     * <p>A head that opens a syllable may take the aligner's measurement of
     * where that syllable begins, under three bounds; {@code read} names the
     * first that refused it.
     *
     * @param melodySeconds   where the group's own first note starts
     * @param syllableSeconds where the aligner put the syllable's start, or null
     *                        where the head opens no syllable
     * @param leftBeats       what taking that start would leave of the head, or
     *                        null under the same condition
     * @param requiredBeats   what it has to leave
     * @param read            one of the constants below
     */
    public record Onset(
            double melodySeconds,
            Double syllableSeconds,
            Double leftBeats,
            double requiredBeats,
            String read) {

        /** The head opens no syllable, so it keeps the melody's own onset. */
        public static final String MELODY = "melody";

        /** The aligner's syllable start was taken. */
        public static final String SYLLABLE = "syllable";

        /** That start is at or before the group's own, so there was nothing to move. */
        public static final String EARLY = "early";

        /** It would leave less of the head than this class discards as decoration. */
        public static final String STUB = "stub";

        /** It falls where the group's notes hold nothing. */
        public static final String SILENT = "silent";

        public Onset {
            Objects.requireNonNull(read, "read");
        }
    }

    /**
     * What the pass over the finished heads made of this one (#670).
     *
     * <p>The rule needs both a chord and a key to refuse anything, so a head one
     * of them does not cover is left alone without being asked. That is a
     * reading of its own here and not support, since a recording with no chart
     * or no key would otherwise read as one whose harmony admits every head.
     *
     * @param fromMidi   the pitch the group chose, which the head prints unless
     *                   the pass replaced it
     * @param leftMidi   the nearest head before it the harmony accounts for, or
     *                   null where there is none
     * @param rightMidi  the nearest such head after it, or null
     * @param homeMidi   the pitch it was printed at instead, or null where the
     *                   pass left it alone
     * @param beats      how long the head lasts
     * @param read       one of the constants below
     */
    public record Return(
            int fromMidi,
            Integer leftMidi,
            Integer rightMidi,
            Integer homeMidi,
            double beats,
            String read) {

        /** The chord under it or the key signature admits its pitch. */
        public static final String SUPPORTED = "supported";

        /** Nothing under it names a chord, so the rule had nothing to refuse it with. */
        public static final String NO_CHORD = "no-chord";

        /** No key covers it, and one reference cannot refuse a head alone. */
        public static final String NO_KEY = "no-key";

        /** Another head sounds while it does, so nothing left and nothing returned. */
        public static final String OVERLAPPED = "overlapped";

        /** Too long to be anything but a note held on purpose. */
        public static final String HELD = "held";

        /** The line does not come back to a supported head on both sides of it. */
        public static final String UNBOUNDED = "unbounded";

        /** Those two heads are too far apart for the line to have returned. */
        public static final String NO_RETURN = "no-return";

        /** It lies between them, which is what a chromatic passing tone looks like. */
        public static final String PASSING = "passing";

        /** It is too far from where the line returned to for this to be a wobble. */
        public static final String DEPARTED = "departed";

        /** It was printed as the pitch the line returned to. */
        public static final String RETURNED = "returned";

        public Return {
            Objects.requireNonNull(read, "read");
        }
    }

    /**
     * How much each rule accounted for, counted off the records above.
     *
     * <p>Counted here so that the page and {@code tools/PlayablePartCheck.java}
     * state one thing rather than each deriving its own.
     *
     * @param estimateNotes notes the estimate held
     * @param heads         notes the part prints
     * @param unclaimed     estimate notes no syllable claimed
     * @param collapsed     estimate notes a syllable's own head absorbed
     * @param ornaments     estimate notes absorbed as an ornament of the next
     * @param syllables     syllables that print at least one head
     * @param melismas      of those, the ones marked as a melisma
     * @param fromAligner   heads whose onset came from the aligner
     * @param chartTies     heads whose printed pitch the chart chose
     * @param returned      heads the pass between them replaced
     * @param moved         those of them it printed at another pitch. Fewer than
     *                      {@code returned} where the line came back to the very
     *                      pitch it left, which the rule reaches and the pitches
     *                      cannot show
     */
    public record Counts(
            int estimateNotes,
            int heads,
            int unclaimed,
            int collapsed,
            int ornaments,
            int syllables,
            int melismas,
            int fromAligner,
            int chartTies,
            int returned,
            int moved) {
    }

    /** What each rule accounted for on this recording. */
    public Counts counts() {
        int unclaimed = 0;
        int collapsed = 0;
        int ornaments = 0;
        for (Source note : notes) {
            if (!note.claimed()) {
                unclaimed++;
            }
            if (Source.ABSORBED.equals(note.read())) {
                if (Source.ORNAMENT.equals(note.groupedBy())) {
                    ornaments++;
                } else {
                    collapsed++;
                }
            }
        }
        // A set rather than a run of equal neighbours: one syllable's heads need
        // not be consecutive, since another syllable's can sound between them.
        Set<Long> claimed = new HashSet<>();
        Set<Long> sustained = new HashSet<>();
        int fromAligner = 0;
        int chartTies = 0;
        int returned = 0;
        int moved = 0;
        for (Head head : heads) {
            if (head.line() != null) {
                long syllable = ((long) head.line() << 32) | head.word();
                claimed.add(syllable);
                if (head.melisma()) {
                    sustained.add(syllable);
                }
            }
            if (Onset.SYLLABLE.equals(head.onset().read())) {
                fromAligner++;
            }
            if (Pitch.CHART.equals(head.pitch().read())) {
                chartTies++;
            }
            if (Return.RETURNED.equals(head.returned().read())) {
                returned++;
            }
            if (head.returned().fromMidi() != head.midiPitch()) {
                moved++;
            }
        }
        return new Counts(notes.size(), heads.size(), unclaimed, collapsed, ornaments,
                claimed.size(), sustained.size(), fromAligner, chartTies, returned, moved);
    }
}
