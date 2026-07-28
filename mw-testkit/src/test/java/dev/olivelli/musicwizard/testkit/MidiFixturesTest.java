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

package dev.olivelli.musicwizard.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.olivelli.musicwizard.core.model.Mode;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fixtures have to be ground truth, so they are checked like anything else.
 *
 * <p>A fixture that is quietly wrong is worse than no fixture: it makes a broken
 * importer pass and a correct one fail, and the argument about which is which
 * takes place over the wrong evidence. The properties asserted here are the ones
 * the fixtures' own documentation claims, particularly the one that is easy to
 * lose by editing a note position -- that they do not begin at the origin.
 */
class MidiFixturesTest {

    @Test
    @DisplayName("no fixture but the smoke one begins at the origin")
    void theTimingFixturesHaveALeadIn() {
        // Every derivation of a musical position agrees exactly at beat 0, so a
        // fixture that starts there cannot tell a correct conversion from one
        // that discarded the phase. Three separate changes on this project have
        // been caught by exactly this.
        List<Supplier<Sequence>> withLeadIn = List.of(
                MidiFixtures::leadInAndTempoChange,
                MidiFixtures::tempoAndMeterChange,
                MidiFixtures::compoundTime,
                MidiFixtures::fourChordSong);
        for (Supplier<Sequence> fixture : withLeadIn) {
            assertThat(firstNoteTick(fixture.get())).isGreaterThan(0);
        }
        assertThat(firstNoteTick(MidiFixtures.cMajorScale())).isZero();
    }

    @Test
    @DisplayName("the fixtures that claim a tempo or meter change actually have one")
    void theChangeFixturesActuallyChange() {
        assertThat(metaCount(MidiFixtures.tempoAndMeterChange(), 0x51)).isEqualTo(2);
        assertThat(metaCount(MidiFixtures.tempoAndMeterChange(), 0x58)).isEqualTo(2);
        assertThat(metaCount(MidiFixtures.leadInAndTempoChange(), 0x51)).isEqualTo(2);
    }

    @Test
    @DisplayName("a fixture survives a trip through a real MIDI file")
    void fixturesWriteAndReadBack(@TempDir Path directory) throws Exception {
        Sequence built = MidiFixtures.tempoAndMeterChange();
        Path file = MidiFixtures.write(built, directory.resolve("fixture.mid"));
        // Type 1 keeps the tracks separate; type 0 would flatten them into one
        // and leave part splitting nothing to split.
        assertThat(MidiSystem.getMidiFileFormat(file.toFile()).getType()).isEqualTo(1);

        Sequence read = MidiSystem.getSequence(file.toFile());
        assertThat(read.getResolution()).isEqualTo(built.getResolution());
        assertThat(read.getTickLength()).isEqualTo(built.getTickLength());
        assertThat(read.getTracks()).hasSameSizeAs(built.getTracks());
    }

    @Test
    @DisplayName("build() may be called twice without the two sharing state")
    void aBuilderIsReusable() {
        MidiFixtures.SequenceBuilder builder = MidiFixtures.sequence().tempo(120);
        builder.part("Melody", 0).note(1, 1, 60);
        Sequence first = builder.build();
        Sequence second = builder.build();
        assertThat(first).isNotSameAs(second);
        assertThat(first.getTickLength()).isEqualTo(second.getTickLength());
        assertThat(first.getTracks()).hasSameSizeAs(second.getTracks());
    }

    @Test
    @DisplayName("a position that is not a whole number of ticks is refused, not rounded")
    void aPositionOffTheTickGridIsRefused() {
        // Rounding it would make the fixture an estimate of itself, and a test
        // asserting exactness against it would be asserting against the rounding.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MidiFixtures.sequence(4).part("Melody", 0).note(0.3, 1, 60))
                .withMessageContaining("whole number of ticks");
        // The same position is fine where the resolution can express it.
        assertThat(MidiFixtures.sequence(10).part("Melody", 0).note(0.3, 1, 60).build())
                .isNotNull();
    }

    @Test
    @DisplayName("a note-on at velocity zero is refused, because on the wire it is a note-off")
    void velocityZeroIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MidiFixtures.sequence().part("Melody", 0).note(0, 1, 60, 0))
                .withMessageContaining("velocity");
    }

    @Test
    @DisplayName("a note shorter than one tick is refused rather than silently zero-length")
    void aNoteShorterThanATickIsRefused() {
        // A duration so small that adding it to the onset does not change the
        // onset. The position passes the whole-tick check -- it is unchanged, so
        // it is still exact -- and the note would come out zero-length.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MidiFixtures.sequence(1)
                        .part("Melody", 0).note(1e15, 1e-9, 60))
                .withMessageContaining("shorter than one tick");
    }

    @Test
    @DisplayName("storedTempo says which tempi a MIDI file can hold exactly")
    void storedTempoReportsWhatTheFormatCanHold() {
        assertThat(MidiFixtures.storedTempo(120)).isEqualTo(120.0);
        assertThat(MidiFixtures.storedTempo(100)).isEqualTo(100.0);
        assertThat(MidiFixtures.storedTempo(60)).isEqualTo(60.0);
        // 60000000/140 is 428571.43, so the file holds 428571.
        assertThat(MidiFixtures.storedTempo(140)).isNotEqualTo(140.0);
        assertThat(MidiFixtures.storedTempo(140)).isEqualTo(60_000_000.0 / 428_571);
        // Below about 3.6 BPM a quarter note is longer than the 24-bit field.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MidiFixtures.storedTempo(3));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MidiFixtures.storedTempo(0));
    }

    @Test
    @DisplayName("a key signature is written where an importer will look for it")
    void keySignaturesAreWrittenToTheMetaTrack() {
        Sequence sequence = MidiFixtures.sequence()
                .keySignature(-3, Mode.MINOR)
                .part("Melody", 0).note(1, 1, 60).build();
        assertThat(metaCount(sequence, 0x59)).isEqualTo(1);
        // On the meta track, where a type 1 file puts it and where an importer
        // will look without having to read the note tracks first.
        assertThat(metaCountOn(sequence.getTracks()[0], 0x59)).isEqualTo(1);
    }

    @Test
    @DisplayName("an out-of-range key signature or channel is refused")
    void outOfRangeArgumentsAreRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MidiFixtures.sequence().keySignature(8, Mode.MAJOR));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MidiFixtures.sequence().part("Melody", 16));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MidiFixtures.sequence(0));
    }

    /** The tick of the earliest sounding note in a sequence, or -1 if there is none. */
    private static long firstNoteTick(Sequence sequence) {
        long earliest = Long.MAX_VALUE;
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof ShortMessage message
                        && message.getCommand() == ShortMessage.NOTE_ON
                        && message.getData2() > 0) {
                    earliest = Math.min(earliest, track.get(i).getTick());
                }
            }
        }
        return earliest == Long.MAX_VALUE ? -1 : earliest;
    }

    /** How many meta events of a type the sequence holds. */
    private static int metaCount(Sequence sequence, int type) {
        int found = 0;
        for (Track track : sequence.getTracks()) {
            found += metaCountOn(track, type);
        }
        return found;
    }

    /** How many meta events of a type one track holds. */
    private static int metaCountOn(Track track, int type) {
        int found = 0;
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == type) {
                found++;
            }
        }
        return found;
    }
}
