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
         * and nothing else about a chart whose bars do not sum. It is reachable
         * through that command: #160 gave the chord chart a {@code \time} and a
         * {@code |} per bar, so the only part {@code mw render} writes today can
         * now fail a check. It could not when this accessor was written, which
         * is worth saying because the accessor came first — for a while the
         * fact was readable and nothing shipped could produce it, and only
         * {@link StaffNotation}'s output, engraved by {@code mw-it}, gave it
         * anything to read.
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
     * <p>LilyPond translates its own messages through gettext, and it translates
     * the <em>prefix</em> while leaving the payload alone: under {@code
     * LANGUAGE=it} a failed bar check is {@code attenzione: bar check failed}
     * and a layout complaint is {@code errore di programmazione: ...}. Anything
     * reading that output for the word "warning" therefore stops reading it at
     * all on an Italian machine, and stops silently — which is the worst way for
     * a check to fail, because the build stays green and the thing it was
     * watching for goes unreported. Round 4 of review found exactly that: under
     * {@code LANGUAGE=it} a bar that did not fill its meter produced no
     * complaint anywhere in the integration suite.
     *
     * <p>So the environment is pinned rather than the parsing widened. Widening
     * would mean carrying LilyPond's message catalogue in this repository and
     * keeping it current, in every language, for a string this project only
     * reads in order to know whether something went wrong.
     *
     * <p><b>One variable removed and twelve written, and every one of them is
     * here because a previous version got it wrong.</b> It took four attempts,
     * and the three wrong answers are worth keeping because they are successive
     * corrections of the same misreading: each fixed the layer the failure was
     * noticed at rather than the one it lived at.
     *
     * <p>Round 4 set {@code LC_ALL}, {@code LANG} and {@code LC_MESSAGES} to
     * {@code C}. Round 5 found that this broke engraving outright for a file
     * whose name is not ASCII: those variables also set the <em>character
     * type</em>, and LilyPond cannot decode a non-ASCII {@code argv} filename
     * under a {@code C} ctype. {@code canción.ly}, {@code Präludium.ly} and
     * {@code 夜曲.ly} all engraved before that change and all failed after it
     * with {@code fatal error: failed files: "canci??n.ly"}. A fix for a test
     * that had silently stopped checking had become a fix that silently stopped
     * engraving, which is worse by a distance.
     *
     * <p>Round 5 therefore narrowed it to {@code LC_MESSAGES} alone — and round
     * 6 found that this reopens the original bug for the commonest way of all to
     * set a locale, because POSIX has {@code LC_ALL} <em>override</em> every
     * individual category. A user with {@code LC_ALL=it_IT.UTF-8} got
     * {@code attenzione: bar check failed} again, and the integration suite went
     * silent again with it.
     *
     * <p>Round 6 then moved {@code LC_ALL} into {@code LC_CTYPE} — and round 7
     * found that one category short, which is the paragraph below.
     *
     * <p>So {@code LC_ALL} has to go — and everything it was covering has to be
     * written out in its place, which is the part round 6 got wrong and round 7
     * caught. {@code LC_ALL} does not merely set the character type; it
     * <em>masks every category</em>, and removing it un-masks whatever the
     * eleven individual variables happen to say. That matters because glibc's
     * locale selection is all-or-nothing: one category naming a locale that is
     * not installed collapses the child's whole locale to {@code C}, ctype
     * included. An ambient {@code LC_ALL=it_IT.UTF-8 LC_TIME=de_DE.UTF-8} — the
     * second harmless until the first is removed — brought round 4's broken
     * filename straight back.
     *
     * <p>Each category is therefore given {@code LC_ALL}'s own value, which is
     * what was in force before. Unconditionally rather than only where unset,
     * because a category that was already set was already being overridden, and
     * preserving it would change the child's behaviour rather than leave it
     * alone.
     *
     * <p>Measured rather than assumed: the same score engraved under a full
     * {@code it_IT.UTF-8} and under this shape produces PDFs that differ in 73
     * bytes, all of them the embedded timestamp.
     *
     * <p>{@code LANGUAGE} is left alone. gettext consults it first but ignores
     * it entirely once the messages locale is {@code C}, so clearing it changes
     * nothing — measured too: {@code LC_MESSAGES=C LANGUAGE=it} still says
     * {@code programming error}. A line that cannot change the answer is a line
     * nothing can test.
     *
     * <p>The cost is that a non-English user sees LilyPond's own complaints in
     * English. That is the trade: this project embeds those lines in its own
     * English messages anyway, and a tool whose behaviour depends on the
     * machine's locale is the same defect {@link StaffNotation}'s
     * {@code Locale.ROOT} tempo mark exists to prevent, one process out.
     *
     * <p>Package-private so it can be tested against a {@link ProcessBuilder}
     * whose environment has been poisoned first. Testing it through the ambient
     * environment proved nothing: round 5 found that deleting the body left the
     * suite green, because no build machine here sets any of this.
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
