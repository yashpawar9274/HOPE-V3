package com.jarvis.assistant.license

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.jarvis.assistant.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Firebase Authentication: Email/Password (sign in, sign up, forgot password) and Google
 * (Credential Manager). Same Firebase project and sign-in methods as the HOPE AI website, so users
 * sign in with the account they bought with.
 */
class LoginActivity : AppCompatActivity() {
    private var createMode = false

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var banner: TextView
    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameInput: TextInputEditText
    private lateinit var emailLayout: TextInputLayout
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var passwordInput: TextInputEditText
    private lateinit var confirmLayout: TextInputLayout
    private lateinit var confirmInput: TextInputEditText
    private lateinit var passwordStrength: TextView
    private lateinit var forgot: TextView
    private lateinit var primary: MaterialButton
    private lateinit var google: MaterialButton
    private lateinit var toggle: TextView
    private lateinit var progress: View
    private lateinit var orb: LicenseOrb

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        title = findViewById(R.id.titleText)
        subtitle = findViewById(R.id.subtitleText)
        banner = findViewById(R.id.bannerText)
        nameLayout = findViewById(R.id.nameLayout)
        nameInput = findViewById(R.id.nameInput)
        emailLayout = findViewById(R.id.emailLayout)
        emailInput = findViewById(R.id.emailInput)
        passwordLayout = findViewById(R.id.passwordLayout)
        passwordInput = findViewById(R.id.passwordInput)
        confirmLayout = findViewById(R.id.confirmLayout)
        confirmInput = findViewById(R.id.confirmInput)
        passwordStrength = findViewById(R.id.passwordStrengthText)
        forgot = findViewById(R.id.forgotText)
        primary = findViewById(R.id.primaryButton)
        google = findViewById(R.id.googleButton)
        toggle = findViewById(R.id.toggleText)
        progress = findViewById(R.id.progress)
        orb = LicenseOrb(findViewById(R.id.orbView)).also { it.start() }

        primary.setOnClickListener { submit() }
        toggle.setOnClickListener {
            createMode = !createMode
            applyMode()
        }
        forgot.setOnClickListener { sendPasswordReset() }
        google.setOnClickListener { signInWithGoogle() }
        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && !createMode) {
                submit()
                true
            } else false
        }
        confirmInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }
        passwordInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updatePasswordStrength(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        applyMode()
        if (!LicenseManager.isFirebaseConfigured(this)) {
            showBanner(
                "Firebase is not set up in this build. Add google-services.json to the app module and rebuild " +
                    "(see LICENSE_GATE_SETUP.md).",
                error = true
            )
        }
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

    private fun applyMode() {
        title.text = if (createMode) "Create your account" else "Welcome back"
        subtitle.text = if (createMode) {
            "Use the same email you used on the HOPE AI website, so your licence key matches."
        } else {
            "Sign in with the account you used to buy HOPE AI."
        }
        primary.text = if (createMode) "Create account" else "Sign in"
        toggle.text = if (createMode) "Already have an account? Sign in" else "New to HOPE? Create account"
        nameLayout.visibility = if (createMode) View.VISIBLE else View.GONE
        confirmLayout.visibility = if (createMode) View.VISIBLE else View.GONE
        passwordStrength.visibility = if (createMode) View.VISIBLE else View.GONE
        forgot.visibility = if (createMode) View.GONE else View.VISIBLE
        nameInput.imeOptions = EditorInfo.IME_ACTION_NEXT
        passwordInput.imeOptions = if (createMode) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
        updatePasswordStrength(passwordInput.text?.toString().orEmpty())
        clearErrors()
    }

    private fun clearErrors() {
        nameLayout.error = null
        emailLayout.error = null
        passwordLayout.error = null
        confirmLayout.error = null
        banner.visibility = View.GONE
    }

    private fun showBanner(message: String, error: Boolean) {
        banner.text = message
        banner.setBackgroundResource(if (error) R.drawable.bg_hope_auth_banner_error else R.drawable.bg_hope_auth_banner_info)
        banner.visibility = View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        orb.setState(if (loading) "thinking" else "idle")
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        primary.isEnabled = !loading
        google.isEnabled = !loading
        toggle.isEnabled = !loading
        forgot.isEnabled = !loading
        nameInput.isEnabled = !loading
        emailInput.isEnabled = !loading
        passwordInput.isEnabled = !loading
        confirmInput.isEnabled = !loading
    }

    private fun firebaseReady(): Boolean {
        if (LicenseManager.isFirebaseConfigured(this)) return true
        showBanner("Firebase is not set up in this build. Add google-services.json and rebuild.", error = true)
        return false
    }

    private fun readEmail(): String? {
        val email = emailInput.text?.toString()?.trim().orEmpty()
        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailLayout.error = "Enter a valid email address"
            return null
        }
        emailLayout.error = null
        return email
    }

    private fun updatePasswordStrength(password: String) {
        if (!createMode) return
        passwordStrength.text = when {
            password.isEmpty() -> "Use at least 6 characters. A longer mix of letters, numbers, and symbols is stronger."
            password.length < 6 -> "Too short - use at least 6 characters."
            password.length < 8 -> "Good start - a longer password is safer."
            password.any(Char::isDigit) && password.any { !it.isLetterOrDigit() } ->
                "Strong password."
            else -> "Add a number or symbol to make this password stronger."
        }
        passwordStrength.setTextColor(
            getColor(if (password.length >= 8) R.color.hope_auth_cyan else R.color.hope_auth_text_muted)
        )
    }

    // ---- email / password -------------------------------------------------------------

    private fun submit() {
        clearErrors()
        if (!firebaseReady()) return
        val fullName = if (createMode) nameInput.text?.toString()?.trim().orEmpty() else ""
        if (createMode && fullName.isEmpty()) {
            nameLayout.error = "Enter your full name"
            return
        }
        val email = readEmail() ?: return
        val password = passwordInput.text?.toString().orEmpty()
        if (password.length < 6) {
            passwordLayout.error = "Use at least 6 characters"
            return
        }
        if (createMode && password != confirmInput.text?.toString().orEmpty()) {
            confirmLayout.error = "Passwords do not match"
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            try {
                val auth = FirebaseAuth.getInstance()
                if (createMode) {
                    val user = auth.createUserWithEmailAndPassword(email, password).await().user
                    if (user != null) {
                        try {
                            user.updateProfile(
                                UserProfileChangeRequest.Builder().setDisplayName(fullName).build()
                            ).await()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Toast.makeText(
                                this@LoginActivity,
                                "Account created, but your name could not be saved.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    auth.signInWithEmailAndPassword(email, password).await()
                }
                onSignedIn()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is FirebaseAuthException && error.errorCode == "ERROR_EMAIL_ALREADY_IN_USE") {
                    createMode = false
                    applyMode()
                }
                showBanner(describe(error), error = true)
            } finally {
                setLoading(false)
            }
        }
    }

    private fun sendPasswordReset() {
        clearErrors()
        if (!firebaseReady()) return
        val email = readEmail() ?: return
        setLoading(true)
        lifecycleScope.launch {
            try {
                FirebaseAuth.getInstance().sendPasswordResetEmail(email).await()
                showBanner("If an account exists for $email, a password reset email is on its way.", error = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showBanner(describe(error), error = true)
            } finally {
                setLoading(false)
            }
        }
    }

    // ---- Google (Credential Manager) --------------------------------------------------

    /** `default_web_client_id` is generated from google-services.json; it is absent until that file is added. */
    private fun webClientId(): String? {
        val id = resources.getIdentifier("default_web_client_id", "string", packageName)
        return if (id == 0) null else getString(id).takeIf { it.isNotBlank() }
    }

    private fun signInWithGoogle() {
        clearErrors()
        if (!firebaseReady()) return
        val serverClientId = webClientId()
        if (serverClientId == null) {
            showBanner(
                "Google sign-in is not configured. Enable Google in Firebase Authentication, add your app's " +
                    "SHA-1 and SHA-256, download a new google-services.json and rebuild.",
                error = true
            )
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            try {
                val option = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(serverClientId)
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = CredentialManager.create(this@LoginActivity).getCredential(this@LoginActivity, request)
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleToken = GoogleIdTokenCredential.createFrom(credential.data)
                    val firebaseCredential = GoogleAuthProvider.getCredential(googleToken.idToken, null)
                    FirebaseAuth.getInstance().signInWithCredential(firebaseCredential).await()
                    onSignedIn()
                } else {
                    showBanner("Google returned an unexpected sign-in type. Please try again.", error = true)
                }
            } catch (error: GetCredentialCancellationException) {
                // The user closed the Google sheet: nothing to show.
            } catch (error: NoCredentialException) {
                showBanner("No Google account was found on this phone. Add one in Settings, or use email and password.", error = true)
            } catch (error: GetCredentialException) {
                showBanner("Google sign-in failed: ${error.message ?: "unknown error"}. Check the SHA-1/SHA-256 in Firebase.", error = true)
            } catch (error: GoogleIdTokenParsingException) {
                showBanner("Google sign-in returned an invalid token. Please try again.", error = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showBanner(describe(error), error = true)
            } finally {
                setLoading(false)
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    private fun onSignedIn() {
        startActivity(
            Intent(this, LicenseGateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(
                    LicenseGateActivity.EXTRA_START_VOICE_SESSION,
                    intent?.getBooleanExtra(LicenseGateActivity.EXTRA_START_VOICE_SESSION, false) == true
                )
        )
        finish()
    }

    private fun describe(error: Throwable): String = when {
        error is FirebaseNetworkException -> "No internet connection. Check your network and try again."
        error is FirebaseTooManyRequestsException -> "Too many attempts. Please wait a few minutes and try again."
        error is FirebaseAuthUserCollisionException ->
            "An account with this email already exists with a different sign-in method. Try Google or your password."
        error is FirebaseAuthException -> when (error.errorCode) {
            "ERROR_INVALID_EMAIL" -> "That email address is not valid."
            "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND" ->
                "Email or password is incorrect. If you signed up with Google, use Continue with Google."
            "ERROR_USER_DISABLED" -> "This account has been disabled. Please contact support."
            "ERROR_EMAIL_ALREADY_IN_USE" ->
                "An account with this email already exists. Sign in with the account you used to buy HOPE AI."
            "ERROR_WEAK_PASSWORD" -> "That password is too weak. Use at least 6 characters."
            "ERROR_OPERATION_NOT_ALLOWED" ->
                "This sign-in method is not enabled in Firebase Authentication."
            else -> "Sign-in failed (${error.errorCode}). Please try again."
        }
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."
    }
}
