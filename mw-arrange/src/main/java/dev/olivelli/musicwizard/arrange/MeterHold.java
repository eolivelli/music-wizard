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
import java.util.List;

/**
 * What a change of meter holds across it: the counted beat (#888), or the
 * quarter and every note value with it, which is how an unmarked change is
 * read (#883). The map records neither, so it is read off the tempo either
 * side: a stated one by its figure, a tracked one by the quarters each pulse
 * spans, which the transcriber sets exactly.
 */
public final class MeterHold {

    private MeterHold() {
    }

    /**
     * Whether the quarter keeps its length from the bar before {@code bar}
     * into it; always where both meters count the same note value, or where
     * no tempo segment starts at the bar line.
     */
    public static boolean holdsTheQuarter(TempoMap map, int bar) {
        if (bar < 1) {
            throw new IllegalArgumentException("bar 0 has no bar before it, got: " + bar);
        }
        TimeSignature before = map.timeSignatureAtBar(bar - 1);
        TimeSignature after = map.timeSignatureAtBar(bar);
        if (before.beatUnitQuarters() == after.beatUnitQuarters()) {
            return true;
        }
        double opens = map.toBeat(new MusicalTime(bar, 0, after));
        List<TempoMap.TempoSegment> segments = map.segments();
        int k = 0;
        while (k < segments.size() && segments.get(k).startBeat() < opens - 1e-9) {
            k++;
        }
        if (k == 0 || k == segments.size() || segments.get(k).startBeat() > opens + 1e-9) {
            return true;
        }
        TempoMap.TempoSegment at = segments.get(k);
        TempoMap.TempoSegment previous = segments.get(k - 1);
        if (at.provenance().isStated() || k + 1 == segments.size()) {
            return at.beatsPerMinute() == previous.beatsPerMinute();
        }
        // A tracked map starts a segment at every pulse.
        double pulseBefore = at.startBeat() - previous.startBeat();
        double pulseAfter = segments.get(k + 1).startBeat() - at.startBeat();
        return Math.abs(pulseAfter - pulseBefore) <= 1e-9 * pulseBefore;
    }
}
