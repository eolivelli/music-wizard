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
 * On the test classpath under an id no real provider will ever take: #312
 * registers {@code onnx-spleeter}, and a fake under that id would collide the
 * day it lands — two providers, one id, and {@code byId} returning whichever
 * the loader yields first, with every assertion still green. The doctor test
 * reaches the "(present)" branch by configuring this id instead.
 */
public final class FakeSeparationProvider implements SeparationProvider {

    @Override
    public String id() {
        return "fake-cli-separation";
    }

    @Override
    public Separation separate(float[][] channels, int sampleRate) {
        return new Separation(channels, channels);
    }
}
