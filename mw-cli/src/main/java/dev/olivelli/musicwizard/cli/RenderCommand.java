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

import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.notation.ChordChart;
import dev.olivelli.musicwizard.notation.LilyPondRenderer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Produces sheet music from an analysed workspace.
 *
 * <p>LilyPond, MusicXML and MIDI are always written for a part that can be
 * produced; the PDF is produced only if a LilyPond binary can be found. A
 * missing binary degrades the output rather than failing the command, since the
 * sources are still useful on their own.
 *
 * <p>The same posture governs the parts themselves, which is what #82 was
 * about. This command used to announce {@code voice, piano, bass, chords} and
 * then write only the chord files, with exit status 0 — so a user could not tell
 * whether three parts had failed, been skipped, or never existed. Melody, bass
 * and piano are M2 and M4 work; a part that cannot be produced is now named
 * along with the reason, exactly as a missing engraver is. The default is
 * therefore what is actually implemented, and naming an unimplemented part
 * explicitly is answered rather than ignored.
 *
 * <p>Non-zero exit is reserved for producing <em>nothing at all</em>. Writing
 * three parts of four is a partial success and the command says which three; a
 * run that wrote nothing has failed at the only thing it was asked to do, and a
 * script chaining {@code render} needs to be able to tell.
 */
@Command(name = "render", description = "Generate sheet music from a workspace.")
final class RenderCommand implements Callable<Integer> {

    /**
     * A part this command knows how to be asked for.
     *
     * <p>The unimplemented ones are listed here rather than left out, because
     * being able to answer "not yet, and here is the issue" is the whole point:
     * a name this command does not recognise at all is a typo, and deserves a
     * different answer from a name it recognises and cannot yet honour.
     */
    private enum Part {
        CHORDS("chords", null),
        VOICE("voice", "melody transcription is not implemented yet (#8)"),
        BASS("bass", "bass transcription is not implemented yet (#8)"),
        PIANO("piano", "the piano reduction is not implemented yet (#10)");

        private final String partName;
        private final String notImplemented;

        Part(String partName, String notImplemented) {
            this.partName = partName;
            this.notImplemented = notImplemented;
        }

        String partName() {
            return partName;
        }

        boolean isImplemented() {
            return notImplemented == null;
        }

        /**
         * Why this part cannot be produced from this score, or {@code null} when
         * it can.
         *
         * <p>The empty-harmony message depends on where the score came from,
         * because the two causes are genuinely different and only one of them is
         * a gap in the tool. An audio analysis that produced no chords looked and
         * found nothing; a MIDI import did not look, because it reads notes and
         * naming the harmony they spell is a stage that does not exist (#115).
         */
        String unavailableReason(Score score, SourceKind kind) {
            if (notImplemented != null) {
                return notImplemented;
            }
            if (this == CHORDS && score.chords().isEmpty()) {
                return kind == SourceKind.MIDI
                        ? "this score holds no chord progression; a MIDI file states notes,"
                                + " not harmony, and nothing estimates one from the other yet (#115)"
                        : "this score holds no chord progression; the analysis found no"
                                + " harmony in this recording";
            }
            return null;
        }

        static Optional<Part> byName(String name) {
            String wanted = name.trim().toLowerCase(Locale.ROOT);
            for (Part part : values()) {
                if (part.partName.equals(wanted)) {
                    return Optional.of(part);
                }
            }
            return Optional.empty();
        }

        static List<Part> implemented() {
            return java.util.Arrays.stream(values()).filter(Part::isImplemented).toList();
        }
    }

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = "WORKSPACE", description = "The workspace directory.")
    Path workspaceDirectory;

    @Option(names = "--parts", split = ",", paramLabel = "PART",
            description = "Which parts to render: chords, voice, bass, piano. Only "
                    + "chords can be produced today; naming any of the others reports "
                    + "why it cannot. Defaults to every part that is implemented.")
    List<String> parts;

    @Option(names = "--transpose", paramLabel = "SEMITONES",
            description = "Transpose every part by this many semitones.")
    Integer transpose;

    @Option(names = "--paper", paramLabel = "SIZE", description = "Paper size, e.g. a4 or letter.")
    String paperSize;

    @Option(names = "--no-pdf", description = "Write sources only; do not invoke LilyPond.")
    boolean noPdf;

    @Override
    public Integer call() {
        Workspace workspace = Workspace.open(workspaceDirectory);
        MusicWizardConfig config = workspace.effectiveConfig(overrides());
        List<Part> requested = requestedParts();

        System.out.println("Workspace  " + workspace.root());
        System.out.println("Parts      " + String.join(", ",
                requested.stream().map(Part::partName).toList()));
        System.out.println("Output     " + workspace.outputDirectory());

        Optional<Path> lilypond = announceEngraver(config);

        Score score = workspace.readScore().orElseThrow(() -> new IllegalStateException(
                "no transcription yet; run: mw analyze " + workspaceDirectory));
        SourceKind kind = SourceKind.detect(workspace.sourceFile());

        List<Path> written = new ArrayList<>();
        List<String> notWritten = new ArrayList<>();
        boolean chartWritten = false;
        for (Part part : requested) {
            String reason = part.unavailableReason(score, kind);
            if (reason != null) {
                notWritten.add(String.format("  %-8s %s", part.partName(), reason));
                continue;
            }
            if (part == Part.CHORDS) {
                written.addAll(writeChordChart(workspace, score, lilypond));
                chartWritten = true;
            }
        }

        if (!written.isEmpty()) {
            System.out.println();
            written.forEach(file -> System.out.println("Wrote " + file));
        }
        if (!notWritten.isEmpty()) {
            System.out.println();
            System.out.println("Not written:");
            notWritten.forEach(System.out::println);
        }

        if (written.isEmpty()) {
            System.out.println();
            System.out.println("Nothing could be written.");
            return CommandLine.ExitCode.SOFTWARE;
        }
        if (chartWritten) {
            System.out.println();
            System.out.println(ChordChart.toText(score));
        }
        return CommandLine.ExitCode.OK;
    }

    /**
     * The parts to attempt.
     *
     * <p>Absent rather than a four-name default, so that "the user asked for
     * everything that works" and "the user asked for piano" stay distinguishable:
     * the second deserves an answer about piano and the first does not.
     */
    private List<Part> requestedParts() {
        if (parts == null) {
            return Part.implemented();
        }
        // Ordered and de-duplicated: --parts chords,chords should not write the
        // chart twice, and the order the user typed is the order to report in.
        LinkedHashSet<Part> resolved = new LinkedHashSet<>();
        for (String name : parts) {
            resolved.add(Part.byName(name).orElseThrow(() -> new CommandLine.ParameterException(
                    spec.commandLine(),
                    "unknown part '" + name + "'; expected one of: " + String.join(", ",
                            java.util.Arrays.stream(Part.values()).map(Part::partName).toList()))));
        }
        if (resolved.isEmpty()) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "--parts was given no part names");
        }
        return List.copyOf(resolved);
    }

    /** Reports which engraver will be used, and returns it. */
    private Optional<Path> announceEngraver(MusicWizardConfig config) {
        if (noPdf) {
            System.out.println("Engraver   skipped (--no-pdf)");
            return Optional.empty();
        }
        // Resolved once. It used to be looked up twice, which on a machine whose
        // PATH changed between the two calls would announce one binary and run
        // another.
        Optional<Path> lilypond = ConfigLoader.findLilyPond(config);
        if (lilypond.isPresent()) {
            System.out.println("Engraver   " + lilypond.get());
            return lilypond;
        }
        System.out.println("Engraver   not found");
        System.out.println();
        System.out.println("LilyPond is not installed, so no PDF will be produced.");
        // Named exactly, and no longer ".ly, .musicxml and .midi": nothing emits
        // MusicXML or MIDI yet, and this command promising two files it does not
        // write is the same defect as the parts list #82 was filed for.
        System.out.println("The .ly source is still written, and can be engraved");
        System.out.println("elsewhere. To install it:");
        System.out.println("  brew install lilypond      (macOS, or Homebrew on Linux)");
        System.out.println("  apt install lilypond       (Debian or Ubuntu)");
        System.out.println("Or set notation.lilypondPath in the workspace config.");
        return Optional.empty();
    }

    /** Writes the chord chart's sources, and its PDF where there is an engraver. */
    private List<Path> writeChordChart(
            Workspace workspace, Score score, Optional<Path> lilypond) {
        Path out = workspace.outputDirectory();
        List<Path> written = new ArrayList<>();
        try {
            Files.createDirectories(out);
            Path txt = out.resolve("chords.txt");
            Files.writeString(txt, ChordChart.toText(score));
            written.add(txt);

            Path ly = out.resolve("chords.ly");
            Files.writeString(ly, ChordChart.toLilyPond(score));
            written.add(ly);

            if (lilypond.isPresent()) {
                LilyPondRenderer.Result result = new LilyPondRenderer(lilypond.get()).render(ly);
                if (result.succeeded()) {
                    written.add(result.pdf().orElseThrow());
                } else {
                    // Reported, not thrown: the sources are still useful, and
                    // losing them because the engraver failed would be worse.
                    System.out.println();
                    System.out.println("LilyPond could not engrave the chart:");
                    result.output().lines().limit(10)
                            .forEach(line -> System.out.println("  " + line));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not write output", e);
        }
        return written;
    }

    private MusicWizardConfig overrides() {
        if (transpose == null && paperSize == null) {
            return null;
        }
        return new MusicWizardConfig(null, null,
                new MusicWizardConfig.NotationConfig(null, paperSize, transpose, null, null),
                null, null, null);
    }
}
