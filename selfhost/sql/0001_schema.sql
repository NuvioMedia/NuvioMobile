-- ===========================================================================
-- Lentra self-hosted backend — 0001_schema.sql
-- Tables, indexes, Row Level Security.
--
-- Legend:
--   VERIFIED  = column names read directly from a @SerialName in the client,
--               or from an RPC argument name found in the Kotlin source.
--   INFERRED  = plausible from the RPC name/args, NOT confirmed against a
--               decoder. VERIFY before trusting.
--
-- Apply with:
--   docker compose exec -T db psql -U postgres -d postgres < sql/0001_schema.sql
--
-- The supabase/postgres image already creates the roles (anon, authenticated,
-- authenticator, supabase_auth_admin, supabase_storage_admin, postgres).
-- This file only grants to them.
-- ===========================================================================

\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------
-- PROFILES — VERIFIED
-- Columns from NuvioProfile @SerialName values in
-- composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/ProfileModels.kt
-- Decoded by: decodeList<NuvioProfile>() after rpc("sync_pull_profiles")
-- ---------------------------------------------------------------------------
create table if not exists public.profiles (
    id                      uuid primary key default gen_random_uuid(),
    user_id                 uuid not null references auth.users(id) on delete cascade,
    profile_index           integer not null,
    name                    text not null default '',
    avatar_color_hex        text not null default '#1E88E5',
    avatar_id               text,
    avatar_url              text,
    profile_background_id   text,
    profile_background_url  text,
    uses_primary_addons     boolean not null default false,
    uses_primary_plugins    boolean not null default false,
    pin_enabled             boolean not null default false,
    -- Stored as a bcrypt/crypt hash, never plaintext. See verify_profile_pin.
    pin_hash                text,
    pin_locked_until        timestamptz,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    unique (user_id, profile_index)
);

create index if not exists profiles_user_id_idx on public.profiles (user_id);

-- ---------------------------------------------------------------------------
-- LIBRARY ITEMS — VERIFIED
-- Columns from LibrarySyncItem @SerialName values in
-- features/library/sync/SupabaseLibrarySyncAdapter.kt
-- Decoded by: rpc("sync_pull_library") -> decodeList<LibrarySyncItem>()
--
-- Note: `genres` is a JSON array of strings in the wire model, so text[]
-- serializes correctly to ["a","b"] via PostgREST.
-- ---------------------------------------------------------------------------
create table if not exists public.library_items (
    id               uuid primary key default gen_random_uuid(),
    user_id          uuid not null references auth.users(id) on delete cascade,
    profile_id       uuid not null references public.profiles(id) on delete cascade,
    content_id       text not null,
    content_type     text not null,
    name             text not null default '',
    poster           text,
    poster_shape     text not null default 'POSTER',
    background       text,
    logo             text,
    description      text,
    release_info     text,
    imdb_rating      real,
    genres           text[] not null default '{}',
    addon_base_url   text,
    added_at         bigint not null default 0,
    updated_at       timestamptz not null default now(),
    unique (profile_id, content_id, content_type)
);

create index if not exists library_items_profile_idx
    on public.library_items (profile_id);
create index if not exists library_items_user_idx
    on public.library_items (user_id);

-- ---------------------------------------------------------------------------
-- LIBRARY EVENTS — VERIFIED (shape) / INFERRED (columns)
-- Backs sync_pull_library_delta + sync_get_library_delta_cursor.
-- LibraryDeltaSyncItem has `event_id` (bigint) and `operation` (text)
-- on top of every LibrarySyncItem column, and the delta RPCs take
-- p_since_event_id + p_limit. So this is an append-only outbox.
-- ---------------------------------------------------------------------------
create table if not exists public.library_events (
    event_id         bigint generated always as identity primary key,
    user_id          uuid not null references auth.users(id) on delete cascade,
    profile_id       uuid not null references public.profiles(id) on delete cascade,
    operation        text not null check (operation in ('upsert', 'delete')),
    content_id       text not null,
    content_type     text not null,
    name             text not null default '',
    poster           text,
    poster_shape     text not null default 'POSTER',
    background       text,
    logo             text,
    description      text,
    release_info     text,
    imdb_rating      real,
    genres           text[] not null default '{}',
    addon_base_url   text,
    added_at         bigint not null default 0,
    created_at       timestamptz not null default now()
);

create index if not exists library_events_profile_event_idx
    on public.library_events (profile_id, event_id);

-- ---------------------------------------------------------------------------
-- WATCH PROGRESS — INFERRED
-- rpc("sync_push_watch_progress") / sync_pull_watch_progress /
-- sync_pull_watch_progress_delta / sync_get_watch_progress_delta_cursor /
-- sync_delete_watch_progress.
-- VERIFY: open the decoder for sync_pull_watch_progress and compare columns.
-- ---------------------------------------------------------------------------
create table if not exists public.watch_progress (
    id               uuid primary key default gen_random_uuid(),
    user_id          uuid not null references auth.users(id) on delete cascade,
    profile_id       uuid not null references public.profiles(id) on delete cascade,
    content_id       text not null,
    content_type     text not null,
    season_number    integer,
    episode_number   integer,
    position_ms      bigint not null default 0,
    duration_ms      bigint not null default 0,
    completed        boolean not null default false,
    updated_at       timestamptz not null default now(),
    -- NULLS NOT DISTINCT (PG15+) is REQUIRED here: movies have NULL
    -- season_number/episode_number, and default NULL-distinct semantics would
    -- let every sync push insert a duplicate row instead of upserting.
    unique nulls not distinct (profile_id, content_id, content_type,
                               season_number, episode_number)
);

create index if not exists watch_progress_profile_idx
    on public.watch_progress (profile_id);

create table if not exists public.watch_progress_events (
    event_id         bigint generated always as identity primary key,
    user_id          uuid not null references auth.users(id) on delete cascade,
    profile_id       uuid not null references public.profiles(id) on delete cascade,
    operation        text not null check (operation in ('upsert', 'delete')),
    payload          jsonb not null default '{}'::jsonb,
    created_at       timestamptz not null default now()
);

create index if not exists watch_progress_events_profile_event_idx
    on public.watch_progress_events (profile_id, event_id);

-- ---------------------------------------------------------------------------
-- WATCHED ITEMS — INFERRED
-- rpc("sync_push_watched_items") / sync_pull_watched_items /
-- sync_pull_watched_items_delta / sync_get_watched_items_delta_cursor /
-- sync_delete_watched_items. Arg p_since_last_watched suggests a timestamp.
-- ---------------------------------------------------------------------------
create table if not exists public.watched_items (
    id                 uuid primary key default gen_random_uuid(),
    user_id            uuid not null references auth.users(id) on delete cascade,
    profile_id         uuid not null references public.profiles(id) on delete cascade,
    content_id         text not null,
    content_type       text not null,
    season_number      integer,
    episode_number     integer,
    last_watched       bigint not null default 0,
    payload            jsonb not null default '{}'::jsonb,
    created_at         timestamptz not null default now(),
    -- See the note on watch_progress: NULLS NOT DISTINCT is required for movies.
    unique nulls not distinct (profile_id, content_id, content_type,
                               season_number, episode_number)
);

create index if not exists watched_items_profile_idx
    on public.watched_items (profile_id);

create table if not exists public.watched_item_events (
    event_id         bigint generated always as identity primary key,
    user_id          uuid not null references auth.users(id) on delete cascade,
    profile_id       uuid not null references public.profiles(id) on delete cascade,
    operation        text not null check (operation in ('upsert', 'delete')),
    payload          jsonb not null default '{}'::jsonb,
    created_at       timestamptz not null default now()
);

create index if not exists watched_item_events_profile_event_idx
    on public.watched_item_events (profile_id, event_id);

-- ---------------------------------------------------------------------------
-- COLLECTIONS — INFERRED
-- rpc("sync_push_collections", p_collections_json) / sync_pull_collections
-- ---------------------------------------------------------------------------
create table if not exists public.collections (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references auth.users(id) on delete cascade,
    profile_id    uuid not null references public.profiles(id) on delete cascade,
    collection_id text not null,
    payload       jsonb not null default '{}'::jsonb,
    updated_at    timestamptz not null default now(),
    unique (profile_id, collection_id)
);

create index if not exists collections_profile_idx on public.collections (profile_id);

-- ---------------------------------------------------------------------------
-- HOME CATALOG SETTINGS — INFERRED
-- rpc("sync_push_home_catalog_settings") / sync_pull_home_catalog_settings
-- ---------------------------------------------------------------------------
create table if not exists public.home_catalog_settings (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references auth.users(id) on delete cascade,
    profile_id    uuid not null references public.profiles(id) on delete cascade,
    settings_json jsonb not null default '{}'::jsonb,
    updated_at    timestamptz not null default now(),
    unique (profile_id)
);

-- ---------------------------------------------------------------------------
-- PROFILE SETTINGS BLOB — VERIFIED (args)
-- rpc("sync_pull_profile_settings_blob") -> decodeList<SettingsBlobResponse>()
-- rpc("sync_push_profile_settings_blob", p_profile_id, p_settings_json)
-- Args p_keys / p_limit / p_offset / p_page / p_page_size appear here.
-- ---------------------------------------------------------------------------
create table if not exists public.profile_settings_blob (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references auth.users(id) on delete cascade,
    profile_id    uuid not null references public.profiles(id) on delete cascade,
    settings_key  text not null,
    settings_json jsonb not null default '{}'::jsonb,
    updated_at    timestamptz not null default now(),
    unique (profile_id, settings_key)
);

create index if not exists profile_settings_blob_profile_idx
    on public.profile_settings_blob (profile_id);

-- ---------------------------------------------------------------------------
-- PROVIDER CREDENTIALS — INFERRED
-- rpc("sync_pull_provider_credentials"). Wire model uses `provider` and
-- `credential_json`. Debrid/tracking service tokens — SENSITIVE.
-- ---------------------------------------------------------------------------
create table if not exists public.provider_credentials (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid not null references auth.users(id) on delete cascade,
    profile_id      uuid not null references public.profiles(id) on delete cascade,
    provider        text not null,
    credential_json jsonb not null default '{}'::jsonb,
    updated_at      timestamptz not null default now(),
    unique (profile_id, provider)
);

-- ---------------------------------------------------------------------------
-- PROFILE LOCKS — INFERRED
-- rpc("sync_pull_profile_locks"). PIN lockout state surfaced to other devices.
-- verify_profile_pin returns retry_after_seconds, so this tracks attempts.
-- ---------------------------------------------------------------------------
create table if not exists public.profile_locks (
    profile_id          uuid primary key references public.profiles(id) on delete cascade,
    user_id             uuid not null references auth.users(id) on delete cascade,
    failed_attempts     integer not null default 0,
    locked_until        timestamptz,
    retry_after_seconds integer not null default 0,
    updated_at          timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- ADDONS / PLUGINS — VERIFIED table names
-- These two are the ONLY tables the client hits directly with from("addons")
-- and from("plugins") instead of an RPC. Must be PostgREST-selectable.
-- Also written via rpc("sync_push_addons", p_addons) /
--          rpc("sync_push_plugins", p_plugins).
-- ---------------------------------------------------------------------------
create table if not exists public.addons (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references auth.users(id) on delete cascade,
    profile_id    uuid references public.profiles(id) on delete cascade,
    addon_id      text not null,
    payload       jsonb not null default '{}'::jsonb,
    sort_order    integer not null default 0,
    enabled       boolean not null default true,
    updated_at    timestamptz not null default now()
);

create index if not exists addons_user_idx on public.addons (user_id);

create table if not exists public.plugins (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references auth.users(id) on delete cascade,
    profile_id    uuid references public.profiles(id) on delete cascade,
    plugin_id     text not null,
    payload       jsonb not null default '{}'::jsonb,
    sort_order    integer not null default 0,
    enabled       boolean not null default true,
    updated_at    timestamptz not null default now()
);

create index if not exists plugins_user_idx on public.plugins (user_id);

-- ---------------------------------------------------------------------------
-- DEVICES — VERIFIED (args)
-- rpc("register_current_device") with p_installation_id, p_device_name,
-- p_device_type, p_platform, p_client_name, p_client_version, p_device_nonce.
-- Called from DeviceSessionRegistration.kt only when NOT anonymous.
-- ---------------------------------------------------------------------------
create table if not exists public.devices (
    id               uuid primary key default gen_random_uuid(),
    user_id          uuid not null references auth.users(id) on delete cascade,
    installation_id  text not null,
    device_name      text not null default '',
    device_type      text not null default '',
    platform         text not null default '',
    client_name      text not null default '',
    client_version   text not null default '',
    device_nonce     text not null default '',
    last_seen_at     timestamptz not null default now(),
    created_at       timestamptz not null default now(),
    unique (user_id, installation_id)
);

-- ---------------------------------------------------------------------------
-- TV LOGIN SESSIONS — VERIFIED (args)
-- rpc("start_device_login_session", p_code, p_redirect_base_url)
-- rpc("poll_tv_login_session")
-- Gated by capabilities.tv_login in the discovery document; the app treats it
-- as optional. Implemented here so you can flip tv_login to true later.
-- ---------------------------------------------------------------------------
create table if not exists public.tv_login_sessions (
    id                  uuid primary key default gen_random_uuid(),
    code                text not null unique,
    status              text not null default 'pending'
                        check (status in ('pending', 'approved', 'denied', 'expired')),
    redirect_base_url   text,
    requested_by        uuid references auth.users(id) on delete set null,
    approved_by         uuid references auth.users(id) on delete set null,
    -- Filled on approval; the polling device exchanges it for a session.
    access_token        text,
    refresh_token       text,
    expires_at          timestamptz not null default (now() + interval '10 minutes'),
    created_at          timestamptz not null default now()
);

create index if not exists tv_login_sessions_code_idx on public.tv_login_sessions (code);

-- ---------------------------------------------------------------------------
-- AVATARS / PROFILE BACKGROUNDS — INFERRED
-- get_avatar_catalog returns display_name, storage_path, sort_order, is_active,
-- bg_color (VERIFIED from the @SerialName block in ProfileModels.kt:60-65).
-- The get_member_* variants are Nuvio's paid-tier catalogs — see STUB note.
-- ---------------------------------------------------------------------------
create table if not exists public.avatars (
    id            uuid primary key default gen_random_uuid(),
    display_name  text not null default '',
    storage_path  text not null default '',
    sort_order    integer not null default 0,
    is_active     boolean not null default true,
    bg_color      text
);

create table if not exists public.profile_backgrounds (
    id            uuid primary key default gen_random_uuid(),
    display_name  text not null default '',
    storage_path  text not null default '',
    sort_order    integer not null default 0,
    is_active     boolean not null default true,
    bg_color      text
);

-- ---------------------------------------------------------------------------
-- updated_at trigger
-- ---------------------------------------------------------------------------
create or replace function public.touch_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

do $$
declare
    t text;
begin
    foreach t in array array[
        'profiles', 'library_items', 'watch_progress', 'watched_items',
        'collections', 'home_catalog_settings', 'profile_settings_blob',
        'provider_credentials', 'profile_locks', 'addons', 'plugins'
    ]
    loop
        execute format(
            'drop trigger if exists set_updated_at on public.%I;
             create trigger set_updated_at
                 before update on public.%I
                 for each row execute function public.touch_updated_at();',
            t, t
        );
    end loop;
end;
$$;

-- ===========================================================================
-- ROW LEVEL SECURITY
--
-- Every table is owned by the user; the client passes the Supabase JWT so
-- auth.uid() is the session user. Policies restrict rows to that user.
--
-- SECURITY NOTE: provider_credentials holds debrid/tracking tokens.
-- It is encrypted only by TLS + RLS here. For a multi-user server, encrypt
-- credential_json at rest with pgcrypto before exposing this route.
-- ===========================================================================
alter table public.profiles               enable row level security;
alter table public.library_items          enable row level security;
alter table public.library_events         enable row level security;
alter table public.watch_progress         enable row level security;
alter table public.watch_progress_events  enable row level security;
alter table public.watched_items          enable row level security;
alter table public.watched_item_events    enable row level security;
alter table public.collections            enable row level security;
alter table public.home_catalog_settings  enable row level security;
alter table public.profile_settings_blob  enable row level security;
alter table public.provider_credentials   enable row level security;
alter table public.profile_locks          enable row level security;
alter table public.addons                 enable row level security;
alter table public.plugins                enable row level security;
alter table public.devices                enable row level security;
alter table public.tv_login_sessions      enable row level security;

-- Catalog tables are world-readable reference data (no user_id column).
alter table public.avatars            enable row level security;
alter table public.profile_backgrounds enable row level security;

drop policy if exists "own profiles" on public.profiles;
create policy "own profiles" on public.profiles
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own library items" on public.library_items;
create policy "own library items" on public.library_items
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own library events" on public.library_events;
create policy "own library events" on public.library_events
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own watch progress" on public.watch_progress;
create policy "own watch progress" on public.watch_progress
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own watch progress events" on public.watch_progress_events;
create policy "own watch progress events" on public.watch_progress_events
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own watched items" on public.watched_items;
create policy "own watched items" on public.watched_items
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own watched item events" on public.watched_item_events;
create policy "own watched item events" on public.watched_item_events
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own collections" on public.collections;
create policy "own collections" on public.collections
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own home catalog settings" on public.home_catalog_settings;
create policy "own home catalog settings" on public.home_catalog_settings
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own profile settings" on public.profile_settings_blob;
create policy "own profile settings" on public.profile_settings_blob
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own provider credentials" on public.provider_credentials;
create policy "own provider credentials" on public.provider_credentials
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own profile locks" on public.profile_locks;
create policy "own profile locks" on public.profile_locks
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own addons" on public.addons;
create policy "own addons" on public.addons
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own plugins" on public.plugins;
create policy "own plugins" on public.plugins
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own devices" on public.devices;
create policy "own devices" on public.devices
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- TV login: a device starts a session anonymously (no auth.uid() yet), so
-- insert is open but the token columns are only readable once approved.
-- REVIEW THIS POLICY CAREFULLY — it is the most sensitive one in the file.
drop policy if exists "create tv session" on public.tv_login_sessions;
create policy "create tv session" on public.tv_login_sessions
    for insert with check (true);

drop policy if exists "read own tv session" on public.tv_login_sessions;
create policy "read own tv session" on public.tv_login_sessions
    for select using (auth.uid() = requested_by or auth.uid() = approved_by);

drop policy if exists "approve tv session" on public.tv_login_sessions;
create policy "approve tv session" on public.tv_login_sessions
    for update using (auth.uid() is not null);

-- Reference catalogs: read-only for everyone signed in or anonymous.
drop policy if exists "read avatars" on public.avatars;
create policy "read avatars" on public.avatars
    for select using (is_active);

drop policy if exists "read profile backgrounds" on public.profile_backgrounds;
create policy "read profile backgrounds" on public.profile_backgrounds
    for select using (is_active);

-- ---------------------------------------------------------------------------
-- Grants. Roles come from the supabase/postgres image.
-- ---------------------------------------------------------------------------
grant usage on schema public to anon, authenticated, service_role;

grant select, insert, update, delete on all tables in schema public
    to authenticated;
grant select on public.avatars, public.profile_backgrounds to anon;
grant all on all tables in schema public to service_role;

grant usage, select on all sequences in schema public to authenticated;
grant usage, select on all sequences in schema public to service_role;

alter default privileges in schema public
    grant select, insert, update, delete on tables to authenticated;
alter default privileges in schema public
    grant usage, select on sequences to authenticated;

-- PostgREST must reload its schema cache to see the new tables/functions.
notify pgrst, 'reload schema';
