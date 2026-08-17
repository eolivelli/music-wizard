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

package dev.olivelli.musicwizard.cli;

import dev.olivelli.musicwizard.core.ml.SeparationProvider;

/**
 * A separator whose class loads and whose native does not — an {@code Error},
 * not an exception, and thrown from {@code separate} rather than from
 * construction, which is where {@link
 * dev.olivelli.musicwizard.core.ml.MlProviders} guards. The real provider
 * touches ONNX Runtime for the first time in exactly that method, so this is
 * the shape an optional ML stack (#25) fails in.
 */
public final class UnloadableSeparationProvider implements SeparationProvider {

    /** Named in the failure, so a test can find it in the report. */
    static final String MISSING = "no fake-onnxruntime in java.library.path";

    @Override
    public String id() {
        return "fake-cli-unloadable-separation";
    }

    @Override
    public Separation separate(float[][] channels, int sampleRate) {
        throw new UnsatisfiedLinkError(MISSING);
    }
}
