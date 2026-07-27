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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * An on-disk workspace: one directory holding a source recording and everything
 * derived from it.
 *
 * <p>The point of a workspace is that transcription is expensive and iterative.
 * Separating stems takes minutes; changing the piano arrangement style should
 * not re-run it. Each stage writes its output into the cache keyed by its
 * inputs and parameters, so re-running only recomputes what actually changed.
 *
 * <p>Layout:
 * <pre>
 *   song.mwz/
 *     workspace.yaml     descriptor: schema version, source identity, preferences
 *     source/            the untouched input recording
 *     cache/             per-stage intermediate results, including LLM responses
 *     score/score.json   the composed transcription
 *     out/               generated .ly, .musicxml, .midi and .pdf files
 *     logs/
 * </pre>
 */
public final class Workspace {

    /** Current workspace schema version. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final String DESCRIPTOR_FILE = "workspace.yaml";
    private static final String SOURCE_DIRECTORY = "source";
    private static final String CACHE_DIRECTORY = "cache";
    private static final String SCORE_DIRECTORY = "score";
    private static final String OUTPUT_DIRECTORY = "out";
    private static final String LOG_DIRECTORY = "logs";
    private static final String SCORE_FILE = "score.json";

    /**
     * What {@code workspace.yaml} holds.
     *
     * @param schemaVersion    layout version, for migration
     * @param sourceFileName   original file name of the recording
     * @param sourceSha256     digest of the recording, so a swapped file is noticed
     * @param createdAt        when the workspace was initialised
     * @param title            title, when known
     * @param artist           artist, when known
     * @param config           per-workspace preference overrides
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Descriptor(
            int schemaVersion,
            String sourceFileName,
            String sourceSha256,
            String createdAt,
            String title,
            String artist,
            MusicWizardConfig config) {
    }

    private final Path root;
    private final ConfigLoader configLoader;

    private Workspace(Path root, ConfigLoader configLoader) {
        this.root = root.toAbsolutePath().normalize();
        this.configLoader = configLoader;
    }

    /**
     * Creates a workspace around a source recording, copying the recording in.
     *
     * @param root       directory to create; must not already exist
     * @param sourceFile the recording to import
     */
    public static Workspace create(Path root, Path sourceFile) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(sourceFile, "sourceFile");
        if (!Files.isRegularFile(sourceFile)) {
            throw new IllegalArgumentException("source file does not exist: " + sourceFile);
        }
        if (Files.exists(root)) {
            throw new IllegalStateException("workspace already exists: " + root);
        }

        Workspace workspace = new Workspace(root, new ConfigLoader());
        try {
            for (Path directory : new Path[] {
                    workspace.sourceDirectory(), workspace.cacheDirectory(),
                    workspace.scoreDirectory(), workspace.outputDirectory(),
                    workspace.logDirectory()}) {
                Files.createDirectories(directory);
            }

            Path imported = workspace.sourceDirectory().resolve(sourceFile.getFileName().toString());
            Files.copy(sourceFile, imported, StandardCopyOption.COPY_ATTRIBUTES);

            workspace.writeDescriptor(new Descriptor(
                    CURRENT_SCHEMA_VERSION,
                    sourceFile.getFileName().toString(),
                    sha256(imported),
                    Instant.now().toString(),
                    null,
                    null,
                    MusicWizardConfig.empty()));
            return workspace;
        } catch (IOException | RuntimeException e) {
            // Otherwise a failure part-way through leaves a directory that cannot
            // be opened (no descriptor) and cannot be recreated (already exists),
            // leaving the user to delete it by hand.
            deleteRecursivelyQuietly(workspace.root());
            if (e instanceof IOException io) {
                throw new UncheckedIOException("could not create workspace at " + root, io);
            }
            throw (RuntimeException) e;
        }
    }

    private static void deleteRecursivelyQuietly(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var entries = Files.walk(directory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: the original failure is what matters.
                }
            });
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    /** Opens an existing workspace, validating its schema version. */
    public static Workspace open(Path root) {
        Objects.requireNonNull(root, "root");
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("not a workspace directory: " + root);
        }
        Workspace workspace = new Workspace(root, new ConfigLoader());
        if (!Files.isRegularFile(workspace.descriptorFile())) {
            throw new IllegalArgumentException(
                    "not a workspace: no " + DESCRIPTOR_FILE + " in " + root);
        }
        Descriptor descriptor = workspace.readDescriptor();
        if (descriptor.schemaVersion() < 1) {
            // A descriptor with the field missing deserializes to 0, so without a
            // lower bound a truncated or foreign YAML opens as a valid workspace.
            throw new IllegalStateException(
                    "workspace at " + root + " has an invalid schema version "
                            + descriptor.schemaVersion() + "; the file may be truncated"
                            + " or may not be a workspace descriptor at all");
        }
        if (descriptor.schemaVersion() > CURRENT_SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "workspace at " + root + " uses schema version " + descriptor.schemaVersion()
                            + ", but this build understands at most " + CURRENT_SCHEMA_VERSION
                            + "; upgrade Music Wizard to open it");
        }
        workspace.sweepAbandonedStagingFilesQuietly();
        return workspace;
    }

    /**
     * Reclaims disk from staging files that an interrupted earlier run left in
     * the cache.
     *
     * <p>Opening is the only moment the tool reliably reaches on every path, and
     * a stem abandoned by a crashed separation is hundreds of megabytes that
     * nothing else will ever delete. The sweep only removes files untouched for
     * {@link StageCache#ABANDONED_STAGING_AGE}, so a run still writing its stem
     * in another process -- opening a second command against a workspace mid-run
     * is ordinary usage -- is not affected.
     *
     * <p>Failure is swallowed on purpose. Reclaiming disk is housekeeping, and a
     * cache directory that is read-only, or is a symbolic link the sweep refuses
     * to delete through, must not be the reason a workspace cannot be opened at
     * all.
     */
    private void sweepAbandonedStagingFilesQuietly() {
        try {
            cache().sweepAbandonedStagingFiles();
        } catch (RuntimeException ignored) {
            // Best effort: an unopenable workspace is far worse than a stale file.
        }
    }

    /** True when the directory looks like a workspace. */
    public static boolean isWorkspace(Path root) {
        return root != null
                && Files.isDirectory(root)
                && Files.isRegularFile(root.resolve(DESCRIPTOR_FILE));
    }

    public Path root() {
        return root;
    }

    public Path descriptorFile() {
        return root.resolve(DESCRIPTOR_FILE);
    }

    public Path sourceDirectory() {
        return root.resolve(SOURCE_DIRECTORY);
    }

    public Path cacheDirectory() {
        return root.resolve(CACHE_DIRECTORY);
    }

    public Path scoreDirectory() {
        return root.resolve(SCORE_DIRECTORY);
    }

    public Path scoreFile() {
        return scoreDirectory().resolve(SCORE_FILE);
    }

    public Path outputDirectory() {
        return root.resolve(OUTPUT_DIRECTORY);
    }

    public Path logDirectory() {
        return root.resolve(LOG_DIRECTORY);
    }

    /** The imported source recording. */
    public Path sourceFile() {
        return resolveSourceFile(readDescriptor());
    }

    /**
     * Resolves the recording named by a descriptor, refusing anything that
     * escapes the workspace.
     *
     * <p>A workspace is a directory people copy and share, so its descriptor is
     * untrusted input. Resolved naively, a {@code sourceFileName} of
     * {@code ../../secret} or an absolute path reads a file from anywhere on
     * the machine.
     */
    private Path resolveSourceFile(Descriptor descriptor) {
        String name = descriptor.sourceFileName();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(
                    "workspace descriptor at " + descriptorFile() + " names no source file");
        }
        Path resolved;
        Path sourceRoot;
        try {
            resolved = sourceDirectory().resolve(name).normalize().toAbsolutePath();
            sourceRoot = sourceDirectory().normalize().toAbsolutePath();
        } catch (java.nio.file.InvalidPathException e) {
            throw new IllegalStateException(
                    "workspace descriptor names an unusable source file: " + name, e);
        }
        if (!resolved.startsWith(sourceRoot) || resolved.equals(sourceRoot)) {
            throw new IllegalStateException(
                    "workspace descriptor names a source file outside the workspace: " + name);
        }
        // Lexical containment is not enough. A workspace is copied and shared,
        // and both tar and zip preserve symlinks, so source/song.wav can be a
        // link to anywhere on the machine. Checking only the normalized path
        // confirms where the name points, not where the file is.
        // The anchor must not itself be attacker-controlled. Resolving
        // sourceRoot with toRealPath() would follow a symlinked source/ too, so
        // the comparison would be target-against-target and always succeed.
        // Anchor on the workspace root instead, whose location the user chose.
        if (Files.isSymbolicLink(sourceRoot)) {
            throw new IllegalStateException(
                    "the workspace's source directory is a symbolic link, which would place the"
                            + " recording outside the workspace: " + sourceRoot);
        }
        if (Files.exists(resolved, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Path real;
            Path realRoot;
            try {
                real = resolved.toRealPath();
                realRoot = root.toRealPath().resolve(SOURCE_DIRECTORY);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "could not resolve the source file named by the workspace descriptor", e);
            }
            if (!real.startsWith(realRoot) || real.equals(realRoot)) {
                throw new IllegalStateException(
                        "workspace descriptor names a source file that resolves outside the"
                                + " workspace (a symbolic link to " + real + "): " + name);
            }
        }
        return resolved;
    }

    public Descriptor readDescriptor() {
        try {
            String content = Files.readString(descriptorFile());
            return configLoader.yamlMapper().readValue(content, Descriptor.class);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + descriptorFile(), e);
        }
    }

    public void writeDescriptor(Descriptor descriptor) {
        // Written via a temporary file and moved into place. A crash midway
        // through a plain write truncates the descriptor, and every accessor on
        // this class then fails -- the workspace becomes unopenable.
        Path target = descriptorFile();
        try {
            String content = configLoader.yamlMapper().writeValueAsString(descriptor);
            Path temporary = Files.createTempFile(root, ".workspace-", ".yaml.tmp");
            try {
                Files.writeString(temporary, content);
                try {
                    Files.move(temporary, target,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temporary);
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + target, e);
        }
    }

    /**
     * The effective configuration for this workspace: defaults, then the user's
     * global config, then this workspace's own preferences, then any overrides.
     */
    public MusicWizardConfig effectiveConfig(MusicWizardConfig commandLineOverrides) {
        MusicWizardConfig workspaceLayer = readDescriptor().config();
        return MusicWizardConfig.DEFAULTS
                .overriddenBy(configLoader.readGlobalLayer())
                .overriddenBy(workspaceLayer)
                .overriddenBy(commandLineOverrides);
    }

    public MusicWizardConfig effectiveConfig() {
        return effectiveConfig(null);
    }

    /**
     * Reads the persisted transcription, if one has been produced.
     *
     * <p>Absent rather than empty when analysis has not run, so a caller can
     * tell "nothing computed yet" from "computed and found nothing".
     */
    public Optional<dev.olivelli.musicwizard.core.model.Score> readScore() {
        Path file = scoreFile();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(dev.olivelli.musicwizard.core.model.ScoreJson.read(file));
    }

    /** Persists the transcription. */
    public void writeScore(dev.olivelli.musicwizard.core.model.Score score) {
        Objects.requireNonNull(score, "score");
        dev.olivelli.musicwizard.core.model.ScoreJson.write(scoreFile(), score);
    }

    /** The stage cache for this workspace. */
    public StageCache cache() {
        return new StageCache(cacheDirectory());
    }

    /**
     * Verifies the imported recording still matches the digest recorded when
     * the workspace was created. A mismatch means every cached result is
     * suspect, so callers should treat it as a reason to invalidate.
     */
    public boolean sourceMatchesDigest() {
        Descriptor descriptor = readDescriptor();
        Path source = resolveSourceFile(descriptor);
        if (!Files.isRegularFile(source) || descriptor.sourceSha256() == null) {
            return false;
        }
        return sha256(source).equals(descriptor.sourceSha256());
    }

    /** Title recorded for this workspace, if any. */
    public Optional<String> title() {
        return Optional.ofNullable(readDescriptor().title());
    }

    /** Artist recorded for this workspace, if any. */
    public Optional<String> artist() {
        return Optional.ofNullable(readDescriptor().artist());
    }

    /** Records title and artist metadata, leaving everything else untouched. */
    public void updateMetadata(String title, String artist) {
        Descriptor current = readDescriptor();
        writeDescriptor(new Descriptor(
                current.schemaVersion(), current.sourceFileName(), current.sourceSha256(),
                current.createdAt(), title, artist, current.config()));
    }

    /** Replaces the per-workspace preference layer. */
    public void updateConfig(MusicWizardConfig config) {
        Descriptor current = readDescriptor();
        writeDescriptor(new Descriptor(
                current.schemaVersion(), current.sourceFileName(), current.sourceSha256(),
                current.createdAt(), current.title(), current.artist(), config));
    }

    static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file);
                 DigestInputStream digesting = new DigestInputStream(in, digest)) {
                byte[] buffer = new byte[8192];
                while (digesting.read(buffer) != -1) {
                    // Reading is what updates the digest.
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        } catch (IOException e) {
            throw new UncheckedIOException("could not digest " + file, e);
        }
    }
}
