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
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Engraves a {@link NoteTrack} as staff notation in LilyPond source.
 *
 * <p>Emitted straight from the domain model. The alternative — export MusicXML
 * and run {@code musicxml2ly} — loses information on the way through, and it
 * loses precisely the things this class exists to get right: how a note is
 * spelled, where a tie falls and how a bar groups. {@link MusicXmlExport} is
 * therefore a <em>sibling</em> of this class rather than a stage before it:
 * both read {@link StaffLayout}, and neither reads the other.
 *
 * <p>Which is to say the engraving decisions are not here. {@link StaffLayout}
 * decides where the bar lines fall, which notes gather into a chord, where a
 * held note is cut and tied, and which bars carry a tuplet bracket; this class
 * spells those decisions as LilyPond. Read {@link StaffWriter} for why the two
 * are separated.
 *
 * <p>What is decided here is LilyPond's own vocabulary: how a pitch is named,
 * how a note value is written, that every bar ends with a {@code |} bar check —
 * so that if the layout's arithmetic were ever wrong LilyPond would say so
 * instead of engraving a bar of the wrong length, which it will otherwise do
 * without complaint — and how a meter states its beat grouping.
 *
 * <p><b>Tuplets are carried, not inferred.</b> {@link #toLilyPond(Score,
 * NoteTrack)} engraves what a plain {@code Score} can say, and a {@code Score}
 * cannot say that a bar is in triplets — three onsets a third of a beat apart
 * and three a half beat apart are both legal on the sixth-of-a-beat grid.
 * {@link #toLilyPond(QuantizedScore, NoteTrack)} takes the quantizer's own
 * per-bar decision and brackets those bars; the {@code Score} overload snaps
 * them onto the 64th-note grid as it always did, because guessing is worse. See
 * #92 and {@link TupletBar}.
 */
public final class StaffNotation {

    /** The LilyPond language version the emitted source declares. */
    static final String LILYPOND_VERSION = "2.24.0";

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
        LilyPondStaffWriter writer = new LilyPondStaffWriter();
        StaffLayout.write(score, track, tuplets, writer);
        return writer.toString();
    }

    /**
     * Spells one laid-out staff as a {@code \new Staff} block.
     *
     * <p>A token buffer and nothing more: it makes no decision the layout has
     * not already made, and every method below is a translation of one callback
     * into LilyPond's vocabulary.
     */
    private static final class LilyPondStaffWriter implements StaffWriter {

        private final StringBuilder out = new StringBuilder();

        /** The current bar's tokens, joined and flushed by {@link #endBar}. */
        private final List<String> tokens = new ArrayList<>();

        /**
         * The full-bar rest token, when the bar is one, and {@code null}
         * otherwise.
         *
         * <p>Held rather than written straight out because it replaces the
         * bar's token line instead of joining it: {@code R1} is the whole bar,
         * so anything beside it would double the bar's length.
         */
        private String wholeBarRest;

        @Override
        public void startStaff(String name, StaffClef clef, Optional<Key> key) {
            out.append("  \\new Staff \\with { instrumentName = \"")
                    .append(escape(name)).append("\" } {\n");
            out.append("    \\clef \"").append(clef.lilyPondName()).append("\"\n");
            // c \major when the score has no key, which prints every accidental
            // explicitly. That is cluttered and it is honest: a key signature is
            // a claim about the music, and guessing one puts accidentals on the
            // page that the pipeline never decided on.
            out.append("    \\key ")
                    .append(key.map(k -> k.tonic().lilyPondName() + " \\" + k.mode().lilyPondName())
                            .orElse("c \\major"))
                    .append('\n');
        }

        @Override
        public void startBar(int index, TimeSignature meter, boolean meterChanged) {
            tokens.clear();
            wholeBarRest = null;
            if (meterChanged) {
                appendMeter(meter);
            }
        }

        @Override
        public void tempo(NoteValue unit, long perMinute) {
            // Locale.ROOT: a LilyPond integer is not a localised number, and
            // under fr_FR this would emit a decimal comma LilyPond rejects.
            out.append(String.format(Locale.ROOT, "    \\tempo %s = %d%n",
                            unit.lilyPondToken(), perMinute)
                    .replace(System.lineSeparator(), "\n"));
        }

        @Override
        public void pickup(long wholeNotesNumerator, long wholeNotesDenominator) {
            out.append("    \\partial ")
                    .append(LilyPondDuration.scaled(wholeNotesNumerator, wholeNotesDenominator))
                    .append('\n');
        }

        @Override
        public void openTuplet(int actual, int normal) {
            tokens.add("\\tuplet " + actual + "/" + normal + " {");
        }

        @Override
        public void closeTuplet() {
            tokens.add("}");
        }

        @Override
        public void symbol(List<PitchSpelling> pitches, NoteValue value, double soundingQuarters,
                           boolean tied) {
            tokens.add(head(pitches) + value.lilyPondToken() + (tied ? "~" : ""));
        }

        @Override
        public void wholeBarRest(long wholeNotesNumerator, long wholeNotesDenominator) {
            wholeBarRest = "R"
                    + LilyPondDuration.scaled(wholeNotesNumerator, wholeNotesDenominator);
        }

        @Override
        public void endBar() {
            out.append("    ")
                    .append(wholeBarRest != null ? wholeBarRest : String.join(" ", tokens))
                    .append(" |\n");
        }

        @Override
        public void endStaff() {
            out.append("    \\bar \"|.\"\n");
            out.append("  }\n");
        }

        @Override
        public String toString() {
            return out.toString();
        }

        /**
         * The time signature, spelled by {@link LilyPondMeter} so that a staff
         * part and a chord chart of the same piece cannot state it differently.
         */
        private void appendMeter(TimeSignature meter) {
            out.append("    ").append(LilyPondMeter.time(meter)).append('\n');
        }

        /** What a token is written on: a rest, a pitch, or a chord. */
        private static String head(List<PitchSpelling> pitches) {
            if (pitches.isEmpty()) {
                return "r";
            }
            if (pitches.size() == 1) {
                return pitches.get(0).lilyPondAbsoluteName();
            }
            return pitches.stream().map(PitchSpelling::lilyPondAbsoluteName)
                    .collect(Collectors.joining(" ", "<", ">"));
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
