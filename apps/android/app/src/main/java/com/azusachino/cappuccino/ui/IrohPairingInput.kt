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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
  OutlinedTextField(
    value = payload,
    onValueChange = onPayload,
    label = { Text("Pairing payload") },
    visualTransformation = PasswordVisualTransformation(),
    singleLine = true,
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
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    TextButton(
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
    ) {
      Text("Scan QR")
    }
    TextButton(
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
    ) {
      Text("Paste")
    }
  }
  scanError?.let { ErrorBanner(it, modifier = Modifier.padding(top = 4.dp)) }
}
