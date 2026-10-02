<div align="center">

  <h1>Lentra</h1>

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Lentra turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [GitHub releases](https://github.com/frRitamDas/LentraOriginal/releases/latest) · [Report an issue](https://github.com/frRitamDas/LentraOriginal/issues)

</div>

## Get Lentra

- [Android APK](https://github.com/frRitamDas/LentraOriginal/releases/latest) — every push to this repository builds and publishes signed APKs automatically.
- In-app updates: the app only follows the releases of this repository, so whatever you push here is what installed apps receive as an update.

## Automatic builds and updates

Pushing to any branch runs [`.github/workflows/lentra-auto-release.yml`](./.github/workflows/lentra-auto-release.yml), which

1. derives the next version from `iosApp/Configuration/Version.xcconfig` (`<version>-auto.<run number>`),
2. builds the signed `full` release APKs (one per ABI),
3. publishes them as a GitHub release with the APKs attached.

The in-app updater reads `AppUpdaterRepository.RELEASE_REPOSITORY` (default `frRitamDas/LentraOriginal`) through the GitHub releases API. Because the automatic version carries an `-auto.` suffix, installed builds default to the **Beta** update channel and pick up every pushed build; the Stable channel only uses plain `x.y.z` releases.

Builds are signed with the keystore committed at `.github/ci-signing/lentra-release.jks` so consecutive builds share one signing identity and can update each other in place. Replace it with your own keystore by adding these repository secrets: `LENTRA_KEYSTORE_BASE64`, `LENTRA_KEYSTORE_PASSWORD`, `LENTRA_KEY_ALIAS`, `LENTRA_KEY_PASSWORD`.

Optional runtime configuration (TMDB key, Supabase backend, tracker client ids) can be provided as the `NUVIO_LOCAL_PROPERTIES_BASE64` secret, which is decoded into `local.properties` before the build. Without it the app still builds, and keys can be entered from the app settings.

## Build from source

```bash
git clone https://github.com/frRitamDas/LentraOriginal.git
cd LentraOriginal
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

## Credits and license

Lentra is based on [Nuvio Mobile](https://github.com/NuvioMedia/NuvioMobile) and remains licensed under the [GNU General Public License v3.0](./LICENSE). Upstream attribution is preserved in the in-app licenses screen.
