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

package dev.olivelli.musicwizard.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChordSymbolTest {

    @ParameterizedTest
    @CsvSource({
            "C,    0,  MAJOR",
            "Am,   9,  MINOR",
            "G7,   7,  DOMINANT_SEVENTH",
            "F#m7, 6,  MINOR_SEVENTH",
            "Bbmaj7, 10, MAJOR_SEVENTH",
            "C6,   0,  SIXTH",
            "Cm6,  0,  MINOR_SIXTH",
            "Bm7b5, 11, HALF_DIMINISHED",
            "Ebdim, 3, DIMINISHED",
    })
    void parsesTheGridShorthand(String token, int rootPc, ChordSymbol.Quality quality) {
        ChordSymbol chord = ChordSymbol.parse(token);
        assertThat(chord.rootPitchClass()).isEqualTo(rootPc);
        assertThat(chord.quality()).isEqualTo(quality);
    }

    @Test
    void pitchClassesStartAtTheRoot() {
        assertThat(ChordSymbol.parse("G7").pitchClasses()).containsExactly(7, 11, 2, 5);
        assertThat(ChordSymbol.parse("Am").pitchClasses()).containsExactly(9, 0, 4);
        assertThat(ChordSymbol.parse("C6").pitchClasses()).containsExactly(0, 4, 7, 9);
    }

    /**
     * A quality the grid cannot spell must fail the token, not fall through to
     * the pattern's empty alternative: a silent fall-through would compile a
     * spec's stated quality into a plain major triad and call it ground truth.
     */
    @ParameterizedTest
    @CsvSource({"H", "Cx", "Cmin", "C/E", "c", "''", "C9", "Cadd4", "Cmaj9", "C69"})
    void refusesWhatTheShorthandCannotSpell(String token) {
        assertThatThrownBy(() -> ChordSymbol.parse(token))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
