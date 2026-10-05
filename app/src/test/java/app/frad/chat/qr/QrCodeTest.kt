package app.frad.chat.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QrCodeTest {
    private fun decode(code: QrCode, scale: Int = 4): String {
        val side = code.size * scale
        val pixels = IntArray(side * side) { i ->
            if (code[(i % side) / scale, (i / side) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun `a node link survives encoding and scanning`() {
        val link = "frad://node?addr=%2Fip4%2F203.0.113.7%2Ftcp%2F4001%2Fp2p%2F12D3KooWGzBsVqGyF3vV7z5dJqXz8u3Mf9pY2r6QkTn4hLwXcA1e"
        assertEquals(link, decode(QrCode.encode(link)))
    }

    @Test
    fun `the quiet zone is light`() {
        val code = QrCode.encode("frad://node?addr=x", quietZone = 2)
        for (i in 0 until code.size) {
            assertFalse(code[i, 0])
            assertFalse(code[0, i])
            assertFalse(code[i, code.size - 1])
        }
    }
}
