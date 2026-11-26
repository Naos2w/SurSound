package com.naos.sursound

import android.app.Activity
import android.content.Intent
import com.google.zxing.integration.android.IntentIntegrator

/** Explicit local camera scan only. No automatic network action or saved barcode image. */
@Suppress("DEPRECATION")
internal object PairingScan {
    const val REQUEST = 12

    /** Fail gracefully before opening the scanner if a packaged dependency is missing. */
    fun verifyRuntime(loader: ClassLoader) {
        try {
            for (name in requiredClasses) Class.forName(name, false, loader)
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("QR scanner runtime unavailable", e)
        } catch (e: LinkageError) {
            throw IllegalStateException("QR scanner runtime incompatible", e)
        }
    }

    private val requiredClasses = listOf(
        "androidx.core.content.ContextCompat",
        "androidx.core.app.ActivityCompat",
        "androidx.activity.result.contract.ActivityResultContract",
        "androidx.fragment.app.Fragment",
        "androidx.fragment.app.FragmentActivity",
        "com.journeyapps.barcodescanner.CaptureActivity",
        "com.journeyapps.barcodescanner.CaptureManager",
        "com.google.zxing.qrcode.QRCodeReader"
    )

    fun launch(activity: Activity) {
        verifyRuntime(activity.classLoader)
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
