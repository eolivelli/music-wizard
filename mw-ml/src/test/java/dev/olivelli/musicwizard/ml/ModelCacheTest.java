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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.olivelli.musicwizard.core.ml.ModelUnavailableException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Against a loopback HTTP server, never the network: {@code mvn verify} stays
 * fast, offline and binary-free, and the "model" is a few bytes because the
 * cache's behaviour does not depend on the payload being large.
 */
@DisplayName("the model cache")
class ModelCacheTest {

    private static final byte[] WEIGHTS = "not a real model".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path cacheDir;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model.onnx", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, WEIGHTS.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(WEIGHTS);
            }
        });
        server.createContext("/missing.onnx", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ModelRef model() {
        return ref(sha256(WEIGHTS), WEIGHTS.length);
    }

    private ModelRef ref(String sha, long size) {
        return new ModelRef("test-model", "model.onnx",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model.onnx"),
                sha, size, "test licence");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("downloads once, verifies, and every later fetch is local")
    void downloadsOnceThenResolvesLocally() {
        ModelCache cache = ModelCache.at(cacheDir, false);
        List<String> progress = new ArrayList<>();

        Path first = cache.fetch(model(), progress::add);

        assertThat(first).exists().hasBinaryContent(WEIGHTS);
        assertThat(progress).hasSize(1);
        assertThat(progress.get(0)).contains("test-model");
        assertThat(hits).hasValue(1);

        Path second = cache.fetch(model(), progress::add);

        assertThat(second).isEqualTo(first);
        assertThat(hits).as("no second network round trip").hasValue(1);
        assertThat(progress).as("nothing new to report").hasSize(1);
    }

    @Test
    @DisplayName("a checksum mismatch is refused and nothing lands in the cache")
    void checksumMismatchRefused() {
        ModelCache cache = ModelCache.at(cacheDir, false);
        ModelRef wrong = ref("0".repeat(64), WEIGHTS.length);

        assertThatThrownBy(() -> cache.fetch(wrong, message -> { }))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("checksum");
        assertThat(cache.contains(wrong)).isFalse();
        assertThat(cacheDir.resolve("test-model").resolve("model.onnx")).doesNotExist();
    }

    @Test
    @DisplayName("no staging file survives a refused download")
    void refusedDownloadLeavesNoStaging() throws IOException {
        ModelCache cache = ModelCache.at(cacheDir, false);
        ModelRef wrong = ref("0".repeat(64), WEIGHTS.length);
        assertThatThrownBy(() -> cache.fetch(wrong, message -> { })).isNotNull();

        try (var files = Files.walk(cacheDir)) {
            assertThat(files.filter(Files::isRegularFile))
                    .as("a truncated or rejected download must not linger")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("offline with the model absent fails naming the file and the cure")
    void offlineAbsentIsHonest() {
        ModelCache cache = ModelCache.at(cacheDir, true);

        assertThatThrownBy(() -> cache.fetch(model(), message -> { }))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("test-model")
                .hasMessageContaining("offline");
        assertThat(hits).as("offline must not touch the network").hasValue(0);
    }

    @Test
    @DisplayName("offline with the model present resolves exactly as online would")
    void offlinePresentResolves() {
        Path fetched = ModelCache.at(cacheDir, false).fetch(model(), message -> { });

        Path resolved = ModelCache.at(cacheDir, true).fetch(model(), message -> { });

        assertThat(resolved).isEqualTo(fetched);
        assertThat(hits).hasValue(1);
    }

    @Test
    @DisplayName("a file at the wrong size is replaced, not trusted")
    void wrongSizeReplaced() throws IOException {
        ModelCache cache = ModelCache.at(cacheDir, false);
        Path target = cache.pathOf(model());
        Files.createDirectories(target.getParent());
        Files.writeString(target, "truncated");

        Path fetched = cache.fetch(model(), message -> { });

        assertThat(fetched).hasBinaryContent(WEIGHTS);
        assertThat(hits).hasValue(1);
    }

    @Test
    @DisplayName("a stalled transfer is abandoned with a message, not waited on forever")
    void stalledTransferAbandoned() {
        // A server that answers, sends a few bytes and stops: the connect
        // timeout does not cover the body, so without a watchdog the CLI sits
        // on the socket forever with no message -- during the one operation
        // most likely to be watched, the first-use download.
        server.createContext("/stall.onnx", exchange -> {
            exchange.sendResponseHeaders(200, WEIGHTS.length * 100L);
            exchange.getResponseBody().write(WEIGHTS);
            exchange.getResponseBody().flush();
            // Never write the rest: hold the socket open past the 200 ms
            // stall limit below, then let the handler end so the suite's
            // teardown is not stuck behind a sleeping executor thread.
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ModelCache cache = ModelCache.at(cacheDir, java.time.Duration.ofMillis(200));
        ModelRef stalling = new ModelRef("test-model", "stall.onnx",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/stall.onnx"),
                sha256(WEIGHTS), WEIGHTS.length, "test licence");

        assertThatThrownBy(() -> cache.fetch(stalling, message -> { }))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("stalled");
        assertThat(cacheDir.resolve("test-model").resolve("stall.onnx")).doesNotExist();
    }

    @Test
    @DisplayName("an HTTP error names the status rather than keeping anything")
    void httpErrorNamed() {
        ModelCache cache = ModelCache.at(cacheDir, false);
        ModelRef missing = new ModelRef("test-model", "missing.onnx",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/missing.onnx"),
                sha256(WEIGHTS), WEIGHTS.length, "test licence");

        assertThatThrownBy(() -> cache.fetch(missing, message -> { }))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("each file gets its own source note, so a multi-file model keeps every provenance")
    void sourceNotePerFile() throws IOException {
        // A 2-stems separation model is two files under one name. One shared
        // note silently kept only the last provenance written, which is the
        // single thing the note exists to prevent losing.
        server.createContext("/second.onnx", exchange -> {
            exchange.sendResponseHeaders(200, WEIGHTS.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(WEIGHTS);
            }
        });
        ModelCache cache = ModelCache.at(cacheDir, false);
        cache.fetch(model(), message -> { });
        cache.fetch(new ModelRef("test-model", "second.onnx",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/second.onnx"),
                sha256(WEIGHTS), WEIGHTS.length, "second licence"), message -> { });

        Path first = cacheDir.resolve("test-model").resolve("model.onnx.source.txt");
        Path second = cacheDir.resolve("test-model").resolve("second.onnx.source.txt");
        assertThat(first).content().contains("test licence").contains("sha256");
        assertThat(second).content().contains("second licence");
    }

    @Test
    @DisplayName("a model whose bytes changed at the same size is replaced, not served stale")
    void staleModelAtSameSizeReplaced() {
        // A model table bumped to a new version whose file happens to be the
        // same length: presence plus size cannot tell, the recorded digest can.
        ModelCache cache = ModelCache.at(cacheDir, false);
        cache.fetch(model(), message -> { });

        byte[] v2 = "NOT A REAL MODEL".getBytes(StandardCharsets.UTF_8);
        assertThat(v2).hasSameSizeAs(WEIGHTS);
        server.removeContext("/model.onnx");
        server.createContext("/model.onnx", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, v2.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(v2);
            }
        });

        Path fetched = cache.fetch(ref(sha256(v2), v2.length), message -> { });

        assertThat(fetched).hasBinaryContent(v2);
        assertThat(hits).hasValue(2);
    }

    @Test
    @DisplayName("a size that contradicts a matching checksum blames the table")
    void sizeContradictingChecksumBlamesTheTable() {
        ModelCache cache = ModelCache.at(cacheDir, false);
        ModelRef wrongSize = ref(sha256(WEIGHTS), WEIGHTS.length + 1);

        assertThatThrownBy(() -> cache.fetch(wrongSize, message -> { }))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("model table");
    }
}
