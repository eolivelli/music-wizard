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

import java.net.URI;
import java.util.Objects;

/**
 * One downloadable model file: where it comes from and what it must hash to.
 *
 * <p>The checksum is part of the reference, not an option, because a model is
 * executed: a corrupted or substituted download does not fail loudly the way a
 * corrupted archive does, it runs and produces subtly wrong numbers, which is
 * the expensive kind of wrong. There is deliberately no constructor without one.
 *
 * <p>{@code licence} is carried so the cache can write it beside the file.
 * Weights carry their own licence separate from the code that runs them —
 * the Demucs lesson in {@code CLAUDE.md} — and a cache directory someone
 * inspects two years from now should say what each file is and under what
 * terms it arrived, without the repository at hand.
 *
 * @param name a short stable id for the model, also the cache subdirectory,
 *        e.g. {@code spleeter-2stems}
 * @param fileName the file's name inside the cache, e.g. {@code model.onnx}
 * @param uri where to fetch it
 * @param sha256 lowercase hex digest the fetched bytes must have
 * @param sizeBytes expected size, for the progress report and a cheap
 *        pre-checksum sanity check; not a substitute for the digest
 * @param licence one line naming the licence the weights arrive under
 */
public record ModelRef(String name, String fileName, URI uri, String sha256,
                       long sizeBytes, String licence) {

    public ModelRef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(licence, "licence");
        Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "sha256 must be 64 lowercase hex characters, got: " + sha256);
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive: " + sizeBytes);
        }
        if (name.isBlank() || fileName.isBlank()) {
            throw new IllegalArgumentException("name and fileName must not be blank");
        }
        // Both become path segments under the cache directory. Refs are
        // compile-time constants today, but the constructor is where validation
        // lives, and a model table read from a file later is exactly the change
        // that would not think to add this.
        for (String segment : new String[] {name, fileName}) {
            if (segment.contains("/") || segment.contains("\\")
                    || segment.contains("..") || segment.startsWith(".")) {
                throw new IllegalArgumentException(
                        "name and fileName must be plain file names, got: " + segment);
            }
        }
    }
}
