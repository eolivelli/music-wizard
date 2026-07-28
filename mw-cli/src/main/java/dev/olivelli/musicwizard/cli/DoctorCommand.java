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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/**
 * Reports whether the environment can run the full pipeline.
 *
 * <p>Worth having as its own command because the two things most likely to be
 * missing, a LilyPond binary and an API key, both fail late and confusingly if
 * they are only discovered halfway through a long analysis run.
 */
@Command(name = "doctor", description = "Check that the environment is set up correctly.")
final class DoctorCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        MusicWizardConfig config = new ConfigLoader().effectiveConfig(null, null);
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
}
