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

/**
 * The Spleeter 2stems checkpoints, as ONNX.
 *
 * <p>The weights are Deezer's, MIT by their own statement — made in the JOSS
 * paper (doi:10.21105/joss.02154, p. 3: "source code and pre-trained models
 * are ... distributed under a MIT license"), not in the repository README,
 * which speaks only of the code and has an open issue asking for exactly this
 * clarification. Cite the paper; the README does not carry the claim.
 *
 * <p>The ONNX conversion is the sherpa-onnx project's (Apache-2.0), whose
 * repository carries the conversion scripts, so
 * the export is reproducible from Deezer's checkpoint rather than a binary of
 * unstated origin. Digests and sizes were taken from the files themselves on
 * 2026-08-12; a re-upload that changes them is exactly what the checksum
 * refusal in {@code ModelCache} exists to catch.
 */
final class SpleeterModels {

    static final ModelRef VOCALS = new ModelRef(
            "spleeter-2stems",
            "vocals.onnx",
            URI.create("https://huggingface.co/csukuangfj/sherpa-onnx-spleeter-2stems"
                    + "/resolve/main/vocals.onnx"),
            "bdc16ab6bf6117ddd4842c19e80e40e2be188fc555295064d424616b0224ac97",
            39_318_336,
            "MIT (Deezer, stated for code and pretrained models in the Spleeter JOSS paper, doi:10.21105/joss.02154)");

    static final ModelRef ACCOMPANIMENT = new ModelRef(
            "spleeter-2stems",
            "accompaniment.onnx",
            URI.create("https://huggingface.co/csukuangfj/sherpa-onnx-spleeter-2stems"
                    + "/resolve/main/accompaniment.onnx"),
            "671ace17acd3720674a2bc14de32ac6292453dec20d9eb0ba4255d4ad8e3d8c0",
            39_318_343,
            "MIT (Deezer, stated for code and pretrained models in the Spleeter JOSS paper, doi:10.21105/joss.02154)");

    private SpleeterModels() {
    }
}
