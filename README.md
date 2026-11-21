## This fork: Nuvio Auto

The full Nuvio app on the **Android Auto** screen, built the way [YT Auto](https://github.com/begati/yt-auto) works.
It installs next to the official app as `com.dave.nuvioauto` ("Nuvio Auto" on the phone, "Nuvio" in the car).

**How it works.** Two entry points, like YT Auto: a projection service on the unofficial Android Auto SDK
(`LegacyCarService`, real touches) and a Car App Library navigation app (`NuvioCarAppService`), since Android Auto
only gives a drawing surface to navigation apps. Both use `CarDisplayHost`, which puts a private virtual display on the car surface and shows the whole Nuvio UI on it in a
Presentation window (`CarNuvioWindow`), like YT Auto does with YouTube: same UI, account, addons and player.
(Android 15+ won't start even an app's own activity on a virtual display it made, so no activity is used.) Car taps, drags and flings are replayed as finger
touches. Code: `androidApp/src/main/kotlin/com/nuvio/app/car/`.

**Get the APK.** Actions > **Nuvio Auto** > latest run > download `NuvioAuto-<n>-arm64.apk`.
Every push to `android-auto` builds one. Set the `NUVIO_AUTO_KEYSTORE_B64` secret so builds install over each other.

**Install.**
1. Install the APK normally. Android Auto settings: tap **Version** 10 times, then ⋮ > **Developer settings** >
   **Unknown sources**. Restart the phone (or force-stop Android Auto).
2. Android Auto settings > **Customize launcher**: tick **Nuvio**.
3. Still missing? Reinstall with [KingInstaller](https://github.com/fcaronte/KingInstaller/releases)
   ("Install as King"), so Android Auto treats it as a Play Store app.
4. Open **Nuvio Auto** on the phone once and sign in. Open **Nuvio Auto Setup** for screen size and the
   driving video lock.

**Setup.** Every in-car extra has its own switch in Nuvio Auto Setup, plus Basic mode (all extras off at once),
reset, and a shareable car log. Extras: car profile and car lock, continue-watching prompt, fill-screen video,
bigger buffer, auto-skip intros, auto next episode, subtitle size and background, steering wheel mapping,
pause on disconnect, data saver, audio-only and next-episode buttons, Downloads shortcut, button bar position
and timeout, phone remote with Quick Settings tile.

**Extras.** Car keyboard search with recent searches; buttons that switch to playback controls while watching;
"Continue watching?" when the car connects; a phone remote for passengers (notification while connected);
data saver that puts 720p-or-lower streams first on mobile data. All toggles are in Nuvio Auto Setup.

**In the car.** Tap to select, drag to scroll (drags start from the middle of the screen). Buttons: Back,
Keyboard (tap Nuvio's search box first), Home, Screen size. Video is covered while the phone's GPS says you're
moving, unless you turn that off in Setup. Only watch while parked.

<div align="center">

  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio" width="320" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/NuvioMedia/NuvioMobile/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## Get Nuvio Mobile

- [Android on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app)
- [Android APK](https://github.com/NuvioMedia/NuvioMobile/releases/latest)
- iOS via AltStore or SideStore: add [this source URL](https://raw.githubusercontent.com/NuvioMedia/NuvioMobile/cmp-rewrite/store.json) in the app's Sources section, then install Nuvio.
- iOS beta builds: add [the beta source URL](https://raw.githubusercontent.com/NuvioMedia/NuvioMobile/cmp-rewrite/store-beta.json) instead to get pre-releases as well.

## Build from source

```bash
git clone https://github.com/NuvioMedia/NuvioMobile.git
cd NuvioMobile
```

### Android

Android development requires Android Studio and the Android SDK.

```bash
./gradlew :androidApp:assembleFullDebug
```

### iOS

iOS development requires macOS and Xcode.

```bash
env NUVIO_IOS_DISTRIBUTION=full xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -derivedDataPath build/ios-derived-full-simulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```

The shared app is built with Kotlin Multiplatform and Compose Multiplatform.

## License

[GNU General Public License v3.0](./LICENSE)
