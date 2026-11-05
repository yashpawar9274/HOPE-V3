package com.jarvis.assistant.license

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.voice.HopeVoiceInteractionSessionService
import kotlinx.coroutines.launch

/**
 * Launcher screen. HOPE's own screens open only after this gate:
 *   not signed in -> Login, signed in -> verify(), licensed -> MainActivity,
 *   NO_LICENSE / DEVICE_NOT_ACTIVATED / REVOKED / EXPIRED -> Key screen.
 */
class LicenseGateActivity : AppCompatActivity() {
    private lateinit var orb: LicenseOrb
    private lateinit var status: TextView
    private lateinit var progress: View
    private lateinit var retry: MaterialButton
    private lateinit var signOut: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_license_gate)
        orb = LicenseOrb(findViewById(R.id.orbView)).also { it.start() }
        status = findViewById(R.id.gateStatus)
        progress = findViewById(R.id.gateProgress)
        retry = findViewById(R.id.gateRetry)
        signOut = findViewById(R.id.gateSignOut)
        retry.setOnClickListener { runGate() }
        signOut.setOnClickListener {
            LicenseManager.signOut(this)
            goToLogin()
        }
        runGate()
    }

    override fun onResume() {
        super.onResume()
        orb.resume()
    }

    override fun onPause() {
        orb.pause()
        super.onPause()
    }

    override fun onDestroy() {
        orb.destroy()
        super.onDestroy()
    }

    private fun startVoiceSession(): Boolean = intent?.getBooleanExtra(EXTRA_START_VOICE_SESSION, false) == true

    private fun runGate() {
        showLoading()
        lifecycleScope.launch {
            if (!LicenseManager.isFirebaseConfigured(this@LicenseGateActivity)) {
                showProblem(
                    "Firebase is not set up in this build. Add google-services.json to the app module " +
                        "and rebuild (see LICENSE_GATE_SETUP.md).",
                    canRetry = false
                )
                return@launch
            }
            val outcome = try {
                LicenseManager.verify(this@LicenseGateActivity)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                VerifyOutcome.Failure("Could not check your licence: ${error.message ?: "unexpected error"}")
            }
            when (outcome) {
                is VerifyOutcome.Licensed -> openMain(this@LicenseGateActivity, startVoiceSession())
                is VerifyOutcome.NotLicensed -> {
                    startActivity(LicenseKeyActivity.intent(this@LicenseGateActivity, outcome.reason, startVoiceSession()))
                    finish()
                }
                VerifyOutcome.SignInRequired -> goToLogin()
                VerifyOutcome.NeedsInternet ->
                    showProblem("Connect to the internet to verify your licence.", canRetry = true)
                is VerifyOutcome.Failure -> showProblem(outcome.message, canRetry = true)
            }
        }
    }

    private fun goToLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java).putExtra(EXTRA_START_VOICE_SESSION, startVoiceSession())
        )
        finish()
    }

    private fun showLoading() {
        orb.setState("thinking")
        status.text = "Checking your licence\u2026"
        progress.visibility = View.VISIBLE
        retry.visibility = View.GONE
        signOut.visibility = View.GONE
    }

    private fun showProblem(message: String, canRetry: Boolean) {
        orb.setState("idle")
        status.text = message
        progress.visibility = View.GONE
        retry.visibility = if (canRetry) View.VISIBLE else View.GONE
        signOut.visibility = if (LicenseManager.currentUser(this) != null) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_START_VOICE_SESSION = "hope_license_start_voice_session"

        /**
         * Used by MainActivity when it is opened without a valid licence (recents, assistant gesture...).
         * Only the known "start voice session" flag is carried over, never a whole Intent, so another app
         * cannot use this exported screen to launch HOPE's private components.
         */
        fun redirect(from: Activity, original: Intent?) {
            val startVoice = original?.getBooleanExtra(
                HopeVoiceInteractionSessionService.EXTRA_START_ASSISTANT_VOICE_SESSION,
                false
            ) == true
            from.startActivity(
                Intent(from, LicenseGateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_START_VOICE_SESSION, startVoice)
            )
        }

        /** Opens the existing HOPE main screen and closes the licence screens. */
        fun openMain(from: Activity, startVoice: Boolean) {
            from.startActivity(
                Intent(from, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(HopeVoiceInteractionSessionService.EXTRA_START_ASSISTANT_VOICE_SESSION, startVoice)
            )
            from.finish()
        }
    }
}
