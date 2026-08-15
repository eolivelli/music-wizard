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

import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchRange;

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

    /** The middle line of the treble staff, B4, which a clef exists to aim at. */
    private static final int TREBLE_CENTRE_MIDI = 71;

    /**
     * The clef for a part.
     *
     * <p>Almost always the role's clef alone. The exception is
     * {@link PartRole#LEAD_VOCAL}, whose right clef cannot be read off the role:
     * it covers a soprano and a baritone, and written at sounding pitch a
     * baritone sits on ledger lines below the staff. Notation's settled answer
     * for a low voice is the octave treble clef — a treble clef with an 8 under
     * it, sounding an octave below written — so a vocal part is given it exactly
     * when writing the part an octave up brings the middle of its range nearer
     * the staff's middle line. A tie keeps sounding pitch, because a modifier
     * the page does not need is one the reader still has to honour.
     */
    static StaffClef of(NoteTrack track) {
        if (track.role() == PartRole.LEAD_VOCAL && track.pitchRange()
                .filter(StaffClef::centresBetterAnOctaveUp).isPresent()) {
            return new StaffClef(TREBLE.sign(), TREBLE.line(), -1);
        }
        return of(track.role());
    }

    /**
     * The clef a role carries on its own.
     *
     * <p>A non-octave transposition would need a transposing instrument rather
     * than a clef modifier. No role has one; if one appears, it is written at
     * sounding pitch under a plain clef rather than silently at the wrong one.
     */
    static StaffClef of(PartRole role) {
        StaffClef base = role.prefersBassClef() ? BASS : TREBLE;
        int semitones = role.writtenTranspositionSemitones();
        if (semitones % 12 != 0) {
            return base;
        }
        return new StaffClef(base.sign(), base.line(), -semitones / 12);
    }

    /**
     * True when writing the range an octave up puts its midpoint strictly
     * nearer the treble staff's middle line.
     *
     * <p>Raising the part moves twice the midpoint up by 24, so it wins exactly
     * when twice the midpoint sits more than 12 below twice the centre —
     * integer arithmetic, because the midpoint itself can fall between two
     * semitones. One octave is the only candidate: no vocal convention writes a
     * voice two octaves off, however low the transcription claims it went.
     */
    private static boolean centresBetterAnOctaveUp(PitchRange range) {
        return range.lowest() + range.highest() < 2 * TREBLE_CENTRE_MIDI - 12;
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
