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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Generated LilyPond against its committed golden file.
 *
 * <p>{@code -Dmw.golden.update=true} rewrites the file from the source tree
 * Maven passes as {@code -Dbasedir}, and the run that does it is expected to
 * read the diff, because the comparison it would have failed is the one the
 * flag suppressed.
 *
 * <p>{@code StaffNotationTest} still holds its own copy of this, which also
 * checks that every bar fills its meter before comparing (#603).
 */
final class Goldens {

    private static final String UPDATE_PROPERTY = "mw.golden.update";

    private Goldens() {
    }

    static void assertGolden(String name, String actual) {
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Path target = directory()
                    .orElseThrow(() -> new AssertionError(UPDATE_PROPERTY
                            + " needs the module directory, which Maven passes as -Dbasedir"))
                    .resolve(name + ".ly");
            try {
                Files.writeString(target, actual);
            } catch (IOException e) {
                throw new UncheckedIOException("could not update golden " + name, e);
            }
            System.err.println("updated golden file " + target);
        }
        assertThat(actual).isEqualTo(read(name));
    }

    /**
     * The golden as it is on disk, falling back to the classpath copy when the
     * source tree cannot be located — which is what happens outside Maven.
     *
     * <p>The two differ during an update run, and reading the classpath copy
     * there made the update mode a trap: every file was rewritten and then
     * compared against what {@code process-test-resources} had already staged.
     */
    private static String read(String name) {
        Optional<Path> onDisk = directory().map(dir -> dir.resolve(name + ".ly"))
                .filter(Files::isRegularFile);
        if (onDisk.isPresent()) {
            try {
                return Files.readString(onDisk.get());
            } catch (IOException e) {
                throw new UncheckedIOException("could not read golden " + name, e);
            }
        }
        try (InputStream in = Goldens.class.getResourceAsStream("/golden/" + name + ".ly")) {
            if (in == null) {
                throw new AssertionError("no golden file for " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read golden " + name, e);
        }
    }

    private static Optional<Path> directory() {
        String basedir = System.getProperty("basedir", System.getProperty("user.dir"));
        if (basedir == null) {
            return Optional.empty();
        }
        Path directory = Path.of(basedir, "src", "test", "resources", "golden");
        return Files.isDirectory(directory) ? Optional.of(directory) : Optional.empty();
    }
}
