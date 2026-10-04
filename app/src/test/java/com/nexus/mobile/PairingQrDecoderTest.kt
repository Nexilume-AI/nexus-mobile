package com.nexus.mobile

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.PlanarYUVLuminanceSource
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PairingQrDecoderTest {
    // Synthetic credentials only. Exercise the very same offline decoder used by the camera.
    private val secureQr = "nexus-mobile://pair?base_url=https%3A%2F%2Fcloud.example%3A443" +
        "&device_id=00000000-0000-4000-8000-000000000001" +
        "&token=mnx-mobile-synthetic-test-token-not-a-real-credential" +
        "&expires_at=2099-01-01T00%3A00%3A00Z"

    private fun decode(value: String, format: BarcodeFormat = BarcodeFormat.QR_CODE, inverted: Boolean = false): String? {
        val matrix = MultiFormatWriter().encode(value, format, 640, 640, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        val pixels = ByteArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width] != inverted) 0 else 255.toByte()
        }
        val image = PlanarYUVLuminanceSource(pixels, matrix.width, matrix.height, 0, 0, matrix.width, matrix.height, false)
        val decoder = PairingQrDecoder.factory().createDecoder(emptyMap<DecodeHintType, Any>())
        // MixedDecoder alternates normal and inverted luminance between camera frames.
        return decoder.decode(image)?.text ?: decoder.decode(image)?.text
    }

    @Test fun decodesSecurePairingFromRealQrImageWithoutNetworkOrGoogleServices() {
        val decoded = decode(secureQr)
        assertEquals(secureQr, decoded)
        val payload = PairingParser.parse(decoded!!, allowInsecureLocalhost = false)
        assertEquals("https://cloud.example:443", payload.config.baseUrl)
        assertEquals("00000000-0000-4000-8000-000000000001", payload.config.deviceId)
    }

    @Test fun decodesInvertedQrImage() {
        assertEquals(secureQr, decode(secureQr, inverted = true))
    }

    @Test fun rejectsNonQrBarcode() {
        assertNull(decode("synthetic-code-128", BarcodeFormat.CODE_128))
    }

    @Test fun decodableNonPairingQrDoesNotBypassPairingValidation() {
        val decoded = decode("https://example.com/not-a-pairing")!!
        assertThrows(PairingValidationException::class.java) {
            PairingParser.parse(decoded, allowInsecureLocalhost = false)
        }
    }

    @Test fun decodableExpiredQrDoesNotBypassExpiryValidation() {
        val decoded = decode(secureQr.replace("2099-01-01", "2020-01-01"))!!
        assertThrows(PairingValidationException::class.java) {
            PairingParser.parse(decoded, allowInsecureLocalhost = false, now = Instant.parse("2026-10-04T00:00:00Z"))
        }
    }

    @Test fun decodableHttpQrStillRequiresHttpsInRelease() {
        val decoded = decode(secureQr.replace("https%3A%2F%2Fcloud.example", "http%3A%2F%2F127.0.0.1"))!!
        assertThrows(PairingValidationException::class.java) {
            PairingParser.parse(decoded, allowInsecureLocalhost = false)
        }
        assertEquals("http://127.0.0.1:443", PairingParser.parse(decoded, allowInsecureLocalhost = true).config.baseUrl)
    }

    @Test fun blankFrameIsNotAPairing() {
        val image = PlanarYUVLuminanceSource(ByteArray(160 * 160) { 255.toByte() }, 160, 160, 0, 0, 160, 160, false)
        val decoder = PairingQrDecoder.factory().createDecoder(emptyMap<DecodeHintType, Any>())
        assertNull(decoder.decode(image))
        assertNull(decoder.decode(image))
    }
}
