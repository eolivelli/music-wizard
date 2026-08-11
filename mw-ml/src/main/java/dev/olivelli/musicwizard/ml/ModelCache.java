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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
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
 * place.</b> The write follows {@code StageCache}'s discipline — temp file,
 * verify, atomic move — for the same reason that class exists: an interrupted
 * download must not leave a file a later run would trust. Later runs then
 * check presence, size, and the digest recorded in the file's source note,
 * without re-hashing hundreds of megabytes per invocation; see
 * {@link #contains}.
 *
 * <p><b>Offline is honest.</b> With {@code ml.offline} set, a model already on
 * disk resolves exactly as it would online, and an absent one fails with a
 * message naming the file, the size, and the fact that clearing the flag would
 * fetch it — never a bare "could not load".
 */
public final class ModelCache {

    /**
     * The staging prefix. {@code StageCache}'s workspace sweep never reaches
     * this directory — no {@code StageCache} is ever constructed over it — so
     * this class runs its own sweep in {@link #fetch}, against the same age.
     */
    private static final String STAGING_PREFIX = ".partial-";

    /** How long a staging file must sit untouched before a sweep may take it. */
    private static final Duration ABANDONED_STAGING_AGE = Duration.ofHours(24);

    /**
     * How long the body transfer may go without a single byte arriving.
     *
     * <p>Time since progress, not since the start: a legitimate fetch of
     * hundreds of megabytes takes as long as it takes, and a total deadline
     * either kills it or is too large to notice a hang. What must never happen
     * is the CLI sitting on a stalled socket forever with no message — the
     * connect timeout does not cover the body, and a server that answers and
     * then stops sending is otherwise indistinguishable from a slow one.
     */
    private static final Duration STALL_LIMIT = Duration.ofSeconds(60);

    private static final String NOTE_SUFFIX = ".source.txt";

    private final Path directory;
    private final boolean offline;
    private final Duration stallLimit;

    /** Built on first download, because an offline cache must not open sockets. */
    private HttpClient client;

    private ModelCache(Path directory, boolean offline, Duration stallLimit) {
        this.directory = directory;
        this.offline = offline;
        this.stallLimit = stallLimit;
    }

    /** A cache at this directory. */
    public static ModelCache at(Path directory, boolean offline) {
        Objects.requireNonNull(directory, "directory");
        return new ModelCache(directory, offline, STALL_LIMIT);
    }

    /** For the stall test only: sixty stalled seconds cannot be in the fast suite. */
    static ModelCache at(Path directory, Duration stallLimit) {
        return new ModelCache(directory, false, stallLimit);
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

    /**
     * True when this model — these bytes, not merely this file name — is on disk.
     *
     * <p>Presence and size alone are not enough: a model table bumped to a new
     * version whose file happens to be the same length would be served stale
     * forever. The digest each download verified is recorded in the file's
     * source note, so this compares one short string instead of re-hashing the
     * model, and a file with no note — or a note naming another digest — is
     * treated as absent and replaced.
     */
    public boolean contains(ModelRef model) {
        Path path = pathOf(model);
        try {
            return Files.isRegularFile(path)
                    && Files.size(path) == model.sizeBytes()
                    && model.sha256().equals(recordedDigest(model));
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
     * @param progress told before a download starts, with the name, size and
     *        source, and periodically as bytes arrive — a multi-hundred-megabyte
     *        fetch must never look like a hang
     * @throws ModelUnavailableException absent and offline; or the download
     *         failed, stalled, or did not match the checksum
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
            sweepAbandonedStaging(target.getParent());
            Path staged = Files.createTempFile(target.getParent(), STAGING_PREFIX,
                    "-" + model.fileName());
            try {
                String digest = downloadTo(model, staged, progress);
                if (!digest.equals(model.sha256())) {
                    throw new ModelUnavailableException(
                            "model " + model.name() + " downloaded from " + model.uri()
                            + " does not match its checksum (expected " + model.sha256()
                            + ", got " + digest + "); refusing to keep it");
                }
                long size = Files.size(staged);
                if (size != model.sizeBytes()) {
                    // The digest matched, so the table's size is what is wrong.
                    // Refuse rather than adjust: contains() reads that size on
                    // every later run, and a wrong entry would re-download the
                    // model forever without ever saying why.
                    throw new ModelUnavailableException(
                            "model " + model.name() + " matches its checksum but not its"
                            + " recorded size (expected " + model.sizeBytes() + " bytes, got "
                            + size + "); the model table is wrong and wants fixing");
                }
                // The note is deleted before the move and rewritten after,
                // so no crash window can leave a file beside a note that
                // describes other bytes -- in either direction. A new note
                // beside an old file serves stale bytes as the new version; an
                // old note beside a new file serves new bytes as the old. Every
                // window here leaves a file with no note instead, and a file
                // with no note is re-downloaded: wasteful, never wrong.
                Files.deleteIfExists(noteFor(model));
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(staged);
                throw e;
            }
            writeSourceNote(model);
            return target;
        } catch (IOException e) {
            throw new ModelUnavailableException(
                    "could not download model " + model.name() + " from " + model.uri()
                    + ": " + e.getMessage(), e);
        }
    }

    /** Streams the body to the staged path, hashing as it goes, watching for stalls. */
    private String downloadTo(ModelRef model, Path staged, Consumer<String> progress)
            throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
        HttpRequest request = HttpRequest.newBuilder(model.uri()).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
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
        // A watchdog closes the stream when nothing has arrived for the stall
        // limit, which is the only way to unblock a read() sitting on a dead
        // socket. The read then fails with an IOException the caller reports;
        // stalled distinguishes that from an ordinary network error. It polls
        // at a quarter of the limit and fires on elapsed time since the last
        // byte, so the limit the message names is the limit that was applied,
        // not up to double it.
        AtomicLong received = new AtomicLong();
        AtomicLong lastReported = new AtomicLong();
        var stalled = new AtomicLong(-1);
        Thread watchdog = watchdog(response.body(), received, stalled, stallLimit);
        try (InputStream in = new DigestInputStream(response.body(), sha);
             OutputStream out = Files.newOutputStream(staged)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                long total = received.addAndGet(read);
                // One line per ~50 MB, not per buffer: progress that scrolls
                // thousands of lines is noise, none at all is a hang.
                if (total - lastReported.get() >= 50L * 1024 * 1024) {
                    lastReported.set(total);
                    progress.accept("  " + megabytes(total) + " / "
                            + megabytes(model.sizeBytes()) + " MB");
                }
            }
        } catch (IOException e) {
            if (stalled.get() >= 0) {
                throw new ModelUnavailableException(
                        "download of model " + model.name() + " stalled: no data for "
                        + stallLimit.toSeconds() + " s after " + stalled.get()
                        + " bytes; giving up rather than hanging", e);
            }
            throw e;
        } finally {
            watchdog.interrupt();
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static Thread watchdog(InputStream body, AtomicLong received, AtomicLong stalled,
                                   Duration stallLimit) {
        long pollMillis = Math.max(25, stallLimit.toMillis() / 4);
        Thread thread = new Thread(() -> {
            long lastCount = 0;
            long lastChangeNanos = System.nanoTime();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(pollMillis);
                } catch (InterruptedException e) {
                    return;
                }
                long now = received.get();
                if (now != lastCount) {
                    lastCount = now;
                    lastChangeNanos = System.nanoTime();
                    continue;
                }
                if (System.nanoTime() - lastChangeNanos >= stallLimit.toNanos()) {
                    stalled.set(now);
                    try {
                        body.close();
                    } catch (IOException e) {
                        // Closing is the interruption; a failure to close is moot.
                    }
                    return;
                }
            }
        }, "mw-model-download-watchdog");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private synchronized HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
        }
        return client;
    }

    /**
     * Deletes staging files old enough to be abandoned, in this model's
     * directory only.
     *
     * <p>The same age {@code StageCache} uses, for the same asymmetry: keeping
     * a dead file another day wastes disk, deleting a live one destroys a
     * download that may be twenty minutes in. Run at fetch time because there
     * is no workspace-open moment here — the next download is the first time
     * anyone looks.
     */
    private static void sweepAbandonedStaging(Path modelDirectory) {
        Instant cutoff = Instant.now().minus(ABANDONED_STAGING_AGE);
        try (DirectoryStream<Path> entries =
                     Files.newDirectoryStream(modelDirectory, STAGING_PREFIX + "*")) {
            for (Path entry : entries) {
                try {
                    if (Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(entry);
                    }
                } catch (IOException e) {
                    // Another process may have taken it; the next sweep retries.
                }
            }
        } catch (IOException e) {
            // A failed sweep must not fail the download that triggered it.
        }
    }

    /**
     * What this file is, where it came from, and its licence — one note per
     * file, because a model may be several files under one name and each has
     * its own provenance.
     *
     * <p>{@code NOTICE} lists what ships in the repository, and models
     * deliberately do not. This note is that answer for the cache directory,
     * where the file actually lives. {@link #contains} also reads the digest
     * back from it, so the note is load-bearing, written before the model is
     * moved into place, and its write failing fails the fetch.
     */
    private void writeSourceNote(ModelRef model) throws IOException {
        Path note = noteFor(model);
        String text = model.fileName() + "\nfrom: " + model.uri()
                + "\nsha256: " + model.sha256()
                + "\nlicence: " + model.licence() + "\n";
        Files.writeString(note, text);
    }

    /** The digest the source note records, or null when there is no note. */
    private String recordedDigest(ModelRef model) throws IOException {
        Path note = noteFor(model);
        if (!Files.isRegularFile(note)) {
            return null;
        }
        for (String line : Files.readAllLines(note)) {
            if (line.startsWith("sha256: ")) {
                return line.substring("sha256: ".length()).strip();
            }
        }
        return null;
    }

    private Path noteFor(ModelRef model) {
        return directory.resolve(model.name()).resolve(model.fileName() + NOTE_SUFFIX);
    }

    private static long megabytes(long bytes) {
        return Math.max(1, bytes / (1024 * 1024));
    }
}
