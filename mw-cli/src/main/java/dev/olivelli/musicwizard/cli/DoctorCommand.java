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
import dev.olivelli.musicwizard.core.ml.MlProviders;
import dev.olivelli.musicwizard.core.ml.ModelCacheLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/**
 * Reports whether the environment can run the full pipeline.
 *
 * <p>Worth having as its own command because a missing LilyPond binary fails
 * late and confusingly if it is only discovered after a long analysis run.
 *
 * <p>The API key is reported for a different reason and with a different claim.
 * Nothing consumes it -- the advisor layer is #11 and {@code mw-llm} holds no
 * source -- so it fails nowhere, and saying it made "the advisor layer
 * available" was this command's version of the defect #82 was filed for, in the
 * one command whose whole job is answering what works.
 */
@Command(name = "doctor", description = "Check that the environment is set up correctly.")
final class DoctorCommand implements Callable<Integer> {

    private final ConfigLoader loader;

    DoctorCommand() {
        this(new ConfigLoader());
    }

    /** For tests, which need a global layer they control rather than the machine's. */
    DoctorCommand(ConfigLoader loader) {
        this.loader = loader;
    }

    @Override
    public Integer call() {
        MusicWizardConfig config = loader.effectiveConfig(null, null);
        boolean allWell = true;

        System.out.println("Java        " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");

        Path globalConfig = ConfigLoader.globalConfigFile();
        System.out.println("Config      " + (Files.isRegularFile(globalConfig)
                ? globalConfig.toString()
                : "none at " + globalConfig + " (defaults in use)"));

        Optional<Path> lilypond = ConfigLoader.findLilyPond(config);
        if (lilypond.isPresent()) {
            System.out.println("LilyPond    " + lilypond.get());
        } else {
            System.out.println("LilyPond    NOT FOUND - PDFs cannot be produced");
            System.out.println("            install with: brew install lilypond");
            System.out.println("            or apt install lilypond");
            allWell = false;
        }

        // Providers are discovered, not linked: mw-ml's implementations reach
        // this module through ServiceLoader at runtime scope, so what this
        // reports is what a user's classpath actually has. An id configured but
        // absent is the LilyPond case one layer up -- name what is missing and
        // what is present, never fail.
        MusicWizardConfig.MlConfig ml = config.ml();
        report("Separation", ml == null ? null : ml.separationProvider(),
                MlProviders.separationIds(), "#312");
        report("Lyrics ASR", ml == null ? null : ml.asrProvider(),
                MlProviders.asrIds(), "#314");
        // Whether the configured provider will actually run is the provider's
        // own question: it knows which files loading demands, and it answers
        // for the environment it configured itself from (#383), which is
        // exactly what the first transcription will use. Doctor holds no
        // second copy of any provider's file list to fall stale.
        var asr = MlProviders.asr(ml == null ? null : ml.asrProvider());
        var problem = asr.flatMap(dev.olivelli.musicwizard.core.ml.AsrProvider::readinessProblem);
        if (problem.isPresent()) {
            System.out.println("            " + problem.get());
            allWell = false;
        }
        report("Alignment", ml == null ? null : ml.alignmentProvider(),
                MlProviders.alignmentIds(), "#313");
        Path modelDir = ModelCacheLocation.directoryFor(
                ml == null ? null : ml.modelCacheDirectory());
        boolean offline = ml != null && Boolean.TRUE.equals(ml.offline());
        System.out.println("Models      " + modelDir
                + (Files.isDirectory(modelDir) ? "" : " (nothing downloaded yet)")
                + (offline ? " -- offline: nothing will be downloaded" : ""));

        // Neither branch may claim the layer is available or would become
        // available, because there is no layer: mw-llm holds no source at all
        // and #11 is the issue to build it. Saying "present (advisor layer
        // available)" is this command's version of the defect #82 was filed for,
        // and round 7 fixed the same claim in analyze and stopped there --
        // grepping isLlmEnabled finds only that command, grepping "advisor"
        // finds both.
        boolean hasKey = System.getenv("ANTHROPIC_API_KEY") != null
                || System.getenv("ANTHROPIC_AUTH_TOKEN") != null;
        System.out.println("Claude key  " + (hasKey ? "present" : "absent")
                + " (nothing uses it yet; the advisor layer is #11)");

        System.out.println();
        System.out.println(allWell
                ? "Everything needed for full output is present."
                : "Music Wizard will run, but some outputs will be unavailable.");
        return 0;
    }

    /**
     * One provider line: the configured id, and whether the classpath has it.
     *
     * <p>A configured id with no provider behind it is ordinary -- the default
     * ASR id names a provider that exists only in builds carrying the sherpa
     * profile (#314) -- so the line says which issue supplies it rather than
     * reading as a broken install.
     */
    private static void report(String label, String configured, List<String> present,
                               String issue) {
        String id = configured == null || configured.isBlank() ? null : configured;
        StringBuilder line = new StringBuilder(String.format("%-11s ", label));
        if (id == null) {
            line.append("not configured");
        } else if (present.contains(id)) {
            line.append(id).append(" (present)");
        } else {
            line.append(id).append(" -- no such provider on this classpath yet (")
                .append(issue).append(')');
        }
        if (!present.isEmpty()) {
            line.append("; available: ").append(String.join(", ", present));
        }
        System.out.println(line);
    }
}
