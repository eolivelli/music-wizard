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

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.AudioDecoder;
import dev.olivelli.musicwizard.core.ml.MlProviders;
import dev.olivelli.musicwizard.core.ml.ModelUnavailableException;
import dev.olivelli.musicwizard.core.ml.SeparationProvider;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Separates a workspace's recording into a vocal stem and the rest.
 *
 * <p>Its own command rather than a stage inside {@code analyze}, because the
 * stems are something a person judges by ear. Writing them where {@code render}
 * writes its PDFs makes that audition a file manager away. The lyric
 * transcriber (#314) consumes the vocal stem too and does its own separation
 * in memory; this command stays the way to listen.
 *
 * <p>Chords are untouched by any of this: they are estimated from the full
 * mix, never from stems, and that rule outranks every provider.
 */
@Command(name = "separate",
        description = "Separate the recording into a vocal stem and accompaniment.")
final class SeparateCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "WORKSPACE",
            description = "The workspace directory.")
    Path workspaceDirectory;

    @Override
    public Integer call() throws IOException {
        Workspace workspace = Workspace.open(workspaceDirectory);
        MusicWizardConfig config = workspace.effectiveConfig();
        MusicWizardConfig.MlConfig ml = config.ml();
        String wanted = ml == null ? null : ml.separationProvider();

        var provider = MlProviders.separation(wanted);
        if (provider.isEmpty()) {
            // The LilyPond pattern: name what is missing, what would supply it,
            // and what is present. Exit zero -- an absent provider is an absent
            // capability, not a failed command.
            System.out.println("No separation provider"
                    + (wanted == null || wanted.isBlank()
                            ? " is configured (ml.separationProvider)."
                            : " named '" + wanted + "' is on this classpath."));
            var present = MlProviders.separationIds();
            System.out.println(present.isEmpty()
                    ? "None are available in this build."
                    : "Available: " + String.join(", ", present));
            return 0;
        }

        // Decode at the model's own rate where it states one;
        // preferredSampleRate's javadoc carries the why.
        int preferred = provider.get().preferredSampleRate();
        AudioBuffer audio = preferred > 0
                ? AudioDecoder.decode(workspace.sourceFile(), preferred)
                : AudioDecoder.decode(workspace.sourceFile());
        System.out.printf("Separating  %.1f s of audio with %s%n",
                audio.durationSeconds(), provider.get().id());
        SeparationProvider.Separation stems;
        try {
            long started = System.nanoTime();
            stems = provider.get().separate(
                    new float[][] {audio.samples()}, audio.sampleRate());
            System.out.printf("Separated   in %.1f s%n", (System.nanoTime() - started) / 1e9);
        } catch (ModelUnavailableException e) {
            // Also not a crash: the message already names the file and the cure.
            System.out.println(e.getMessage());
            return 1;
        }

        Files.createDirectories(workspace.outputDirectory());
        Path vocals = workspace.outputDirectory().resolve("vocals.wav");
        Path accompaniment = workspace.outputDirectory().resolve("accompaniment.wav");
        writeWav(vocals, stems.vocals()[0], audio.sampleRate());
        writeWav(accompaniment, stems.accompaniment()[0], audio.sampleRate());
        System.out.println("Wrote       " + vocals);
        System.out.println("Wrote       " + accompaniment);
        return 0;
    }

    /** 16-bit PCM WAV, the least surprising thing a media player can be handed. */
    private static void writeWav(Path path, float[] samples, int sampleRate)
            throws IOException {
        byte[] pcm = new byte[samples.length * 2];
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : samples) {
            float clamped = Math.max(-1f, Math.min(1f, sample));
            buffer.putShort((short) Math.round(clamped * Short.MAX_VALUE));
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, samples.length)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }
}
