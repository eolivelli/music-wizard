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
    @DisplayName("the licence note is written beside the model")
    void licenceNoteWritten() {
        ModelCache cache = ModelCache.at(cacheDir, false);
        cache.fetch(model(), message -> { });

        Path note = cacheDir.resolve("test-model").resolve("SOURCE.txt");
        assertThat(note).exists();
        assertThat(note).content().contains("test licence").contains("sha256");
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
