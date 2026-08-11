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
 * On the test classpath under the id the default configuration names, so
 * {@code doctor}'s "(present)" branch runs in a test — the branch that will be
 * the ordinary case the day #312 lands, and that no assertion would otherwise
 * execute until then.
 */
public final class FakeSeparationProvider implements SeparationProvider {

    @Override
    public String id() {
        return "onnx-spleeter";
    }

    @Override
    public Separation separate(float[][] channels, int sampleRate) {
        return new Separation(channels, channels);
    }
}
