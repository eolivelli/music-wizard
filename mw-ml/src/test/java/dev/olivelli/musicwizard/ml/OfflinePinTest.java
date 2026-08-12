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

import dev.olivelli.musicwizard.core.config.ConfigLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This module's classpath can reach a real provider, so its test JVMs must see
 * {@code ml.offline} — the failure message carries the rest of the story.
 */
@DisplayName("the offline pin")
class OfflinePinTest {

    @Test
    @DisplayName("every test JVM in this module is offline")
    void testJvmIsOffline() {
        assertThat(new ConfigLoader().effectiveConfig(null, null).isOffline())
                .withFailMessage("This test JVM does not see ml.offline: true,"
                        + " so a test that reaches a real provider would download"
                        + " its model (it happened once: 76 MB inside mvn test)."
                        + " The pin is XDG_CONFIG_HOME in this module's pom,"
                        + " aimed at test-config/ through"
                        + " maven.multiModuleProjectDirectory, which needs the"
                        + " .mvn marker directory at the repo root to resolve"
                        + " from inside a module. An IDE running JUnit directly sets no such"
                        + " variable and needs it in its run configuration.")
                .isTrue();
    }
}
