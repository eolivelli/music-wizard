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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Runs a command the way the {@code mw} binary runs it, and captures what a user
 * would see.
 *
 * <p>Through {@link MusicWizardCommand#commandLine()} rather than by calling a
 * {@code Callable} directly, because a good part of what these tests are about
 * lives outside the command bodies: the exit code picocli derives, the parameter
 * validation it performs, and the exception handler that turns a refused input
 * into one line instead of a stack trace.
 *
 * <p>{@code System.out} is swapped for the duration of the call. That is safe
 * here only because this suite runs sequentially -- JUnit parallel execution is
 * not enabled, and #36 records that turning it on breaks the workspace tests for
 * related reasons. A test that starts threads must not use this.
 */
final class CliRunner {

    /** What one command run produced. */
    record Result(int exitCode, String out, String err) {

        /** Everything the user saw, in no particular interleaving. */
        String all() {
            return out + err;
        }
    }

    private CliRunner() {
    }

    static Result run(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            // Built inside the swap, so picocli's own writers wrap the captured
            // streams rather than the real ones.
            int exitCode = MusicWizardCommand.commandLine().execute(args);
            System.out.flush();
            System.err.flush();
            return new Result(exitCode,
                    out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
