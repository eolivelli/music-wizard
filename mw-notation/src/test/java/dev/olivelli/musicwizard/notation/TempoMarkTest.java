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

import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The metronome mark both LilyPond emitters print.
 *
 * <p>The two emitters are covered where they live — {@code ChordChartTest} for
 * the chart, the staff goldens for {@link StaffNotation}. What is here is what
 * neither of them can reach: the guards, and the shape of the emitted mark
 * stated once so that a change to it has to be made deliberately rather than by
 * regenerating a golden.
 */
class TempoMarkTest {

    private static Score at(double quarterBpm, TimeSignature meter) {
        return Score.empty(TempoMap.constant(quarterBpm, meter), 60.0);
    }

    @Test
    @DisplayName("counts the beat the meter is counted in, not the stored quarter")
    void countsTheCountedBeat() {
        assertThat(TempoMark.of(at(120, TimeSignature.FOUR_FOUR), TimeSignature.FOUR_FOUR, 0))
                .contains(new TempoMark(new NoteValue(4, false), 120));
        // 180 quarter notes a minute is 120 dotted quarters. Both halves of the
        // mark change: the unit gains a dot and the figure drops by a third.
        assertThat(TempoMark.of(at(180, TimeSignature.SIX_EIGHT), TimeSignature.SIX_EIGHT, 0))
                .contains(new TempoMark(new NoteValue(4, true), 120));
    }

    @Test
    @DisplayName("rounds to a whole count, because a metronome has no fractions")
    void roundsTheCount() {
        assertThat(TempoMark.of(at(119.6, TimeSignature.FOUR_FOUR), TimeSignature.FOUR_FOUR, 0)
                .orElseThrow().perMinute()).isEqualTo(120);
    }

    @Test
    @DisplayName("prints nothing at all rather than a mark of zero")
    void aTempoTooSlowToPrintIsNotPrinted() {
        // Half a beat a minute rounds to a count of zero, and "4 = 0" is not a
        // tempo. Nothing the pipeline builds is this slow; the guard is here
        // because a score is a public type and a hand-built one can be.
        assertThat(TempoMark.of(at(0.5, TimeSignature.FOUR_FOUR), TimeSignature.FOUR_FOUR, 0))
                .isEmpty();
    }

    @Test
    @DisplayName("marks the figure as an estimate, and keeps LilyPond's own metronome mark")
    void theLilyPondFormIsAQualifiedMetronomeMark() {
        // \tempo <markup> <unit> = <count>, not a markup that draws its own note
        // head: engraved on LilyPond 2.26 the two are indistinguishable, but
        // only this form reaches MIDI, so a \midi block added to one of our
        // files plays at the tempo on the page rather than at LilyPond's own
        // default.
        assertThat(new TempoMark(new NoteValue(4, false), 159).lilyPond())
                .isEqualTo("\\tempo \\markup { \\italic \"ca.\" } 4 = 159");
        assertThat(new TempoMark(new NoteValue(4, true), 96).lilyPond())
                .isEqualTo("\\tempo \\markup { \\italic \"ca.\" } 4. = 96");
    }

    @Test
    @DisplayName("emits a count LilyPond can parse in any locale")
    void theCountIsLocaleIndependent() {
        // A LilyPond count is not a localised number. Under ar-EG a default
        // locale would emit Arabic-Indic digits, which LilyPond rejects outright
        // -- the same trap the text chart's tempo line carries a comment about,
        // one process further out.
        Locale original = Locale.getDefault();
        try {
            for (Locale locale : List.of(Locale.forLanguageTag("fr-FR"),
                    Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN"))) {
                Locale.setDefault(locale);

                assertThat(new TempoMark(new NoteValue(4, false), 159).lilyPond())
                        .as("under %s", locale)
                        .isEqualTo("\\tempo \\markup { \\italic \"ca.\" } 4 = 159");
            }
        } finally {
            Locale.setDefault(original);
        }
    }
}
