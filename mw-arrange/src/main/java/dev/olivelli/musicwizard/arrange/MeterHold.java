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

import dev.olivelli.musicwizard.core.model.MusicalTime;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;

/**
 * What a change of meter holds across it: the counted beat (#888), or the
 * quarter and every note value with it, which is how an unmarked change is
 * read (#883). The map records neither, so it is read off the lengths of the
 * bars either side.
 */
public final class MeterHold {

    private MeterHold() {
    }

    /**
     * Whether the quarter keeps its length from the bar before {@code bar}
     * into it; always where both meters count the same note value.
     */
    public static boolean holdsTheQuarter(TempoMap map, int bar) {
        if (bar < 1) {
            throw new IllegalArgumentException("bar 0 has no bar before it, got: " + bar);
        }
        TimeSignature before = map.timeSignatureAtBar(bar - 1);
        TimeSignature after = map.timeSignatureAtBar(bar);
        double countedHeld = before.beatUnitQuarters() / after.beatUnitQuarters();
        if (countedHeld == 1.0) {
            return true;
        }
        double opens = map.toBeat(new MusicalTime(bar, 0, after));
        double at = map.beatsToSeconds(opens);
        double quarterBefore = (at - map.beatsToSeconds(opens - before.quarterBeatsPerBar()))
                / before.quarterBeatsPerBar();
        double quarterAfter = (map.beatsToSeconds(opens + after.quarterBeatsPerBar()) - at)
                / after.quarterBeatsPerBar();
        double ratio = quarterAfter / quarterBefore;
        return Math.abs(Math.log(ratio)) <= Math.abs(Math.log(ratio / countedHeld));
    }
}
