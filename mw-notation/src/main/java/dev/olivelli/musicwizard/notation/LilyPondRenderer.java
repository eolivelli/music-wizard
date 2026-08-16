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
import java.util.Map;
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

    /**
     * The outcome of an engraving attempt.
     *
     * <p>{@link #succeeded()} answers "did LilyPond run and produce a PDF", and
     * deliberately nothing more. It is not "is the PDF right": those are
     * different questions and collapsing them would lose one of the answers.
     *
     * @param succeeded whether a PDF was produced at all
     * @param pdf       the PDF; {@link #render} fills this in exactly when
     *                  {@code succeeded}, but the record does not enforce it and
     *                  tests build results that carry neither
     * @param output    everything LilyPond said, stdout and stderr interleaved
     */
    public record Result(boolean succeeded, Optional<Path> pdf, String output) {

        public Result {
            Objects.requireNonNull(pdf, "pdf");
            Objects.requireNonNull(output, "output");
        }

        /**
         * The moments at which LilyPond reported a bar that did not fill its
         * meter, in the order it reported them.
         *
         * <p><b>A non-empty list means the engraved music is wrong, and
         * {@link #succeeded()} is still {@code true}.</b> That is not a
         * contradiction, it is the whole point of #156: a failed bar check is a
         * warning, so LilyPond draws the short bar, exits zero and hands over a
         * real PDF. A caller that only asks whether engraving succeeded is
         * therefore told nothing about whether the page is correct — which is
         * why {@code RenderCommand} could print {@code Wrote .../chords.pdf}
         * and nothing else about a chart whose bars do not sum. #160 gave the
         * chord chart a {@code \time} and a {@code |} per bar, so a defect in
         * the only part {@code mw render} writes today now reaches the user as
         * a warning instead of as a silently wrong page. No valid input produces
         * one — that is what the bar checks are for — so what changed is the
         * failure mode, not the frequency. It could not when this accessor was
         * written, which is worth saying because the accessor came first: for a
         * while the fact was readable and nothing shipped could produce it, and
         * only {@link StaffNotation}'s output, engraved by {@code mw-it}, gave
         * it anything to read.
         *
         * <p>Derived from {@link #output()} on each call rather than stored
         * beside it, so that the two cannot disagree. A stored copy would be a
         * second reading of the same fact, and this project has paid for that
         * shape more than once.
         *
         * <p>Empty for a run that never produced output to read — a timeout, an
         * interrupt — because "LilyPond did not complain about the bars" and
         * "LilyPond never got that far" are both correctly reported by
         * {@code succeeded() == false}, and neither is a bar-check finding.
         *
         * @see LilyPondComplaints for which diagnostics are read and which are
         *      deliberately not
         */
        public List<String> failedBarChecks() {
            return LilyPondComplaints.failedBarChecksIn(output);
        }
    }

    /**
     * Runs LilyPond with its diagnostics in one language, whatever the machine
     * is set to — and changes nothing else about the process.
     *
     * <p>LilyPond translates its message <em>prefixes</em> through gettext, so
     * anything reading its output for the word "warning" stops reading it at
     * all on a non-English machine — silently, with the build green and a
     * failed bar check unreported. The environment is pinned rather than the
     * parsing widened, which would mean carrying LilyPond's message catalogue
     * in every language.
     *
     * <p><b>One variable removed and twelve written, and it took four
     * attempts; the three wrong answers are recorded because each is the
     * obvious next simplification.</b> Setting {@code LC_ALL=C} (with
     * {@code LANG} and {@code LC_MESSAGES}) breaks engraving outright for a
     * file whose name is not ASCII, since those variables also set the
     * character type and LilyPond cannot decode a non-ASCII {@code argv}
     * filename under a {@code C} ctype. Setting {@code LC_MESSAGES=C} alone
     * reopens the original bug for the commonest way of setting a locale,
     * because POSIX has {@code LC_ALL} override every category. And moving
     * {@code LC_ALL} into {@code LC_CTYPE} alone is one category short:
     * removing {@code LC_ALL} un-masks whatever the eleven individual
     * variables say, and glibc's locale selection is all-or-nothing — one
     * category naming an uninstalled locale collapses the child's whole
     * locale to {@code C}, ctype included. So each masked category is given
     * {@code LC_ALL}'s own value — unconditionally, since an already-set
     * category was already being overridden — and {@code LC_MESSAGES} is set
     * last. {@code LANGUAGE} is left alone: gettext ignores it once the
     * messages locale is {@code C}, and a line that cannot change the answer
     * is a line nothing can test.
     *
     * <p>The cost is that a non-English user sees LilyPond's complaints in
     * English — the same trade {@link TempoMark}'s {@code Locale.ROOT} makes,
     * one process out. Package-private so it can be tested against a
     * {@link ProcessBuilder} whose environment has been poisoned first;
     * testing through the ambient environment proved nothing, because no
     * build machine here sets any of this.
     */
    static void speakEnglish(ProcessBuilder builder) {
        Map<String, String> environment = builder.environment();
        // An empty LC_ALL is not a setting -- POSIX has it fall through to the
        // individual categories -- so it masks nothing and carries nothing.
        String everything = environment.remove("LC_ALL");
        if (everything != null && !everything.isEmpty()) {
            for (String category : MASKED_BY_LC_ALL) {
                environment.put(category, everything);
            }
        }
        environment.put("LC_MESSAGES", "C");
    }

    /**
     * Every locale category {@code LC_ALL} masks, except the one being changed.
     *
     * <p>The first five are POSIX; the rest are glibc's, and they are here
     * because glibc is what reads them — leaving one out re-exposes whatever the
     * ambient environment set it to, and one uninstalled locale among them
     * takes the whole child down to {@code C}.
     *
     * <p>{@code LC_MESSAGES} is deliberately absent: it is the one category this
     * method is here to change, and {@link #speakEnglish} sets it afterwards.
     */
    private static final List<String> MASKED_BY_LC_ALL = List.of(
            "LC_CTYPE", "LC_NUMERIC", "LC_TIME", "LC_COLLATE", "LC_MONETARY",
            "LC_PAPER", "LC_NAME", "LC_ADDRESS", "LC_TELEPHONE", "LC_MEASUREMENT",
            "LC_IDENTIFICATION");

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
        speakEnglish(builder);

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
            ProcessBuilder builder = new ProcessBuilder(binary.toString(), "--version")
                    .redirectErrorStream(true);
            speakEnglish(builder);
            Process process = builder.start();
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
