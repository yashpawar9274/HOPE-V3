package com.jarvis.assistant.license

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.R
import kotlinx.coroutines.launch

/**
 * Licence key screen: one field (HOPE-XXXXX-XXXXX-XXXXX-XXXXX, upper-cased and dashed as you type or
 * paste), "Unlock" (POST /api/activate), "Buy HOPE AI" and "Sign out". The server's own message is
 * shown for every error (WRONG_ACCOUNT, DEVICE_LIMIT, KEY_NOT_FOUND, LICENSE_REVOKED, RATE_LIMITED...).
 */
class LicenseKeyActivity : AppCompatActivity() {
    private lateinit var banner: TextView
    private lateinit var keyLayout: TextInputLayout
    private lateinit var keyInput: TextInputEditText
    private lateinit var unlock: MaterialButton
    private lateinit var buy: MaterialButton
    private lateinit var signOut: MaterialButton
    private lateinit var progress: View
    private var orb: LicenseOrb? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = LicenseManager.currentUser(this)
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java).putExtra(LicenseGateActivity.EXTRA_START_VOICE_SESSION, startVoiceSession()))
            finish()
            return
        }
        setContentView(R.layout.activity_license_key)
        banner = findViewById(R.id.bannerText)
        keyLayout = findViewById(R.id.keyLayout)
        keyInput = findViewById(R.id.keyInput)
        unlock = findViewById(R.id.unlockButton)
        buy = findViewById(R.id.buyButton)
        signOut = findViewById(R.id.signOutButton)
        progress = findViewById(R.id.progress)
        orb = LicenseOrb(findViewById(R.id.orbView)).also { it.start() }

        findViewById<TextView>(R.id.accountText).text =
            "Signed in as " + (user.email ?: user.displayName ?: "your HOPE account")

        reasonMessage(intent?.getStringExtra(EXTRA_REASON))?.let { (message, isError) -> showBanner(message, isError) }

        keyInput.addTextChangedListener(object : TextWatcher {
            private var busy = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (busy || s == null) return
                val formatted = LicenseKeyFormat.format(s.toString())
                if (formatted != s.toString()) {
                    busy = true
                    s.replace(0, s.length, formatted)
                    busy = false
                    keyInput.setSelection(formatted.length)
                }
                keyLayout.error = null
            }
        })
        keyInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                unlock()
                true
            } else false
        }
        unlock.setOnClickListener { unlock() }
        buy.setOnClickListener { openBuyPage() }
        signOut.setOnClickListener { signOutAndLogin() }
    }

    override fun onResume() {
        super.onResume()
        orb?.resume()
    }

    override fun onPause() {
        orb?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        orb?.destroy()
        super.onDestroy()
    }

    private fun startVoiceSession(): Boolean =
        intent?.getBooleanExtra(LicenseGateActivity.EXTRA_START_VOICE_SESSION, false) == true

    /** Returns (message, isError) for the reason the gate sent the user here, or null. */
    private fun reasonMessage(reason: String?): Pair<String, Boolean>? = when (reason) {
        "NO_LICENSE" ->
            "No HOPE licence is linked to this account yet. Buy HOPE AI, or sign in with the account you bought it with." to false
        "DEVICE_NOT_ACTIVATED" -> "Enter your licence key to activate HOPE on this phone." to false
        "LICENSE_REVOKED" -> "This licence has been revoked. Please contact support." to true
        "LICENSE_EXPIRED" -> "Your licence has expired. Renew it on the website, then enter your key here." to true
        else -> null
    }

    private fun showBanner(message: String, error: Boolean) {
        banner.text = message
        banner.setBackgroundResource(if (error) R.drawable.bg_hope_auth_banner_error else R.drawable.bg_hope_auth_banner_info)
        banner.visibility = View.VISIBLE
    }

    private fun setLoading(value: Boolean) {
        loading = value
        orb?.setState(if (value) "thinking" else "idle")
        progress.visibility = if (value) View.VISIBLE else View.GONE
        unlock.isEnabled = !value
        buy.isEnabled = !value
        signOut.isEnabled = !value
        keyInput.isEnabled = !value
    }

    private fun unlock() {
        if (loading) return
        val formatted = LicenseKeyFormat.format(keyInput.text?.toString().orEmpty())
        if (!LicenseKeyFormat.isComplete(formatted)) {
            keyLayout.error = "Enter the full key: HOPE-XXXXX-XXXXX-XXXXX-XXXXX"
            return
        }
        keyLayout.error = null
        banner.visibility = View.GONE
        setLoading(true)
        lifecycleScope.launch {
            when (val outcome = LicenseManager.activate(this@LicenseKeyActivity, formatted)) {
                is ActivateOutcome.Success -> {
                    Toast.makeText(this@LicenseKeyActivity, "HOPE unlocked", Toast.LENGTH_SHORT).show()
                    LicenseGateActivity.openMain(this@LicenseKeyActivity, startVoiceSession())
                }
                is ActivateOutcome.Rejected -> {
                    setLoading(false)
                    showBanner(outcome.message, error = true) // the server's message, as is
                }
                ActivateOutcome.SignInRequired -> {
                    startActivity(
                        Intent(this@LicenseKeyActivity, LoginActivity::class.java)
                            .putExtra(LicenseGateActivity.EXTRA_START_VOICE_SESSION, startVoiceSession())
                    )
                    finish()
                }
                is ActivateOutcome.NetworkError -> {
                    setLoading(false)
                    showBanner(outcome.message, error = true)
                }
            }
        }
    }

    private fun openBuyPage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LicenseConfig.BUY_URL)))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "No browser found. Open ${LicenseConfig.BUY_URL}", Toast.LENGTH_LONG).show()
        }
    }

    private fun signOutAndLogin() {
        setLoading(true)
        lifecycleScope.launch {
            try {
                CredentialManager.create(this@LicenseKeyActivity).clearCredentialState(ClearCredentialStateRequest())
            } catch (error: Exception) {
                // Clearing the Google credential state is best effort.
            }
            LicenseManager.signOut(this@LicenseKeyActivity)
            startActivity(
                Intent(this@LicenseKeyActivity, LoginActivity::class.java)
                    .putExtra(LicenseGateActivity.EXTRA_START_VOICE_SESSION, startVoiceSession())
            )
            finish()
        }
    }

    companion object {
        private const val EXTRA_REASON = "hope_license_reason"

        fun intent(context: Context, reason: String?, startVoice: Boolean): Intent =
            Intent(context, LicenseKeyActivity::class.java)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(LicenseGateActivity.EXTRA_START_VOICE_SESSION, startVoice)
    }
}
