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

package dev.olivelli.musicwizard.teacher;

import dev.olivelli.musicwizard.testkit.MidiFixtures;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import javax.sound.midi.Sequence;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Compiles a {@code .spec.txt} into its ground-truth MIDI file.
 *
 * <p>Rendering the MIDI to audio is the job of
 * {@code tools/music-teacher/generate.sh}, which invokes this and then
 * FluidSynth and ffmpeg; this command touches nothing but the spec and the
 * MIDI so it stays runnable offline and binary-free.
 */
@Command(name = "generate-sample", mixinStandardHelpOptions = true,
        description = "Compile a synthetic-sample spec into ground-truth MIDI.")
public final class GenerateSample implements Callable<Integer> {

    @Parameters(index = "0", description = "The .spec.txt file.")
    private Path specFile;

    @Option(names = "--out", description = "Output directory; default is the spec's own.")
    private Path outDirectory;

    @Override
    public Integer call() {
        SampleSpec spec = SpecParser.parse(specFile);
        Sequence sequence = Arranger.arrange(spec);
        Path directory = outDirectory != null ? outDirectory : specFile.toAbsolutePath()
                .getParent();
        Path midi = directory.resolve(baseName(specFile) + ".mid");
        MidiFixtures.write(sequence, midi);

        double beats = spec.bars().size() * spec.meter().quarterBeatsPerBar();
        System.out.printf("%s: %d bars, %s %s, %.0f BPM, %s -> %.0f s%n",
                midi, spec.bars().size(), spec.keyTonic(), spec.mode(), spec.tempoBpm(),
                spec.style().id(), beats * 60 / spec.tempoBpm());
        return 0;
    }

    /** The package name: the spec file name with its extensions removed. */
    static String baseName(Path specFile) {
        String name = specFile.getFileName().toString();
        if (name.endsWith(".spec.txt")) {
            return name.substring(0, name.length() - ".spec.txt".length());
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new GenerateSample()).execute(args));
    }
}
