# Nuvio Mobile + IPTV — port plan

Goal: bring every Live TV feature from [homelessbrian/NuvioPlusIPTV](https://github.com/homelessbrian/NuvioPlusIPTV)
(1.1.0-beta.7.5, a fork of NuvioTV) to a fork of [NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)
(`cmp-rewrite`), Android only, with a Google Drive backup that is **the same file** the TV app reads and writes,
and a GitHub Actions sync that mirrors every upstream Nuvio Mobile release automatically.

## Why this is a rewrite, not a copy

| | NuvioPlusIPTV (source) | NuvioMobile (target) |
|---|---|---|
| Module | `app/`, package `com.nuvio.tv` | `composeApp/` (KMP), package `com.nuvio.app` |
| UI | Jetpack Compose **for TV**, D-pad focus, OK / long-OK | Compose Multiplatform, touch, gestures, bottom tabs |
| DI | Hilt `@Inject` | No DI framework; `object XRepository` + `installPersistence(load, save)` lambdas wired from `androidMain` |
| Storage | DataStore prefs + Room (EPG, On Demand) | SharedPreferences + JSON files; no DB yet |
| Player | Media3 + libmpv, TV `LiveTvPlaybackController` | `features/player` (Media3 `PlatformExoPlayback`, mpv via `mpv-android-lib`) |
| Size of Live TV | 116 Kotlin files, ~53k lines | — |

Nothing in `com.nuvio.tv.livetv` compiles inside `composeApp`. Models, parsers and algorithms transliterate;
every screen and controller is rewritten for touch against the mobile app's own player, navigation and settings.

## Source inventory (NuvioPlusIPTV `app/src/main/java/com/nuvio/tv/livetv/`)

| Package | Files | Lines | What it is | Phase |
|---|---|---|---|---|
| `model/` | 2 | 857 | `LiveChannel`, `PlaylistSource`, `EpgSource`, `Programme`, settings data classes | 1 |
| `data/` | 15 | 7,662 | `LiveTvPreferences` (DataStore + **export/import JSON**), `LiveTvRepository`, M3U/Xtream/XMLTV loaders, logo library, poster matching, guide DB | 1 |
| `sync/` | 1 | 709 | `LiveTvDriveSync`: Drive appdata upload/download, three-way merge of sources | 1 |
| `startup/`, `crash/` | 5 | 810 | Start-Up Report, crash guard for channels that crash the player | 1 |
| `player/` | 7 | 3,160 | Live playback controller: HLS→TS fallback, backup-channel switch, audio-track memory, pause buffer, frame-rate match | 2 |
| `ui/` | 19 | 12,367 | Guide (TiviMate-style grid), overlay guide, info panel, channel surfing, reorder, manage visibility, settings pages | 2 |
| `home/` | 9 | 1,736 | Live TV rows on Nuvio Home, side-menu entries, search results | 2 |
| `branding/`, `parental/`, `reminders/` | 5 | 287 | Small helpers | 2 |
| `calendar/` | 11 | 5,672 | Catch-up: time shift, template catch-up, HLS/TS probing, local replay cache | 3 |
| `ondemand/` | 13 | 4,624 | Provider VOD: Room DB, TMDB matching, grid/modern layouts, watched marks | 3 |
| `record/` | 9 | 2,958 | Recordings: scheduler, foreground service, series rules, catch-up saves, storage picker | 3 |
| `sports/` | 20 | 12,369 | Sports hub: 160+ leagues, follow teams, channel matching, game pages, alerts | 4 |

## Target layout (inside the fork)

```
composeApp/src/commonMain/kotlin/com/nuvio/app/features/livetv/
  model/        LiveTvModels.kt            ← transliterated 1:1 (field names + JSON encoding preserved)
  data/         LiveTvPreferences.kt       ← key names IDENTICAL to TV app; exportForSync/importFromSync
                LiveTvRepository.kt, M3uParser.kt, XtreamClient.kt, XmltvParser.kt, LogoLibrary.kt
  sync/         LiveTvDriveSync.kt         ← same file name, envelope, merge rules as TV
  player/       LiveTvPlaybackCoordinator.kt (wraps features/player; no second player)
  ui/           LiveTvTab.kt, GuideScreen.kt, ChannelListScreen.kt, InfoSheet.kt, LiveTvSettingsPage.kt
  catchup/ ondemand/ record/ sports/       ← phases 3–4
composeApp/src/androidMain/kotlin/com/nuvio/app/features/livetv/
  LiveTvStorage.android.kt     SharedPreferences-backed installPersistence (mirrors AutoSyncPreferencesAndroid)
  GuideDatabase.android.kt     Room: programmes, matched channels (TV app's schema, Android-only)
  OnDemandDatabase.android.kt  Room (phase 3)
  RecordingService.android.kt  foreground service, Android 13+ exact alarms (phase 3)
  DriveAuth.android.kt         Google Sign-In / AppAuth for drive.appdata
```

Wiring points in the existing mobile app (small, merge-conflict-prone — keep each edit to a few lines):
- `AppScreenTab.kt` / `MainTabsDestination.kt` / `RootTabHost.kt`: add a **Live TV** tab (hideable in settings).
- `SettingsDestinations.kt` + `features/settings/SettingsScreen.kt`: add **Live TV** section.
- `features/search`: add Live TV + On Demand result rows.
- `features/home`: optional "Live now" / recent channels row.
- `PlayerDestination.kt`: accept a `LiveTvPlayerRequest` (channel id, catch-up window) so the one player plays live streams.

## Backup/restore — byte-compatible with the TV app

File: **`Nuvio Live TV backup.json`** in the user's Google Drive **appDataFolder** (scope `drive.appdata`);
"file" mode (visible Drive file) also supported, same name. Envelope:

```json
{ "format": 1, "savedAt": 1761955200000, "deviceId": "…", "device": "Pixel 9", "data": { … } }
```

`data` is a flat map of DataStore key → typed entry:

```json
"favorites":      { "t": "s",   "v": ["ch1","ch2"] }     // string set
"guide_days":     { "t": "i",   "v": 7 }
"playlists":      { "t": "str", "v": "[{…PlaylistSource JSON…}]" }
"auto_backup_channel": { "t": "b", "v": true }
"some_long":      { "t": "l",   "v": 123 }
```

Rules copied from `LiveTvPreferences.exportForSync` / `importFromSync` and `LiveTvDriveSync`:
- Never synced (per-device): `last_channel`, `previous_channel`, `last_group`, `recent`, `on_demand_imports`,
  `channel_quality`, `developer_tools`, `recording_storage` — and their per-profile copies (`_p<N>` suffix).
- On export, `playlists` entries are written with `lastUpdatedMs=0, lastError=null, channelCount=0, serverTimezone="", catchupNoHlsSince=0`;
  `epgs` entries with `lastUpdatedMs=0, lastError=null, programCount=0`. On import the local copies of those
  fields are kept for sources the device already had; a playlist whose URL / Xtream login / user agent /
  stream format changed is re-downloaded.
- Import removes synced keys the other device doesn't have, then writes everything it does.
- Drive sync keeps a local `livetv_drive_base.json` and does a three-way merge of the playlist / EPG lists
  (`unionSources`, removed-here tracking) so two devices adding different sources don't clobber each other.
- Sports follows (phase 4) ride in the same `data` map, so they sync too.

The mobile `LiveTvPreferences` must use the **exact TV key names** and the **exact JSON encoders**
(`encodePlaylists`, `encodeEpgs`, `EPG_SEP = "\u0001"`, list-map encoding). A test in `commonTest` round-trips a
real TV export fixture to guarantee this. Settings that only make sense on a TV (match frame rate, info-panel
background, overlay guide size…) are still stored under their TV keys so a round-trip through a phone never
drops them.

## Phases

1. **Data layer + backup (no UI beyond a debug page).** Models, prefs, M3U/Xtream/XMLTV, guide DB, Drive sync,
   export/import round-trip test. Exit: restore the TV's backup on a phone and see the same playlists/EPGs/favorites.
2. **Live TV tab + player.** Channel list with groups/favorites/hidden/search, programme-now/next, EPG grid,
   playback through the existing mobile player (HLS→TS fallback, backup channel, audio-track memory,
   error reasons), Live TV settings page, Drive backup/restore UI, Start-Up Report. Exit: first public beta.
3. **Catch-up → On Demand → Recordings.** In that order; recordings use a foreground service writing to app
   storage / SAF-picked folder (no USB-drive UI).
4. **Sports hub.** Largest single feature; data model and matcher transliterate, UI rebuilt as a tab/sheet.

## Upstream sync (`.github/workflows/`)

- `sync-nuvio-mobile-releases.yml` — hourly check of `NuvioMedia/NuvioMobile` releases; on a new tag: merge the
  tag, build `:androidApp:assembleFullRelease`, publish under the same tag, refresh the `downloader` release.
- `merge-nuvio-mobile-release.yml` — manual; on a conflict merges with `-X ours` and opens a PR listing the files
  to fix by hand.

Secrets to add on the fork: `SYNC_TOKEN`, `NUVIO_LOCAL_PROPERTIES_BASE64`, `NUVIO_RELEASE_KEYSTORE_BASE64`.
Use a fresh keystore (`keytool -genkeypair -v -keystore release.jks -alias livetv -keyalg RSA -keysize 2048 -validity 10000`)
so the fork installs alongside the Play Store build.

Merge-conflict budget: keep every edit to an upstream file as small and additive as possible; all Live TV
code lives in `features/livetv/`. Upstream touches the tab host and settings often — those are the expected
conflict sites.
