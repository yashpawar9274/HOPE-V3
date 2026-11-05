# HOPE — Changes README (iPhone-style Phone UI + HOPE Smart Auto-Reply)

Ye file sirf **is update mein jo badla** uske baare mein hai. Original project README alag hai (`README.md`).

> **Important — honest status**
> * Ye code is sandbox mein **Android SDK ke bina** likha gaya, isliye **APK yahan build nahi hua** aur **phone par test nahi hua**.
> * Jo test ho paya: Smart Auto-Reply ka logic (`SmartReplyLogic`) real Kotlin compiler se compile karke **38 checks pass** (neeche dekho). Baaki Kotlin code ko type-check kiya (stubs ke saath) aur saare resource references verify kiye (XML sahi, koi missing drawable/layout/id nahi).
> * APK banane ke liye neeche "APK kaise banayein" dekho. Pehli baar build par agar koi compile error aaye to mujhe error ka text bhej do, main fix kar dunga.

---

## 0. Ab tak ke saare updates (HOPE 2.0.0) - ek nazar mein

Ye wahi list hai jo app ke **Updates** tab mein bhi dikhti hai. Har item ka detail neeche section A-L mein hai.

| # | Update | Section |
|---|---|---|
| 1 | Login (Firebase Email/Password + Google) aur **licence key gate** (website + Firebase + app ek saath linked), home wale orb ke saath | J, L |
| 2 | **Insaan jaisi chhoti auto-reply** (WhatsApp / Instagram / SMS): 1 line, sender ka style mirror, pehle reply mein "AI assistant" saaf bolna | I-K, L |
| 3 | **HOPE Smart Auto-Reply**: ek switch, koi template nahi, group chats skip, cooldown, "kyun reply nahi gaya" ka reason screen par | C |
| 4 | **Permissions Center** mein naye cards: notifications padhna, files/media, location all the time, SMS + call info | L |
| 5 | **Camera / location foreground-service types** (jab permission mile) aur call ke dauran mic ka sahi handling | L |
| 6 | **Device settings tools** (koi bhi settings screen kholna, brightness / timeout / auto-rotate badalna) aur **UPI payment prepare** (khud pay nahi karti) | L |
| 7 | **Meeting Mode**: call decline + SMS + chat, starred / repeated calls ring, time limit, log, voice se on/off | G |
| 8 | **Screen off par bhi tasks** (screen khud on) | F |
| 9 | **Female voice grammar** + sirf bulane par bolna (30 sec follow-up window) | F |
| 10 | **Default digital assistant** support (ASSIST activity) | F |
| 11 | **iPhone-style phone**: dialer, contacts, recents (missed), incoming / outgoing / active call, banner notification, proximity screen-off, caller photo background, orb | B, H, I |
| 12 | Auto-reply fix: valid **Gemini model names** + fallback | D |
| 13 | **Naya HOPE logo** (adaptive + themed) | F |
| 14 | Security safai: signature check, unused permission / resources hataye, Jarvis -> HOPE naming | pehle round |

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
* Smart Auto-Reply on hone par **koi bhi template (rule ka ya global) use nahi hota** — har reply us message ke hisaab se AI likhta hai.
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

### D. Fix (update 2): "auto reply on hai par reply nahi ja raha"

**Asli wajah:** cloud AI ka model naam `gemini-3.1-flash` galat tha — Google ki official model list mein ye ID hai hi nahi, isliye har AI call 404 se fail hoti thi aur (offline model install na hone se) reply chupchap nahi jata tha.

**Fix:**
* `ai/GeminiTextGeneration.kt`: ab valid models ki list try hoti hai — `gemini-3.5-flash` → `gemini-3.1-flash-lite` → `gemini-2.5-flash` (jo chal jaye use yaad rakhta hai). Galat/blocked API key (401/403) par saaf error batata hai.
* WhatsApp 1:1 chat ko galti se "group" na maane (group tabhi jab title aur likhne wale ka naam alag ho).
* Smart mode mein rule/global **template ab bilkul ignore** hota hai.
* **Reason dikhta hai:** ab har skip/fail ka reason **Auto-Reply screen → "Last auto-reply result"** mein likha aata hai (jaise "Skipped: group chat", "Gemini (gemini-3.5-flash) returned HTTP 403: API key not valid"). Agar phir bhi reply na jaye to wahan jo likha ho wo mujhe bhej do.

### E. Update 3: chhote replies, pehle reply mein HOPE ka intro, repeat nahi

* **Bahut chhota reply:** ab 1 line (max 2), ~200 characters. Lamba aaye to code sentence ke end par kaat deta hai.
* **Pehla reply (us chat mein):** HOPE feminine mein apna parichay deti hai aur usi message mein jawab bhi — jaise *"Hi, main HOPE hoon, YASH ki assistant, unki taraf se aapse baat kar rahi hoon. Haan boliye, kya baat hai?"*
* **Uske baad ke replies:** intro/naam dobara **nahi**; seedha sender ke latest message ka jawab. Agar sender sirf "Hi" dobara bheje to alag tarah se jawab deti hai.
* **Repeat nahi:** HOPE ko us chat ke apne pichhle 5 replies bataye jaate hain ("inhe repeat mat karo"). Phir bhi naya reply purane jaisa nikle to code use pakadta hai (70% se zyada milta-julta), ek baar dobara likhwata hai, aur phir bhi same ho to bhejta hi nahi.
* Intro ka time **save** rehta hai, isliye app restart/band hone ke baad bhi wahi chat mein dobara intro nahi aata (12 ghante baad hi naya intro).
* Test: `SmartReplyLogic` ke ab 56 checks real compiler par pass; naye JUnit tests bhi `SmartReplyLogicTest.kt` mein.

### F. Update 4: female tone, dost se baat par reply, screen-off tasks, logo, default assistant

**1. Female voice par male tone (fix)**
* Wajah: system prompt ke examples mein "kar **raha** hoon", "kar **deta** hoon", "karta hoon" jaise male forms the, isliye female voice (Aoede/Kore/Leda/Zephyr) bhi male grammar bolti thi.
* Fix (`PromptGenerator.kt`): female voice par prompt ke sabse upar **FEMININE VOICE MANDATE** lagta hai (kar rahi hoon, deti hoon, sakti hoon, chahti hoon...), aur prompt ke saare male examples (Hinglish/Hindi/Marathi/Punjabi) automatic female ho jaate hain. Male voice (Puck/Charon...) par kuch nahi badla.
* Default voice ab **Aoede** (female) hai. Notification/call announcements (device TTS) ke liye bhi female-named voice chuni jaati hai, nahi mili to pitch badhaya jaata hai.
* Test: asli prompt generate karke 12 checks pass.

**2. Dost se baat karte waqt HOPE ka reply dena (fix)**
* Wajah 1: "Hey HOPE" ke baad session **30 minute** khula rehta tha aur user ki *kisi bhi* awaaz se refresh hota tha. Wajah 2: prompt mein likha tha "agar koi phone ki taraf bol raha ho to HAMESHA jawab do".
* Fix: ab HOPE ke aakhri reply / task ke baad sirf **30 second ka follow-up window** hai (isme "Hey HOPE" dobara bolna nahi padta). Uske baad HOPE wake-word standby mein chali jaati hai aur sirf "Hey HOPE" sunti hai. Task chalte waqt, bolte waqt ya soch-te waqt standby nahi hota.
* Prompt ka naya **ADDRESSING** rule: sirf tab bolo jab (a) "HOPE" naam liya ho, (b) HOPE ke reply ke turant baad follow-up ho, ya (c) HOPE ke sawaal ka jawab ho. Dost/family/phone-call/TV se baat par **bilkul chup**, "sorry"/"samajh nahi aaya" bhi nahi. Shak ho to chup.

**3. Screen off par bhi tasks**
* Naya `util/ScreenWaker.kt`: jab bhi koi screen wala task aata hai (app kholna, click, type, scroll, YouTube, WhatsApp, search, screenshot), HOPE pehle **khud screen on** karti hai (wake lock) aur task chalte waqt on rakhti hai; `task_completed` par chhod deti hai. Call, volume, torch, location, media control jaise tasks screen off par seedhe chalte hain.
* **Limit (Android security):** agar phone **PIN / pattern / password / fingerprint** se locked hai to HOPE use khud unlock **nahi kar sakti** (aur kabhi PIN save nahi karti). Us case mein screen on karke HOPE bolti hai "phone unlock kar dijiye, phir 'continue' boliye". Task ek baar unlock karne ke baad aage chalta hai. Bina lock (ya Smart Lock jab trusted) wale phone par poora hands-free chalta hai.
* Mic ke liye background mein foreground service chalti hi hai; battery optimization "Unrestricted" aur "Display over other apps" allow rakhna zaroori hai (Xiaomi/Oppo/Vivo par Autostart bhi).

**4. App logo**
* Naya HOPE logo: dark indigo gradient, glowing ring, "H" jiske beech haath uthaye insaan + ugta suraj ("hope"). Adaptive icon (round/squircle sab), purane PNG fallbacks, aur Android 13 **themed (monochrome)** icon bhi. Sirf logo badla, baaki kuch nahi.
* Aapke paas apna logo file ho to bhej do, usse replace kar dunga.

**5. Default assistant**
* Pehle `VoiceInteractionService` to tha lekin Android ke **ASSISTANT role** ke liye ek `ACTION_ASSIST` activity chahiye hoti hai jo missing thi (isliye HOPE list mein nahi aati/select nahi hoti thi). Ab jodi: `voice/HopeAssistActivity.kt` (ASSIST + VOICE_COMMAND), `voice/HopeRecognitionService.kt`, aur `hope_voice_interaction_service.xml` mein `recognitionService` + keyguard se launch support.
* Set kaise karein: HOPE **Settings → Default assistant** (ya phone Settings → Apps → Default apps → Digital assistant app → HOPE). Uske baad power-button long-press / home long-press / "assist" gesture par HOPE khulti hai aur voice session shuru hoti hai.
* Dhyan: kuch phones (Samsung/Xiaomi) par long-press gesture ko Settings mein "Digital assistant" par set karna padta hai.

**Test status (update 4):** prompt ke 12 + reply-logic ke 52 checks asli compiler par pass; XML/manifest/resource references verify; naya Kotlin type-check. Phone par abhi bhi test nahi hua (ye sandbox mein possible nahi), isliye neeche ka checklist chalao:
- [ ] Female voice se baat karo: "mera naam ... hai, ek kaam karo" -> HOPE "kar rahi hoon / kar deti hoon" bole.
- [ ] "Hey HOPE" bolo, jawab ke baad 40 sec dost se baat karo -> HOPE na bole.
- [ ] Screen off + unlocked (ya swipe-only lock) par "YouTube kholo" -> screen on hoke kaam ho.
- [ ] Pattern/PIN lock par wahi bolo -> HOPE unlock ko kahe.
- [ ] Phone Settings -> Default digital assistant -> HOPE dikhe aur select ho.
- [ ] Launcher mein naya logo, Android 13 themed icon on karke bhi.

### G. Update 5: Meeting Mode (call khud handle karna)

**Sawaal: kya HOPE call khud receive karke awaaz mein baat kar sakti hai (aapki apni SIM par)?**
Seedha jawab: **nahi — ye Android allow hi nahi karta.** Normal SIM call mein koi bhi app na caller ki awaaz sun sakta hai, na call mein bol sakta hai (call audio sirf system/phone app ke paas hai; Android 9-10 se ye aur band hua). Speakerphone + mic ka jugaad technically hota hai, lekin meeting mein poori awaaz bahar aati hai (aapka maqsad hi khatam) aur quality/echo bhi kharab. Isliye maine jo cheez **sach mein reliable** hai wo banayi:

**Meeting Mode kya karta hai (Settings -> Meeting Mode, ya "HOPE, meeting mode on karo"):**
1. Call aate hi HOPE use **bina ring kiye decline** kar deti hai.
2. Aapki **apni SIM** se caller ko **SMS** bhejti hai (AI likhta hai, aapke prompt ke hisaab se): *"Namaste, main HOPE hoon, YASH ki AI assistant. YASH abhi meeting mein hain... aap yahin batayiye kya kaam hai, main unhe bata dungi."*
3. Caller SMS/WhatsApp par jawab de to **Smart Auto-Reply** use us situation ke saath jawab deti hai (naam, kaam poochna, message note karna) — **aapka manual prompt** bhi isme judta hai.
4. Aapko notification + **Recent activity** list milti hai: kisne call kiya, HOPE ne kya bheja, caller ne kya likha aur HOPE ne kya kaha.
5. **Emergency safety:** wahi banda **5 minute mein dobara call** kare to ring hogi; **starred contacts** hamesha ring honge (dono options band kar sakte ho).
6. Time-limited: 30 min / 1 ghanta / 2 ghante / jab tak band na karo. Time khatam hote hi mode apne aap band hota hai. (Smart Auto-Reply agar Meeting Mode ne on kiya tha to wapas pehle jaisa ho jata hai.)
7. Situation: **Meeting / Sleeping / Busy** — SMS aur replies isi ke hisaab se.
8. **Aapka prompt:** Settings -> Meeting Mode -> "How HOPE should talk" (max 1200 characters), jaise *"Formal rehna. Caller ka naam, company aur kaam poochna. Delivery ho to guard ko dene ko bolna."* Ye SMS aur har reply ke prompt mein jata hai (safety rules ke neeche, yaani aapka prompt unhe override nahi kar sakta).

**Zaroori setup:** HOPE = default Phone app, SMS permission, Notification access, (Contacts permission = naam aur starred ke liye). Meeting Mode screen par "SETUP" mein har cheez ka status dikhta hai.

**HOPE khud call karke baat karna:** wahi limit — awaaz mein baat nahi ho sakti. HOPE aapke bolne par **message** (WhatsApp/SMS) bhej sakti hai. Agar aapko sach mein AI-awaaz wali call chahiye, to ek alag raasta hai: phone ki **call forwarding** ek cloud voice-agent number (jaise Twilio/Vapi + Gemini Live) par — ye paid server-side setup hai, is app ke andar nahi ho sakta; chaho to uska design bata dunga.

**Limits (honest):**
* Sirf **SIM/phone calls** screen hote hain. WhatsApp/Telegram ki calls nahi.
* Dual-SIM mein SMS **default SMS SIM** se jata hai (call kis SIM par aayi us se nahi).
* SMS ka kharcha aapke plan ke SMS jaisa; **SEND_SMS** permission sideloaded APK ke liye theek hai, Play Store ke liye alag policy form lagta hai.
* Hidden/private number par SMS nahi ja sakta (sirf decline + notification).
* HOPE ko default Phone app hona zaroori hai, warna system calls HOPE tak aati hi nahi.

**Nayi files:** `meeting/MeetingModeLogic.kt` (rules, pure), `MeetingModePreferences.kt`, `MeetingCallScreener.kt`, `MeetingModeEndReceiver.kt`, `MeetingNotifications.kt`, `ui/settings/MeetingModeActivity.kt`, settings row + `set_meeting_mode` voice tool, `HopeInCallService` hook, manifest (SEND_SMS, activity, receiver), tests `MeetingModeLogicTest.kt`.

**Test status:** `MeetingModeLogic` ke 30 + reply-logic ke 56 checks asli compiler par pass; XML/manifest/resources verify; naya Kotlin type-check (sirf stub-kami wale errors). **Phone par test nahi hua** — checklist:
- [ ] HOPE default Phone app; Meeting Mode on (SMS permission do).
- [ ] Kisi dusre number se call karo -> phone ring na kare, call decline ho, us number par HOPE ka SMS pahunche.
- [ ] Usi number se 2 minute mein dobara call -> is baar ring.
- [ ] SMS ka jawab do ("Rahul bol raha hoon, kal ka kaam") -> HOPE meeting-context mein reply kare, aapka prompt follow kare.
- [ ] Recent activity mein sab dikhe; time khatam hone par mode off.
- [ ] "HOPE, main meeting mein hoon, 1 ghante ke liye mode on karo" -> chalu ho.

### H. Update 6: call screen fix (buttons, screen-off at ear, ORB, caller background)

**1. Active / outgoing call par buttons nahi dikh rahe the (fix)**
* Wajah: buttons ek `GridLayout` ke weighted cells mein the (width 0 + weight), jo kuch devices par zero-size render hote hain. Saath hi outgoing call ke pre-connect states (NEW / PULLING / SELECT_PHONE_ACCOUNT) par code "Waiting..." dikha kar buttons chhupa deta tha.
* Fix: ab buttons simple `LinearLayout` rows (3 per row, equal weight) mein hain, jo har device par render hote hain. Poora screen ek `ScrollView` mein hai, isliye chhoti screen par bhi buttons kabhi neeche nahi katte. Chhoti screens (< 700dp) par buttons thode chhote hote hain.
* **Active call:** mute, keypad, speaker, add call, hold, merge/contacts + laal End.
* **Outgoing call:** wahi 6-button grid. Jo abhi kaam nahi kar sakte (keypad, add call, hold) **grey** dikhte hain, chhupte nahi; mute/speaker/contacts chalte hain.

**2. Kaan par phone lagane par screen off nahi hoti thi (fix)**
* Wajah: call screen `FLAG_KEEP_SCREEN_ON` laga ke rakhti thi aur proximity wake lock kahin tha hi nahi.
* Fix: naya `CallProximityLock` (`telephony/CallScreenSupport.kt`). Call connect/dial hone par aur **earpiece** use hone par proximity screen-off lock lagta hai (kaan ke paas screen off, hatane par on). **Speaker / Bluetooth / wired headset** par ye lock hat jata hai aur screen on rehti hai. Ringing mein screen on rehti hai. Lock `onPause`/`onDestroy`/call end par release hota hai.
* Agar phone mein proximity sensor wake-lock supported nahi hai to ye silently skip hota hai.

**3. ORB har call screen ke beech mein**
* Pehle wala HOPE ORB (`call_orb.html`, transparent) wapas laga diya: **incoming** ("ringing"), **outgoing** ("thinking/connecting"), **active**, **hold** ("idle"), **end** ("disconnected"), voice-confirmation sunte waqt "listening". ORB ki height screen ke hisaab se apne aap set hoti hai (min 120dp).

**4. Caller ka background**
* Caller ka **contact photo** blur + dim hoke poore screen ka background banta hai (naam ke niche se dark scrim taaki text/buttons saaf dikhein).
* Photo na ho (ya unknown number) to **naam se bana hua apna rang-gradient** aata hai (har caller ka alag, par har baar same).
* Photo ke liye Contacts permission chahiye.

**Files:** `telephony/IncomingCallActivity.kt` (UI), `telephony/CallScreenSupport.kt` (NAYA: proximity lock + caller background).

**Test status:** naya Kotlin type-check (sirf `androidx.webkit`/AlertDialog stub-kami wale errors, wahi purana WebView code). **Phone par test nahi hua.** Checklist:
- [ ] Active call: 6 buttons dikhein; mute/speaker/hold safed hokar toggle hon.
- [ ] Outgoing call: 6 buttons (3 grey) dikhein.
- [ ] Phone kaan par lagao: screen off; hatao: on. Speaker on karo: screen on rahe.
- [ ] Incoming / outgoing / active teeno par beech mein ORB aaye.
- [ ] Photo wale contact ka blurred background; bina photo wale ka rang-gradient.
- [ ] Chhoti screen / landscape par buttons kate nahi (scroll ho jaye).

### I. Update 7: ORB fix (native orb)

* **Problem:** call screen par orb ki jagah sirf background dikh raha tha.
* **Wajah:** purana orb three.js (WebGL) particle orb tha jo WebView ke andar transparent canvas par chalta hai. Kai phones par (hardware layer + transparent WebGL + additive blending) ye blank render hota hai, aur kahin error bhi nahi dikhta.
* **Fix:** orb ab **native Android view** hai: `telephony/HopeOrbView.kt` (WebView/WebGL ki zaroorat nahi, har phone par aur lock screen par bhi chalta hai).
  * Glassy sphere (upar-left highlight), glow, ghoomte inner bands aur rim-light, 54 orbiting particles (kuch sphere ke peeche, kuch aage), ripples.
  * States: **ringing** (cyan, tez ripples), **listening** (green), **connecting/outgoing** (purple), **active** (blue, shaant breathing), **hold** (amber, dim), **ended** (laal, fade).
  * States ke beech colour/pulse smoothly badalte hain. Preview: `HOPE-orb-preview.png`.
* `IncomingCallActivity.kt` se WebView hataya; `call_orb.html` asset ab call screen mein use nahi hota (baaki jagah purane orb ke liye rakha hai).
* Test: `HopeOrbView` real `android.jar` ke against compile hua (koi error nahi). **Phone par test baaki.**

### J. Update 8: Login + licence-key gate (SaaS)

Poori detail aur setup steps: **`LICENSE_GATE_SETUP.md`** (Firebase console, Android Studio, SHA keys, test checklist).
* **Launcher** ab `license/LicenseGateActivity` hai: login nahi -> Login (Firebase Email/Password + Google via Credential Manager) -> `POST /api/verify` -> licensed ho to purana `MainActivity`; warna Key screen (`POST /api/activate`).
* **Key screen:** `HOPE-XXXXX-XXXXX-XXXXX-XXXXX` auto-format (paste ok, O->0, I/L->1), Unlock, Buy HOPE AI, Sign out; server ka `message` har error par dikhta hai.
* **LicenseManager:** deviceId (random UUID, EncryptedSharedPreferences), 401 par token refresh + 1 retry, licenceToken (ES256 JWT) offline cache, public key (assets ya server se cache), 24h re-verify, clock-rollback check.
* **MainActivity guard** (2 chhoti jagah) taaki recents/assistant gesture se gate bypass na ho. Baaki HOPE features ko haath nahi lagaya.
* **Test:** JWT/JSON/key-format logic asli compiler par 51 checks + repo ka `LicenseCoreTest` (7) pass; naya Kotlin type-check (Firebase/OkHttp stubs ke saath). **Gradle build aur phone par test baaki.**

### K. Update 9: Auto-reply ab insaan jaisi (par AI hone ka saaf pata chalta hai)

**Prompt (`SmartReplyLogic.kt`) mein naye rules:**
* **Insaan ki tarah texting:** relaxed, warm, roz ke shabd ("achha", "arre", "haan", "theek hai", "oh nice"), chhote vakya. **Call-centre wale shabd mana:** "How may I assist you", "Thank you for reaching out", "Certainly", "Kindly", "I understand your concern". Har reply ek hi tarah se shuru nahi hoga.
* **Sender ko mirror karna:** unki language/script (Hindi / Hinglish / English), unki length (chhota msg = chhota reply), tone aur emoji (zyada se zyada 1 emoji, sirf jab sender ne bheja ho). App sender ke msg ka style khud detect karke model ko batata hai (jaise "Hinglish, chhoti lowercase line, emoji ke saath").
* **Specific jawab:** sender ne jo detail/naam/jagah/feeling bataya uspar react, generic nahi. Zyada se zyada ek chhota follow-up sawaal.
* **AI hone ki imandari:** pehle reply mein saaf likha jata hai, jaise *"Hi, main HOPE hoon, YASH ki AI assistant, unki taraf se baat kar rahi hoon..."*. Code check karta hai ki "AI" shabd aaya ya nahi; na aaya to "assistant" ko "AI assistant" bana deta hai ya "(Main YASH ki AI assistant hoon.)" jod deta hai. Koi pooche "tum insaan ho / bot ho?" to HOPE seedha batati hai ki wo AI assistant hai. Wo kabhi insaan, shareer, location, khana-safar jaisi nijee baatein nahi kehti.
* Pehle jaisi baaki cheezein: female grammar, repeat nahi, chhota (~200 char), group chats mein reply nahi, safety rules.

**Code-level safai (`cleanForChat`):** markdown/lists hata ke ek line, "HOPE:" label, "Certainly!" / "As an AI," jaise stiff opener hatana, "!!!!!" ko "!!", emoji maximum 2.

**Typing delay:** reply bhejne se pehle insaan jaisa wait: ~1.2 sec + 45 ms har character (1.5-8 sec), thoda random. Pehle jaisa `Reply delay` setting bhi chalta hai.

**Variety:** Gemini temperature 0.7 -> 0.85 (reply thode alag-alag aate hain).

**Likely tone (udaharan, model ka asli output nahi):**
* Sender: "hi" -> "Hi! Main HOPE hoon, YASH ki AI assistant, unki taraf se baat kar rahi hoon. Bolo, kya chal raha hai?"
* Sender: "bhai kal milte hain? 😄" -> "Haha achha plan! YASH abhi busy hain, main unhe bata dungi, wo khud confirm karenge." (pehle reply mein AI intro bhi judta hai)
* Sender (agla msg): "ok pakka?" -> "Haan, bol dungi unhe. Kuch aur batana hai?"

**Test:** reply-logic ke 81 checks (asli compiler par) + `SmartReplyLogicTest` ke 17 tests pass; type-check clean. **Asli WhatsApp par reply ka lehja model par bhi depend karta hai** (usse phone par dekh ke bata dena, main prompt tune kar dunga).

### L. Update 10: short texting, permissions, camera/mic, settings + payments, licence orb

**1. Texting aur chhoti (WhatsApp / Instagram)**
* Limit **200 -> 120 characters** (pehla reply 180). Prompt mein: "ek chhoti line, aam taur par 12 shabd se kam, jaise asli insaan WhatsApp / Instagram par likhta hai; do bahut chhote vakya sirf zaroorat par; koi paragraph / list nahi."
* Pehle jaisa: AI hone ka saaf zikr pehle reply mein, sender ka style mirror, repeat nahi, typing-delay.

**2. Permissions (Permissions screen mein naye cards)**
* **Read notifications** (notification access), **Files and media** (photos / videos / audio + "all files access"), **Location all the time** (background), **Messages and call info** (SMS, call log, phone state), aur optional **Advanced system settings** (adb command copy karne ka button).
* "GRANT ALL PERMISSIONS" ab runtime permissions ek saath maangta hai, phir har dabane par agla Android settings page kholta hai: Accessibility -> Notification access -> Display over other apps -> Modify system settings -> All files access -> Location all the time -> Battery.
* **Sach:** Android kisi app ko ye permissions khud nahi de sakta. HOPE inhe maang sakti hai aur sahi Settings page khol sakti hai, par **switch aapko dabana hota hai**. Manifest mein ye sab declare ho gayi hain: `READ_MEDIA_*`, `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_LOCATION`, `WRITE_SECURE_SETTINGS` (ye sirf adb se milti hai).
* **Location:** sirf permission add hui hai. HOPE location ka koi history / continuous tracking record **nahi** karti; permission se bas "main kahan hoon" jaise kaam screen band hone par bhi chalte hain.
* Play Store par `MANAGE_EXTERNAL_STORAGE` aur background location ke liye alag declaration form lagta hai (APK sideload par nahi).
* adb command (sirf agar advanced system settings chahiye): `adb shell pm grant com.jarvis.assistant android.permission.WRITE_SECURE_SETTINGS`

**3. Camera aur microphone**
* Service ab permission milne par **camera** aur **location** foreground-service types bhi maangti hai (pehle sirf microphone / media). Android 14 mein bina is type ke background se camera nahi chalta, isliye "camera access nahi ho pa raha" ka ek kaaran ye tha. Agar Android extra type refuse kare to service chhote set par girti hai, microphone service hamesha chalti rehti hai.
* **Call ke dauran mic:** Android ek chalte phone call ko microphone de deta hai, isliye koi bhi normal app call ke time aapki awaaz nahi sun sakti. Ye Android ki rok hai, HOPE ise bypass **nahi** kar sakti. HOPE call ke dauran listening pause karti hai (pehle se tha) aur ab prompt mein bhi ek chhota jawab hai ki mic call ke dauran available nahi; call ke baad wapas chalti hai.

**4. Device system settings aur payments**
* Naye voice tools: `open_system_settings` (Wi-Fi, Bluetooth, airplane, display, sound, battery, location, NFC, apps, notification access, accessibility, default apps, date, language, storage, privacy, security, mobile network, developer options, all settings) aur `set_system_setting` (**brightness %**, **screen timeout**, **auto-rotate**; "Modify system settings" permission chahiye, nahi ho to uska page khud kholti hai).
* Android Wi-Fi / Bluetooth / airplane / mobile-data ke switches app se flip karne nahi deta; isliye HOPE sahi screen (ya quick panel) kholti hai aur aapko ek tap karna hota hai. HOPE "ho gaya" tabhi bolti hai jab tool success de.
* **Payments:** `prepare_upi_payment` sirf aapke UPI app mein payee + amount bhar ke screen kholta hai. HOPE pehle payee aur amount **bol kar** aapki "haan" ka intezaar karti hai (`confirmed_by_owner`), phir hi tool chalta hai. **Paisa aap apne UPI PIN / fingerprint se bhejte ho.** HOPE kabhi PIN / OTP nahi bharti, payment poora nahi karti, kisi caller / message ke kehne par pay nahi karti, aur 1 lakh se upar ke payment refuse karti hai. (Banking apps accessibility automation ko block karte hain aur use bypass karna unsafe hota; isliye ye design jaanbujhkar rakha hai.)

**5. Licence screens ka orb (home jaisa)**
* Login, Licence key aur Gate screens par ab wahi **`orb.html`** orb hai jo HOME screen par dikhta hai, HomeFragment jaisi exact WebView settings ke saath (transparent hardware layer, file access, `orb.html?embedded=1`). States: idle (ruka hua), thinking (jab server check chal raha ho), dark theme.

**6. Bugs jo mile aur fix hue (is round mein)**
* **Service file duplicate ho gayi thi** (meri edit ne ek function dhoondhte waqt galat jagah match kar li aur ~550 lines duplicate ho gayi). Type-check ne pakda, file pichhle sahi version se restore karke edit safe anchors ke saath dobara lagayi, aur ab poore project par duplicate / brace-imbalance scan clean hai.
* Auto-reply ke tests naye (chhote) limits par update hue.
* Version **1.9.6 -> 2.0.0** (code 153), Updates tab mein 14 naye items.

**Test (is round ka):** reply-logic (82), licence (51), settings + payment (26), meeting, prompt generator harness sab pass; repo ke JUnit: SmartReplyLogicTest 17, LicenseCoreTest 7, SettingsAndPaymentTest 5, MeetingModeLogicTest 7 pass. XML / manifest / resources verify. Naya Kotlin type-check (stubs ke saath) mein naye code par koi error nahi. **Gradle build aur asli phone par test baaki** (is environment mein Android SDK nahi hai).

---

## 2. Directory / files

```
HOPE-V3-main/
├── README_CHANGES.md                       ← ye file
├── .github/workflows/build-apk.yml         ← NAYA: GitHub par APK banane wala workflow
└── app/src/
    ├── main/
    │   ├── java/com/jarvis/assistant/
    │   │   ├── license/ (12 files)  ← NAYA: login + licence gate; LicenseOrb.kt = home wala orb
    │   │   ├── util/PermissionExtras.kt, SystemSettingsLogic.kt, SystemSettingsController.kt,
    │   │   │   UpiPaymentLogic.kt, UpiPaymentHelper.kt   ← NAYA: permissions, settings, UPI
    │   │   ├── ui/home/UpdatesFragment.kt  ← 14 naye update items
    │   │   ├── meeting/MeetingModeLogic.kt, MeetingModePreferences.kt, MeetingCallScreener.kt,
    │   │   │   MeetingModeEndReceiver.kt, MeetingNotifications.kt   ← NAYA: Meeting Mode
    │   │   ├── ui/settings/MeetingModeActivity.kt ← NAYA: Meeting Mode screen
    │   │   ├── voice/HopeAssistActivity.kt  ← NAYA: ASSIST entry (default assistant)
    │   │   ├── voice/HopeRecognitionService.kt ← NAYA
    │   │   ├── util/ScreenWaker.kt          ← NAYA: screen-off tasks
    │   │   ├── util/PromptGenerator.kt      ← female mandate + addressing rule
    │   │   ├── util/HopeSpeech.kt           ← female TTS fallback
    │   │   ├── background/JarvisConversationService.kt ← 30s follow-up window, screen wake
    │   │   ├── ai/GeminiTextGeneration.kt  ← FIX: valid Gemini model IDs + fallback + clear errors
    │   │   ├── telephony/
    │   │   │   ├── HopeOrbView.kt          ← NAYA: native ORB (call screens)
    │   │   │   ├── CallScreenSupport.kt    ← NAYA: proximity screen-off lock + caller background
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
    │       ├── mipmap-*/ic_launcher*.png + mipmap-anydpi-v26   ← NAYA LOGO
    │       ├── drawable/ic_launcher_background_hope.xml, ic_launcher_monochrome.xml ← NAYE
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
* `SmartReplyLogic` ko real Kotlin compiler se compile karke **52 checks pass**: Hi/Hello/hii bhai/Good morning → greeting, "kya karu" → what-to-do (greeting par bhi jeet-ta hai), kaise ho, thanks, bye, sawaal, sirf photo/emoji, prompt mein HOPE naam + safety rules, HOPE-naam enforcement, 500 char limit, auto-responder/loop detection, 20s cooldown, 6/ghanta cap, 12 ghante intro window.
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
