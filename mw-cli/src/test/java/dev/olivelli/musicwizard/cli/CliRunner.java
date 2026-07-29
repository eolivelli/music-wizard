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

    /**
     * What one command run produced.
     *
     * @param exitCode    what picocli returned
     * @param out         stdout alone
     * @param err         stderr alone
     * @param interleaved both, in the order they were written — see
     *                    {@link #transcript()}
     */
    record Result(int exitCode, String out, String err, String interleaved) {

        /** Everything the user saw, in no particular interleaving. */
        String all() {
            return out + err;
        }

        /**
         * Everything the user saw, in the order a terminal would have shown it.
         *
         * <p>Separate from {@link #all()} because the two answer different
         * questions and only this one can answer "did the warning come after the
         * line it is about". Round 1 of review on #156 found that it could not
         * be asked at all: {@code render} prints the files it wrote on stdout
         * and warns about them on stderr, and moving the warning block to before
         * the file list — the exact regression the {@code Emitted} record exists
         * to prevent — left every test green, because two separate buffers
         * cannot record which was written first.
         *
         * <p>Order is faithful only to the granularity of a write. Both streams
         * are auto-flushing and every caller here uses {@code println}, so a line
         * is one write and lines cannot interleave within themselves; nothing in
         * this suite runs the command on more than one thread.
         */
        String transcript() {
            return interleaved;
        }
    }

    private CliRunner() {
    }

    static Result run(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(new Tee(out, both), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new Tee(err, both), true, StandardCharsets.UTF_8));
            // Built inside the swap, so picocli's own writers wrap the captured
            // streams rather than the real ones.
            int exitCode = MusicWizardCommand.commandLine().execute(args);
            System.out.flush();
            System.err.flush();
            return new Result(exitCode,
                    out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8),
                    both.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /**
     * Writes every byte to its own stream and to the shared transcript.
     *
     * <p>Both destinations are {@link ByteArrayOutputStream}s, which neither
     * throw nor need closing, so there is nothing to fail halfway and nothing to
     * release; the overrides exist only so a single {@code println} lands in the
     * transcript as one contiguous run rather than byte by byte.
     */
    private static final class Tee extends java.io.OutputStream {

        private final ByteArrayOutputStream own;
        private final ByteArrayOutputStream shared;

        Tee(ByteArrayOutputStream own, ByteArrayOutputStream shared) {
            this.own = own;
            this.shared = shared;
        }

        @Override
        public void write(int b) {
            own.write(b);
            shared.write(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            own.write(bytes, offset, length);
            shared.write(bytes, offset, length);
        }
    }
}
