package com.nuvio.app.features.livetv

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

class LiveTvEpgCompressionTest {
    private val xml = """
        <tv>
            <programme channel="RTP1.pt" start="20260101000000 +0000" stop="20260101010000 +0000">
                <title>Programme</title>
            </programme>
        </tv>
    """.trimIndent()

    @Test
    fun decodesGzipXmlTvPayload() {
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(xml.encodeToByteArray()) }
        }.toByteArray()

        assertEquals(xml, decodeLiveTvEpgPayload("guide.xml.gz", compressed))
    }

    @Test
    fun decodesXzXmlTvPayload() {
        val compressed = ByteArrayOutputStream().also { output ->
            XZOutputStream(output, LZMA2Options()).use { xz -> xz.write(xml.encodeToByteArray()) }
        }.toByteArray()

        assertEquals(xml, decodeLiveTvEpgPayload("guide.xml.xz", compressed))
    }

    @Test
    fun decodesXmlTvFileFromZipArchive() {
        val compressed = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write("Provider guide".encodeToByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("guide.xml"))
                zip.write(xml.encodeToByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        assertEquals(xml, decodeLiveTvEpgPayload("download", compressed))
    }

    @Test
    fun detectsCompressionFromMagicBytes() {
        assertEquals(
            LiveTvEpgCompression.Xz,
            detectLiveTvEpgCompression("guide.xml", byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)),
        )
        assertEquals(
            LiveTvEpgCompression.Gzip,
            detectLiveTvEpgCompression("guide.xml", byteArrayOf(0x1F, 0x8B.toByte())),
        )
        assertEquals(
            LiveTvEpgCompression.Zip,
            detectLiveTvEpgCompression("guide.xml", byteArrayOf(0x50, 0x4B, 0x03, 0x04)),
        )
    }
}
