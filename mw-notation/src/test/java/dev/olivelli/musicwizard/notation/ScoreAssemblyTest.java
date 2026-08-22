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

package dev.olivelli.musicwizard.notation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one property the assembler exists for (#605): no context can be added
 * without being offered the score's pickup.
 */
class ScoreAssemblyTest {

    @Test
    @DisplayName("every context is handed the score's own pickup, in order")
    void everyContextIsHandedThePickup() {
        Optional<StaffNotation.Pickup> pickup = Optional.of(new StaffNotation.Pickup(1, 4));
        List<Optional<StaffNotation.Pickup>> seen = new ArrayList<>();

        String assembled = new ScoreAssembly(pickup)
                .add(p -> {
                    seen.add(p);
                    return Optional.of("  a\n");
                })
                .add(p -> {
                    seen.add(p);
                    return Optional.empty();
                })
                .add(p -> {
                    seen.add(p);
                    return Optional.of("  b\n");
                })
                .lilyPond();

        assertThat(seen).containsExactly(pickup, pickup, pickup);
        assertThat(assembled).isEqualTo("  <<\n  a\n  b\n  >>\n");
    }
}
