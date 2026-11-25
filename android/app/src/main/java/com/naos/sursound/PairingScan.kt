package com.naos.sursound

import android.app.Activity
import android.content.Intent
import com.google.zxing.integration.android.IntentIntegrator

/** Explicit local camera scan only. No automatic network action or saved barcode image. */
@Suppress("DEPRECATION")
internal object PairingScan {
    const val REQUEST = 12

    fun launch(activity: Activity) {
        IntentIntegrator(activity)
            .setRequestCode(REQUEST)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt(activity.getString(R.string.pair_scan_prompt))
            .setBeepEnabled(false)
            .setBarcodeImageEnabled(false)
            .setTimeout(90_000)
            .initiateScan()
    }

    fun parse(resultCode: Int, data: Intent?): PairingTicket? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val result = IntentIntegrator.parseActivityResult(resultCode, data)
        if (result.formatName != IntentIntegrator.QR_CODE) return null
        return result.contents?.let { PairingTicket.parse(it) }
    }
}
