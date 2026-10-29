# HOPE — Changes README (iPhone-style Phone UI + HOPE Smart Auto-Reply)

Ye file sirf **is update mein jo badla** uske baare mein hai. Original project README alag hai (`README.md`).

> **Important — honest status**
> * Ye code is sandbox mein **Android SDK ke bina** likha gaya, isliye **APK yahan build nahi hua** aur **phone par test nahi hua**.
> * Jo test ho paya: Smart Auto-Reply ka logic (`SmartReplyLogic`) real Kotlin compiler se compile karke **38 checks pass** (neeche dekho). Baaki Kotlin code ko type-check kiya (stubs ke saath) aur saare resource references verify kiye (XML sahi, koi missing drawable/layout/id nahi).
> * APK banane ke liye neeche "APK kaise banayein" dekho. Pehli baar build par agar koi compile error aaye to mujhe error ka text bhej do, main fix kar dunga.

---

## 1. Kya-kya naya hai

### A. iPhone-style Phone UI (`telephony/`)

| Screen | Ab kaisa hai |
|---|---|
| **Keypad (dial)** | iOS keypad: gol keys, digit ke neeche ABC/DEF letters, bada number, hari gol **Call** button, backspace (long-press = sab clear), `0` long-press = `+`. Neeche iOS tab bar. |
| **Contacts** | Bada "Contacts" title, rounded search bar, A–Z grouped list (rounded cards), A-Z jump strip, row par tap = details, neela phone icon = call. |
| **Recents** | Segmented control **All / Missed / Incoming / Outgoing**. Missed calls ka naam **laal**. Time/Yesterday/date + ⓘ button (menu). Row tap = call. |
| **Incoming call** | Full-screen dark gradient, bada caller naam, "mobile", neeche **Message** shortcut aur gol laal **Decline** / hara **Accept**. |
| **Outgoing call** | Naam + "calling…", Mute / Keypad / Speaker grid, laal End button. |
| **Active call** | Naam + live timer, 3×2 gol grid: mute, keypad, speaker, add call, hold, merge/contacts. On hone par button safed ho jata hai (iOS jaisa). Laal End button. |
| **In-call keypad** | Dark rounded panel, iOS-style gol keys, tone bhejte waqt typed digits dikhte hain. |
| **Caller popup / background UI** | Call aane par heads-up banner: avatar, naam, "mobile", gol **Decline/Accept**. Call chalte waqt background notification: naam + **live timer** + gol laal End. |
| **Missed call** | Recents mein **Missed** filter + laal naam. (Missed-call notification Android system khud deta hai — default dialer ke liye usse alag se dubara nahi banaya, warna double notification aati.) |

HOPE ka voice-confirmation ("answer" / "decline" bolna) pehle jaisa hi kaam karta hai. Purana orb WebView call screen se hata diya; uski jagah chhota "● HOPE is listening…" indicator aata hai.

### B. HOPE Smart Auto-Reply (`autoreply/`)

* **Ek switch** (Auto-Reply screen ke top par): *HOPE Smart Auto-Reply*.
* On karte hi HOPE khud saari zaroori settings chalu kar deta hai (master, notification reading, WhatsApp / Instagram / SMS, AI replies). **Koi rule ya template banane ki zaroorat nahi.**
* Naya message aane par HOPE:
  1. message + chat ka recent context **padhta** hai,
  2. **samajhta** hai (Hi / Hello / Kaise ho / **Kya karu** / thanks / bye / sawaal / sirf photo...),
  3. **apna naam leke** reply karta hai — jaise *"Hi! Main HOPE hoon, YASH ka assistant. YASH abhi available nahi hain, main aapki kya madad kar sakta hoon?"*
  4. sender ki **bhasha** match karta hai (Hindi / Hinglish / English).
* **Screen off** + **internet on** par bhi chalta hai (notification listener + Gemini; internet na ho to offline Gemma model fallback, AI mode "Automatic" mein).
* Owner ka naam `Settings → userName` se aata hai (default "YASH").
* Agar HOPE ka pehla reply model mein HOPE ka naam bhool jaye, to code khud "HOPE here, <owner>'s assistant." jod deta hai.

**Safety (jaanbujhkar rakhi gayi):**
* Smart Auto-Reply **default OFF** hai — aapko ek baar switch on karna padta hai (bina permission ke koi bhi app aapke naam se msg na bheje).
* **Group chats mein kabhi reply nahi.**
* OTP / bank / password jaise sensitive messages par reply nahi.
* Dusre auto-responder ya HOPE-to-HOPE loop par reply nahi.
* Same chat mein **20 sec cooldown**, **6 reply / ghanta**, aur daily limit.
* Quiet hours, excluded contacts/keywords, duplicate-protection pehle jaise kaam karte hain.
* Reply sirf messaging app ke apne quick-reply action se jaata hai (app kholke Send tap nahi karta).
* Agar aap koi custom rule + template banate ho, to wo us message ke liye Smart Auto-Reply ko override karta hai.
* Voice command "WhatsApp auto-reply start karo" ab rule na hone par bhi kaam karta hai (smart mode on kar deta hai).

**Auto-Reply screen redesign:** iOS Settings jaisa — bada title, hero card + green switch, rounded grouped lists, setup status (notification access, notifications, background running). Purane saare advanced controls "ADVANCED CONTROLS" ke neeche safe hain.

### C. Naam / link badlav (rebranding)

* App ke andar jahan bhi user ko **Jarvis / JARVIS** dikhta tha (toasts, permission cards, notices, errors, chat copy label, persona naam) → **HOPE**.
* Persona ke saved naam: `Jarvis Pro` → `HOPE Pro`, `TechGPT X Mode` → `HOPE Neural Mode`. **Purane users ki saved setting automatic migrate hoti hai** (`AppPreferences.personality`).
* Hands-Free screen ka `instagram.com/techgptx.ai` link → HOPE ka GitHub repo (`github.com/yashpawar9274/HOPE-V3`), label "Star HOPE on GitHub" + naya GitHub icon.
* **Jaanbujhkar nahi badla:**
  * Internal identifiers (package `com.jarvis.assistant`, class names `Jarvis*`, `JarvisBridge`, `window.JarvisOrb`, SharedPreferences file names). Inhe badalne se app update par purana data/settings chale jayenge aur Play/installed app ka ID badal jayega. Ye user ko dikhte nahi.
  * **`LICENSE` aur `security/DeveloperInfo.kt`** — LICENSE (TAMCL v1.0) mein likha hai ki "Tech GPTx" ka attribution **hataya nahi ja sakta**. Maine original author ka credit/license nahi hataya. Agar aap hi Tech GPTx ho ya aapke paas likhit permission hai, to in do jagah ko khud edit kar sakte ho.
  * `theitsash` / Telegram links (`SettingsFragment`, `PrivacyShieldActivity`, `HandsFreeActivity`) — ye aapke apne community links lagte hain; HOPE ke naye links bata do to badal dunga.

---

## 2. Directory / files

```
HOPE-V3-main/
├── README_CHANGES.md                       ← ye file
├── .github/workflows/build-apk.yml         ← NAYA: GitHub par APK banane wala workflow
└── app/src/
    ├── main/
    │   ├── java/com/jarvis/assistant/
    │   │   ├── telephony/
    │   │   │   ├── IosCallStyle.kt         ← NAYA: iOS colours, round buttons, avatar, keypad letters
    │   │   │   ├── HopeDialerActivity.kt   ← REWRITE (UI): keypad / contacts / recents + tab bar
    │   │   │   ├── IncomingCallActivity.kt ← REWRITE (UI): incoming / outgoing / active screen
    │   │   │   └── HopeCallController.kt   ← call banner + ongoing notification (RemoteViews)
    │   │   ├── autoreply/
    │   │   │   ├── SmartReplyLogic.kt      ← NAYA: intent samajhna, HOPE prompt, throttle (pure Kotlin)
    │   │   │   ├── SmartAutoReply.kt       ← NAYA: ek-switch enable/disable, group detect
    │   │   │   ├── AutoReplyCoordinator.kt ← smart mode, HOPE persona, cooldown, group skip
    │   │   │   ├── AutoReplyPreferences.kt ← smart-mode flag
    │   │   │   ├── NotificationIntelligenceProcessor.kt ← rule ke bina bhi smart mode chalta hai
    │   │   │   └── WhatsAppAutoReplyControl.kt          ← voice start/stop smart mode ke saath
    │   │   ├── ui/settings/
    │   │   │   ├── AutoReplySettingsActivity.kt ← iOS-style redesign + smart switch
    │   │   │   ├── HandsFreeActivity.kt         ← techgptx link → HOPE GitHub
    │   │   │   └── PersonaActivity.kt           ← HOPE naam
    │   │   ├── data/model/GeminiLiveModels.kt   ← persona naam + legacy mapping
    │   │   ├── data/preferences/AppPreferences.kt ← persona migration
    │   │   └── (20 files)                       ← Jarvis → HOPE (sirf user-visible text/comments)
    │   └── res/
    │       ├── layout/notification_call_banner.xml  ← NAYA: iPhone-style call banner
    │       ├── layout/item_hope_chat_message.xml    ← (pichhle round se) naya naam
    │       └── drawable/
    │           ├── ic_ios_*.xml (16)                ← NAYE: call, call_end, keypad, mic, speaker, ...
    │           ├── bg_ios_avatar / bg_ios_circle_{red,green}.xml ← NAYE
    │           └── ic_social_github.xml             ← NAYA
    └── test/java/com/jarvis/assistant/autoreply/
        └── SmartReplyLogicTest.kt          ← NAYA: JUnit tests
```

Pichhle round (security / permission / Naira cleanup) ki files bhi zip mein shaamil hain: `SecurityManager.kt` (pinned-signature check), `AndroidManifest.xml` (`READ_PHONE_NUMBERS` hataya), dead `*naira*` resources hataye, `naira_*` colours → `hope_*`.

---

## 3. APK kaise banayein

**Option A — GitHub Actions (sabse aasan, PC par kuch install nahi):**
1. Is poore folder ko apne GitHub repo (`HOPE-V3`) mein push karo.
2. GitHub → **Actions** → *Build HOPE APK* → **Run workflow**.
3. 5–10 min baad run ke neeche **Artifacts → HOPE-debug-apk** se APK download karo. Workflow pehle unit tests chalata hai, phir `assembleDebug`.

**Option B — Android Studio / Windows:**
```
copy local.properties.example local.properties   (sdk.dir sahi karo)
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```
APK: `app\build\outputs\apk\debug\app-debug.apk`
(Release/signed APK ke liye purana `scripts\build-release.ps1` use karo.)

---

## 4. Setup (phone par pehli baar)

1. HOPE ko **default Phone app** banao (Phone UI ke liye).
2. **Auto-Reply** screen kholo → *Grant HOPE notification access* → HOPE ko allow karo.
3. **Allow background running** tap karo (screen off par replies ke liye zaroori).
4. Settings mein **Gemini API key** daalo (ya offline model use karo).
5. Top ka **HOPE Smart Auto-Reply** switch on karo. Ho gaya.

---

## 5. Testing — kya hua, kya baaki

**Ho chuka (is sandbox mein):**
* `SmartReplyLogic` ko real Kotlin compiler se compile karke **38 checks pass**: Hi/Hello/hii bhai/Good morning → greeting, "kya karu" → what-to-do (greeting par bhi jeet-ta hai), kaise ho, thanks, bye, sawaal, sirf photo/emoji, prompt mein HOPE naam + safety rules, HOPE-naam enforcement, 500 char limit, auto-responder/loop detection, 20s cooldown, 6/ghanta cap, 12 ghante intro window.
* Whole project ka Kotlin type-check (Android SDK jar + chhote stubs): meri nayi/badli files mein koi type/syntax error nahi.
* Saare XML well-formed; har `@drawable/@layout/@color/R.id/ItemXBinding` reference maujood.
* Naye icons render karke dekhe (sab shape sahi).
* `SmartReplyLogicTest.kt` (JUnit) repo mein hai — `./gradlew testDebugUnitTest` se chalega.

**Baaki (phone / Gradle par karna hoga — main yahan nahi kar sakta):**
* `assembleDebug` (Android SDK + Gradle chahiye).
* Asli phone par: keypad/contacts/recents ka look, incoming/outgoing/active call, heads-up banner, ongoing notification timer, Dark/Light theme.
* Asli WhatsApp/SMS message par auto-reply (screen off, internet on). Isme device ki battery-optimization / OEM (Xiaomi, Oppo, Vivo) restrictions asar daal sakti hain — "Allow background running" aur app ko "Autostart" dena padta hai.

**Manual test checklist:**
- [ ] Keypad: number likho, backspace, `0` long-press `+`, call lagao.
- [ ] Recents: All/Missed filter, missed ka naam laal, row tap = call.
- [ ] Contacts: search, A-Z strip, phone icon = call.
- [ ] Incoming: lock screen par full-screen; heads-up banner mein Decline/Accept; Message button.
- [ ] Active: mute/speaker toggle safed hota hai, hold, keypad panel, timer chalta hai.
- [ ] Background: home dabao → notification mein live timer + End.
- [ ] Auto-reply: dusre phone se "Hi", "Hello", "kya karu", sirf photo bhejo (screen off) → HOPE naam ke saath reply.
- [ ] Group chat mein msg aane par reply **nahi** aana chahiye.
- [ ] OTP jaisa msg bhejo → reply nahi.

---

## 6. Known limits

* Smart Auto-Reply sirf un apps mein jo notification mein **quick-reply** deti hain (WhatsApp, Instagram, SMS apps).
* AI fail ho jaye (internet + offline model dono nahi) to HOPE **kuch nahi bhejta** (status notification dikhata hai) — galat/khali msg nahi bhejna.
* Dusre log ko pata chalna chahiye ki reply AI se hai — isliye HOPE hamesha apna naam leta hai. Isse mat hatana.
* Package naam `com.jarvis.assistant` rakha gaya hai (upar wajah likhi hai).
