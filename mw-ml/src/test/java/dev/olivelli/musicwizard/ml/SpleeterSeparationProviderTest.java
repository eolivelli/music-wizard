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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End to end through the real machinery — download, cache, ONNX Runtime
 * session, STFT, masks, inverse — against a 134-byte identity model, because
 * nothing in the plumbing depends on the model being a real separator. The
 * real checkpoints are exercised locally and judged by ear; this holds
 * everything around them.
 */
@DisplayName("the spleeter provider")
class SpleeterSeparationProviderTest {

    private static final byte[] IDENTITY = fixture();

    @TempDir
    Path cacheDir;

    private HttpServer server;

    private static byte[] fixture() {
        try (InputStream in = SpleeterSeparationProviderTest.class
                .getResourceAsStream("/identity-stem.onnx")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model.onnx", exchange -> {
            exchange.sendResponseHeaders(200, IDENTITY.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(IDENTITY);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SpleeterSeparationProvider provider() {
        String sha;
        try {
            sha = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(IDENTITY));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/model.onnx");
        ModelRef vocals = new ModelRef("identity", "vocals.onnx", uri, sha,
                IDENTITY.length, "test");
        ModelRef accompaniment = new ModelRef("identity", "accompaniment.onnx", uri, sha,
                IDENTITY.length, "test");
        return new SpleeterSeparationProvider(
                ModelCache.at(cacheDir, false), vocals, accompaniment);
    }

    @Test
    @DisplayName("splits a mix into stems that sum back to it")
    void stemsSumToTheMix() {
        // Identity stems mean equal magnitude estimates, so each soft mask is
        // one half and each stem is half the mix -- which exercises download,
        // session, STFT, segmentation, masking and the inverse in one pass,
        // and pins the property real masks must also hold: complementarity.
        float[] mix = new float[SpleeterSeparationProvider.MODEL_RATE * 2];
        for (int i = 0; i < mix.length; i++) {
            mix[i] = (float) (0.4 * Math.sin(2 * Math.PI * 220 * i
                    / (double) SpleeterSeparationProvider.MODEL_RATE));
        }

        var result = provider().separate(new float[][] {mix},
                SpleeterSeparationProvider.MODEL_RATE);

        assertThat(result.vocals()).hasNumberOfRows(1);
        assertThat(result.vocals()[0]).hasSize(mix.length);
        double residual = 0;
        double energy = 0;
        for (int i = 0; i < mix.length; i++) {
            double sum = result.vocals()[0][i] + result.accompaniment()[0][i];
            residual += (sum - mix[i]) * (sum - mix[i]);
            energy += mix[i] * mix[i];
        }
        // A 220 Hz tone sits far below the model's 1024-bin ceiling, so the
        // zero mask extension above ~11 kHz costs nothing here and the stems
        // must reconstruct the mix almost exactly.
        assertThat(residual / energy).isLessThan(1e-4);
    }

    @Test
    @DisplayName("the vocals model's estimate drives the vocals stem, not the other way round")
    void masksLandOnTheRightStems() throws IOException {
        // Identity for vocals, a zero output for accompaniment: the vocals
        // mask is exactly one and the accompaniment mask exactly zero, so
        // swapping stems()'s two estimate arguments -- which the sum-to-mix
        // test cannot see, both stems being mix/2 there -- flips which stem is
        // silent. The zero-stem fixture multiplies its input by zero.
        byte[] zeros;
        try (InputStream in = SpleeterSeparationProviderTest.class
                .getResourceAsStream("/zero-stem.onnx")) {
            zeros = in.readAllBytes();
        }
        server.createContext("/zero.onnx", exchange -> {
            exchange.sendResponseHeaders(200, zeros.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(zeros);
            }
        });
        String zeroSha;
        try {
            zeroSha = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(zeros));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        URI zeroUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/zero.onnx");
        String idSha;
        try {
            idSha = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(IDENTITY));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        URI idUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/model.onnx");
        SpleeterSeparationProvider provider = new SpleeterSeparationProvider(
                ModelCache.at(cacheDir, false),
                new ModelRef("mixed", "vocals.onnx", idUri, idSha, IDENTITY.length, "test"),
                new ModelRef("mixed", "accompaniment.onnx", zeroUri, zeroSha,
                        zeros.length, "test"));

        float[] mix = new float[SpleeterSeparationProvider.MODEL_RATE];
        for (int i = 0; i < mix.length; i++) {
            mix[i] = (float) (0.4 * Math.sin(2 * Math.PI * 220 * i
                    / (double) SpleeterSeparationProvider.MODEL_RATE));
        }

        var result = provider.separate(new float[][] {mix},
                SpleeterSeparationProvider.MODEL_RATE);

        double vocalsEnergy = 0;
        double accompanimentEnergy = 0;
        double mixEnergy = 0;
        for (int i = 0; i < mix.length; i++) {
            vocalsEnergy += result.vocals()[0][i] * result.vocals()[0][i];
            accompanimentEnergy += result.accompaniment()[0][i]
                    * result.accompaniment()[0][i];
            mixEnergy += mix[i] * mix[i];
        }
        assertThat(vocalsEnergy / mixEnergy)
                .as("vocals under a mask of one carry the mix")
                .isGreaterThan(0.99);
        assertThat(accompanimentEnergy / mixEnergy)
                .as("accompaniment under a mask of zero is silent")
                .isLessThan(1e-6);
    }

    @Test
    @DisplayName("a rate the model does not use is resampled in and back")
    void resamplesForTheModel() {
        int rate = 22050;
        float[] mix = new float[rate];
        for (int i = 0; i < mix.length; i++) {
            mix[i] = (float) (0.3 * Math.sin(2 * Math.PI * 440 * i / (double) rate));
        }

        var result = provider().separate(new float[][] {mix}, rate);

        // The contract is the caller's shape and rate back, exactly.
        assertThat(result.vocals()[0]).hasSize(mix.length);
        assertThat(result.accompaniment()[0]).hasSize(mix.length);
        double energy = 0;
        for (float v : result.vocals()[0]) {
            energy += v * v;
        }
        assertThat(energy).as("the stem is not silence").isGreaterThan(0.001);
    }

    @Test
    @DisplayName("shape edges: kept, or named, never silently reshaped")
    void shapeEdges() {
        // The guards run before any model is touched, so a provider with no
        // cache behind it exercises them all. Order matters and is asserted by
        // the [empty, eight] row: ragged is checked before empty, which is
        // the only order in which that input is a named contract error rather
        // than a silent pair of empty stems for eight real samples.
        SpleeterSeparationProvider bare =
                new SpleeterSeparationProvider(null, null, null);
        float[] eight = new float[8];

        // hasNumberOfRows(0), not isEmpty(): AssertJ's 2D isEmpty() asserts
        // every row is empty, which [[]] satisfies -- the exact answer the
        // reverted emptyLike gives a zero-channel input.
        assertThat(bare.separate(new float[0][], 44100).vocals())
                .hasNumberOfRows(0);
        assertThat(bare.separate(new float[][] {new float[0]}, 44100).vocals())
                .hasNumberOfRows(1);
        assertThat(bare.separate(new float[][] {new float[0], new float[0]}, 44100)
                .accompaniment()).hasNumberOfRows(2);
        assertThatThrownBy(() -> bare.separate(
                new float[][] {new float[0], eight}, 44100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same length");
        assertThatThrownBy(() -> bare.separate(
                new float[][] {eight, new float[0]}, 44100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 and 0");
        assertThatThrownBy(() -> bare.separate(
                new float[][] {eight, new float[9]}, 44100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 and 9");
        assertThatThrownBy(() -> bare.separate(
                new float[][] {eight, eight, eight}, 44100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most two");
    }

    @Test
    @DisplayName("the id is the one the default configuration names")
    void idMatchesConfig() {
        assertThat(provider().id()).isEqualTo("onnx-spleeter");
    }
}
