-- ===========================================================================
-- Lentra self-hosted backend — 0002_rpc.sql
-- The 40 Postgres functions the client calls via PostgREST /rest/v1/rpc/<name>.
--
-- Legend:
--   VERIFIED = signature derived from an rpc("<name>") call site + the p_*
--              argument names found in the Kotlin source.
--   INFERRED = body is a reasonable implementation, NOT confirmed against the
--              client's decoder. Test it.
--   STUB     = returns a safe no-op. Nuvio paid-tier surface with no
--              client-side schema to infer from.
--
-- Apply with:
--   docker compose exec -T db psql -U postgres -d postgres < sql/0002_rpc.sql
--
-- CONVENTIONS
--   * security definer + pinned search_path: the function runs as its owner so
--     it can bypass RLS deliberately, then re-checks ownership itself.
--   * Explicit column projections on every RETURN QUERY. Never `setof <table>`
--     — that would leak pin_hash and break strict JSON decoding.
--   * All writes are scoped to auth.uid().
-- ===========================================================================

\set ON_ERROR_STOP on

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------------
-- Shared helper: fail loudly if the caller does not own the profile.
-- ---------------------------------------------------------------------------
create or replace function public.assert_profile_owner(p_profile_id uuid)
returns uuid
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid;
begin
    v_user := auth.uid();
    if v_user is null then
        raise exception 'not authenticated'
            using errcode = '28000';
    end if;

    if not exists (
        select 1 from public.profiles
        where id = p_profile_id and user_id = v_user
    ) then
        raise exception 'profile % not owned by caller', p_profile_id
            using errcode = '42501';
    end if;

    return v_user;
end;
$$;

revoke all on function public.assert_profile_owner(uuid) from public;
grant execute on function public.assert_profile_owner(uuid) to authenticated;


-- ===========================================================================
-- PROFILES
-- ===========================================================================

-- VERIFIED: rpc("sync_pull_profiles") with no args;
-- decoded via decodeList<NuvioProfile>() (ProfileRepository.kt:131-132).
-- Returned columns exactly match NuvioProfile's @SerialName values.
create or replace function public.sync_pull_profiles()
returns table (
    user_id                uuid,
    profile_index          integer,
    name                   text,
    avatar_color_hex       text,
    avatar_id              text,
    avatar_url             text,
    profile_background_id  text,
    profile_background_url text,
    uses_primary_addons    boolean,
    uses_primary_plugins   boolean,
    pin_enabled            boolean,
    pin_locked_until       text,
    created_at             text,
    updated_at             text
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select
        p.user_id,
        p.profile_index,
        p.name,
        p.avatar_color_hex,
        p.avatar_id,
        p.avatar_url,
        p.profile_background_id,
        p.profile_background_url,
        p.uses_primary_addons,
        p.uses_primary_plugins,
        p.pin_enabled,
        -- ISO-8601 text, matching the Kotlin `String` fields.
        to_char(p.pin_locked_until at time zone 'utc',
                'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        to_char(p.created_at at time zone 'utc',
                'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        to_char(p.updated_at at time zone 'utc',
                'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    from public.profiles p
    where p.user_id = auth.uid()
    order by p.profile_index;
$$;

-- VERIFIED args: p_profiles (jsonb array), p_origin_client_id.
-- INFERRED body. Each element uses NuvioProfilePayload's @SerialName keys
-- (ProfileModels.kt:31-39): profile_index, name, avatar_color_hex,
-- uses_primary_addons, uses_primary_plugins, avatar_id, avatar_url,
-- profile_background_id, profile_background_url.
create or replace function public.sync_push_profiles(
    p_profiles          jsonb,
    p_origin_client_id  text default null
)
returns void
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_row  jsonb;
begin
    if v_user is null then
        raise exception 'not authenticated' using errcode = '28000';
    end if;
    if p_profiles is null then
        return;
    end if;

    for v_row in select * from jsonb_array_elements(p_profiles)
    loop
        insert into public.profiles as p (
            user_id, profile_index, name, avatar_color_hex,
            avatar_id, avatar_url,
            profile_background_id, profile_background_url,
            uses_primary_addons, uses_primary_plugins
        )
        values (
            v_user,
            (v_row ->> 'profile_index')::int,
            coalesce(v_row ->> 'name', ''),
            coalesce(v_row ->> 'avatar_color_hex', '#1E88E5'),
            v_row ->> 'avatar_id',
            v_row ->> 'avatar_url',
            v_row ->> 'profile_background_id',
            v_row ->> 'profile_background_url',
            coalesce((v_row ->> 'uses_primary_addons')::boolean, false),
            coalesce((v_row ->> 'uses_primary_plugins')::boolean, false)
        )
        on conflict (user_id, profile_index) do update set
            name                   = excluded.name,
            avatar_color_hex       = excluded.avatar_color_hex,
            avatar_id              = excluded.avatar_id,
            avatar_url             = excluded.avatar_url,
            profile_background_id  = excluded.profile_background_id,
            profile_background_url = excluded.profile_background_url,
            uses_primary_addons    = excluded.uses_primary_addons,
            uses_primary_plugins   = excluded.uses_primary_plugins,
            updated_at             = now();
        -- NOTE: pin_enabled / pin_hash are deliberately NOT writable here.
        -- They change only through set_profile_pin / clear_profile_pin.
    end loop;
end;
$$;


-- ===========================================================================
-- PROFILE PIN
-- ===========================================================================

-- VERIFIED args: p_profile_id, p_pin.
-- PINs are stored hashed with pgcrypto crypt(); never plaintext.
create or replace function public.set_profile_pin(
    p_profile_id  uuid,
    p_pin         text
)
returns void
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
begin
    perform public.assert_profile_owner(p_profile_id);

    if p_pin is null or length(trim(p_pin)) = 0 then
        raise exception 'pin must not be blank' using errcode = '22023';
    end if;

    update public.profiles set
        pin_enabled    = true,
        pin_hash       = crypt(trim(p_pin), gen_salt('bf', 10)),
        pin_locked_until = null,
        updated_at     = now()
    where id = p_profile_id;

    delete from public.profile_locks where profile_id = p_profile_id;
end;
$$;

-- VERIFIED: verify_profile_pin returns retry_after_seconds
-- (ProfileModels.kt:45 @SerialName("retry_after_seconds")).
-- INFERRED: the exact full response shape. Confirm against the decoder.
create or replace function public.verify_profile_pin(
    p_profile_id  uuid,
    p_pin         text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_user        uuid;
    v_hash        text;
    v_enabled     boolean;
    v_locked      timestamptz;
    v_attempts    integer := 0;
    v_retry       integer := 0;
    v_new_locked  timestamptz;
begin
    v_user := public.assert_profile_owner(p_profile_id);

    select pin_hash, pin_enabled, pin_locked_until
      into v_hash, v_enabled, v_locked
      from public.profiles where id = p_profile_id;

    -- Already locked out?
    if v_locked is not null and v_locked > now() then
        v_retry := greatest(0, ceil(extract(epoch from (v_locked - now())))::int);
        return jsonb_build_object(
            'success', false,
            'retry_after_seconds', v_retry
        );
    end if;

    if not coalesce(v_enabled, false) or v_hash is null then
        -- No PIN set: treat as open.
        return jsonb_build_object('success', true, 'retry_after_seconds', 0);
    end if;

    if v_hash = crypt(coalesce(p_pin, ''), v_hash) then
        update public.profiles
           set pin_locked_until = null, updated_at = now()
         where id = p_profile_id;
        delete from public.profile_locks where profile_id = p_profile_id;
        return jsonb_build_object('success', true, 'retry_after_seconds', 0);
    end if;

    -- Wrong PIN: exponential-ish backoff, 5 attempts then 5 minutes.
    insert into public.profile_locks (profile_id, user_id, failed_attempts)
    values (p_profile_id, v_user, 1)
    on conflict (profile_id) do update
       set failed_attempts = public.profile_locks.failed_attempts + 1,
           updated_at      = now()
    returning failed_attempts into v_attempts;

    v_retry := case
        when v_attempts >= 5 then 300
        when v_attempts >= 3 then 30
        else 0
    end;

    if v_retry > 0 then
        v_new_locked := now() + make_interval(secs => v_retry);
        update public.profiles set pin_locked_until = v_new_locked
         where id = p_profile_id;
        update public.profile_locks
           set locked_until = v_new_locked, retry_after_seconds = v_retry
         where profile_id = p_profile_id;
    end if;

    return jsonb_build_object('success', false, 'retry_after_seconds', v_retry);
end;
$$;

-- VERIFIED args: p_profile_id, p_current_pin.
create or replace function public.clear_profile_pin(
    p_profile_id   uuid,
    p_current_pin  text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_result jsonb;
begin
    perform public.assert_profile_owner(p_profile_id);
    v_result := public.verify_profile_pin(p_profile_id, p_current_pin);

    if coalesce((v_result ->> 'success')::boolean, false) then
        update public.profiles set
            pin_enabled      = false,
            pin_hash         = null,
            pin_locked_until = null,
            updated_at       = now()
        where id = p_profile_id;
        delete from public.profile_locks where profile_id = p_profile_id;
    end if;

    return v_result;
end;
$$;

-- VERIFIED args: p_profile_id, p_account_password.
-- Escape hatch: clear the PIN using the ACCOUNT password instead of the PIN.
-- Must verify against auth.users via the service role, NOT by re-deriving the
-- hash here. INFERRED implementation — review before enabling in production.
create or replace function public.clear_profile_pin_with_account_password(
    p_profile_id        uuid,
    p_account_password  text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_user   uuid;
    v_email  text;
    v_ok     boolean := false;
begin
    v_user := public.assert_profile_owner(p_profile_id);

    select email into v_email from auth.users where id = v_user;

    if v_email is null then
        return jsonb_build_object(
            'success', false, 'error', 'account has no email');
    end if;

    -- Ask GoTrue to authenticate. This keeps password hashing in one place.
    -- Requires the DB to reach the auth service; if your network policy blocks
    -- that, replace this with a check against auth.users.encrypted_password.
    begin
        perform http_post_with_retry(
            '/auth/v1/token?grant_type=password',
            jsonb_build_object('email', v_email, 'password', p_account_password)
        );
        v_ok := true;
    exception when others then
        v_ok := false;
    end;

    if not v_ok then
        return jsonb_build_object('success', false, 'retry_after_seconds', 0);
    end if;

    update public.profiles set
        pin_enabled      = false,
        pin_hash         = null,
        pin_locked_until = null,
        updated_at       = now()
    where id = p_profile_id;
    delete from public.profile_locks where profile_id = p_profile_id;

    return jsonb_build_object('success', true, 'retry_after_seconds', 0);
end;
$$;

-- Helper used above. Requires the pg_net extension:
--     create extension if not exists pg_net;
-- If you would rather not install pg_net, rewrite
-- clear_profile_pin_with_account_password to verify against
-- auth.users.encrypted_password directly.
create or replace function public.http_post_with_retry(
    p_path  text,
    p_body  jsonb
)
returns void
language plpgsql
volatile
as $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_net') then
        perform net.http_post(
            url := current_setting('app.settings.api_url', true),
            headers := '{"Content-Type": "application/json", "apikey": "'
                || coalesce(current_setting('app.settings.anon_key', true), '')
                || '"}'::jsonb,
            body := p_body
        );
    else
        raise exception
            'pg_net not installed; rewrite clear_profile_pin_with_account_password'
            using errcode = '0A000';
    end if;
end;
$$;

-- VERIFIED: sync_pull_profile_locks. INFERRED shape.
create or replace function public.sync_pull_profile_locks()
returns table (
    profile_id          uuid,
    pin_enabled         boolean,
    failed_attempts     integer,
    locked_until        text,
    retry_after_seconds integer
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select
        p.id,
        p.pin_enabled,
        coalesce(l.failed_attempts, 0),
        to_char(coalesce(l.locked_until, p.pin_locked_until) at time zone 'utc',
                'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        coalesce(l.retry_after_seconds, 0)
    from public.profiles p
    left join public.profile_locks l on l.profile_id = p.id
    where p.user_id = auth.uid();
$$;


-- ===========================================================================
-- LIBRARY
-- ===========================================================================

-- VERIFIED: rpc("sync_pull_library") -> decodeList<LibrarySyncItem>().
-- Columns exactly match LibrarySyncItem's @SerialName values.
create or replace function public.sync_pull_library(p_profile_id uuid)
returns table (
    content_id      text,
    content_type    text,
    name            text,
    poster          text,
    poster_shape    text,
    background      text,
    logo            text,
    description     text,
    release_info    text,
    imdb_rating     real,
    genres          text[],
    addon_base_url  text,
    added_at        bigint
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select
        l.content_id, l.content_type, l.name, l.poster, l.poster_shape,
        l.background, l.logo, l.description, l.release_info, l.imdb_rating,
        l.genres, l.addon_base_url, l.added_at
    from public.library_items l
    where l.profile_id = p_profile_id
      and l.user_id    = auth.uid()
    order by l.added_at desc;
$$;

-- VERIFIED args: p_items, p_profile_id, p_origin_client_id.
-- INFERRED body. Upserts library rows AND appends to the delta outbox.
create or replace function public.sync_push_library_items(
    p_profile_id        uuid,
    p_items             jsonb,
    p_origin_client_id  text default null
)
returns void
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid;
    v_row  jsonb;
begin
    v_user := public.assert_profile_owner(p_profile_id);
    if p_items is null then return; end if;

    for v_row in select * from jsonb_array_elements(p_items)
    loop
        insert into public.library_items as l (
            user_id, profile_id, content_id, content_type, name, poster,
            poster_shape, background, logo, description, release_info,
            imdb_rating, genres, addon_base_url, added_at
        )
        values (
            v_user,
            p_profile_id,
            v_row ->> 'content_id',
            v_row ->> 'content_type',
            coalesce(v_row ->> 'name', ''),
            v_row ->> 'poster',
            coalesce(v_row ->> 'poster_shape', 'POSTER'),
            v_row ->> 'background',
            v_row ->> 'logo',
            v_row ->> 'description',
            v_row ->> 'release_info',
            nullif(v_row ->> 'imdb_rating', '')::real,
            coalesce(
                (select array_agg(x) from jsonb_array_elements_text(
                    coalesce(v_row -> 'genres', '[]'::jsonb)) as x),
                '{}'::text[]),
            v_row ->> 'addon_base_url',
            coalesce((v_row ->> 'added_at')::bigint, 0)
        )
        on conflict (profile_id, content_id, content_type) do update set
            name           = excluded.name,
            poster         = excluded.poster,
            poster_shape   = excluded.poster_shape,
            background     = excluded.background,
            logo           = excluded.logo,
            description    = excluded.description,
            release_info   = excluded.release_info,
            imdb_rating    = excluded.imdb_rating,
            genres         = excluded.genres,
            addon_base_url = excluded.addon_base_url,
            added_at       = excluded.added_at,
            updated_at     = now();

        insert into public.library_events (
            user_id, profile_id, operation, content_id, content_type, name,
            poster, poster_shape, background, logo, description, release_info,
            imdb_rating, genres, addon_base_url, added_at
        )
        select
            v_user, p_profile_id, 'upsert', l.content_id, l.content_type,
            l.name, l.poster, l.poster_shape, l.background, l.logo,
            l.description, l.release_info, l.imdb_rating, l.genres,
            l.addon_base_url, l.added_at
        from public.library_items l
        where l.profile_id  = p_profile_id
          and l.content_id  = v_row ->> 'content_id'
          and l.content_type = v_row ->> 'content_type';
    end loop;
end;
$$;

-- VERIFIED args: p_profile_id, p_keys, p_origin_client_id.
create or replace function public.sync_delete_library_items(
    p_profile_id        uuid,
    p_keys              jsonb,
    p_origin_client_id  text default null
)
returns void
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid;
    v_key  jsonb;
begin
    v_user := public.assert_profile_owner(p_profile_id);
    if p_keys is null then return; end if;

    -- LibrarySyncKey (LibrarySyncAdapter.kt:8-11) = {content_id, content_type}
    for v_key in select * from jsonb_array_elements(p_keys)
    loop
        delete from public.library_items
         where profile_id   = p_profile_id
           and user_id      = v_user
           and content_id   = v_key ->> 'content_id'
           and content_type = v_key ->> 'content_type';

        insert into public.library_events (
            user_id, profile_id, operation, content_id, content_type
        ) values (
            v_user, p_profile_id, 'delete',
            v_key ->> 'content_id', v_key ->> 'content_type'
        );
    end loop;
end;
$$;

-- VERIFIED args: p_profile_id, p_since_event_id, p_limit.
-- Returns LibraryDeltaSyncItem: every library column + event_id + operation.
create or replace function public.sync_pull_library_delta(
    p_profile_id       uuid,
    p_since_event_id   bigint default 0,
    p_limit            integer default 500
)
returns table (
    event_id        bigint,
    operation       text,
    content_id      text,
    content_type    text,
    name            text,
    poster          text,
    poster_shape    text,
    background      text,
    logo            text,
    description     text,
    release_info    text,
    imdb_rating     real,
    genres          text[],
    addon_base_url  text,
    added_at        bigint
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select
        e.event_id, e.operation, e.content_id, e.content_type, e.name,
        e.poster, e.poster_shape, e.background, e.logo, e.description,
        e.release_info, e.imdb_rating, e.genres, e.addon_base_url, e.added_at
    from public.library_events e
    where e.profile_id = p_profile_id
      and e.user_id    = auth.uid()
      and e.event_id   > coalesce(p_since_event_id, 0)
    order by e.event_id asc
    limit greatest(1, least(coalesce(p_limit, 500), 2000));
$$;

-- VERIFIED: sync_get_library_delta_cursor
create or replace function public.sync_get_library_delta_cursor(
    p_profile_id uuid
)
returns bigint
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select coalesce(max(e.event_id), 0)
    from public.library_events e
    where e.profile_id = p_profile_id
      and e.user_id    = auth.uid();
$$;


-- ===========================================================================
-- WATCH PROGRESS — INFERRED bodies, VERIFIED names/args
-- ===========================================================================

create or replace function public.sync_pull_watch_progress(p_profile_id uuid)
returns table (
    content_id     text,
    content_type   text,
    season_number  integer,
    episode_number integer,
    position_ms    bigint,
    duration_ms    bigint,
    completed      boolean,
    updated_at     text
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select
        w.content_id, w.content_type, w.season_number, w.episode_number,
        w.position_ms, w.duration_ms, w.completed,
        to_char(w.updated_at at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    from public.watch_progress w
    where w.profile_id = p_profile_id and w.user_id = auth.uid();
$$;

create or replace function public.sync_push_watch_progress(
    p_profile_id        uuid,
    p_items             jsonb,
    p_origin_client_id  text default null
)
returns void
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid;
    v_row  jsonb;
begin
    v_user := public.assert_profile_owner(p_profile_id);
    if p_items is null then return; end if;

    for v_row in select * from jsonb_array_elements(p_items)
    loop
        insert into public.watch_progress as w (
            user_id, profile_id, content_id, content_type,
            season_number, episode_number, position_ms, duration_ms, completed
        )
        values (
            v_user, p_profile_id,
            v_row ->> 'content_id',
            v_row ->> 'content_type',
            nullif(v_row ->> 'season_number', '')::int,
            nullif(v_row ->> 'episode_number', '')::int,
            coalesce((v_row ->> 'position_ms')::bigint, 0),
            coalesce((v_row ->> 'duration_ms')::bigint, 0),
            coalesce((v_row ->> 'completed')::boolean, false)
        )
        on conflict (profile_id, content_id, content_type,
                     season_number, episode_number) do update set
            position_ms = excluded.position_ms,
            duration_ms = excluded.duration_ms,
            completed   = excluded.completed,
            updated_at  = now();

        insert into public.watch_progress_events
            (user_id, profile_id, operation, payload)
        values (v_user, p_profile_id, 'upsert',
                jsonb_build_object(
                    'content_id',     v_row ->> 'content_id',
                    'content_type',   v_row ->> 'content_type',
                    'season_number',  v_row -> 'season_number',
                    'episode_number', v_row -> 'episode_number',
                    'position_ms',    v_row -> 'position_ms',
                    'duration_ms',    v_row -> 'duration_ms',
                    'completed',      v_row -> 'completed'));
    end loop;
end;
$$;

create or replace function public.sync_delete_watch_progress(
    p_profile_id        uuid,
    p_keys              jsonb,
    p_origin_client_id  text default null
)
returns void
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid;
    v_key  jsonb;
begin
    v_user := public.assert_profile_owner(p_profile_id);
    if p_keys is null then return; end if;

    for v_key in select * from jsonb_array_elements(p_keys)
    loop
        delete from public.watch_progress
         where profile_id   = p_profile_id
           and user_id      = v_user
           and content_id   = v_key ->> 'content_id'
           and content_type = v_key ->> 'content_type'
           and season_number  is not distinct from
               nullif(v_key ->> 'season_number', '')::int
           and episode_number is not distinct from
               nullif(v_key ->> 'episode_number', '')::int;

        insert into public.watch_progress_events
            (user_id, profile_id, operation, payload)
        values (v_user, p_profile_id, 'delete', v_key);
    end loop;
end;
$$;

create or replace function public.sync_pull_watch_progress_delta(
    p_profile_id      uuid,
    p_since_event_id  bigint  default 0,
    p_limit           integer default 500
)
returns table (
    event_id  bigint,
    operation text,
    payload   jsonb
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select e.event_id, e.operation, e.payload
    from public.watch_progress_events e
    where e.profile_id = p_profile_id
      and e.user_id    = auth.uid()
      and e.event_id   > coalesce(p_since_event_id, 0)
    order by e.event_id asc
    limit greatest(1, least(coalesce(p_limit, 500), 2000));
$$;

create or replace function public.sync_get_watch_progress_delta_cursor(
    p_profile_id uuid
)
returns bigint
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select coalesce(max(e.event_id), 0)
    from public.watch_progress_events e
    where e.profile_id = p_profile_id and e.user_id = auth.uid();
$$;


-- ===========================================================================
-- WATCHED ITEMS — INFERRED bodies, VERIFIED names/args
-- ===========================================================================

create or replace function public.sync_pull_watched_items(
    p_profile_id          uuid,
    p_since_last_watched  bigint default 0
)
returns table (
    content_id     text,
    content_type   text,
    season_number  integer,
    episode_number integer,
    last_watched   bigint,
    payload        jsonb
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select
        w.content_id, w.content_type, w.season_number, w.episode_number,
        w.last_watched, w.payload
    from public.watched_items w
    where w.profile_id   = p_profile_id
      and w.user_id      = auth.uid()
      and w.last_watched >= coalesce(p_since_last_watched, 0)
    order by w.last_watched desc;
$$;

create or replace function public.sync_push_watched_items(
    p_profile_id        uuid,
    p_items             jsonb,
    p_origin_client_id  text default null
)
returns void
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid;
    v_row  jsonb;
begin
    v_user := public.assert_profile_owner(p_profile_id);
    if p_items is null then return; end if;

    for v_row in select * from jsonb_array_elements(p_items)
    loop
        insert into public.watched_items as w (
            user_id, profile_id, content_id, content_type,
            season_number, episode_number, last_watched, payload
        )
        values (
            v_user, p_profile_id,
            v_row ->> 'content_id',
            v_row ->> 'content_type',
            nullif(v_row ->> 'season_number', '')::int,
            nullif(v_row ->> 'episode_number', '')::int,
            coalesce((v_row ->> 'last_watched')::bigint, 0),
            v_row
        )
        on conflict (profile_id, content_id, content_type,
                     season_number, episode_number) do update set
            last_watched = excluded.last_watched,
            payload      = excluded.payload;

        insert into public.watched_item_events
            (user_id, profile_id, operation, payload)
        values (v_user, p_profile_id, 'upsert', v_row);
    end loop;
end;
$$;

create or replace function public.sync_delete_watched_items(
    p_profile_id        uuid,
    p_keys              jsonb,
    p_origin_client_id  text default null
)
returns void
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid;
    v_key  jsonb;
begin
    v_user := public.assert_profile_owner(p_profile_id);
    if p_keys is null then return; end if;

    for v_key in select * from jsonb_array_elements(p_keys)
    loop
        delete from public.watched_items
         where profile_id   = p_profile_id
           and user_id      = v_user
           and content_id   = v_key ->> 'content_id'
           and content_type = v_key ->> 'content_type';

        insert into public.watched_item_events
            (user_id, profile_id, operation, payload)
        values (v_user, p_profile_id, 'delete', v_key);
    end loop;
end;
$$;

create or replace function public.sync_pull_watched_items_delta(
    p_profile_id      uuid,
    p_since_event_id  bigint  default 0,
    p_limit           integer default 500
)
returns table (
    event_id  bigint,
    operation text,
    payload   jsonb
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select e.event_id, e.operation, e.payload
    from public.watched_item_events e
    where e.profile_id = p_profile_id
      and e.user_id    = auth.uid()
      and e.event_id   > coalesce(p_since_event_id, 0)
    order by e.event_id asc
    limit greatest(1, least(coalesce(p_limit, 500), 2000));
$$;

create or replace function public.sync_get_watched_items_delta_cursor(
    p_profile_id uuid
)
returns bigint
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select coalesce(max(e.event_id), 0)
    from public.watched_item_events e
    where e.profile_id = p_profile_id and e.user_id = auth.uid();
$$;


-- ===========================================================================
-- COLLECTIONS / HOME CATALOG / SETTINGS BLOB / CREDENTIALS / ADDONS / PLUGINS
-- All INFERRED bodies; names + args VERIFIED.
-- ===========================================================================

create or replace function public.sync_pull_collections(p_profile_id uuid)
returns table (collection_id text, payload jsonb, updated_at text)
language sql stable security definer set search_path = public, pg_temp
as $$
    select c.collection_id, c.payload,
           to_char(c.updated_at at time zone 'utc',
                   'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    from public.collections c
    where c.profile_id = p_profile_id and c.user_id = auth.uid();
$$;

create or replace function public.sync_push_collections(
    p_profile_id        uuid,
    p_collections_json  jsonb,
    p_origin_client_id  text default null
)
returns void
language plpgsql volatile security definer set search_path = public, pg_temp
as $$
declare
    v_user uuid;
    v_row  jsonb;
begin
    v_user := public.assert_profile_owner(p_profile_id);
    if p_collections_json is null then return; end if;

    for v_row in select * from jsonb_array_elements(p_collections_json)
    loop
        insert into public.collections (user_id, profile_id, collection_id, payload)
        values (v_user, p_profile_id,
                coalesce(v_row ->> 'collection_id', v_row ->> 'id'),
                v_row)
        on conflict (profile_id, collection_id)
            do update set payload = excluded.payload, updated_at = now();
    end loop;
end;
$$;

create or replace function public.sync_pull_home_catalog_settings(p_profile_id uuid)
returns table (settings_json jsonb, updated_at text)
language sql stable security definer set search_path = public, pg_temp
as $$
    select h.settings_json,
           to_char(h.updated_at at time zone 'utc',
                   'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    from public.home_catalog_settings h
    where h.profile_id = p_profile_id and h.user_id = auth.uid();
$$;

create or replace function public.sync_push_home_catalog_settings(
    p_profile_id        uuid,
    p_settings_json     jsonb,
    p_origin_client_id  text default null
)
returns void
language plpgsql volatile security definer set search_path = public, pg_temp
as $$
declare
    v_user uuid;
begin
    v_user := public.assert_profile_owner(p_profile_id);

    insert into public.home_catalog_settings (user_id, profile_id, settings_json)
    values (v_user, p_profile_id, coalesce(p_settings_json, '{}'::jsonb))
    on conflict (profile_id)
        do update set settings_json = excluded.settings_json,
                      updated_at    = now();
end;
$$;

-- VERIFIED: rpc("sync_pull_profile_settings_blob") ->
--           decodeList<SettingsBlobResponse>()   (ProfileSettingsSync.kt:123)
create or replace function public.sync_pull_profile_settings_blob(
    p_profile_id  uuid,
    p_keys        jsonb default null
)
returns table (settings_key text, settings_json jsonb, updated_at text)
language sql stable security definer set search_path = public, pg_temp
as $$
    select b.settings_key, b.settings_json,
           to_char(b.updated_at at time zone 'utc',
                   'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    from public.profile_settings_blob b
    where b.profile_id = p_profile_id
      and b.user_id    = auth.uid()
      and (p_keys is null
           or b.settings_key = any (
                select jsonb_array_elements_text(p_keys)));
$$;

create or replace function public.sync_push_profile_settings_blob(
    p_profile_id     uuid,
    p_settings_json  jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql volatile security definer set search_path = public, pg_temp
as $$
declare
    v_user uuid;
    v_key  text;
begin
    v_user := public.assert_profile_owner(p_profile_id);
    if p_settings_json is null then return; end if;

    for v_key in select * from jsonb_object_keys(p_settings_json)
    loop
        insert into public.profile_settings_blob
            (user_id, profile_id, settings_key, settings_json)
        values (v_user, p_profile_id, v_key, p_settings_json -> v_key)
        on conflict (profile_id, settings_key)
            do update set settings_json = excluded.settings_json,
                          updated_at    = now();
    end loop;
end;
$$;

-- VERIFIED: sync_pull_provider_credentials (ProviderCredentialSync.kt).
-- SENSITIVE: returns debrid/tracking tokens. RLS + TLS are the only guards.
create or replace function public.sync_pull_provider_credentials(
    p_profile_id uuid default null
)
returns table (provider text, credential_json jsonb, updated_at text)
language sql stable security definer set search_path = public, pg_temp
as $$
    select c.provider, c.credential_json,
           to_char(c.updated_at at time zone 'utc',
                   'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    from public.provider_credentials c
    where c.user_id = auth.uid()
      and (p_profile_id is null or c.profile_id = p_profile_id);
$$;

create or replace function public.sync_push_addons(
    p_profile_id        uuid,
    p_addons            jsonb,
    p_origin_client_id  text default null
)
returns void
language plpgsql volatile security definer set search_path = public, pg_temp
as $$
declare
    v_user uuid;
    v_row  jsonb;
    v_idx  integer := 0;
begin
    v_user := public.assert_profile_owner(p_profile_id);
    if p_addons is null then return; end if;

    -- Replace-the-set semantics: the client pushes its full list.
    delete from public.addons
     where user_id = v_user and profile_id = p_profile_id;

    for v_row in select * from jsonb_array_elements(p_addons)
    loop
        insert into public.addons
            (user_id, profile_id, addon_id, payload, sort_order, enabled)
        values (
            v_user, p_profile_id,
            coalesce(v_row ->> 'addon_id', v_row ->> 'id'),
            v_row, v_idx,
            coalesce((v_row ->> 'enabled')::boolean, true)
        );
        v_idx := v_idx + 1;
    end loop;
end;
$$;

create or replace function public.sync_push_plugins(
    p_profile_id        uuid,
    p_plugins           jsonb,
    p_origin_client_id  text default null
)
returns void
language plpgsql volatile security definer set search_path = public, pg_temp
as $$
declare
    v_user uuid;
    v_row  jsonb;
    v_idx  integer := 0;
begin
    v_user := public.assert_profile_owner(p_profile_id);
    if p_plugins is null then return; end if;

    delete from public.plugins
     where user_id = v_user and profile_id = p_profile_id;

    for v_row in select * from jsonb_array_elements(p_plugins)
    loop
        insert into public.plugins
            (user_id, profile_id, plugin_id, payload, sort_order, enabled)
        values (
            v_user, p_profile_id,
            coalesce(v_row ->> 'plugin_id', v_row ->> 'id'),
            v_row, v_idx,
            coalesce((v_row ->> 'enabled')::boolean, true)
        );
        v_idx := v_idx + 1;
    end loop;
end;
$$;

-- Cascade-wipe everything belonging to one profile.
create or replace function public.sync_delete_profile_data(p_profile_id uuid)
returns void
language plpgsql volatile security definer set search_path = public, pg_temp
as $$
declare
    v_user uuid;
begin
    v_user := public.assert_profile_owner(p_profile_id);

    delete from public.library_events         where profile_id = p_profile_id;
    delete from public.library_items          where profile_id = p_profile_id;
    delete from public.watch_progress_events  where profile_id = p_profile_id;
    delete from public.watch_progress         where profile_id = p_profile_id;
    delete from public.watched_item_events    where profile_id = p_profile_id;
    delete from public.watched_items          where profile_id = p_profile_id;
    delete from public.collections            where profile_id = p_profile_id;
    delete from public.home_catalog_settings  where profile_id = p_profile_id;
    delete from public.profile_settings_blob  where profile_id = p_profile_id;
    delete from public.provider_credentials   where profile_id = p_profile_id;
    delete from public.profile_locks          where profile_id = p_profile_id;
    delete from public.addons                 where profile_id = p_profile_id;
    delete from public.plugins                where profile_id = p_profile_id;
    delete from public.profiles               where id = p_profile_id
                                                and user_id = v_user;
end;
$$;


-- ===========================================================================
-- DEVICES
-- ===========================================================================

-- VERIFIED args (DeviceSessionRegistration.kt:51): p_installation_id,
-- p_device_name, p_device_type, p_platform, p_client_name, p_client_version,
-- p_device_nonce. Called only when the session is NOT anonymous.
create or replace function public.register_current_device(
    p_installation_id  text,
    p_device_name      text default '',
    p_device_type      text default '',
    p_platform         text default '',
    p_client_name      text default '',
    p_client_version   text default '',
    p_device_nonce     text default ''
)
returns jsonb
language plpgsql volatile security definer set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_id   uuid;
begin
    if v_user is null then
        raise exception 'not authenticated' using errcode = '28000';
    end if;
    if p_installation_id is null or p_installation_id = '' then
        raise exception 'p_installation_id required' using errcode = '22023';
    end if;

    insert into public.devices as d (
        user_id, installation_id, device_name, device_type,
        platform, client_name, client_version, device_nonce
    )
    values (
        v_user, p_installation_id, coalesce(p_device_name, ''),
        coalesce(p_device_type, ''), coalesce(p_platform, ''),
        coalesce(p_client_name, ''), coalesce(p_client_version, ''),
        coalesce(p_device_nonce, '')
    )
    on conflict (user_id, installation_id) do update set
        device_name    = excluded.device_name,
        device_type    = excluded.device_type,
        platform       = excluded.platform,
        client_name    = excluded.client_name,
        client_version = excluded.client_version,
        device_nonce   = excluded.device_nonce,
        last_seen_at   = now()
    returning d.id into v_id;

    return jsonb_build_object('device_id', v_id, 'registered', true);
end;
$$;


-- ===========================================================================
-- TV LOGIN  (only reachable if the discovery document sets tv_login: true)
-- ===========================================================================

create or replace function public.start_device_login_session(
    p_code               text,
    p_redirect_base_url  text default null
)
returns jsonb
language plpgsql volatile security definer set search_path = public, pg_temp
as $$
declare
    v_id uuid;
begin
    insert into public.tv_login_sessions (code, redirect_base_url)
    values (upper(trim(p_code)), p_redirect_base_url)
    returning id into v_id;

    return jsonb_build_object(
        'session_id', v_id,
        'status', 'pending',
        'expires_at',
        to_char((now() + interval '10 minutes') at time zone 'utc',
                'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
end;
$$;

create or replace function public.poll_tv_login_session(p_code text)
returns jsonb
language plpgsql volatile security definer set search_path = public, pg_temp
as $$
declare
    v_row public.tv_login_sessions;
begin
    select * into v_row from public.tv_login_sessions
     where code = upper(trim(p_code))
     limit 1;

    if not found then
        return jsonb_build_object('status', 'not_found');
    end if;

    if v_row.expires_at < now() and v_row.status = 'pending' then
        update public.tv_login_sessions set status = 'expired'
         where id = v_row.id;
        return jsonb_build_object('status', 'expired');
    end if;

    if v_row.status = 'approved' then
        return jsonb_build_object(
            'status', 'approved',
            'access_token', v_row.access_token,
            'refresh_token', v_row.refresh_token
        );
    end if;

    return jsonb_build_object('status', v_row.status);
end;
$$;


-- ===========================================================================
-- COSMETICS + MEMBERSHIP — STUBS
--
-- These are Nuvio's PAID-TIER backend. get_avatar_catalog's row shape is
-- VERIFIED (display_name, storage_path, sort_order, is_active, bg_color —
-- ProfileModels.kt:60-65); the get_member_* and get_my_member* variants have
-- no client-side schema to infer from, so they return a safe empty/no-access
-- answer. That is the correct behaviour for a personal server: you simply
-- have no paid cosmetics and no membership.
-- ===========================================================================

-- VERIFIED row shape.
create or replace function public.get_avatar_catalog(
    p_page       integer default 0,
    p_page_size  integer default 100
)
returns table (
    id            uuid,
    display_name  text,
    storage_path  text,
    sort_order    integer,
    is_active     boolean,
    bg_color      text
)
language sql stable security definer set search_path = public, pg_temp
as $$
    select a.id, a.display_name, a.storage_path, a.sort_order,
           a.is_active, a.bg_color
    from public.avatars a
    where a.is_active
    order by a.sort_order, a.display_name
    limit greatest(1, least(coalesce(p_page_size, 100), 500))
    offset greatest(0, coalesce(p_page, 0))
        * greatest(1, least(coalesce(p_page_size, 100), 500));
$$;

-- STUB: paid catalog. Returns the free catalog so the UI still works.
create or replace function public.get_member_profile_avatar_catalog(
    p_profile_id uuid default null,
    p_offset     integer default 0,
    p_limit      integer default 100
)
returns table (
    id            uuid,
    display_name  text,
    storage_path  text,
    sort_order    integer,
    is_active     boolean,
    bg_color      text
)
language sql stable security definer set search_path = public, pg_temp
as $$
    select a.id, a.display_name, a.storage_path, a.sort_order,
           a.is_active, a.bg_color
    from public.profile_backgrounds a
    where false          -- STUB: no member backgrounds on a personal server
    order by a.sort_order
    limit coalesce(p_limit, 100) offset coalesce(p_offset, 0);
$$;

-- STUB: paid catalog.
create or replace function public.get_member_profile_background_catalog(
    p_profile_id uuid default null,
    p_offset     integer default 0,
    p_limit      integer default 100
)
returns table (
    id            uuid,
    display_name  text,
    storage_path  text,
    sort_order    integer,
    is_active     boolean,
    bg_color      text
)
language sql stable security definer set search_path = public, pg_temp
as $$
    select b.id, b.display_name, b.storage_path, b.sort_order,
           b.is_active, b.bg_color
    from public.profile_backgrounds b
    where b.is_active
    order by b.sort_order
    limit coalesce(p_limit, 100) offset coalesce(p_offset, 0);
$$;

-- STUB: no membership => free tier.
create or replace function public.get_my_member_access()
returns jsonb
language sql stable security definer set search_path = public, pg_temp
as $$
    select jsonb_build_object(
        'is_member',       false,
        'tier',            'free',
        'entitlements',    '[]'::jsonb,
        'expires_at',      null,
        'self_hosted',     true
    );
$$;

-- STUB
create or replace function public.get_my_membership_overview()
returns jsonb
language sql stable security definer set search_path = public, pg_temp
as $$
    select jsonb_build_object(
        'is_member',   false,
        'tier',        'free',
        'self_hosted', true
    );
$$;


-- ===========================================================================
-- Grants — PostgREST exposes functions to these roles.
-- ===========================================================================
do $$
declare
    r record;
begin
    for r in
        select p.oid::regprocedure as sig
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
          and (p.proname like 'sync\_%'
               or p.proname in (
                   'set_profile_pin', 'verify_profile_pin', 'clear_profile_pin',
                   'clear_profile_pin_with_account_password',
                   'register_current_device', 'start_device_login_session',
                   'poll_tv_login_session', 'get_avatar_catalog',
                   'get_member_profile_avatar_catalog',
                   'get_member_profile_background_catalog',
                   'get_my_member_access', 'get_my_membership_overview'))
    loop
        execute format('revoke all on function %s from public', r.sig);
        execute format('grant execute on function %s to authenticated, service_role',
                       r.sig);
    end loop;
end;
$$;

-- Anonymous devices may start/poll a TV login session before they have a JWT.
grant execute on function public.start_device_login_session(text, text) to anon;
grant execute on function public.poll_tv_login_session(text)            to anon;
-- The public avatar catalog is reference data.
grant execute on function public.get_avatar_catalog(integer, integer)   to anon;

notify pgrst, 'reload schema';

-- ===========================================================================
-- VERIFY: all 40 RPCs present?
-- ===========================================================================
select count(*) as rpc_count
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and (p.proname like 'sync\_%'
       or p.proname in ('set_profile_pin','verify_profile_pin','clear_profile_pin',
           'clear_profile_pin_with_account_password','register_current_device',
           'start_device_login_session','poll_tv_login_session',
           'get_avatar_catalog','get_member_profile_avatar_catalog',
           'get_member_profile_background_catalog','get_my_member_access',
           'get_my_membership_overview'));
-- Expected: 40
