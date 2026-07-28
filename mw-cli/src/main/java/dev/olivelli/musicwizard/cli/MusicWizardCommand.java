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

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Entry point for the {@code mw} command.
 *
 * <p>The command set follows the workspace lifecycle: {@code init} imports a
 * recording, {@code analyze} fills in the transcription, {@code render}
 * produces sheets, and {@code info} reports what has been computed so far.
 * Splitting analysis from rendering is deliberate, because analysis is the
 * expensive half and rendering is the half users iterate on.
 */
@Command(
        name = "mw",
        mixinStandardHelpOptions = true,
        versionProvider = MusicWizardCommand.VersionProvider.class,
        description = "Generate sheet music from a recording.",
        subcommands = {
                InitCommand.class,
                AnalyzeCommand.class,
                RenderCommand.class,
                InfoCommand.class,
                DoctorCommand.class
        })
public final class MusicWizardCommand implements Runnable {

    @Option(names = {"-v", "--verbose"}, description = "Log what each stage is doing.")
    boolean verbose;

    @Override
    public void run() {
        // With no subcommand there is nothing to do but explain the options.
        CommandLine.usage(this, System.out);
    }

    /**
     * The command line the {@code mw} binary runs, error handling included.
     *
     * <p>Separate from {@link #main} so that a test can execute a command the way
     * a user does. It is the exception handler below that turns a refused input
     * into one readable line, and a test that built its own {@code CommandLine}
     * would get picocli's default handler and a stack trace instead -- proving
     * nothing about what the tool actually prints.
     */
    static CommandLine commandLine() {
        return new CommandLine(new MusicWizardCommand())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((exception, commandLine, parseResult) -> {
                    // Users get a readable message; the stack trace stays behind --verbose.
                    commandLine.getErr().println("error: " + rootMessage(exception));
                    if (parseResult.hasMatchedOption("--verbose")) {
                        exception.printStackTrace(commandLine.getErr());
                    }
                    return CommandLine.ExitCode.SOFTWARE;
                });
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message != null ? message : current.getClass().getSimpleName();
    }

    /** Reports the version recorded in the jar manifest, or a dev placeholder. */
    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            Package pkg = MusicWizardCommand.class.getPackage();
            String version = pkg != null ? pkg.getImplementationVersion() : null;
            return new String[] {"mw " + (version != null ? version : "(development build)")};
        }
    }
}
