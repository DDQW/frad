package app.frad.chat.media

import org.junit.Assert.assertEquals
import org.junit.Test

class FileTypeCheckTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0)
    private val mp4 = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray() + ByteArray(8)
    private val apk = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4) + ByteArray(26) + "AndroidManifest.xml".toByteArray()
    private val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4) + ByteArray(26) + "notes.txt".toByteArray()

    @Test
    fun `a real image keeps its type`() {
        assertEquals("image/jpeg", FileTypeCheck.verifiedMimeType(jpeg, "image/jpeg"))
        assertEquals("image/png", FileTypeCheck.verifiedMimeType(png, "image/png"))
    }

    @Test
    fun `the content decides the exact image type`() {
        assertEquals("image/png", FileTypeCheck.verifiedMimeType(png, "image/jpeg"))
    }

    @Test
    fun `something that only claims to be an image becomes unknown`() {
        assertEquals(FileTypeCheck.UNKNOWN, FileTypeCheck.verifiedMimeType("#!/bin/sh\nrm -rf /".toByteArray(), "image/jpeg"))
        assertEquals(FileTypeCheck.UNKNOWN, FileTypeCheck.verifiedMimeType(zip, "video/mp4"))
    }

    @Test
    fun `an app package is never passed on as such`() {
        assertEquals(FileTypeCheck.UNKNOWN, FileTypeCheck.verifiedMimeType(apk, "application/vnd.android.package-archive"))
        assertEquals(FileTypeCheck.UNKNOWN, FileTypeCheck.verifiedMimeType(apk, "image/jpeg"))
        assertEquals(FileTypeCheck.UNKNOWN, FileTypeCheck.verifiedMimeType(apk, "application/zip"))
    }

    @Test
    fun `media sent as a generic type is recognised`() {
        assertEquals("video/mp4", FileTypeCheck.verifiedMimeType(mp4, "application/octet-stream"))
    }

    @Test
    fun `other documents keep a well-formed claimed type`() {
        assertEquals("text/plain", FileTypeCheck.verifiedMimeType("hello".toByteArray(), "text/plain"))
        assertEquals("application/zip", FileTypeCheck.verifiedMimeType(zip, "application/zip"))
        assertEquals(FileTypeCheck.UNKNOWN, FileTypeCheck.verifiedMimeType("hello".toByteArray(), "text/plain; evil"))
    }
}
