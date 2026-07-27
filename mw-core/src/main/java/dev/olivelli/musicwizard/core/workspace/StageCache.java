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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** The prefix every staging file carries, and the only thing a sweep touches. */
    private static final String STAGING_PREFIX = ".partial-";

    /**
     * How long a staging file must have been untouched before a sweep is willing
     * to treat it as abandoned.
     *
     * <p>Deliberately far longer than any stage takes. There is no portable way
     * to ask whether another process still has a file open, so the sweep infers
     * it from the modification time, and the cost of the two errors is wildly
     * asymmetric: keeping a dead file for another day wastes disk, while
     * deleting a live one destroys a separation run that may already be several
     * minutes in.
     */
    public static final Duration ABANDONED_STAGING_AGE = Duration.ofHours(24);

    /**
     * Staging files this JVM reserved and has neither committed nor discarded.
     *
     * <p>Static because the ownership is the process's, not the instance's:
     * {@link Workspace#cache()} hands out a fresh {@code StageCache} every call,
     * so the object that reserved a path is routinely not the one that commits it.
     */
    private static final Set<Path> OUTSTANDING_STAGED = ConcurrentHashMap.newKeySet();

    private static final AtomicBoolean CLEANUP_HOOK_INSTALLED = new AtomicBoolean();

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
            Key key = new Key(stage);
            encodeStrictly(stage);
            return key;
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
            String encoded = value == null ? NULL_MARKER : VALUE_TAG + value;
            // Fail at the call site rather than later inside digest() or, worse,
            // inside toString() while someone is debugging.
            encodeStrictly(name);
            encodeStrictly(encoded);
            components.put(name, encoded);
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
            Path temporary = Files.createTempFile(target.getParent(), STAGING_PREFIX, extension);
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
            Path temporary = Files.createTempFile(target.getParent(), STAGING_PREFIX, extension);
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
     *
     * <p>The reservation is remembered until it is committed or discarded, and
     * anything still outstanding is deleted when the JVM exits. Without that,
     * the only thing collecting an abandoned stem would be the age-based sweep,
     * which cannot fire until a day later and only if somebody opens that same
     * workspace again -- so the ordinary case, a run that dies and is retried
     * minutes later, would leak its stem for good.
     */
    public Path stagingPath(Key key, String extension) {
        Path target = pathFor(key, extension);
        try {
            Files.createDirectories(target.getParent());
            Path staged = Files.createTempFile(target.getParent(), STAGING_PREFIX, extension);
            rememberOutstanding(staged);
            return staged;
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
            forgetOutstanding(staged);
        } catch (IOException e) {
            throw new UncheckedIOException("could not discard staged file " + staged, e);
        }
    }

    private static void rememberOutstanding(Path staged) {
        // Tracked before the hook is armed, never after: a hook installed by an
        // earlier reservation is already able to collect this one, and a file
        // that exists but is not tracked is exactly the leak being fixed.
        OUTSTANDING_STAGED.add(staged.toAbsolutePath().normalize());
        installCleanupHook();
    }

    /**
     * Arms the exit-time cleanup, once per process.
     *
     * <p>Installed on first use rather than from a static initialiser, so a
     * process that never stages anything installs no hook at all.
     */
    private static void installCleanupHook() {
        if (CLEANUP_HOOK_INSTALLED.get()) {
            return;
        }
        synchronized (CLEANUP_HOOK_INSTALLED) {
            if (CLEANUP_HOOK_INSTALLED.get()) {
                return;
            }
            try {
                Runtime.getRuntime().addShutdownHook(
                        new Thread(StageCache::discardOutstandingStagedFiles, "mw-staging-cleanup"));
            } catch (IllegalStateException alreadyShuttingDown) {
                // A stage reached from somebody else's shutdown hook cannot arm
                // one of its own. Refusing the reservation over that would be a
                // worse answer than not arming it: the caller would get an
                // exception type its contract never mentions, and the file
                // createTempFile has already made would be left behind -- a new
                // leak introduced by the code meant to remove them.
            }
            // Set even when the call failed. Shutdown does not un-begin, so
            // there is nothing to retry, and the flag's meaning is "we have
            // stopped trying" rather than "a hook is running".
            CLEANUP_HOOK_INSTALLED.set(true);
        }
    }

    private static void forgetOutstanding(Path staged) {
        OUTSTANDING_STAGED.remove(staged.toAbsolutePath().normalize());
    }

    /**
     * Deletes every staging file this JVM still owns. Run from a shutdown hook,
     * and package-private so a test can exercise it without ending the JVM.
     *
     * <p>This is what collects an abandoned stem in every failure short of
     * {@code kill -9} or the power going out: an exception propagating out of a
     * stage, {@code System.exit}, or Ctrl-C. The age-based sweep is the backstop
     * for the cases that never get to run a hook at all -- and on its own it is
     * nearly useless here, because it cannot fire until a day after the crash
     * and only if somebody opens that same workspace again.
     *
     * <p>Only {@link #stagingPath} reservations are covered. The temporaries
     * that {@link #writeText} and {@link #writeBytes} use are deliberately not
     * tracked: they live for the duration of a single small write, both methods
     * already delete their own on failure, and the sweep catches the remainder.
     * Registering them would put set churn on every cache write to shorten the
     * life of a file measured in kilobytes.
     */
    static void discardOutstandingStagedFiles() {
        for (Path staged : OUTSTANDING_STAGED) {
            OUTSTANDING_STAGED.remove(staged);
            try {
                Files.deleteIfExists(staged);
            } catch (IOException | RuntimeException ignored) {
                // Shutdown is no place to fail, and the sweep will get it later.
            }
        }
    }

    /**
     * Removes staging files left behind by an interrupted earlier run, using the
     * default {@link #ABANDONED_STAGING_AGE} threshold.
     */
    public int sweepAbandonedStagingFiles() {
        return sweepAbandonedStagingFiles(ABANDONED_STAGING_AGE);
    }

    /**
     * Removes staging files that have not been modified for at least
     * {@code minimumAge}, and returns how many were removed.
     *
     * <p>The age threshold is the whole point. A workspace can be open in more
     * than one process, and {@code .partial-} is also the name a <em>live</em>
     * stage writes its stem under, so an unconditional sweep run at any
     * plausible moment -- opening a second workspace command while the first is
     * still separating -- deletes hundreds of megabytes out from under a run
     * that then fails at {@link #commit}. A file whose last write was a day ago
     * belongs to no such run.
     *
     * <p>Symbolic links are left alone and a symlinked cache directory is
     * refused outright, for the same reason {@link #invalidateStage} refuses
     * one: this method deletes, and a workspace is a directory people copy and
     * share, so a {@code cache/} pointing at somebody's home directory would
     * make the sweep delete there instead.
     *
     * @param minimumAge how long a staging file must have been idle; {@code ZERO}
     *                   sweeps everything and is only safe when no other process
     *                   can be using the workspace
     */
    public int sweepAbandonedStagingFiles(Duration minimumAge) {
        Objects.requireNonNull(minimumAge, "minimumAge");
        if (minimumAge.isNegative()) {
            throw new IllegalArgumentException("minimumAge must not be negative: " + minimumAge);
        }
        if (Files.isSymbolicLink(directory)) {
            throw new IllegalStateException(
                    "the workspace's cache directory is a symbolic link; refusing to delete"
                            + " through it: " + directory);
        }
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(minimumAge);
        int removed = 0;
        // Files.walk does not follow links, so a symlinked stage directory is
        // reported but never descended into. Filtering by name before collecting
        // keeps a large cache from being materialised in full, on a path that now
        // runs on every workspace open.
        try (var entries = Files.walk(directory)) {
            for (Path path : entries
                    .filter(path -> path.getFileName().toString().startsWith(STAGING_PREFIX))
                    .toList()) {
                if (removeIfAbandoned(path, cutoff)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not sweep staging files in " + directory, e);
        }
        return removed;
    }

    /**
     * Deletes one staging file if it is old enough, reporting whether it went.
     *
     * <p>Every failure is confined to this one file on purpose. A single
     * undeletable entry -- a stage directory copied in with somebody else's
     * permissions, or a handle an antivirus scanner is holding on Windows --
     * would otherwise abort the walk and silently strand every staging file
     * after it in walk order, permanently.
     */
    private static boolean removeIfAbandoned(Path path, Instant cutoff) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Instant modified =
                    Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
            // A modification time in the future -- clock skew on shared storage --
            // reads as "not yet old enough", which is the side to err on.
            if (modified.isAfter(cutoff)) {
                return false;
            }
            boolean deleted = Files.deleteIfExists(path);
            if (deleted) {
                forgetOutstanding(path);
            }
            return deleted;
        } catch (IOException e) {
            // Includes NoSuchFileException, when another process committed or
            // discarded the file between the walk and now -- the outcome we
            // wanted anyway -- and AccessDeniedException, which is not ours to fix.
            return false;
        }
    }

    /**
     * Moves a staged artifact into its final cache position.
     *
     * <p>A commit that races exit-time cleanup loses: the hook may delete the
     * staged file first and the move then fails. That is deliberate rather than
     * merely tolerated -- the alternative is a lock held across a move of
     * hundreds of megabytes, on a path whose only purpose is to run while the
     * process is already going down. The cache is never left inconsistent by
     * it: the entry simply does not appear, exactly as if the run had died a
     * moment earlier.
     */
    public Path commit(Path staged, Key key, String extension) {
        Path target = pathFor(key, extension);
        try {
            Files.createDirectories(target.getParent());
            moveIntoPlace(staged, target);
            forgetOutstanding(staged);
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
        // Same trap as the workspace source check: a cache/ that is itself a
        // symlink would make the containment test compare the link target
        // against itself, and this method deletes recursively.
        if (Files.isSymbolicLink(directory)) {
            throw new IllegalStateException(
                    "the workspace's cache directory is a symbolic link; refusing to delete"
                            + " through it: " + directory);
        }
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
