package com.muses.player.core.media.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCodecCompatibilityTest {
    @Test fun targetsOnlyKnownDeviceAndSystem() {
        assertTrue(requiresSynchronousCodec("OnePlus", "PJZ110", 37))
        assertTrue(requiresSynchronousCodec("oneplus", "pjz110", 38))
        assertFalse(requiresSynchronousCodec("OnePlus", "PJZ110", 36))
        assertFalse(requiresSynchronousCodec("OnePlus", "another", 37))
        assertFalse(requiresSynchronousCodec("Other", "PJZ110", 37))
    }
}
