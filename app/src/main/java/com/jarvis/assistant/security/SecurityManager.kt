package com.jarvis.assistant.security

import android.app.AlertDialog
import android.content.Context
import com.jarvis.assistant.BuildConfig
import kotlin.system.exitProcess

/**
 * HOPE ASSISTANT — SECURITY MODULE
 * Developer       : Yash Pawar
 * Copyright © 2026: Yash Pawar. All Rights Reserved.
 * GitHub          : https://github.com/yashpawar9274/HOPE-V3.git
 */
object SecurityManager {

    const val DEVELOPER_NAME    = "Yash Pawar"
    const val COPYRIGHT_NOTICE  = "Copyright © 2026 Yash Pawar. All Rights Reserved."
    const val GITHUB_REPO       = "https://github.com/yashpawar9274/HOPE-V3.git"
    const val LICENSE_NAME      = "TAMCL v1.0 — Content creators MUST credit Yash Pawar"

    private const val EXPECTED_PACKAGE = "com.jarvis.assistant"

    fun verify(context: Context) {
        checkPackageName(context)
        checkSignature(context)
    }

    private fun checkPackageName(context: Context) {
        if (context.packageName != EXPECTED_PACKAGE) {
            showTamperAlert(
                context,
                "Clone Detected",
                "This is an unauthorised clone of the HOPE app by $DEVELOPER_NAME.\n\n" +
                "Original: $EXPECTED_PACKAGE\n" +
                "Found   : ${context.packageName}\n\n" +
                "The app will now close. Please download the official version from:\n$GITHUB_REPO"
            )
        }
    }

    /**
     * Release builds must be signed with the pinned certificate (JARVIS_RELEASE_CERT_SHA256).
     * Skipped in debug builds and when no pin is configured, so local builds never lock themselves out
     * (MainActivity still blocks sensitive features in that case via AppIdentityVerifier).
     * Note: this is a client-side deterrent, not tamper-proof; a repackaged app can remove it.
     */
    private fun checkSignature(context: Context) {
        if (BuildConfig.DEBUG || BuildConfig.PINNED_RELEASE_CERT_SHA256.isBlank()) return
        if (!AppIdentityVerifier.isTrustedRelease(context)) {
            showTamperAlert(
                context,
                "Tampered APK Detected",
                "This APK is not signed with the official release certificate.\n\n" +
                "$DEVELOPER_NAME only distributes APKs signed with the original certificate.\n\n" +
                "Download the official APK from:\n$GITHUB_REPO"
            )
        }
    }

    private fun showTamperAlert(context: Context, title: String, message: String) {
        try {
            AlertDialog.Builder(context)
                .setTitle("⚠️ $title")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> exitProcess(1) }
                .show()
        } catch (_: Exception) {
            exitProcess(1)
        }
    }
}
