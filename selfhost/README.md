# Lentra — Self-Hosted Backend

Your own private backend, wire-compatible with the Nuvio/Lentra mobile app.

The app **already supports this natively** — there is a first-class self-hosting
protocol in `composeApp/src/commonMain/kotlin/com/nuvio/app/core/network/ServerDiscovery.kt`.
You do not need to patch the app. You need to (a) stand up a Supabase-compatible
backend, (b) serve a discovery document, (c) build the **full** flavor.

---

## Architecture

```
┌─────────────────────┐      ┌──────────────────────────────────────┐
│  Lentra mobile app  │      │  Your server (Docker)                │
│  (full flavor)      │      │                                      │
│                     │  1   │  nginx  ── /.well-known/nuvio        │
│  Settings →         │─────▶│            (discovery document)      │
│  Connect to server  │      │                                      │
│                     │  2   │  Supabase stack:                     │
│                     │─────▶│    kong (API gateway)                │
│                     │      │    auth  (GoTrue)   ← email+password │
│                     │      │    rest  (PostgREST)← 40 RPC funcs   │
│                     │      │    db    (Postgres) ← schema + RLS   │
│                     │      │    storage, realtime, meta           │
└─────────────────────┘      └──────────────────────────────────────┘
```

Step 1 hands the app the URL + anon key. Step 2 is all normal Supabase traffic.

---

## ⚠️ Prerequisite: you MUST build the `full` flavor

`customServerConnectionsEnabled` gates the entire "connect to custom server" UI.
It is **only true in two of five variants**:

| Variant | `customServerConnectionsEnabled` | Self-host possible? |
|---|---|---|
| `androidFull` | **`true`** | ✅ yes |
| `iosFull` | **`true`** | ✅ yes |
| `androidPlaystore` | `false` | ❌ UI hidden |
| `desktopMain` | `false` | ❌ UI hidden |
| `iosAppStore` | `false` | ❌ UI hidden |

Source: `composeApp/src/*/kotlin/com/nuvio/app/core/build/AppFeaturePolicy.*.kt`

```bash
./gradlew :androidApp:assembleFullDebug -Pnuvio.android.distribution=full
```

If you build `playstore`, the "connect to a server" option simply will not appear.

---

## Step 1 — Boot the stack

```bash
cd selfhost
cp .env.example .env
# EDIT .env — change EVERY secret. At minimum:
#   POSTGRES_PASSWORD, JWT_SECRET, ANON_KEY, SERVICE_ROLE_KEY, DASHBOARD_PASSWORD
openssl rand -base64 48   # use this to generate fresh secrets

docker compose up -d
docker compose ps         # wait until all services are healthy
```

## Step 2 — Load the schema + RPCs

```bash
# 0001 = tables, indexes, RLS.  0002 = the 40 Postgres functions.
docker compose exec -T db psql -U postgres -d postgres < sql/0001_schema.sql
docker compose exec -T db psql -U postgres -d postgres < sql/0002_rpc.sql
```

## Step 3 — Publish the discovery document

Edit `discovery/nuvio.json` and set `publishable_key` to the `ANON_KEY` from your
`.env` (they must match — the app sends it as the `apikey` header).

nginx in the compose file already serves it at `/.well-known/nuvio`. Verify:

```bash
curl -s http://localhost/.well-known/nuvio | jq
```

## Step 4 — Point the app at it

In the app: **Settings → Connect to a server** → enter your host.

- On a **real device**, `http://localhost` will not work — use your machine's LAN
  IP, e.g. `http://192.168.1.50`.
- The app upgrades bare hosts to `https://`, so **include `http://` explicitly**
  for local/dev, or terminate TLS properly.

The app then runs its validation gauntlet (see below). If any check fails it shows
a specific reason — match it against the table in *Troubleshooting*.

## Step 5 — Create an account

The discovery document must advertise `email_password_auth: true`, and the app uses
`auth.signUpWith(Email)` / `auth.signInWith(Email)`. So in your `.env`:

```
ENABLE_EMAIL_SIGNUP=true
ENABLE_EMAIL_AUTOCONFIRM=true    # skip SMTP for dev; wire real SMTP for prod
```

Anonymous sessions also exist (`AuthRepository.signInAnonymously()`), which is the
local-only mode with no sync.

---

## The discovery contract (verified from source)

`GET {host}/.well-known/nuvio` must return JSON that passes **every** rule below,
or discovery is rejected:

```json
{
  "version": 1,
  "service": "nuvio",
  "self_hosted": true,
  "backend_url": "http://192.168.1.50",
  "publishable_key": "<your ANON_KEY>",
  "capabilities": { "email_password_auth": true, "tv_login": false }
}
```

| Rule | Enforced by | Failure shown |
|---|---|---|
| `version` must be exactly `1` | `ServerDiscovery.kt:71` | `UnsupportedVersion` |
| `service` must equal `"nuvio"` (case-insensitive) | `:74` | `WrongService` |
| `self_hosted` must be `true` | `:77` | `NotSelfHosted` |
| `publishable_key` must be non-blank | `:80` | `MissingConfiguration` |
| `capabilities.email_password_auth` must be `true` | `:83` | `UnsupportedAuthentication` |
| `backend_url` must be http/https, **no query string, no fragment, no `@` in authority** | `normalizeBackendUrl` | `MissingConfiguration` |
| body ≤ **64 KB** | `ServerDiscoveryService` | `ResponseTooLarge` |
| HTTP status must be `2xx` | `ServerDiscoveryService` | `HttpError` |
| host must **not** be `api.nuvio.tv` or the compiled-in official URL | `isOfficial()` | `OfficialServer` |
| if requested over `https://`, final URL must still be `https://` (no TLS downgrade redirect) | `ServerDiscoveryService` | `ConnectionFailed` |
| request timeout is **15 s** | `ServerDiscoveryService` | `ConnectionFailed` |

Unknown JSON keys are ignored (`ignoreUnknownKeys = true`), so you can add extras.

---

## Backend surface you must implement

### Auth (GoTrue)
`signUpWith(Email)`, `signInWith(Email)`, `signOut`, plus anonymous sessions.
No magic-link, no OAuth against *your* backend.

### PostgREST — plain tables (2)
`addons`, `plugins` — accessed directly via `from("...")`.

### PostgREST — RPC functions (40)
All args use the `p_` prefix convention. Complete list:

**Sync — push (9)**
`sync_push_addons`, `sync_push_collections`, `sync_push_home_catalog_settings`,
`sync_push_library_items`, `sync_push_plugins`, `sync_push_profile_settings_blob`,
`sync_push_profiles`, `sync_push_watch_progress`, `sync_push_watched_items`

**Sync — pull (12)**
`sync_pull_collections`, `sync_pull_home_catalog_settings`, `sync_pull_library`,
`sync_pull_library_delta`, `sync_pull_profile_locks`, `sync_pull_profile_settings_blob`,
`sync_pull_profiles`, `sync_pull_provider_credentials`, `sync_pull_watch_progress`,
`sync_pull_watch_progress_delta`, `sync_pull_watched_items`, `sync_pull_watched_items_delta`

**Sync — cursors / deletes (7)**
`sync_get_library_delta_cursor`, `sync_get_watch_progress_delta_cursor`,
`sync_get_watched_items_delta_cursor`, `sync_delete_library_items`,
`sync_delete_profile_data`, `sync_delete_watch_progress`, `sync_delete_watched_items`

**Profiles & PIN (5)**
`set_profile_pin`, `verify_profile_pin`, `clear_profile_pin`,
`clear_profile_pin_with_account_password`, `sync_pull_profile_locks`

**Devices & TV login (4)**
`register_current_device`, `start_device_login_session`, `poll_tv_login_session`

**Membership & cosmetics (6)**
`get_my_member_access`, `get_my_membership_overview`, `get_avatar_catalog`,
`get_member_profile_avatar_catalog`, `get_member_profile_background_catalog`

### Argument names in use
`p_profile_id`, `p_platform`, `p_limit`, `p_since_event_id`, `p_keys`,
`p_device_name`, `p_settings_json`, `p_pin`, `p_items`, `p_installation_id`,
`p_device_nonce`, `p_current_pin`, `p_client_version`, `p_client_name`,
`p_since_last_watched`, `p_redirect_base_url`, `p_profiles`, `p_plugins`,
`p_page_size`, `p_page`, `p_origin_client_id`, `p_offset`, `p_entries`,
`p_device_type`, `p_credentials`, `p_collections_json`, `p_code`,
`p_client_max_profiles`, `p_addons`, `p_account_password`

### Response shapes are the column names
RPCs are decoded with `decodeList<T>()` into `@Serializable` models whose
`@SerialName` values **are** the Postgres columns. E.g. `NuvioProfile`
(`features/profiles/ProfileModels.kt`) ⇒ `user_id`, `profile_index`, `name`,
`avatar_color_hex`, `avatar_id`, `avatar_url`, `profile_background_id`,
`profile_background_url`, `uses_primary_addons`, `uses_primary_plugins`,
`pin_enabled`, `pin_locked_until`, `created_at`, `updated_at`.

So the reliable way to derive any remaining shape:

```bash
grep -rn -A20 'rpc("sync_pull_library"' composeApp/src/commonMain
```
…then open the model it decodes into and read the `@SerialName`s.

---

## Status of the SQL in this folder

Honest breakdown of `sql/0001_schema.sql` and `sql/0002_rpc.sql`:

- **Complete & verified** — discovery contract, `profiles`, `library_items`,
  `library_events` (delta cursors), auth wiring, RLS policies, `addons`, `plugins`.
- **Best-effort** — `watch_progress`, `watched_items`, `collections`,
  `home_catalog_settings`, `profile_settings_blob`, `provider_credentials`,
  `devices`, `tv_login_sessions`, `avatars`. Columns inferred from the RPC arg
  names and the Kotlin models; verify each against its decoder before trusting it.
- **Deliberate stubs** — the 6 membership/cosmetic RPCs. These are Nuvio's
  *paid-tier* backend (`get_my_member_access`, supporter wallpapers). There is no
  client-side schema to infer from. Stubs return "no membership", which is the
  correct behavior for a personal server.

Everything is marked inline with `-- VERIFIED:` / `-- INFERRED:` / `-- STUB:`.

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| "Cannot reach servers" still | You didn't rebuild. Config is `const val`, baked at compile time. |
| No "connect to server" option in UI | You built `playstore`/`desktop`/`appStore`, not `full`. |
| `OfficialServer` | Your host resolves to `api.nuvio.tv`. Use a different hostname/IP. |
| `UnsupportedAuthentication` | `capabilities.email_password_auth` isn't `true` in the document. |
| `NotSelfHosted` | You set `self_hosted: false`. It must be `true`. |
| `ConnectionFailed` on a phone | You used `localhost`. Use the LAN IP. |
| Discovery OK but sync 404s | `0002_rpc.sql` not applied, or function arg names mismatch. |
| 401 on every RPC | `publishable_key` in the document ≠ `ANON_KEY` in `.env`. |
| Writes silently ignored | RLS. Confirm `auth.uid()` matches the row's `user_id`. |

---

## Production hardening

This compose file is a **dev/personal** setup. Before exposing it publicly:

1. **TLS** — terminate with a real cert (Caddy/Traefik/Let's Encrypt). The app
   treats `http://` as insecure (`ServerConfiguration.isSecure`) and rejects
   https→http downgrades during discovery.
2. **Rotate every secret** in `.env`. Never ship the defaults.
3. **Backups** — `pg_dump` on a schedule; the DB holds all user watch history.
4. **Rate limits** — the client has its own `BackendRateLimitCoordinator`, but
   protect the origin at Kong/nginx too.
5. **Restrict the dashboard** — don't expose Supabase Studio to the internet.
6. Review every RLS policy in `0001_schema.sql` before real users touch it.

---

## Legal

The app is **GPL-3.0** (`LICENSE`). Your own backend code and data are yours, but
if you distribute a modified client you must honor GPL-3.0 obligations.
Don't reuse Nuvio's trademarks/branding for a public service.
