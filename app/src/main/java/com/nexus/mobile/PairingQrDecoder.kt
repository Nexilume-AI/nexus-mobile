package com.nexus.mobile

import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.DefaultDecoderFactory

internal object PairingQrDecoder {
    // Same bundled decoder is exercised by real QR image unit tests and camera frames.
    fun factory() = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE), null, "UTF-8", 2)

    const val MAX_PAYLOAD_LENGTH = 8192
}
