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

    /** Distinguishes an absent value from the four-character string "null". */
    private static final String NULL_MARKER = "\u0000null";
    private static final String VALUE_TAG = "=";

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

        /**
         * Adds a named parameter to the key.
         *
         * <p>A null value is tagged distinctly rather than stringified, because
         * {@code String.valueOf(null)} is literally {@code "null"} and would
         * otherwise collide with a parameter whose value is that word.
         */
        public Key with(String name, Object value) {
            Objects.requireNonNull(name, "name");
            components.put(name, value == null ? NULL_MARKER : VALUE_TAG + value);
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
            // Every part is length-prefixed. Delimiter characters alone are not
            // enough: nothing stops a parameter value containing one, and a
            // collision here means one stage's cached output is served for
            // different inputs, which is a silently wrong transcription.
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                digestLengthPrefixed(sha, stage);
                for (Map.Entry<String, String> entry : components.entrySet()) {
                    digestLengthPrefixed(sha, entry.getKey());
                    digestLengthPrefixed(sha, entry.getValue());
                }
                return HexFormat.of().formatHex(sha.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required but unavailable", e);
            }
        }

        /**
         * Feeds one component to the digest, prefixed by its length in BYTES.
         *
         * <p>Counting characters instead would not disambiguate anything, because
         * UTF-8 encoding folds every unpaired surrogate to {@code '?'}: five
         * distinct values then produce identical bytes, and a character count
         * cannot undo an encoding that already erased the difference. A collision
         * here serves one stage's cached output for a different input, which is a
         * silently wrong transcription.
         */
        private static void digestLengthPrefixed(MessageDigest digest, String value) {
            byte[] encoded = encodeStrictly(value);
            digest.update(Integer.toString(encoded.length).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(encoded);
            digest.update((byte) ';');
        }

        /**
         * Encodes a component to UTF-8, refusing anything that cannot be encoded
         * faithfully.
         *
         * <p>A byte-length prefix alone does not make the digest injective,
         * because the encoding is itself lossy: {@code String.getBytes(UTF_8)}
         * silently replaces every unpaired surrogate with a single {@code '?'},
         * so five distinct values collapse to identical bytes before the length
         * is ever counted. Rejecting is the honest response -- a lone surrogate
         * in a cache key means the value upstream is already corrupt, and the
         * alternative is serving one stage's output for a different input.
         */
        private static byte[] encodeStrictly(String value) {
            java.nio.charset.CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            try {
                java.nio.ByteBuffer buffer = encoder.encode(java.nio.CharBuffer.wrap(value));
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                return bytes;
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new IllegalArgumentException(
                        "cache key components must be valid text; this one contains an unpaired"
                                + " surrogate or otherwise unencodable character", e);
            }
        }

        /**
         * The stage name reduced to something safe to use as a single path
         * component.
         *
         * <p>Applied to the directory as well as the file name. Used raw, a
         * stage name of {@code ..} or an absolute path escapes the workspace
         * entirely, which turns cache invalidation into an arbitrary recursive
         * delete.
         */
        String safeStageName() {
            String sanitized = stage.replaceAll("[^A-Za-z0-9._-]", "_");
            // "." and ".." survive the character filter but are still traversal.
            if (sanitized.isEmpty() || sanitized.equals(".") || sanitized.equals("..")) {
                return "_";
            }
            return sanitized;
        }

        /**
         * A readable file name: the stage name, then a short digest. Keeping the
         * stage name visible makes a workspace directory diagnosable by eye.
         */
        String fileName(String extension) {
            return safeStageName() + "-" + digest().substring(0, 16) + extension;
        }

        @Override
        public String toString() {
            return stage + "/" + digest().substring(0, 16);
        }
    }

    /** Where an entry for this key would live. */
    public Path pathFor(Key key, String extension) {
        return directory.resolve(key.safeStageName()).resolve(key.fileName(extension));
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
            try {
                Files.writeString(temporary, content);
                moveIntoPlace(temporary, target);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temporary);
                throw e;
            }
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
            try {
                Files.write(temporary, content);
                moveIntoPlace(temporary, target);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temporary);
                throw e;
            }
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

    /**
     * Discards a staged artifact that will never be committed, so an abandoned
     * stage does not leave hundreds of megabytes behind.
     */
    public void discard(Path staged) {
        if (staged == null) {
            return;
        }
        try {
            Files.deleteIfExists(staged);
        } catch (IOException e) {
            throw new UncheckedIOException("could not discard staged file " + staged, e);
        }
    }

    /** Removes any staging files left behind by an interrupted earlier run. */
    public int sweepAbandonedStagingFiles() {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        int removed = 0;
        try (var entries = Files.walk(directory)) {
            for (Path path : entries.filter(Files::isRegularFile).toList()) {
                if (path.getFileName().toString().startsWith(".partial-")) {
                    Files.deleteIfExists(path);
                    removed++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not sweep staging files in " + directory, e);
        }
        return removed;
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

    /**
     * Removes every entry for a stage, forcing it to recompute.
     *
     * <p>The stage name is sanitized and the resolved directory is checked to be
     * inside the cache before anything is deleted. This method walks and deletes
     * recursively, so an unchecked name would be an arbitrary delete primitive,
     * and stage names can reach here from a command-line flag.
     */
    public void invalidateStage(String stage) {
        Objects.requireNonNull(stage, "stage");
        Path stageDirectory = directory.resolve(Key.forStage(stage).safeStageName())
                .normalize().toAbsolutePath();
        Path cacheRoot = directory.normalize().toAbsolutePath();
        if (!stageDirectory.startsWith(cacheRoot) || stageDirectory.equals(cacheRoot)) {
            throw new IllegalArgumentException(
                    "refusing to invalidate outside the cache directory: " + stage);
        }
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
