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
 * <p>A {@code \partial} moves the shared timing of the whole {@code \score},
 * so every timed context in it has to open on the pickup — and a context that
 * is not told sits a bar less the pickup behind the music from bar one on,
 * behind a single failed bar check, because LilyPond reports the first
 * mismatch and then resynchronises (#601). While the pickup was a parameter
 * each call site had to remember, a context added later repeated that in
 * silence. Here the assembler carries it and every block is built from it: a
 * context that needs no timing ignores its argument visibly, rather than
 * never being offered it.
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
