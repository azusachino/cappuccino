package com.azusachino.cappuccino.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    .addOnFailureListener { e ->
      onFailure(
        e.localizedMessage
          ?: "Could not scan the pairing QR code. You can copy & paste the payload JSON directly."
      )
    }
}

/** Reads the clipboard. The payload is never logged. */
fun takePairingPayloadFromClipboard(context: Context): String? {
  val clipboard = context.getSystemService(ClipboardManager::class.java)
  val text =
    clipboard.primaryClip
      ?.takeIf { it.itemCount > 0 }
      ?.getItemAt(0)
      ?.coerceToText(context)
      ?.toString()
  return text?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The pairing payload field with Scan QR and Paste. Shared by the add-machine dialog and the "Pair
 * over iroh" entry for an existing machine.
 */
@Composable
fun PairingPayloadInput(
  payload: String,
  onPayload: (String) -> Unit,
  scanError: String?,
  onScanError: (String?) -> Unit,
  busy: Boolean,
) {
  val context = LocalContext.current
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    androidx.compose.material3.Button(
      onClick = {
        scanPairingPayload(
          context,
          onPayload = {
            onPayload(it)
            onScanError(null)
          },
          onFailure = onScanError,
        )
      },
      enabled = !busy,
      modifier = Modifier.weight(1f),
    ) {
      Text("Scan QR")
    }
    androidx.compose.material3.OutlinedButton(
      onClick = {
        val pasted = takePairingPayloadFromClipboard(context)
        if (pasted == null) {
          onScanError("Clipboard has no pairing payload")
        } else {
          onPayload(pasted)
          onScanError(null)
        }
      },
      enabled = !busy,
      modifier = Modifier.weight(1f),
    ) {
      Text("Paste")
    }
  }
  OutlinedTextField(
    value = payload,
    onValueChange = onPayload,
    label = { Text("Pairing payload") },
    placeholder = { Text("Scan QR or paste payload JSON") },
    maxLines = 3,
    enabled = !busy,
    modifier = Modifier.fillMaxWidth(),
    colors =
      OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
      ),
  )
  scanError?.let { ErrorBanner(it, modifier = Modifier.padding(top = 4.dp)) }
}
