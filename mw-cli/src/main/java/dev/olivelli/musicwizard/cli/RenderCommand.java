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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Produces sheet music from an analysed workspace.
 *
 * <p>LilyPond, MusicXML and MIDI are always written; the PDF is produced only if
 * a LilyPond binary can be found. A missing binary degrades the output rather
 * than failing the command, since the sources are still useful on their own.
 */
@Command(name = "render", description = "Generate sheet music from a workspace.")
final class RenderCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "WORKSPACE", description = "The workspace directory.")
    Path workspaceDirectory;

    @Option(names = "--parts", split = ",", paramLabel = "PART",
            description = "Which parts to render: voice, piano, bass, chords. Defaults to all.")
    List<String> parts = List.of("voice", "piano", "bass", "chords");

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

        System.out.println("Workspace  " + workspace.root());
        System.out.println("Parts      " + String.join(", ", parts));
        System.out.println("Output     " + workspace.outputDirectory());

        if (noPdf) {
            System.out.println("Engraver   skipped (--no-pdf)");
        } else {
            Optional<Path> lilypond = ConfigLoader.findLilyPond(config);
            if (lilypond.isPresent()) {
                System.out.println("Engraver   " + lilypond.get());
            } else {
                System.out.println("Engraver   not found");
                System.out.println();
                System.out.println("LilyPond is not installed, so no PDF will be produced.");
                System.out.println("The .ly, .musicxml and .midi sources are still written, and");
                System.out.println("can be engraved elsewhere. To install it:");
                System.out.println("  brew install lilypond      (macOS, or Homebrew on Linux)");
                System.out.println("  apt install lilypond       (Debian or Ubuntu)");
                System.out.println("Or set notation.lilypondPath in the workspace config.");
            }
        }

        Score score = workspace.readScore().orElseThrow(() -> new IllegalStateException(
                "no transcription yet; run: mw analyze " + workspaceDirectory));

        java.nio.file.Path out = workspace.outputDirectory();
        try {
            java.nio.file.Files.createDirectories(out);
            java.nio.file.Path txt = out.resolve("chords.txt");
            java.nio.file.Files.writeString(txt, ChordChart.toText(score));
            System.out.println();
            System.out.println("Wrote " + txt);

            java.nio.file.Path ly = out.resolve("chords.ly");
            java.nio.file.Files.writeString(ly, ChordChart.toLilyPond(score));
            System.out.println("Wrote " + ly);

            if (!noPdf) {
                java.util.Optional<java.nio.file.Path> binary = ConfigLoader.findLilyPond(config);
                if (binary.isPresent()) {
                    LilyPondRenderer.Result result =
                            new LilyPondRenderer(binary.get()).render(ly);
                    if (result.succeeded()) {
                        System.out.println("Wrote " + result.pdf().orElseThrow());
                    } else {
                        // Reported, not thrown: the sources are still useful, and
                        // losing them because the engraver failed would be worse.
                        System.out.println();
                        System.out.println("LilyPond could not engrave the chart:");
                        result.output().lines().limit(10)
                                .forEach(line -> System.out.println("  " + line));
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("could not write output", e);
        }

        System.out.println();
        System.out.println(ChordChart.toText(score));
        return 0;
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
