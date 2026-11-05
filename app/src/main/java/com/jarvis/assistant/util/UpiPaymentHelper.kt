package com.jarvis.assistant.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Opens the owner's UPI app with the payee and amount filled in. The owner confirms with their UPI
 * PIN or biometric. HOPE never completes, approves or enters credentials for a payment.
 */
object UpiPaymentHelper {
    private const val TAG = "UpiPayment"

    class Result(val ok: Boolean, val message: String)

    fun prepare(
        context: Context,
        payeeUpiId: String,
        payeeName: String,
        amountRupees: Double,
        note: String,
        confirmedByOwner: Boolean
    ): Result {
        return when (val check = UpiPaymentLogic.check(payeeUpiId, payeeName, amountRupees, note, confirmedByOwner)) {
            is UpiPaymentLogic.Check.Refused -> Result(false, check.message)
            is UpiPaymentLogic.Check.Ok -> try {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(check.uri))
                val chooser = Intent.createChooser(view, "Pay with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                Result(
                    true,
                    "Opened the payment screen for ${check.summary}. NOT paid yet: the owner must pick their UPI app " +
                        "and approve with their own UPI PIN or fingerprint. Tell the owner that, and never ask for or read out a PIN or OTP."
                )
            } catch (error: ActivityNotFoundException) {
                Result(false, "No UPI payment app is installed on this phone.")
            } catch (error: Exception) {
                Log.w(TAG, "Could not open the payment screen.", error)
                Result(false, "I could not open the payment screen: ${error.message ?: "unknown error"}.")
            }
        }
    }
}
