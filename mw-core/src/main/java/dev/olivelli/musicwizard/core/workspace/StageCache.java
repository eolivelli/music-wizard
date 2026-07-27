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

package dev.olivelli.musicwizard.core.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Content-addressed storage for the output of a pipeline stage.
 *
 * <p>A stage's result is stored under a key derived from the stage name, the
 * digests of its inputs and its parameters. Re-running with anything changed
 * misses the cache and recomputes; re-running with nothing changed is free.
 * This is what makes the tool usable: separating stems takes minutes, and
 * adjusting the piano arrangement afterwards must not pay that cost again.
 *
 * <p>Writes go to a temporary file and are then moved into place, so an
 * interrupted run cannot leave a truncated entry that a later run would trust.
 */
public final class StageCache {

    /**
     * Delimiters used when building key material. Control characters are used
     * deliberately: they cannot occur in a stage name or parameter value, so
     * no combination of components can collide by concatenating differently.
     */
    private static final char FIELD_SEPARATOR = '\u0000';
    private static final char VALUE_SEPARATOR = '\u0001';

    private final Path directory;

    StageCache(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /**
     * A cache key. Built from a stage name plus arbitrary named components,
     * which are sorted before hashing so that key construction is order-independent.
     */
    public static final class Key {
        private final String stage;
        private final Map<String, String> components = new TreeMap<>();

        private Key(String stage) {
            this.stage = Objects.requireNonNull(stage, "stage");
        }

        public static Key forStage(String stage) {
            return new Key(stage);
        }

        /** Adds a named parameter to the key. */
        public Key with(String name, Object value) {
            components.put(name, String.valueOf(value));
            return this;
        }

        /** Adds a file's content digest to the key. */
        public Key withFile(String name, Path file) {
            components.put(name, "sha256:" + Workspace.sha256(file));
            return this;
        }

        /** The stage this key belongs to. */
        public String stage() {
            return stage;
        }

        /** The hex digest identifying this key. */
        public String digest() {
            StringBuilder material = new StringBuilder(stage);
            for (Map.Entry<String, String> entry : components.entrySet()) {
                material.append(FIELD_SEPARATOR).append(entry.getKey())
                        .append(VALUE_SEPARATOR).append(entry.getValue());
            }
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                byte[] hash = sha.digest(material.toString().getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required but unavailable", e);
            }
        }

        /**
         * A readable file name: the stage name, then a short digest. Keeping the
         * stage name visible makes a workspace directory diagnosable by eye.
         */
        String fileName(String extension) {
            String safeStage = stage.replaceAll("[^A-Za-z0-9._-]", "_");
            return safeStage + "-" + digest().substring(0, 16) + extension;
        }

        @Override
        public String toString() {
            return stage + "/" + digest().substring(0, 16);
        }
    }

    /** Where an entry for this key would live. */
    public Path pathFor(Key key, String extension) {
        return directory.resolve(key.stage()).resolve(key.fileName(extension));
    }

    /** True when a result is already stored for this key. */
    public boolean contains(Key key, String extension) {
        return Files.isRegularFile(pathFor(key, extension));
    }

    /** The stored path for this key, if present. */
    public Optional<Path> find(Key key, String extension) {
        Path path = pathFor(key, extension);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /** Reads a stored text result. */
    public Optional<String> readText(Key key, String extension) {
        return find(key, extension).map(path -> {
            try {
                return Files.readString(path);
            } catch (IOException e) {
                throw new UncheckedIOException("could not read cache entry " + path, e);
            }
        });
    }

    /** Stores a text result, replacing any existing entry atomically. */
    public Path writeText(Key key, String extension, String content) {
        Path target = pathFor(key, extension);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".partial-", extension);
            Files.writeString(temporary, content);
            moveIntoPlace(temporary, target);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("could not write cache entry " + target, e);
        }
    }

    /** Stores a binary result, replacing any existing entry atomically. */
    public Path writeBytes(Key key, String extension, byte[] content) {
        Path target = pathFor(key, extension);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".partial-", extension);
            Files.write(temporary, content);
            moveIntoPlace(temporary, target);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("could not write cache entry " + target, e);
        }
    }

    /** Reads a stored binary result. */
    public Optional<byte[]> readBytes(Key key, String extension) {
        return find(key, extension).map(path -> {
            try {
                return Files.readAllBytes(path);
            } catch (IOException e) {
                throw new UncheckedIOException("could not read cache entry " + path, e);
            }
        });
    }

    /**
     * Reserves a path for a stage to write a large artifact directly, such as a
     * separated stem. The caller writes to the returned temporary path and then
     * calls {@link #commit}, so a crashed run leaves no half-written entry that
     * a later run would mistake for a complete one.
     */
    public Path stagingPath(Key key, String extension) {
        Path target = pathFor(key, extension);
        try {
            Files.createDirectories(target.getParent());
            return Files.createTempFile(target.getParent(), ".partial-", extension);
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage cache entry for " + key, e);
        }
    }

    /** Moves a staged artifact into its final cache position. */
    public Path commit(Path staged, Key key, String extension) {
        Path target = pathFor(key, extension);
        try {
            Files.createDirectories(target.getParent());
            moveIntoPlace(staged, target);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("could not commit cache entry " + target, e);
        }
    }

    /** Removes every entry for a stage, forcing it to recompute. */
    public void invalidateStage(String stage) {
        Path stageDirectory = directory.resolve(stage);
        if (!Files.isDirectory(stageDirectory)) {
            return;
        }
        try (var entries = Files.walk(stageDirectory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException("could not delete " + path, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("could not invalidate stage " + stage, e);
        }
    }

    private static void moveIntoPlace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Some filesystems cannot move atomically; a plain replace is the
            // best available and still avoids a partially written target.
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
