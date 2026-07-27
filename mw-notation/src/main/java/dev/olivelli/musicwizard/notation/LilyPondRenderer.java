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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Engraves LilyPond source to PDF by invoking the LilyPond binary.
 *
 * <p>A separate process, never a linked library: LilyPond is GPL-3.0, and
 * calling it this way keeps this project's licensing clean while still using the
 * best free engraver there is. It also means the tool works without it — the
 * sources are useful on their own, so a missing binary degrades the output
 * rather than failing the run.
 */
public final class LilyPondRenderer {

    /** How long to wait before deciding LilyPond has hung. */
    private static final long TIMEOUT_SECONDS = 120;

    private final Path binary;

    public LilyPondRenderer(Path binary) {
        this.binary = Objects.requireNonNull(binary, "binary");
    }

    /** The outcome of an engraving attempt. */
    public record Result(boolean succeeded, Optional<Path> pdf, String output) {
    }

    /**
     * Engraves a source file, writing the PDF beside it.
     *
     * @param source a {@code .ly} file
     * @return the result, which reports failure rather than throwing, because a
     *         failed engraving should not lose the analysis that produced it
     */
    public Result render(Path source) {
        Objects.requireNonNull(source, "source");
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("no such LilyPond source: " + source);
        }

        Path directory = source.toAbsolutePath().getParent();
        String stem = source.getFileName().toString().replaceFirst("\\.ly$", "");

        ProcessBuilder builder = new ProcessBuilder(
                binary.toString(), "--pdf", "-o", stem, source.getFileName().toString());
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);

        Process process = null;
        try {
            process = builder.start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(false, Optional.empty(),
                        "LilyPond did not finish within " + TIMEOUT_SECONDS + " seconds");
            }
            Path pdf = directory.resolve(stem + ".pdf");
            boolean produced = process.exitValue() == 0 && Files.isRegularFile(pdf);
            return new Result(produced, produced ? Optional.of(pdf) : Optional.empty(), output);
        } catch (IOException e) {
            throw new UncheckedIOException("could not run LilyPond at " + binary, e);
        } catch (InterruptedException e) {
            // Restore the flag rather than swallowing it: something is trying to
            // shut this thread down and the caller needs to be able to see that.
            Thread.currentThread().interrupt();
            return new Result(false, Optional.empty(), "interrupted while waiting for LilyPond");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** Writes source to a file and engraves it in one step. */
    public Result renderSource(Path targetLyFile, String lilyPondSource) {
        try {
            Path parent = targetLyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(targetLyFile, lilyPondSource);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + targetLyFile, e);
        }
        return render(targetLyFile);
    }

    /** The version LilyPond reports, useful for diagnostics. */
    public Optional<String> version() {
        try {
            Process process = new ProcessBuilder(binary.toString(), "--version")
                    .redirectErrorStream(true).start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            process.waitFor(15, TimeUnit.SECONDS);
            return output.lines().findFirst();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Convenience for callers that already resolved the binary. */
    public static List<String> supportedOutputs() {
        return List.of("pdf");
    }
}
