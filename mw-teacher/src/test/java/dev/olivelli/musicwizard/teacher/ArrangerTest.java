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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ArrangerTest {

    private static SampleSpec spec(SampleSpec.Style style, long seed) {
        return SpecParser.parse("""
                title: test
                style: %s
                tempo: 120
                key: C major
                seed: %d
                bars:
                C G Am F
                C G7 Am-G F
                """.formatted(style.id(), seed));
    }

    @ParameterizedTest
    @EnumSource(SampleSpec.Style.class)
    void everyStyleWritesEveryPart(SampleSpec.Style style) {
        Sequence sequence = Arranger.arrange(spec(style, 1));
        // Meta track plus melody, comp, bass, drums.
        assertThat(sequence.getTracks()).hasSize(5);
        for (int i = 1; i < 5; i++) {
            assertThat(noteCount(sequence.getTracks()[i]))
                    .as("track %d of %s", i, style)
                    .isPositive();
        }
    }

    @ParameterizedTest
    @EnumSource(SampleSpec.Style.class)
    void everyNoteLandsInsideTheGrid(SampleSpec.Style style) {
        Sequence sequence = Arranger.arrange(spec(style, 2));
        long gridTicks = 8L * 4 * 480;
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage message
                        && message.getCommand() == ShortMessage.NOTE_ON) {
                    assertThat(event.getTick()).isLessThan(gridTicks);
                }
            }
        }
    }

    @Test
    void sameSpecCompilesToTheSameBytes() throws IOException {
        byte[] first = bytes(Arranger.arrange(spec(SampleSpec.Style.POP_ROCK, 5)));
        byte[] second = bytes(Arranger.arrange(spec(SampleSpec.Style.POP_ROCK, 5)));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void theSeedActuallyVariesTheArrangement() throws IOException {
        byte[] first = bytes(Arranger.arrange(spec(SampleSpec.Style.POP_ROCK, 5)));
        byte[] second = bytes(Arranger.arrange(spec(SampleSpec.Style.POP_ROCK, 6)));
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void drumsStayOnTheDrumChannel() {
        Sequence sequence = Arranger.arrange(spec(SampleSpec.Style.POP_ROCK, 3));
        Track drums = sequence.getTracks()[4];
        for (int i = 0; i < drums.size(); i++) {
            if (drums.get(i).getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.NOTE_ON) {
                assertThat(message.getChannel()).isEqualTo(9);
            }
        }
    }

    @Test
    void compoundMeterIsRefusedNotMangled() {
        assertThatThrownBy(() -> Arranger.arrange(SpecParser.parse("""
                title: t
                style: pop-rock
                tempo: 120
                key: C major
                meter: 6/8
                seed: 1
                bars:
                C F G C
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4/4");
    }

    private static int noteCount(Track track) {
        int count = 0;
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.NOTE_ON) {
                count++;
            }
        }
        return count;
    }

    private static byte[] bytes(Sequence sequence) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MidiSystem.write(sequence, 1, out);
        return out.toByteArray();
    }
}
