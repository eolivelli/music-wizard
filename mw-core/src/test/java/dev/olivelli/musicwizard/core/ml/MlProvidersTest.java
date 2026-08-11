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

package dev.olivelli.musicwizard.core.ml;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.LyricWord;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("provider discovery")
class MlProvidersTest {

    /**
     * Registered via META-INF/services on the test classpath, which is the
     * same route mw-ml's real implementations take to reach a CLI that only
     * depends on that module at runtime scope. The test therefore exercises
     * the discovery mechanism itself, not a mock of it.
     */
    public static final class FakeSeparation implements SeparationProvider {
        @Override
        public String id() {
            return "fake-separation";
        }

        @Override
        public Separation separate(float[][] channels, int sampleRate) {
            return new Separation(channels, channels);
        }
    }

    /** Listed in the services file to prove one broken provider hides nothing. */
    public static final class ThrowingSeparation implements SeparationProvider {
        public ThrowingSeparation() {
            throw new IllegalStateException("no natives here");
        }

        @Override
        public String id() {
            return "throwing";
        }

        @Override
        public Separation separate(float[][] channels, int sampleRate) {
            throw new UnsupportedOperationException();
        }
    }

    public static final class FakeAsr implements AsrProvider {
        @Override
        public String id() {
            return "fake-asr";
        }

        @Override
        public List<String> languages() {
            return List.of("it", "en");
        }

        @Override
        public List<LyricWord> transcribe(float[] samples, int sampleRate,
                                          String languageTag) {
            return List.of();
        }
    }

    @Test
    @DisplayName("a provider on the classpath is found by its id")
    void findsByServiceLoader() {
        assertThat(MlProviders.separation("fake-separation"))
                .get().isInstanceOf(FakeSeparation.class);
        assertThat(MlProviders.asr("fake-asr")).get().isInstanceOf(FakeAsr.class);
    }

    @Test
    @DisplayName("an absent provider is empty, never a throw")
    void absentIsEmpty() {
        // The pipeline treats this like an absent LilyPond: report, continue.
        assertThat(MlProviders.separation("no-such-provider")).isEmpty();
        assertThat(MlProviders.asr("no-such-provider")).isEmpty();
    }

    @Test
    @DisplayName("an unset id is empty rather than matching anything")
    void unsetIdMatchesNothing() {
        assertThat(MlProviders.separation(null)).isEmpty();
        assertThat(MlProviders.separation("")).isEmpty();
        assertThat(MlProviders.asr("  ")).isEmpty();
    }

    @Test
    @DisplayName("the id listing names what is present, for the absence report")
    void listsWhatIsPresent() {
        assertThat(MlProviders.separationIds()).contains("fake-separation");
        assertThat(MlProviders.asrIds()).contains("fake-asr");
    }

    @Test
    @DisplayName("a broken provider hides nothing: not one that throws, not one that is absent")
    void brokenProvidersAreSkippedAlone() {
        // The services file lists three, and the working fake is LAST: a
        // class that does not exist anywhere -- which fails in the traversal,
        // before any provider object is constructed, the way a service entry
        // does when the ML stack is an optional download (#25) -- then one
        // whose constructor throws, the way an ONNX provider does on a machine
        // without the natives, and only then the fake. Listed first, the fake
        // would survive a guard that abandons discovery at the first failure,
        // and continuation is the property this test exists to hold.
        assertThat(MlProviders.separationIds())
                .contains("fake-separation")
                .doesNotContain("throwing");
        assertThat(MlProviders.separation("fake-separation")).isPresent();
    }
}
