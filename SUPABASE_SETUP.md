# HOPE cloud long-term memory setup

HOPE uses Supabase as its private transcript database and Edge Function gateway, and Mem0 as its semantic memory extraction/search service. Users do **not** create a Supabase account. The app reuses the existing Firebase sign-in; the verified Firebase UID is the only memory owner ID.

Cloud memory is **off by default**. When the user enables it in **Settings → Neural Memory Bank**, HOPE asks for consent, backs up up to 100 locally retained chat turns already associated with that Firebase account once, and saves later completed text chats and regular voice transcripts. Older untagged history is not copied, to avoid mixing data from different sign-ins. Clipboard requests and automation-only turns are excluded. Audio is never stored. Users can pause cloud memory or clear cloud transcripts and queue deletion of their Mem0 memories from that screen.

## 1. Configure Android's public Supabase endpoint

The project URL and publishable key are public client configuration, not secrets. Set them in the Windows user-level Gradle properties file, outside the repository:

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_KEY
```

Or provide the same names as Gradle environment variables. Rebuild the app after configuration. Never put a Supabase secret/service-role key, `MEM0_API_KEY`, or Gemini server key in Android or Gradle properties.

## 2. Authenticate requests with the existing Firebase account

The Android app sends its Firebase ID token to `hope-memory`. The function verifies the signature using Google's published signing certificates and checks the configured Firebase project ID, issuer, audience, expiry, auth time, and subject. Its `verify_jwt` gateway check is disabled because Firebase tokens are not Supabase-issued JWTs; no operation runs before this explicit verification succeeds.

After verification, the function uses Supabase's server-provided `SUPABASE_SERVICE_ROLE_KEY` to access the database. Every stored or searched owner ID comes only from the verified Firebase token; the request body cannot choose a `user_id`. The service-role key stays inside the Edge Function and must never be added to Android, Gradle properties, or client responses. This server-side pattern avoids requiring a Firebase custom `role: authenticated` claim or a Supabase Third-Party Auth dashboard integration.

## 3. Apply the database migration

Install the Supabase CLI, authenticate, and link the existing project:

```powershell
supabase login
supabase link --project-ref YOUR_PROJECT_REF
supabase db push
```

`supabase/migrations/202611110001_hope_long_term_memory.sql` creates one transcript table with RLS enabled and forced. Owner policies use the Firebase JWT `sub` claim, stored as text because Firebase UIDs are not necessarily UUIDs. The follow-up migration changes the search RPC to accept an owner ID and grants it only to `service_role`; the Edge Function supplies the ID only after verifying the Firebase token. The app never accesses the database directly and never receives the service-role key.

Test with two Firebase accounts: each should save, search, and clear only their own rows. An unauthenticated request must be rejected, and a request body containing a forged owner ID must never affect another user's rows.

## 4. Set server-side Firebase and Mem0 configuration

Use the Firebase project ID from the app's `google-services.json` and a Mem0 API key created in Mem0. Set them only as Supabase function secrets:

```powershell
supabase secrets set FIREBASE_PROJECT_ID=YOUR_FIREBASE_PROJECT_ID MEM0_API_KEY=YOUR_MEM0_API_KEY
supabase functions deploy hope-memory
```

`MEM0_API_KEY` is used only by the Edge Function to call Mem0's `/v3/memories/add/`, `/v3/memories/search/`, and user-scoped deletion endpoint. The verified Firebase UID is passed as Mem0 `user_id`; clients cannot supply or override it. `SUPABASE_SERVICE_ROLE_KEY` is provided to the Edge Function runtime by Supabase and is used only for server-side database access.

The app's existing Gradle config already accepts `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY`. Never share the Mem0 key or put it in `local.properties`, app source, `BuildConfig`, or the APK. Supabase provides the service-role key to the Edge Function runtime; do not copy it into Android or expose it to clients.

## 5. Deploy the optional Gemini gateway

Existing Gemini and Gemini Live requests remain on their current client-side paths. To configure the optional text gateway scaffold:

```powershell
supabase secrets set GEMINI_API_KEY=YOUR_GEMINI_SERVER_KEY
supabase functions deploy hope-ai-gateway
```

This key belongs only in Supabase Function Secrets. The gateway is not wired into HOPE's existing AI path, so this setup does not migrate Gemini Live, image requests, or normal Gemini traffic.

## Behavior and limitations

- Mem0 extraction is asynchronous. A successful save means the transcript is stored and extraction was queued; newly extracted facts may not be searchable immediately.
- Search combines Mem0 memories with matching transcript turns. Semantic recall is not guaranteed to find every conversation verbatim; exact old transcripts remain in the private database and can be searched by text.
- Existing local chat history is capped by the app at 100 turns; only turns tagged to the currently signed-in Firebase account can be backed up when cloud memory is first enabled. Older untagged history and earlier deleted local turns cannot be recovered.
- Clearing memory deletes transcripts immediately and requests user-scoped Mem0 deletion; Mem0 processes deletion asynchronously.
- The production project has both migrations and the `hope-memory` Edge Function deployed. Cloud memory still needs an authenticated save/search test from the Android app; Mem0 extraction is asynchronous.
- Existing Gemini credentials remain client-held and can be recovered from a compromised or instrumented device. The optional gateway does not silently replace the existing paths.
