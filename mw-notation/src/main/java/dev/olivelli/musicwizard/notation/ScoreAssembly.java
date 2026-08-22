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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Assembles the parallel contexts of one {@code \score}, handing each of them
 * the pickup (#605).
 *
 * <p>A context not told the pickup engraves displaced behind one failed bar
 * check (#601). Every context this assembles takes the pickup in its
 * signature, so the question cannot be skipped at those calls; this class is
 * where a lead sheet's one answer comes from.
 */
final class ScoreAssembly {

    /** One context of the score, built from the score's shared pickup. */
    @FunctionalInterface
    interface Timed {
        Optional<String> block(Optional<StaffNotation.Pickup> pickup);
    }

    private final Optional<StaffNotation.Pickup> pickup;

    private final List<String> blocks = new ArrayList<>();

    ScoreAssembly(Optional<StaffNotation.Pickup> pickup) {
        this.pickup = Objects.requireNonNull(pickup, "pickup");
    }

    /** Builds one context from the pickup and adds it, in reading order. */
    ScoreAssembly add(Timed context) {
        context.block(pickup).ifPresent(blocks::add);
        return this;
    }

    /** The {@code << ... >>} holding every added context. */
    String lilyPond() {
        StringBuilder out = new StringBuilder();
        out.append("  <<\n");
        for (String block : blocks) {
            out.append(block);
        }
        out.append("  >>\n");
        return out.toString();
    }
}
