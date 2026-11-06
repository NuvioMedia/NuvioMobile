<div align="center">

# Nuvio + IPTV Mobile

**Everything in Nuvio Mobile, plus everything IPTV: a Live TV guide, a Sports hub that finds every game on your own channels, catch-up, recordings, a Calendar, your provider's movies and series, and a built-in VPN for your playback, all in one app for Android phones and tablets. Your Live TV setup follows your Nuvio account, so it's the same on your phone and on your TV.**

[![Latest release](https://img.shields.io/github/v/release/homelessbrian/NuvioPlusIPTV-Mobile?include_prereleases&label=latest&style=for-the-badge)](https://github.com/homelessbrian/NuvioPlusIPTV-Mobile/releases)
[![Downloads](https://img.shields.io/github/downloads/homelessbrian/NuvioPlusIPTV-Mobile/total?style=for-the-badge)](https://github.com/homelessbrian/NuvioPlusIPTV-Mobile/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge)](LICENSE)
[![Based on Nuvio Mobile](https://img.shields.io/badge/based%20on-NuvioMedia%2FNuvioMobile-8A2BE2?style=for-the-badge)](https://github.com/NuvioMedia/NuvioMobile)
[![Discord](https://img.shields.io/badge/chat-Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/NvK5JVvvm)
[![Support on Ko-fi](https://img.shields.io/badge/support-Ko--fi-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/homelessbrian)

[Sports hub](#-sports-hub) · [Calendar](#-calendar) · [VPN](#-built-in-vpn) · [Download](#-installation) · [Features](#-features) · [Setup](#-getting-started) · [Report a bug](#-reporting-bugs-and-requesting-features) · [Discord](https://discord.gg/NvK5JVvvm) · [Support](#-support-the-project) · [TV app](https://github.com/homelessbrian/NuvioPlusIPTV)

> ### 🙌 Big thanks to [ghbarker](https://github.com/ghbarker)
> The **built-in VPN** is his work. He wrote the WireGuard and OpenVPN support and contributed it to this project, so your playback can be protected without a separate VPN app. This app is also built on his original phone port. Thank you!

</div>

---

## 📱 One setup, every screen

- **Setup Sync (Nuvio Account Sync):** sign in to Nuvio and your playlists, guides, favorites, Calendar follows, VPN profiles and settings come back by themselves, on your phone, tablet and TV. Change something on one and it shows up on the others. You can **choose what to sync**.
- **Works with the TV app:** [Nuvio + IPTV](https://github.com/homelessbrian/NuvioPlusIPTV) for Google TV, Android TV and Fire TV uses the same setup. Set it up once, on whichever screen is easier.
- **Encrypted:** your saved setup (which includes your IPTV logins) is encrypted before it's stored.
- **Phone-only choices stay on the phone:** things like Channel Display are just for this device, so your TV looks the way you left it.

---

## 🏆 Sports hub

Sports shows scores and schedules, and works out **which of your own channels are showing each game**.

- **My Sports:** Live & Upcoming first, then a row for every team, league and name you follow, in your teams' colours.
- **All Events:** every game in your region's leagues plus everything you follow, with **day tabs** from a week back to a week ahead.
- **Follow anything:** teams, countries, whole leagues, or a **name** (a player, driver or fighter). One search finds teams and leagues alike.
- **200+ leagues:** NFL, NBA, NHL, MLB, MLS, college sports, the Premier League and the rest of UK and European football, rugby, cricket, F1, NASCAR, IndyCar, golf, tennis, UFC and more. Your own TV guide fills the gaps.
- **Every channel showing the game,** best match first. HD, FHD and SD copies of a channel share one row, and **Channels from** shows one region's or language's channels first. Your own country's TV channels are added to each game, national channels first.
- **League Networks:** each league's own channel in one place (NFL Network, NBA TV, Golf Channel and more), with the option to pick one by hand.
- **Sports addons:** have a sports addon installed in Nuvio, like **HighFly** or **StremVerse**? Sports uses it too, with nothing to set up.
  - **Found automatically:** open a game and the app looks for it in your addons. While the page is open and nothing has shown up yet, it keeps checking, since streams usually appear close to the start.
  - **Live streams in the channel list:** a game that's on shows the addons' streams after your own channels.
  - **Replays & highlights:** a finished game gets its own list of replays, highlights and recaps.
  - **Recording and picking a channel** work on your own playlist channels, not on an addon's streams.
- **Choose leagues:** a row on All Events turns any league on or off.
- **Tables:** league tables with form, place changes and playoff lines, and your team marked.
- **Game pages:** the score by period, scoring as it happens, team stats, line-ups, leaderboards and fight cards.
- **Never miss it:** reminders, **Game Alerts** (offered when you first follow a team) with the live score while your team plays and a heads-up when a game starts, even with the app closed, and **Record a game** with extra time for overtime.
- **Spoiler-free:** **Hide Scores** keeps results off the screen until you're ready.

---

## 📅 Calendar

See what's coming for the things you care about: new episodes of the shows you follow, movies, and your teams' games.

- **Views:** List, Week or Month.
- **Airing today, Recently aired and Following,** with a sheet to follow or hide shows.
- **Filters:** On my TV (episodes that are in your guide) and Sports.
- **Watch live, Record, Remind or Choose channel** for an episode found in your guide, and for your teams' games.
- **Follow a show** with the bell on its page or in Live TV. Choose its channel once with **Always use** and it's remembered.
- **On Home:** new-episode cards and an **Upcoming** row for what's coming this week.
- **The same as your TV:** followed and hidden shows are part of your synced setup.

---

## 🔐 Built-in VPN

Protect what you watch without installing a separate VPN app. **Settings → VPN** imports a **WireGuard** or **OpenVPN** profile from your VPN provider and uses it for your playback.

> **Thank you to [ghbarker](https://github.com/ghbarker),** who built the VPN and contributed it to Nuvio + IPTV. This feature exists because of him.

- **Choose how much it protects:** Off, HTTP streams only, HTTP + HTTPS streams, or the Full app (Android asks you to allow it).
- **Import your provider's file:** WireGuard configs and self-contained OpenVPN profiles (UDP or TCP). If the profile needs a username and password, the app asks for them.
- **One choice for every profile,** or a separate one for just the Nuvio profile you're using.
- **Comes with your setup:** your VPN profiles are carried to your other devices by Setup Sync and encrypted on each one.
- The same VPN is in the [TV app](https://github.com/homelessbrian/NuvioPlusIPTV).

---

## ✨ Features

### 📺 Live TV, made for touch
- **Group cards:** each group shows its first channel logos and how many channels it has. Tap to open it, and its name stays pinned at the top while you scroll.
- **Guide:** channels down the side and a timeline across, with what's on now highlighted, a progress bar and a "now" line (which you can hide).
- **Playlist menu:** with more than one playlist, pick one in the group view, the guide and On Demand.
- **Channel Display:** show logos, names, numbers, or any mix. Logos only fills each tile with the logo.
- **Favorites** and **Recently watched**, plus a star on the logo of your favorite channels. **Pick favorites** puts a star on each group: open it and star many channels at once, with Select all, Clear and one Save.
- **Change logo:** in a channel's long-press menu, pick a logo from your playlists and guides, tv-logos, iptv-org or TMDB, or paste a link.

### 🎬 While watching
- **Channel panel:** a Channels button in the player opens your groups and a mini guide beside the video (below it when your phone is upright, along the bottom on a tablet). Switch channels without leaving the player.
- **Swipe to change channel:** swipe up or down in the middle of the picture.
- **Catch-up:** watch past shows with a seek bar, or restart what's on now. The app remembers which kind of replay link your provider answers and tries it first.
- **Reminders** for upcoming shows, with a notification.

### 📡 Your sources, your way
- **Multiple M3U playlists** and **multiple XMLTV guides,** merged into one guide.
- **Xtream logins,** with separate switches for TV channels and for movies & series.
- **Smart guide matching** (fine-tune any channel with **Assign EPG**), plus guide and catch-up time shifts for providers that are off by an hour or more.

### ⏺️ Recordings
- **Record a show** from the guide or a game from Sports, and watch it later in **Recordings**.

### 🗂️ Channel management
- **Hide, reorder and rename** channels and groups.
- **New groups** you add to a playlist show up next to their neighbours, and hiding a playlist group never empties your own groups.

### 🎞️ On Demand
- **Movies and series from your Xtream provider,** with categories, search and sorting.
- **📡 On Demand in the source list:** your provider's copy shows up next to your addons' sources on a title's page, best quality first, with quality tags. Choose where its tab goes, and whether auto-play may pick it.
- **Adult titles** never appear in Continue watching.

### 🔒 Parental controls
- **PIN locks** for any group or category. Adult content locks automatically.

### 🔎 Part of Nuvio, not bolted on
- Everything you get in Nuvio Mobile is still here: your addons, library, Continue Watching, Trakt, Simkl and MDBList, subtitles and the player.
- Live TV, Sports, Calendar, On Demand and Recordings sit inside the app, with their settings in Nuvio's own Settings.
- **Search** finds Live TV channels and On Demand movies and series along with everything else.

---

## 📥 Installation

1. Open the **[latest release](https://github.com/homelessbrian/NuvioPlusIPTV-Mobile/releases)**.
2. Download the APK for your device:

   | Device | APK |
   |---|---|
   | Almost every phone and tablet from the last several years | `arm64-v8a` |
   | Older or budget phones | `armeabi-v7a` |
   | Chromebooks and emulators | `x86_64` |

   Not sure? Pick `arm64-v8a`. If it won't install, try `armeabi-v7a`.

3. Open the downloaded file and tap **Install**, allowing installs from unknown sources when asked.

Requires **Android 7.0 or newer**. It installs **alongside** the official Nuvio app and other Nuvio IPTV apps.

**Updating:** updates appear **inside the app**.

---

## 🚀 Getting started

1. **Sign in to Nuvio.** If you already use Nuvio + IPTV on your TV with the same account, your Live TV setup comes over by itself, and you can skip to step 4.
2. Open **Settings → Live TV** and choose **Add M3U playlist** or **Add Xtream login**. Turn on **Import movies & series** if you want your provider's movies and series too.
3. Optionally **Add EPG source** for an extra TV guide.
4. Open **Live TV**, **Sports** or **On Demand**. The first load can take a minute with big providers.
5. In **Sports**, follow your teams and leagues. Set your region, reminders and Game Alerts in Sports settings.
6. Open **Calendar** and follow the shows you watch.
7. Optional: import a WireGuard or OpenVPN profile under **Settings → VPN** to protect your playback.

---

## 🐞 Reporting bugs and requesting features

Questions and chat are welcome on **[Discord](https://discord.gg/NvK5JVvvm)**. Please search [existing issues](https://github.com/homelessbrian/NuvioPlusIPTV-Mobile/issues) first, then [open a new one](https://github.com/homelessbrian/NuvioPlusIPTV-Mobile/issues/new). Say which phone or tablet you use and its Android version.

**If something in Sports is wrong,** say which game and which channel you expected.

Problems with Nuvio's movies, shows, addons or accounts that also happen in the official app belong with [Nuvio](https://github.com/NuvioMedia/NuvioMobile/issues). Problems with the TV app go to [Nuvio + IPTV](https://github.com/homelessbrian/NuvioPlusIPTV/issues).

---

## ❓ FAQ

<details>
<summary><b>Does this include any channels or movies?</b></summary>

No. It only plays what you add yourself: your own playlists, guides, Xtream logins, addons and debrid services.
</details>

<details>
<summary><b>Do I need the TV app?</b></summary>

No. The phone app works on its own. If you have both, sign in to the same Nuvio account and they share one Live TV setup.
</details>

<details>
<summary><b>My TV setup didn't show up on my phone.</b></summary>

Make sure both are signed in to the same Nuvio account and that **Setup Sync** is on in **Settings → Live TV** on the TV (and **Setup Sync** on the phone). Then open Live TV on the phone, or tap **Sync Now**.
</details>

<details>
<summary><b>I don't see On Demand.</b></summary>

Turn on **Import movies & series** for your playlist in **Settings → Live TV**. On Demand appears once the first import finishes.
</details>

<details>
<summary><b>Sports shows the wrong channel for a game, or none at all.</b></summary>

Open the game and pick the right channel. It's remembered from then on. If your team or league is missing, search for it when following: search covers every league, not just your region's.
</details>

<details>
<summary><b>Some channels show "Programming" or the wrong listings.</b></summary>

Choose **Assign EPG** in the channel's menu. If every channel is off by the same amount, use the guide time shift in **Settings → Live TV**.
</details>

<details>
<summary><b>Where do the scores come from?</b></summary>

Scores, schedules, tables, team logos and colours come from ESPN's free public feed, plus HockeyTech, MLB's stats service and TheSportsDB for leagues ESPN doesn't cover. Sports they don't cover are found in your own TV guide.
</details>

<details>
<summary><b>How do I use the VPN?</b></summary>

Open **Settings → VPN**, import the WireGuard config or OpenVPN profile your provider gives you, and pick how much to protect: HTTP streams only, HTTP + HTTPS, or the full app. If the profile asks for a username and password, enter them when prompted.
</details>

<details>
<summary><b>Which VPN files work?</b></summary>

WireGuard configs and OpenVPN profiles that contain everything in one file (the certificates inside it, not separate files). Profiles that need extra files, scripts or interactive login aren't supported yet.
</details>

<details>
<summary><b>What does Nuvio Account Sync store, and where?</b></summary>

Your Live TV setup, encrypted, linked to your Nuvio account. Nothing is written to Nuvio's own servers. See the [privacy policy](https://homelessbrian.github.io/privacy.html).
</details>

---

## ☕ Support the project

Nuvio + IPTV is free. If it's made your phone or TV better and you'd like to say thanks, you can buy me a coffee on **[Ko-fi](https://ko-fi.com/homelessbrian)**. It's completely optional, and every bit helps keep updates coming.

[![Support me on Ko-fi](https://img.shields.io/badge/Buy%20me%20a%20coffee-Ko--fi-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/homelessbrian)

---

## 🙏 Credits

- **[Nuvio Mobile](https://github.com/NuvioMedia/NuvioMobile)** by the NuvioMedia team: this app is built on their work.
- **🙌 [ghbarker](https://github.com/ghbarker): a huge thank you.** He built the **built-in VPN** (WireGuard and OpenVPN playback protection) and contributed it to this project. His [port of Nuvio Mobile with IPTV](https://github.com/ghbarker/NuvioMobile-IPTV) is also what this app is built on, with his permission.
- Live TV and Sports come from **[Nuvio + IPTV](https://github.com/homelessbrian/NuvioPlusIPTV)** for TV.
- Sports data from **ESPN**'s public feed, **[TheSportsDB](https://www.thesportsdb.com)**, **HockeyTech** and **MLB**'s stats service. Pictures and logos from **[TMDB](https://www.themoviedb.org)**. Nuvio + IPTV isn't affiliated with any of them, and team and league names and logos belong to their owners.
- Built with Kotlin Multiplatform and Compose Multiplatform.

Nuvio + IPTV Mobile is unofficial and is **not affiliated with or endorsed by NuvioMedia**. Please report issues here, not to the Nuvio team.

## ⚖️ Legal

This app is a client-side player. It does not host, store or distribute any media, and it ships with no channels, playlists or content sources. Only use sources you own or are authorized to access.

Licensed under the **GNU General Public License v3.0**, like upstream Nuvio. See [LICENSE](LICENSE).
