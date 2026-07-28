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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LilyPondDurationTest {

    @ParameterizedTest
    @CsvSource({
            "4.0,     1",
            "2.0,     2",
            "1.0,     4",
            "0.5,     8",
            "0.25,    16",
            "0.125,   32",
            "0.0625,  64",
    })
    @DisplayName("a plain value is named by how many of it fill a whole note")
    void plainValues(double quarters, String expected) {
        assertThat(LilyPondDuration.of(quarters)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "6.0,     1.",
            "3.0,     2.",
            "1.5,     4.",
            "0.75,    8.",
            "0.375,   16.",
            "0.1875,  32.",
            "0.09375, 64.",
    })
    @DisplayName("a dot adds half again, and is written after the number")
    void dottedValues(double quarters, String expected) {
        assertThat(LilyPondDuration.of(quarters)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(doubles = {
            2.5,       // five eighths: a tie, not a value
            3.5,       // double-dotted half, deliberately not offered
            5.0,
            8.0,       // a breve; the splitter ties two whole notes instead
            0.03125,   // shorter than the shortest value named
            1.0 / 3,   // a triplet quarter, which no dyadic value can express
            0.0,
            -1.0,
    })
    @DisplayName("a length no single value has is refused rather than rounded")
    void refusesLengthsThatAreNotValues(double quarters) {
        assertThat(LilyPondDuration.of(quarters)).isEmpty();
        assertThat(LilyPondDuration.isSingleValue(quarters)).isFalse();
    }

    @Test
    @DisplayName("NaN is not a value")
    void refusesNaN() {
        assertThat(LilyPondDuration.of(Double.NaN)).isEmpty();
        assertThat(LilyPondDuration.of(Double.POSITIVE_INFINITY)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            // A single value is preferred, because "\\partial 4." is what a
            // musician recognises and "\\partial 1*3/8" is not.
            "1.0,    4",
            "1.5,    4.",
            "4.0,    1",
            // Otherwise a whole note scaled by the fraction of one it covers.
            "5.0,    1*5/4",
            "2.5,    1*5/8",
            "8.0,    1*2",
            "0.3125, 1*5/64",
    })
    @DisplayName("a length that is not a value is written as a whole note times a fraction")
    void scaledLengths(double quarters, String expected) {
        assertThat(LilyPondDuration.scaled(quarters)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a length off the grid cannot be written at all, and says so")
    void scaledRejectsLengthsOffTheGrid() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LilyPondDuration.scaled(1.0 / 3))
                .withMessageContaining("not a whole number");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LilyPondDuration.scaled(0.0));
    }
}
