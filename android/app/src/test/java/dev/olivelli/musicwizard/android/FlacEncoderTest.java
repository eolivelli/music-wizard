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

package dev.olivelli.musicwizard.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.nio.ByteBuffer;
import org.junit.Test;

/**
 * The one part of the encoder that is not {@code MediaCodec}.
 *
 * <p>Everything else in {@code FlacEncoder} is a loop around a codec that
 * exists only on a device, and is checked on the emulator. This is a pure
 * function of two arguments, and the bug it exists for was silent: an empty
 * second header replacing a good first one produced a file with no
 * {@code fLaC} magic and no {@code STREAMINFO}, uploaded and reported as sent.
 */
public class FlacEncoderTest {

    private static final byte[] REAL = {'f', 'L', 'a', 'C', 0, 0, 0, 34};

    /** A device that emits the header twice, the second time empty. */
    @Test
    public void anEmptyCandidateNeverReplacesARealHeader() {
        assertSame(REAL, FlacEncoder.betterHeader(REAL, ByteBuffer.allocate(0)));
        assertSame(REAL, FlacEncoder.betterHeader(REAL, null));
    }

    /** With nothing yet, an empty candidate leaves nothing — not an empty header. */
    @Test
    public void anEmptyCandidateDoesNotCountAsAHeader() {
        assertNull(FlacEncoder.betterHeader(null, ByteBuffer.allocate(0)));
        assertNull(FlacEncoder.betterHeader(null, null));
    }

    /** A real one is taken, whether or not there was one before. */
    @Test
    public void aRealCandidateIsTaken() {
        assertArrayEquals(REAL, FlacEncoder.betterHeader(null, ByteBuffer.wrap(REAL)));
        assertArrayEquals(REAL, FlacEncoder.betterHeader(new byte[] {1, 2}, ByteBuffer.wrap(REAL)));
    }

    /** The candidate is copied out, so the codec may reuse the buffer underneath. */
    @Test
    public void theCandidateIsCopiedRatherThanAliased() {
        byte[] backing = REAL.clone();
        byte[] taken = FlacEncoder.betterHeader(null, ByteBuffer.wrap(backing));
        backing[0] = 'x';
        assertArrayEquals(REAL, taken);
    }
}
