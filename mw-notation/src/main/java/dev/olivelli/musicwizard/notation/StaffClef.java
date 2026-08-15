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

import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import java.util.List;

/**
 * The clef a part is written in, carrying its octave transposition.
 *
 * <p>Named the way both output formats name it — a sign, the staff line it sits
 * on, and how many octaves the staff sounds away from where it reads — because
 * that is the intersection of what LilyPond and MusicXML each want and neither
 * can be derived from the other's spelling. {@code \clef "bass_8"} and
 * {@code <sign>F</sign><line>4</line><clef-octave-change>-1</clef-octave-change>}
 * are this record written twice.
 *
 * <p><b>The transposition belongs here and not to the pitches.</b> A part in
 * {@link PartRole#BASS} sounds an octave below where it is written, and the
 * clef is what says so; moving the pitches as well puts the line two octaves
 * above where it sounds, and moving them <em>instead</em> leaves nothing on the
 * page saying the part sounds lower than it reads. Both emitters therefore write
 * sounding pitches under a transposing clef.
 *
 * @param sign         the clef letter, {@code G} or {@code F}
 * @param line         the staff line it is centred on, counting up from 1
 * @param octaveChange octaves the staff sounds below where it is written,
 *                     negative for the ordinary {@code bass_8} case
 */
record StaffClef(char sign, int line, int octaveChange) {

    /** Treble clef, G on the second line. */
    static final StaffClef TREBLE = new StaffClef('G', 2, 0);

    /** Bass clef, F on the fourth line. */
    static final StaffClef BASS = new StaffClef('F', 4, 0);

    /** Bottom line of the treble staff, E4, on the diatonic ladder. */
    private static final int TREBLE_BOTTOM_LINE = 30;

    /** Top line of the treble staff, F5, on the diatonic ladder. */
    private static final int TREBLE_TOP_LINE = 38;

    /** The written shift of the octave treble clef, in diatonic steps. */
    private static final int OCTAVE_STEPS = 7;

    /**
     * The clef for a part, given the pitches that will actually be engraved.
     *
     * <p>Almost always the role's clef alone. The exception is
     * {@link PartRole#LEAD_VOCAL}, whose right clef cannot be read off the role:
     * it covers a soprano and a baritone, and written at sounding pitch a
     * baritone sits on ledger lines below the staff. Notation's settled answer
     * for a low voice is the octave treble clef — a treble clef with an 8 under
     * it, sounding an octave below written — so a vocal part is given it exactly
     * when that costs strictly fewer ledger lines over the part's engraved
     * notes; a tie keeps the plain clef.
     *
     * <p>Counted over what reaches the page, once per sounding note, and that
     * is what makes the decision robust: each note votes with the ledger lines
     * it would need, so a lone low blip — an octave error is the melody
     * tracker's documented failure mode, and a note too short to engrave never
     * gets here at all — cannot outvote the body of the part.
     */
    static StaffClef of(PartRole role, List<PitchSpelling> engraved) {
        if (role == PartRole.LEAD_VOCAL
                && ledgerLines(engraved, OCTAVE_STEPS) < ledgerLines(engraved, 0)) {
            StaffClef base = of(role);
            return new StaffClef(base.sign(), base.line(), base.octaveChange() - 1);
        }
        return of(role);
    }

    /**
     * The clef a role carries on its own.
     *
     * <p>A non-octave transposition would need a transposing instrument rather
     * than a clef modifier. No role has one; if one appears, it is written at
     * sounding pitch under a plain clef rather than silently at the wrong one.
     */
    private static StaffClef of(PartRole role) {
        StaffClef base = role.prefersBassClef() ? BASS : TREBLE;
        int semitones = role.writtenTranspositionSemitones();
        if (semitones % 12 != 0) {
            return base;
        }
        return new StaffClef(base.sign(), base.line(), -semitones / 12);
    }

    /**
     * Ledger lines the treble staff needs for these pitches, written
     * {@code diatonicShift} steps above where they sound.
     *
     * <p>Diatonic steps rather than semitones, because ledger lines are drawn
     * on the staff ladder: C4 and C sharp 4 sit on the same line. A note one
     * step off the staff hangs off it without a line; every two steps beyond
     * that add one.
     */
    private static int ledgerLines(List<PitchSpelling> engraved, int diatonicShift) {
        int lines = 0;
        for (PitchSpelling pitch : engraved) {
            int position = pitch.diatonicPosition() + diatonicShift;
            if (position < TREBLE_BOTTOM_LINE) {
                lines += (TREBLE_BOTTOM_LINE - position) / 2;
            } else if (position > TREBLE_TOP_LINE) {
                lines += (position - TREBLE_TOP_LINE) / 2;
            }
        }
        return lines;
    }

    /**
     * The LilyPond clef name, e.g. {@code treble} or {@code bass_8}.
     *
     * <p>LilyPond counts the modifier in staff positions rather than in octaves,
     * so an octave is 8 and two are 15 — one less than a naive multiple, because
     * both ends of the interval are counted.
     */
    String lilyPondName() {
        String base = sign == 'F' ? "bass" : "treble";
        if (octaveChange == 0) {
            return base;
        }
        return base + (octaveChange < 0 ? "_" : "^") + (Math.abs(octaveChange) * 7 + 1);
    }
}
