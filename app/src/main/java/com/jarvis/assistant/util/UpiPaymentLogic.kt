package com.jarvis.assistant.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder

/**
 * Rules for HOPE's "prepare a UPI payment" tool. HOPE only OPENS the payment screen with the payee
 * and amount filled in. The owner always confirms with their own UPI PIN or biometric inside the
 * payment app; HOPE never enters a PIN/OTP and never completes a payment.
 */
object UpiPaymentLogic {
    const val MAX_AMOUNT_RUPEES = 100_000.0
    private const val MAX_NOTE_CHARS = 60
    private val upiId = Regex("""^[a-zA-Z0-9._\-]{2,256}@[a-zA-Z][a-zA-Z0-9]{1,63}$""")

    sealed class Check {
        class Ok(val uri: String, val summary: String) : Check()
        class Refused(val message: String) : Check()
    }

    fun isValidUpiId(value: String): Boolean = upiId.matches(value.trim())

    fun formatAmount(amount: Double): String =
        BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toPlainString()

    fun check(
        payeeUpiId: String,
        payeeName: String,
        amountRupees: Double,
        note: String,
        confirmedByOwner: Boolean
    ): Check {
        if (!confirmedByOwner) {
            return Check.Refused(
                "Not done: first say the payee and the amount out loud and wait for the owner's clear yes, " +
                    "then call this tool again with confirmed_by_owner = true."
            )
        }
        val id = payeeUpiId.trim()
        if (!isValidUpiId(id)) return Check.Refused("That UPI ID does not look valid. Ask the owner to repeat it.")
        if (amountRupees.isNaN() || amountRupees.isInfinite() || amountRupees <= 0.0) {
            return Check.Refused("The amount must be more than zero.")
        }
        if (amountRupees > MAX_AMOUNT_RUPEES) {
            return Check.Refused("HOPE does not prepare payments above Rs 1,00,000. The owner must do this one in the payment app.")
        }
        val name = payeeName.trim().replace(Regex("""[\u0000-\u001F]"""), "").take(60)
        val cleanNote = note.trim().replace(Regex("""[\u0000-\u001F]"""), " ").take(MAX_NOTE_CHARS)
        val amount = formatAmount(amountRupees)
        val query = buildList {
            add("pa=" + encode(id))
            if (name.isNotEmpty()) add("pn=" + encode(name))
            add("am=$amount")
            add("cu=INR")
            if (cleanNote.isNotEmpty()) add("tn=" + encode(cleanNote))
        }.joinToString("&")
        val who = if (name.isNotEmpty()) "$name ($id)" else id
        return Check.Ok("upi://pay?$query", "Rs $amount to $who")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
