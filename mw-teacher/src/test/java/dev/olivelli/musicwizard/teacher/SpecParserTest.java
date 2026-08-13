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

import dev.olivelli.musicwizard.core.model.Mode;
import org.junit.jupiter.api.Test;

class SpecParserTest {

    private static final String SPEC = """
            title: Axis progression in C
            genre: pop
            style: pop-rock
            tempo: 96
            key: C major
            seed: 7

            bars:
            # verse
            C G Am F
            C G Am-G F
            """;

    @Test
    void parsesHeadersAndGrid() {
        SampleSpec spec = SpecParser.parse(SPEC);
        assertThat(spec.title()).isEqualTo("Axis progression in C");
        assertThat(spec.style()).isEqualTo(SampleSpec.Style.POP_ROCK);
        assertThat(spec.tempoBpm()).isEqualTo(96);
        assertThat(spec.keyTonic()).isEqualTo("C");
        assertThat(spec.mode()).isEqualTo(Mode.MAJOR);
        assertThat(spec.seed()).isEqualTo(7);
        assertThat(spec.meter().numerator()).isEqualTo(4);
        assertThat(spec.bars()).hasSize(8);
        assertThat(spec.melodyProgram())
                .isEqualTo(SampleSpec.Style.POP_ROCK.defaultMelodyProgram());
    }

    @Test
    void aDashSplitsABarInTwo() {
        SampleSpec.Bar split = SpecParser.parse(SPEC).bars().get(6);
        assertThat(split.first().token()).isEqualTo("Am");
        assertThat(split.second().token()).isEqualTo("G");
        assertThat(split.chordAt(0, SpecParser.parse(SPEC).meter()).token()).isEqualTo("Am");
        assertThat(split.chordAt(2, SpecParser.parse(SPEC).meter()).token()).isEqualTo("G");
    }

    @Test
    void melodyNoneOmitsTheMelody() {
        SampleSpec spec = SpecParser.parse(SPEC.replace("seed: 7", "seed: 7\nmelody: none"));
        assertThat(spec.melodyProgram()).isNull();
    }

    @Test
    void minorKeysCarryTheirSignature() {
        SampleSpec spec = SpecParser.parse(SPEC
                .replace("key: C major", "key: B minor"));
        assertThat(spec.mode()).isEqualTo(Mode.MINOR);
        assertThat(spec.sharpsOrFlats()).isEqualTo(2);
    }

    @Test
    void unknownHeadersAreErrors() {
        assertThatThrownBy(() -> SpecParser.parse(SPEC.replace("genre:", "gnere:")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gnere");
    }

    @Test
    void missingRequiredHeaderIsAnError() {
        assertThatThrownBy(() -> SpecParser.parse(SPEC.replace("tempo: 96", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempo");
    }
}
