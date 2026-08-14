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
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.Score;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A lead sheet: the melody on a staff, its chord symbols above, the words
 * below.
 *
 * <p>Nothing here is new engraving. The chord names are {@link ChordChart}'s
 * own block, the staff is {@link StaffNotation}'s, and the words are {@link
 * LyricEngraving}'s — this class places the three in one {@code \score} and
 * settles what each of them stops carrying when it is no longer alone on the
 * page.
 *
 * <p><b>This page and {@code chords.pdf} can still put a chord in a different
 * bar, and it is not a second emitter that does it (#501).</b> A chart is laid
 * out from a score whose chords carry no beat positions, so {@link ChartLayout}
 * bars them on the tracked downbeats; a staff has to be barred by the tempo
 * map, because that is the axis its notes were quantized onto, so the same
 * layout takes its other route here. The two coincide only when the tracked
 * downbeat happens to fall on a bar line of the tempo map — and nothing puts it
 * there, because the map carries no bar phase at all (#84). Where it does not,
 * this page's bar lines are displaced against the music by however far the
 * phase is out, with or without a chart to compare it to.
 *
 * <p>The two blocks are aligned by musical time and not by counting bars. Each
 * writes its own bar checks, and LilyPond places both against the same clock,
 * so a chart that runs past the last note or a melody that runs past the last
 * chord simply leaves the other context empty rather than sliding it. What is
 * <em>not</em> automatic is where each of them starts: a pickup is a shortened
 * first bar, and the two layouts decide it separately.
 * {@code LeadSheetTest} holds a fixture with one for that reason.
 */
public final class LeadSheet {

    private LeadSheet() {
    }

    /**
     * A complete LilyPond document engraving one score as a lead sheet.
     *
     * @throws IllegalArgumentException if the track is percussion, or holds a
     *     note that has not been quantized
     */
    public static String toLilyPond(QuantizedScore quantized, NoteTrack melody) {
        Objects.requireNonNull(quantized, "quantized");
        Objects.requireNonNull(melody, "melody");
        Score score = quantized.score();
        List<ChartLayout.Bar> bars = ChartLayout.of(score);

        StringBuilder out = new StringBuilder();
        out.append("\\version \"").append(StaffNotation.LILYPOND_VERSION).append("\"\n\n");
        out.append("\\header {\n");
        out.append("  title = \"").append(escape(score.title().orElse("Untitled"))).append("\"\n");
        score.artist().ifPresent(artist ->
                out.append("  composer = \"").append(escape(artist)).append("\"\n"));
        out.append("  tagline = ##f\n");
        out.append("}\n\n");

        // The staff first, though it is printed second: it decides whether the
        // score opens with a pickup, and the chord names above it have to be
        // told before they can write their own first bar.
        StaffNotation.Staff staff = StaffNotation.staff(quantized, melody);

        out.append("\\score {\n");
        out.append("  <<\n");
        // The chord names give up the tempo mark and the closing bar line here:
        // the staff below carries both already, and a lead sheet that printed
        // the tempo twice would be reporting one estimate as two.
        out.append(ChordChart.chordNamesBlock(score, bars, false, staff.pickup()));
        out.append(staff.lilyPond());
        // Under the staff rather than under the chords, so the words sit where a
        // singer reads them; that is also what decides which way the lane leans.
        Optional<String> lyrics =
                LyricEngraving.block(score, bars, LyricEngraving.Attachment.BELOW_STAFF);
        lyrics.ifPresent(out::append);
        out.append("  >>\n");
        out.append("  \\layout { }\n");
        out.append("}\n");
        return out.toString();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
