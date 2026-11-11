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

## 2. Connect existing Firebase identity to Supabase RLS

In the Supabase Dashboard, configure Firebase as a **Third-Party Auth provider** for the same Firebase project used by HOPE. This lets PostgREST validate Firebase ID tokens and expose their `sub` claim to RLS. Do not enable Supabase email sign-up for HOPE memory users.

The `hope-memory` Edge Function independently verifies Firebase ID tokens using Google's published signing certificates and checks the configured Firebase project ID, issuer, audience, expiry, auth time, and subject. Its `verify_jwt` gateway check is disabled because Firebase tokens are not Supabase-issued JWTs; no function operation runs before this explicit verification succeeds.

## 3. Apply the database migration

Install the Supabase CLI, authenticate, and link the existing project:

```powershell
supabase login
supabase link --project-ref YOUR_PROJECT_REF
supabase db push
```

`supabase/migrations/202611110001_hope_long_term_memory.sql` creates one transcript table. RLS is enabled and forced; policies use the verified Firebase JWT `sub` claim, stored as text because Firebase UIDs are not necessarily UUIDs. The search RPC is `SECURITY INVOKER`, so it remains subject to those owner policies. No service-role key is used by the app or the Edge Functions.

After enabling Firebase third-party auth, test with two Firebase accounts: each should read, insert, search, and delete only their own rows. An unauthenticated request and a request with another user's token must be rejected.

## 4. Set server-side Firebase and Mem0 configuration

Use the Firebase project ID from the app's `google-services.json` and a Mem0 API key created in Mem0. Set them only as Supabase function secrets:

```powershell
supabase secrets set FIREBASE_PROJECT_ID=YOUR_FIREBASE_PROJECT_ID MEM0_API_KEY=YOUR_MEM0_API_KEY
supabase functions deploy hope-memory
```

`MEM0_API_KEY` is used only by the Edge Function to call Mem0's `/v3/memories/add/`, `/v3/memories/search/`, and user-scoped deletion endpoint. The verified Firebase UID is passed as Mem0 `user_id`; clients cannot supply or override it. Transcript inserts and deletes use the user's Firebase token with the Supabase publishable key, relying on the configured Firebase third-party auth integration and RLS.

The app's existing Gradle config already accepts `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY`. Never share the Mem0 key or put it in `local.properties`, app source, `BuildConfig`, or the APK. The Edge Function does not need a Supabase service-role key.

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
- Supabase project configuration, migration, Firebase third-party auth, Mem0 secrets, and functions must be deployed and tested before cloud memory is considered connected.
- Existing Gemini credentials remain client-held and can be recovered from a compromised or instrumented device. The optional gateway does not silently replace the existing paths.
