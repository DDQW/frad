package app.frad.chat.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeviceFingerprintTest {
    private val deviceA = ByteArray(32) { 1 }
    private val deviceB = ByteArray(32) { 2 }
    private val alice = ByteArray(32) { 10 }
    private val bob = ByteArray(32) { 20 }

    @Test
    fun `the same device always shows the same peer the same value`() {
        // Independent of our own identity: this is what keeps a block alive across our resets.
        assertEquals(DeviceFingerprint.forPeer(deviceA, alice), DeviceFingerprint.forPeer(deviceA, alice))
    }

    @Test
    fun `different peers see unrelated values, so they can't link us`() {
        assertNotEquals(DeviceFingerprint.forPeer(deviceA, alice), DeviceFingerprint.forPeer(deviceA, bob))
    }

    @Test
    fun `different devices are told apart`() {
        assertNotEquals(DeviceFingerprint.forPeer(deviceA, alice), DeviceFingerprint.forPeer(deviceB, alice))
    }
}
