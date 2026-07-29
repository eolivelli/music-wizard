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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * What a workspace's source file is, and therefore which transcriber reads it.
 *
 * <p>Decided by the file's <em>content</em> and never by its name. That is the
 * whole point of this class rather than a switch on the extension: reading a
 * file as the wrong kind does not fail here, it succeeds and lies. Before this
 * existed, {@code mw analyze} handed a Standard MIDI File to the audio decoder,
 * FFmpeg synthesised it, and the DSP stages analysed the synthesis -- a file
 * stating 120 BPM and eight bars of C G Am F was reported as 60.3 BPM and
 * engraved as five bars, with exit status 0. An extension is a claim by whoever
 * named the file; the header is the file itself.
 */
enum SourceKind {

    /** Anything the audio decoder is asked to open. */
    AUDIO("audio"),

    /** A Standard MIDI File, read symbolically. */
    MIDI("Standard MIDI File");

    /**
     * The four bytes every Standard MIDI File begins with.
     *
     * <p>ASCII rather than the platform charset, because a header is bytes: on a
     * machine defaulting to a charset that does not agree with ASCII on these
     * code points the comparison would silently stop matching.
     */
    private static final byte[] MIDI_MAGIC = "MThd".getBytes(StandardCharsets.US_ASCII);

    private final String description;

    SourceKind(String description) {
        this.description = description;
    }

    /** How to name this kind in output meant for a person. */
    String description() {
        return description;
    }

    /**
     * The kind of a file, from its first bytes.
     *
     * <p>Only the bare {@code MThd} chunk is recognised as MIDI. An RMID
     * container -- a {@code RIFF} wrapper around the same chunk -- is
     * deliberately <em>not</em>, because {@code MidiSystem.getSequence} cannot
     * read one either, so routing it here would exchange one wrong answer for an
     * error. See #116.
     *
     * <p>Anything else is audio, including a file that is neither: deciding it
     * is not audio is the decoder's job and it has better messages for it than a
     * magic-number test could.
     *
     * @throws IllegalArgumentException if there is no file there to look at
     * @throws UncheckedIOException if the file cannot be read at all
     */
    static SourceKind detect(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file)) {
            // Named the way MidiTranscriber names it, because this now runs
            // before the workspace is created and is therefore the first thing a
            // mistyped path reaches. "could not read" -- what the catch below
            // would have said -- describes a permissions problem, not a typo.
            throw new IllegalArgumentException("not a readable file: " + file);
        }
        byte[] header = new byte[MIDI_MAGIC.length];
        try (InputStream in = Files.newInputStream(file)) {
            // readNBytes rather than read: a single read is allowed to return
            // fewer bytes than asked for even when more are available, which on
            // a stream that happened to hand back three bytes would make every
            // MIDI file in the world look like audio.
            int read = in.readNBytes(header, 0, header.length);
            if (read < header.length) {
                return AUDIO;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
        return java.util.Arrays.equals(header, MIDI_MAGIC) ? MIDI : AUDIO;
    }
}
