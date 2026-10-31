package com.azusachino.cappuccino.ui

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Scans a pairing QR with the Play services code scanner. The scanner UI runs out of process, so
 * the app needs no camera permission. The payload is never logged.
 */
fun scanPairingPayload(context: Context, onPayload: (String) -> Unit, onFailure: (String) -> Unit) {
  val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
  GmsBarcodeScanning.getClient(context, options)
    .startScan()
    .addOnSuccessListener { barcode ->
      val payload = barcode.rawValue
      if (payload.isNullOrBlank()) onFailure("No pairing QR code found") else onPayload(payload)
    }
    .addOnFailureListener { e -> onFailure(e.localizedMessage ?: "Could not scan QR code") }
}
