package app.frad.chat.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code as a square grid of modules (true = dark), for links people should be able to pass
 * on by pointing a camera at a screen - e.g. a `frad://node` link (see
 * [app.frad.chat.wideradius.NodeLinks]): any camera app that reads QR codes opens it in FRAD.
 */
class QrCode private constructor(val size: Int, private val modules: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]

    companion object {
        /** @param quietZone light border in modules; scanners need some, 2 is plenty on a screen. */
        fun encode(text: String, quietZone: Int = 2): QrCode {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to quietZone,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            // Size 0: one pixel per module, whatever the version the text needs.
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            val size = matrix.width
            return QrCode(size, BooleanArray(size * size) { matrix[it % size, it / size] })
        }
    }
}
