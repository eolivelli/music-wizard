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
 * Guards the guard: this module's classpath can reach a real provider, and a
 * test that does must find {@code ml.offline} set, or it downloads a model
 * inside the fast suite — which happened once, 76 MB of it. The pin lives in
 * this module's pom, pointed through {@code maven.multiModuleProjectDirectory}
 * at the committed layer under {@code test-config/}; that property resolves
 * through the {@code .mvn} marker at the repo root, and without the marker a
 * build launched inside the module resolves it to a path that does not exist
 * and the pin silently vanishes. This test is what makes that loud.
 */
@DisplayName("the offline pin")
class OfflinePinTest {

    @Test
    @DisplayName("every test JVM in this module is offline")
    void testJvmIsOffline() {
        assertThat(new ConfigLoader().effectiveConfig(null, null).isOffline())
                .withFailMessage("""
                        This test JVM does not see ml.offline: true, so a test                         that reaches a real provider would download its model.                         The pin is XDG_CONFIG_HOME in this module's pom, aimed                         at test-config/ through maven.multiModuleProjectDirectory,                         which needs the .mvn marker directory at the repo root                         to resolve from inside a module. An IDE needs the same                         variable in its run configuration.""")
                .isTrue();
    }
}
