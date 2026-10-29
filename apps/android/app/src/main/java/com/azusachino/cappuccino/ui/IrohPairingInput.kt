package com.azusachino.cappuccino.ui

import android.content.ClipboardManager
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
    .addOnFailureListener { onFailure("Could not scan the pairing QR code") }
}

/** Reads the clipboard once and clears it, so a pairing payload does not linger there. */
fun takePairingPayloadFromClipboard(context: Context): String? {
  val clipboard = context.getSystemService(ClipboardManager::class.java)
  val text =
    clipboard.primaryClip
      ?.takeIf { it.itemCount > 0 }
      ?.getItemAt(0)
      ?.coerceToText(context)
      ?.toString()
  clipboard.clearPrimaryClip()
  return text?.trim()?.takeIf { it.isNotEmpty() }
}
