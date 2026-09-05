<div align="center">
           &nbsp;&nbsp; &nbsp;&nbsp; &nbsp;&nbsp;
            &nbsp;&nbsp; &nbsp;&nbsp; &nbsp;&nbsp;
   &nbsp;&nbsp; &nbsp;&nbsp; &nbsp;&nbsp;
            &nbsp;&nbsp; &nbsp;&nbsp; &nbsp;&nbsp;
  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio" width="320" />

  <br />

  <p>A free, open-source media app for your phone, your desktop, and the TV you already own.</p>

  <p>
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  <p>
    <a href="https://nuvio.tv">Website</a>
    ·
    <a href="https://github.com/NuvioMedia/NuvioMobile/releases">GitHub Releases</a>
    ·
    <a href="https://nuvio.tv">Support Nuvio</a>
  </p>


  <br />

  <img src="https://img.shields.io/github/v/release/NuvioMedia/NuvioMobile?style=for-the-badge&color=2596be&labelColor=1e1e2e&logo=github" alt="Latest Version" />
  <img src="https://img.shields.io/github/downloads/NuvioMedia/NuvioMobile/total?style=for-the-badge&color=2596be&labelColor=1e1e2e&logo=github" alt="Downloads" />
  <img src="https://img.shields.io/github/stars/NuvioMedia/NuvioMobile?style=for-the-badge&color=2596be&labelColor=1e1e2e&logo=github" alt="Stars" />
  <img src="https://img.shields.io/github/license/NuvioMedia/NuvioMobile?style=for-the-badge&color=2596be&labelColor=1e1e2e" alt="License" />

  <br />

  <img src="https://img.shields.io/badge/Language-Kotlin-6366f1?style=for-the-badge&logo=kotlin&logoColor=white&labelColor=1e1e2e" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Kotlin_Multiplatform-6366f1?style=for-the-badge&logo=kotlin&logoColor=white&labelColor=1e1e2e" alt="Kotlin Multiplatform" />
  <img src="https://img.shields.io/badge/Compose_Multiplatform-6366f1?style=for-the-badge&logo=jetpackcompose&logoColor=white&labelColor=1e1e2e" alt="Compose Multiplatform" />

  <br />
  <br />

  <a href="https://trendshift.io/repositories/22977?utm_source=repository-badge&amp;utm_medium=badge&amp;utm_campaign=badge-repository-22977" target="_blank" rel="noopener noreferrer">
    <img src="https://trendshift.io/api/badge/repositories/22977" alt="NuvioMedia/NuvioMobile | Trendshift" width="250" height="55" />
  </a>

</div>

---

## Get Nuvio Mobile

* [Android on Google Play](https://play.google.com/)
* [Android APK](https://github.com/NuvioMedia/NuvioMobile/releases)
* iOS must be built from source.

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

[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.en.html)
