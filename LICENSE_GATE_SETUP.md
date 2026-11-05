# HOPE licence gate: setup checklist

Implements `hope_license_api.md` in the Android app: **Login (Firebase) -> Licence key -> HOPE**.
Package / applicationId: `com.jarvis.assistant` (unchanged). Backend: `https://hope-ai.sites.tab.bot`.

## 1. Firebase console (project `hopev3-c0ff4`)
1. **Project settings -> Your apps -> Add app -> Android.** Package name: `com.jarvis.assistant`.
2. **Add SHA-1 and SHA-256** of every key that signs a build you will run (needed for Google sign-in):
   * Debug key: `./gradlew :app:signingReport` (or Android Studio: Gradle -> app -> Tasks -> android -> signingReport), or
     `keytool -list -v -alias androiddebugkey -keystore ~/.android/debug.keystore -storepass android -keypass android`
   * Release key: `keytool -list -v -keystore <your-release.jks> -alias <alias>`
   * If you publish on Google Play with Play App Signing: Play Console -> App integrity -> copy the **App signing key** SHA-1/SHA-256 too.
3. **Download `google-services.json` AFTER adding the SHA keys** and put it at `app/google-services.json` (next to `app/build.gradle.kts`).
4. **Authentication -> Sign-in method:** enable **Email/Password** and **Google** (set the support email).
5. **Authentication -> Settings -> User actions:** keep **"One account per email address"** on. (The website and the app share this project, so the same email = the same account.)
6. **Authentication -> Settings -> Authorized domains:** `hope-ai.sites.tab.bot` (and the staging domain).
7. Google sign-in error `10` / "developer error" or "no credentials available" = the SHA-1/SHA-256 of the installed build is not in Firebase. Add it and download a fresh `google-services.json`.

## 2. Android Studio
1. Open the project and **Sync Gradle** (adds Firebase BoM 33.7.0, Firebase Auth, Credential Manager 1.3.0, googleid 1.1.1, coroutines-play-services; the Google services plugin 4.4.2 is applied only when `app/google-services.json` exists, so the project still builds without it).
2. Put `google-services.json` in `app/` (step 1.3).
3. **Bundle the licence public key** (recommended). In a terminal:
   `curl -o app/src/main/assets/license_public_key.json https://hope-ai.sites.tab.bot/api/license/public-key`
   The file must be the JWK set returned by the server. If you do not bundle it, the app downloads and caches it the first time a verify/activate succeeds (it is then trusted from that HTTPS response).
4. Run on a phone or emulator **with Google Play services** (for Google sign-in).
5. For testing against staging change `BASE_URL` in `license/LicenseConfig.kt` to `https://hope-ai-staging.sites.tab.bot`.

## 3. GitHub Actions (optional)
* Add repository secret **`GOOGLE_SERVICES_JSON`** = the full content of `google-services.json`; the workflow writes it before building.
* **Important:** a GitHub-built *debug* APK is signed with a fresh debug key on every run, so its SHA-1 changes each time and **Google sign-in will fail** (email/password still works). For Google sign-in on CI builds, sign them with your own fixed keystore (add its SHA-1/SHA-256 to Firebase). Tell me and I will wire the workflow for that.

## 4. How it works (matches the API spec)
| Step | Behaviour |
|---|---|
| Launch | `LicenseGateActivity` is the launcher. Not signed in -> `LoginActivity`. Signed in -> `POST /api/verify {deviceId}`. |
| Licensed | Opens the existing `MainActivity`. `licenseToken` is saved in EncryptedSharedPreferences. |
| `NO_LICENSE`, `DEVICE_NOT_ACTIVATED`, `LICENSE_REVOKED`, `LICENSE_EXPIRED` | `LicenseKeyActivity` (with a message for the reason). |
| Unlock | `POST /api/activate {key, deviceId, deviceName}` with `Authorization: Bearer <Firebase ID token>`. The server's `message` is shown for every error. |
| 401 | The ID token is force-refreshed and the call retried once; a second 401 signs the user out and opens Login. |
| Offline | The cached token is accepted only if: ES256 signature verifies with the public key, `iss == hope-license`, `sub ==` current Firebase uid, `dev ==` this device id, `exp` in the future, and the phone clock was not set back. Otherwise: "Connect to the internet to verify your licence." |
| Re-verify | On every app start, and `MainActivity` re-checks on every resume: if the last verification is older than 24 h and the phone is online, it goes back through the gate. |
| Device id | Random UUID created once, stored in EncryptedSharedPreferences (never IMEI). |
| Guard | `MainActivity` itself checks the licence too, so recents, the assistant gesture and notification taps cannot skip the gate. Only the "start voice session" flag is carried over (never a whole Intent). |

The unlock screens use the **same HOPE ORB as the home screen** (`assets/orb.html`, `LicenseOrb.kt`).

## 5. Files
```
app/src/main/java/com/jarvis/assistant/license/
  LicenseConfig.kt          URLs, intervals
  LicenseModels.kt          VerifyOutcome / ActivateOutcome
  LicenseManager.kt         verify(), activate(), 401 retry, offline grace, guard, sign out
  LicenseApi.kt             OkHttp + coroutines client
  SecureStore.kt            EncryptedSharedPreferences (device id, token, keys, timestamps)
  LicensePublicKeys.kt      bundled asset + cached key set
  Es256JwtVerifier.kt       ES256 JWT verification (java.security only) + token policy
  MiniJson.kt               small strict JSON parser (pure Kotlin)
  LicenseKeyFormat.kt       HOPE-XXXXX-XXXXX-XXXXX-XXXXX formatting / validation
  LicenseOrb.kt             the home-screen orb (orb.html) for these screens
  LicenseGateActivity.kt    launcher gate
  LoginActivity.kt          Email/Password (sign in, sign up, forgot password) + Google (Credential Manager)
  LicenseKeyActivity.kt     key field, Unlock, Buy HOPE AI, Sign out
app/src/main/res/layout/    activity_license_gate.xml, activity_login.xml, activity_license_key.xml
app/src/main/res/values/    themes_license.xml (Theme.Hope.Auth, Material 3 dark purple/blue), colors_license.xml
app/src/main/res/drawable/  bg_hope_auth*.xml
app/src/test/.../license/LicenseCoreTest.kt
Changed: AndroidManifest.xml (launcher moved to the gate), ui/home/MainActivity.kt (guard, 2 places),
         build.gradle.kts (root + app), proguard-rules.pro, .github/workflows/build-apk.yml
```

## 6. Test checklist (on a phone)
- [ ] Fresh install -> Login screen. Wrong password shows a clear message; "Forgot password?" sends the reset email.
- [ ] Create account with an email already used on the website -> "already exists, sign in" and the screen switches to Sign in.
- [ ] Sign in with Google (with your SHA keys registered) and with Email/Password -> same account as the website.
- [ ] Account without a licence -> Key screen with the "no licence" text; "Buy HOPE AI" opens the website.
- [ ] Paste `hope d0j94 40pvm cbfn1 34rhh` -> becomes `HOPE-D0J94-40PVM-CBFN1-34RHH`; Unlock works.
- [ ] Key bought by another account -> `WRONG_ACCOUNT` message from the server. Key on a 2nd phone -> `DEVICE_LIMIT` message.
- [ ] Remove the device on the website dashboard -> next app start goes to the Key screen (`DEVICE_NOT_ACTIVATED`).
- [ ] Airplane mode after a successful verify -> HOPE still opens (cached token, up to 72 h). After the token expires -> "Connect to the internet...".
- [ ] Sign out on the Key screen -> Login. Sign in as a different user offline -> not allowed (`sub` mismatch).
- [ ] Revoke the licence on the website -> next start shows the Key screen with the revoked message.

## 7. Not covered (be aware)
* The gate guards HOPE's **screens**. Background features (call screening, auto-reply, wake-word service) run without UI; if you want them to stop for unlicensed users, add `LicenseManager.guardAllows(context)` checks there (small change, say the word).
* As the API notes say, HOPE uses each user's own Gemini key, so this is a sign-in + key gate; a modified APK can skip a check that runs only on the phone.
* Not compiled with Gradle in my environment (no Android SDK): the new Kotlin was type-checked against `android.jar` with stubs for Firebase/OkHttp/Credential Manager, and the JWT/JSON/key-format logic was run for real (51 checks + the JUnit file). Please run `./gradlew testDebugUnitTest assembleDebug` (or the GitHub Action) and send me any error.
