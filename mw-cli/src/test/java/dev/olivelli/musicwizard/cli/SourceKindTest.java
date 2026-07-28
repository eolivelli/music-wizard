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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.olivelli.musicwizard.testkit.MidiFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a source file is, which is decided by its bytes and never by its name.
 *
 * <p>The extension is the tempting test and the wrong one. Reading a file as the
 * wrong kind does not fail: before #114 a Standard MIDI File was handed to the
 * audio decoder, synthesised by FFmpeg and analysed as a recording, which
 * reported a file stating 120 BPM as 60.3 BPM and engraved five bars of a
 * thirty-two bar progression, with exit status 0.
 */
class SourceKindTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("a MIDI file is MIDI whatever it is called")
    void midiIsDetectedFromItsHeader() {
        // Named .mp3 on purpose: an extension-based test passes this file to the
        // audio decoder, which is exactly the bug.
        Path disguised = MidiFixtures.write(
                MidiFixtures.cMajorScale(), directory.resolve("song.mp3"));

        assertThat(SourceKind.detect(disguised)).isEqualTo(SourceKind.MIDI);
    }

    @Test
    @DisplayName("a file that is not MIDI is audio whatever it is called")
    void audioIsDetectedFromItsHeader() throws Exception {
        Path disguised = directory.resolve("song.mid");
        Files.write(disguised, "RIFF....WAVEfmt ".getBytes(StandardCharsets.US_ASCII));

        assertThat(SourceKind.detect(disguised)).isEqualTo(SourceKind.AUDIO);
    }

    @Test
    @DisplayName("a file too short to hold a header is audio, not an error")
    void shortFileIsAudio() throws Exception {
        // Three bytes: shorter than the magic number, so the header comparison
        // has nothing to compare. The decoder has better words for this file than
        // a magic-number test does, so it is left to say them.
        Path tiny = directory.resolve("tiny.mid");
        Files.write(tiny, new byte[] {'M', 'T', 'h'});

        assertThat(SourceKind.detect(tiny)).isEqualTo(SourceKind.AUDIO);
    }

    @Test
    @DisplayName("an empty file is audio, not an error")
    void emptyFileIsAudio() throws Exception {
        Path empty = Files.createFile(directory.resolve("empty.mid"));

        assertThat(SourceKind.detect(empty)).isEqualTo(SourceKind.AUDIO);
    }

    @Test
    @DisplayName("a path with no file behind it is named, not read")
    void missingFileIsRefusedByName() {
        assertThatThrownBy(() -> SourceKind.detect(directory.resolve("nothing.mid")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a readable file");
    }

    @Test
    @DisplayName("a directory is not a source file")
    void directoryIsRefused() {
        // Files.newInputStream on a directory throws IOException on some
        // platforms and returns a stream that fails on read on others, so the
        // regular-file check has to happen first rather than be relied on to
        // fall out of the read.
        assertThatThrownBy(() -> SourceKind.detect(directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a readable file");
    }
}
