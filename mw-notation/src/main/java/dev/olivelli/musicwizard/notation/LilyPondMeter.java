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

import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.stream.Collectors;

/**
 * How a meter is spelled in LilyPond source.
 *
 * <p>One definition for every emitter, because a chart and a staff part of the
 * same piece stating different meters is the kind of disagreement nothing would
 * report: both engrave, and only a reader holding the two pages notices. The
 * chord chart stated no meter at all until #64, which is how it came to engrave
 * every 3/4 song in 4/4.
 */
final class LilyPondMeter {

    private LilyPondMeter() {
    }

    /**
     * The {@code \time} command for a meter, carrying the model's beat grouping
     * where it has one.
     *
     * <p>{@code \time #'(3 3) 6/8} rather than a pair of {@code \set Timing}
     * lines: the list form takes the grouping in denominator units, which is
     * exactly what {@link TimeSignature#beatStructure()} returns, and it does
     * not name {@code baseMoment}, which LilyPond renamed in 2.26 and now warns
     * about. One line, no deprecation, same grouping on both versions.
     */
    static String time(TimeSignature meter) {
        StringBuilder out = new StringBuilder("\\time ");
        if (statesItsOwnGrouping(meter)) {
            out.append("#'(")
                    .append(meter.beatStructure().stream().map(String::valueOf)
                            .collect(Collectors.joining(" ")))
                    .append(") ");
        }
        return out.append(meter.numerator()).append('/').append(meter.denominator()).toString();
    }

    /**
     * Whether the model's beat structure is worth emitting for this meter.
     *
     * <p>{@link TimeSignature#beatStructure()} is authoritative for compound time
     * — it is what beams a 6/8 bar as two groups of three — and for meters
     * counted in quarters or halves, where one beat per denominator unit is how
     * the bar is actually counted.
     *
     * <p>It is not authoritative for an irregular meter in eighths. There it
     * declines to guess and returns one beat per eighth, which as a beam grouping
     * means no beams at all: 7/8 would come out as seven separate eighth notes.
     * LilyPond's own table has a conventional grouping for those, so it is left
     * to make the guess this model deliberately does not make. See #62.
     */
    private static boolean statesItsOwnGrouping(TimeSignature meter) {
        return meter.isCompound() || meter.denominator() <= 4;
    }
}
