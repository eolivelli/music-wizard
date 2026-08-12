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

package dev.olivelli.musicwizard.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("vocal segmentation")
class VocalSegmentsTest {

    private static final int RATE = 16_000;

    private static float[] signal(double... onOffSeconds) {
        // Alternating quiet/loud stretches, starting quiet: (quiet, loud, ...).
        int total = 0;
        for (double s : onOffSeconds) {
            total += (int) (s * RATE);
        }
        float[] out = new float[total];
        int at = 0;
        boolean loud = false;
        for (double s : onOffSeconds) {
            int n = (int) (s * RATE);
            if (loud) {
                for (int i = 0; i < n; i++) {
                    out[at + i] = (float) (0.5 * Math.sin(2 * Math.PI * 220 * i / RATE));
                }
            }
            at += n;
            loud = !loud;
        }
        return out;
    }

    @Test
    @DisplayName("silence at the edges is not part of any segment")
    void edgesAreTrimmed() {
        var segments = VocalSegments.split(signal(2.0, 3.0, 2.0), RATE);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).startSeconds(RATE))
                .isBetween(2.0 - VocalSegments.PADDING_SECONDS - 0.1, 2.0);
        assertThat((double) segments.get(0).end() / RATE)
                .isBetween(5.0, 5.0 + VocalSegments.PADDING_SECONDS + 0.1);
    }

    @Test
    @DisplayName("a breath stays inside its phrase; a real gap splits")
    void gapsSplitAndBreathsDoNot() {
        // 0.3 s is a breath, 2 s is a gap between lines.
        var segments = VocalSegments.split(
                signal(1.0, 2.0, 0.3, 2.0, 2.0, 2.0), RATE);

        assertThat(segments).hasSize(2);
    }

    @Test
    @DisplayName("all silence means no segments, not one empty one")
    void allSilence() {
        assertThat(VocalSegments.split(new float[5 * RATE], RATE)).isEmpty();
    }

    @Test
    @DisplayName("a stretch past the cap is split, and every piece stays sung")
    void longStretchesAreCapped() {
        var segments = VocalSegments.split(
                signal(0.5, 3 * VocalSegments.MAX_SEGMENT_SECONDS), RATE);

        assertThat(segments.size()).isGreaterThanOrEqualTo(3);
        for (VocalSegments.Segment segment : segments) {
            assertThat((segment.end() - segment.start()) / (double) RATE)
                    .isLessThanOrEqualTo(VocalSegments.MAX_SEGMENT_SECONDS);
        }
        // And nothing was lost at the cuts: the pieces tile the stretch.
        for (int i = 1; i < segments.size(); i++) {
            assertThat(segments.get(i).start())
                    .isEqualTo(segments.get(i - 1).end());
        }
    }

    @Test
    @DisplayName("a blip shorter than a syllable is noise, not a segment")
    void blipsAreDropped() {
        var segments = VocalSegments.split(signal(2.0, 0.1, 2.0), RATE);

        assertThat(segments).isEmpty();
    }
}
