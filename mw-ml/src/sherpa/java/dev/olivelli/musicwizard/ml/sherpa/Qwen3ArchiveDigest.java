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

package dev.olivelli.musicwizard.ml.sherpa;

/**
 * The archive's identity, read from the downloaded file on 2026-08-12.
 *
 * <p>Its own class so the two numbers that must move together live on
 * adjacent lines; a re-released archive changes both, and the checksum
 * refusal in {@code ModelCache} is what notices.
 */
final class Qwen3ArchiveDigest {

    static final String SHA256 =
            "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96";

    static final long SIZE_BYTES = 878_702_423L;

    private Qwen3ArchiveDigest() {
    }
}
