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

import dev.olivelli.musicwizard.ml.ModelRef;
import java.net.URI;

/**
 * The Qwen3-ASR checkpoint, as sherpa-onnx's own release archive.
 *
 * <p>The 0.6B int8 export attached to sherpa-onnx's {@code asr-models} release
 * — the official artifact, converted by the project whose runtime this
 * provider links, with the conversion scripts in the same repository. The
 * weights are Alibaba's Qwen3-ASR, Apache-2.0 on code and weights alike.
 *
 * <p><b>Why not the 1.7B that #314 names:</b> no official export of it exists
 * — the only copies are unprovenanced mirrors, which this project does not
 * run. When sherpa-onnx publishes one, this table grows a row and the larger
 * model becomes selectable; the digest below was read from the file itself.
 */
final class Qwen3Models {

    static final String UNPACKED_DIRECTORY = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25";

    static final ModelRef ARCHIVE = new ModelRef(
            "qwen3-asr-0.6b",
            UNPACKED_DIRECTORY + ".tar.bz2",
            URI.create("https://github.com/k2-fsa/sherpa-onnx/releases/download/"
                    + "asr-models/" + UNPACKED_DIRECTORY + ".tar.bz2"),
            Qwen3ArchiveDigest.SHA256,
            Qwen3ArchiveDigest.SIZE_BYTES,
            "Apache-2.0 (Alibaba Qwen3-ASR weights; sherpa-onnx int8 export,"
                    + " conversion scripts in the sherpa-onnx repository)");

    private Qwen3Models() {
    }
}
