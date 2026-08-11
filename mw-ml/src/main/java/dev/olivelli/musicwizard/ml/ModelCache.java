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

package dev.olivelli.musicwizard.ml;

import dev.olivelli.musicwizard.core.ml.ModelCacheLocation;
import dev.olivelli.musicwizard.core.ml.ModelUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Downloads models on first use and hands back verified local paths.
 *
 * <p>No model weights ship in the repository; this is the mechanism that
 * policy describes. A model is fetched once into a per-user directory, its
 * checksum verified before anything trusts it, and every later run resolves it
 * from disk without the network.
 *
 * <p><b>Verification happens on the staged file, before the move into
 * place.</b> A file that reaches its final path has therefore already matched
 * its digest, which is what lets {@link #fetch} trust presence plus size on
 * later runs instead of re-hashing hundreds of megabytes per invocation. The
 * write itself follows {@code StageCache}'s discipline — temp file, verify,
 * atomic move — for the same reason that class exists: an interrupted download
 * must not leave a file a later run would trust.
 *
 * <p><b>Offline is honest.</b> With {@code ml.offline} set, a model already on
 * disk resolves exactly as it would online, and an absent one fails with a
 * message naming the file, the size, and the fact that clearing the flag would
 * fetch it — never a bare "could not load".
 */
public final class ModelCache {

    /** Matches StageCache's prefix so its abandoned-file sweep logic applies. */
    private static final String STAGING_PREFIX = ".partial-";

    private final Path directory;
    private final boolean offline;
    private final HttpClient client;

    private ModelCache(Path directory, boolean offline, HttpClient client) {
        this.directory = directory;
        this.offline = offline;
        this.client = client;
    }

    /** A cache at this directory. */
    public static ModelCache at(Path directory, boolean offline) {
        Objects.requireNonNull(directory, "directory");
        return new ModelCache(directory, offline,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(30))
                        .build());
    }

    /** The default per-user location; one statement, in {@link ModelCacheLocation}. */
    public static Path defaultDirectory() {
        return ModelCacheLocation.defaultDirectory();
    }

    /** Where this cache lives, for reporting. */
    public Path directory() {
        return directory;
    }

    /** Whether this cache will refuse to touch the network. */
    public boolean offline() {
        return offline;
    }

    /** True when the model is already on disk at its expected size. */
    public boolean contains(ModelRef model) {
        Path path = pathOf(model);
        try {
            return Files.isRegularFile(path) && Files.size(path) == model.sizeBytes();
        } catch (IOException e) {
            return false;
        }
    }

    /** Where this model lives, or would live, inside the cache. */
    public Path pathOf(ModelRef model) {
        return directory.resolve(model.name()).resolve(model.fileName());
    }

    /**
     * The model's local path, downloading it first if it is absent.
     *
     * <p>A file already present at its expected size is returned as is — it
     * matched its digest before it was moved into place, and re-hashing it per
     * run would cost more than the inference. A file present at the <em>wrong</em>
     * size is treated as absent and replaced: it can only be a truncated copy
     * from before this class enforced staging, or outside interference, and
     * both mean it must not be trusted.
     *
     * @param progress told once before a download starts, with the name, size
     *        and source — a multi-hundred-megabyte fetch must never look like
     *        a hang
     * @throws ModelUnavailableException absent and offline; or the download
     *         failed; or what arrived did not match the checksum
     */
    public Path fetch(ModelRef model, Consumer<String> progress) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(progress, "progress");
        Path target = pathOf(model);
        if (contains(model)) {
            return target;
        }
        if (offline) {
            throw new ModelUnavailableException(
                    "model " + model.name() + " (" + model.fileName() + ", "
                    + megabytes(model.sizeBytes()) + " MB) is not in " + directory
                    + " and ml.offline is set; unset it to download from " + model.uri());
        }
        progress.accept("downloading " + model.name() + " ("
                + megabytes(model.sizeBytes()) + " MB) from " + model.uri());
        try {
            Files.createDirectories(target.getParent());
            Path staged = Files.createTempFile(target.getParent(), STAGING_PREFIX,
                    "-" + model.fileName());
            try {
                String digest = downloadTo(model, staged);
                if (!digest.equals(model.sha256())) {
                    throw new ModelUnavailableException(
                            "model " + model.name() + " downloaded from " + model.uri()
                            + " does not match its checksum (expected " + model.sha256()
                            + ", got " + digest + "); refusing to keep it");
                }
                long size = Files.size(staged);
                if (size != model.sizeBytes()) {
                    // The digest matched, so the table's size is what is wrong.
                    // Refuse rather than adjust: contains() trusts that size on
                    // every later run, and a wrong entry would re-download the
                    // model forever without ever saying why.
                    throw new ModelUnavailableException(
                            "model " + model.name() + " matches its checksum but not its"
                            + " recorded size (expected " + model.sizeBytes() + " bytes, got "
                            + size + "); the model table is wrong and wants fixing");
                }
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(staged);
                throw e;
            }
            writeLicenceNote(model);
            return target;
        } catch (IOException e) {
            throw new ModelUnavailableException(
                    "could not download model " + model.name() + " from " + model.uri()
                    + ": " + e.getMessage(), e);
        }
    }

    /** Streams the body to the staged path, hashing as it goes. */
    private String downloadTo(ModelRef model, Path staged) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
        HttpRequest request = HttpRequest.newBuilder(model.uri()).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException(
                    "download of model " + model.name() + " was interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ModelUnavailableException(
                    "model " + model.name() + ": " + model.uri() + " answered HTTP "
                    + response.statusCode());
        }
        try (InputStream in = new DigestInputStream(response.body(), sha);
             OutputStream out = Files.newOutputStream(staged)) {
            in.transferTo(out);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /**
     * Writes what this file is and its licence beside it, once.
     *
     * <p>The repository's NOTICE lists what ships in the repository, and models
     * deliberately do not. This note is that answer for the cache directory,
     * where the file actually lives.
     */
    private void writeLicenceNote(ModelRef model) {
        Path note = directory.resolve(model.name()).resolve("SOURCE.txt");
        String text = model.fileName() + "\nfrom: " + model.uri()
                + "\nsha256: " + model.sha256()
                + "\nlicence: " + model.licence() + "\n";
        try {
            Files.writeString(note, text);
        } catch (IOException e) {
            // The model itself is verified and in place; a failed note must not
            // fail the run that needed the model.
        }
    }

    private static long megabytes(long bytes) {
        return Math.max(1, bytes / (1024 * 1024));
    }
}
