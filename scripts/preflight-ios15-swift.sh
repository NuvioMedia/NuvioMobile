#!/usr/bin/env bash
# SDK availability preflight that runs before Kotlin/Native linking.
# Swift files that import the generated ComposeApp framework cannot be
# independently typechecked until that framework exists. We typecheck
# the standalone native files and parse the full Swift source tree.
set -euo pipefail

cd "$(dirname "$0")/.."
sdk="$(xcrun --sdk iphoneos --show-sdk-path)"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-swift15-preflight.XXXXXX")"
trap 'rm -rf "${scratch}"' EXIT

echo "Parsing all Swift source files ..."
find iosApp -type f -name '*.swift' -print0 | xargs -0 xcrun swiftc -frontend -parse

echo "Typechecking iOS 15 WidgetKit extension ..."
xcrun swiftc \
  -target arm64-apple-ios15.0 \
  -sdk "${sdk}" \
  -application-extension \
  -parse-as-library \
  -typecheck \
  -module-name DownloadsWidgetExtension \
  iosApp/DownloadsWidgetExtension/DownloadsLiveActivityWidget.swift \
  iosApp/DownloadsWidgetExtension/DownloadsWidgetBundle.swift

# MetalLayer references one project-specific PiP helper. Supply only its
# declaration here so the real CAMetalLayer implementation is checked
# against the iOS 15 SDK before the expensive Kotlin framework is built.
cat > "${scratch}/PlayerPictureInPictureControllerStub.swift" <<'SWIFT'
import CoreVideo
final class PlayerPictureInPictureController {
    func submit(_ pixelBuffer: CVPixelBuffer, pts: Double, isPictureInPictureFrame: Bool) {}
}
SWIFT

echo "Typechecking CAMetalLayer subclass against iOS 15 ..."
xcrun swiftc \
  -target arm64-apple-ios15.0 \
  -sdk "${sdk}" \
  -parse-as-library \
  -typecheck \
  -module-name NuvioMetalLayerPreflight \
  "${scratch}/PlayerPictureInPictureControllerStub.swift" \
  iosApp/iosApp/Player/MetalLayer.swift

# Typecheck the safe AVPlayer bridge against the iOS 15 SDK.
cat > "${scratch}/ComposeAppStub.swift" <<'SWIFT'
import Foundation
public protocol NuvioPlayerBridge {}
public protocol NuvioPlayerBridgeCreator {
    func createBridge() -> any NuvioPlayerBridge
}
public class PlayerSystemControlHandler {}
public class SubtitleTextHandler {}
SWIFT
xcrun swiftc -target arm64-apple-ios15.0 -sdk "${sdk}" -parse-as-library \
  -emit-module -module-name ComposeApp \
  -emit-module-path "${scratch}/ComposeApp.swiftmodule" \
  "${scratch}/ComposeAppStub.swift"
echo "Typechecking native AVPlayer fallback for iOS 15 ..."
xcrun swiftc -target arm64-apple-ios15.0 -sdk "${sdk}" \
  -I "${scratch}" -parse-as-library -typecheck \
  -module-name NuvioLegacyPlayerPreflight \
  iosApp/iosApp/Player/PlayerAudioSessionController.swift \
  iosApp/iosApp/Player/LegacyAVPlayerBridge.swift

# Download the exact binary release used by the pinned upstream MPVKit
# package and typecheck the SW render API without compiling Kotlin/Native.
echo "Typechecking libmpv software video output for iOS 15 ..."
curl --fail --location --retry 3 --silent --show-error --max-time 240 \
  --output "${scratch}/Libmpv.zip" \
  https://github.com/mpvkit/MPVKit/releases/download/0.41.0/Libmpv.xcframework.zip
unzip -q "${scratch}/Libmpv.zip" -d "${scratch}/mpv"
xcrun swiftc \
  -target arm64-apple-ios15.0 \
  -sdk "${sdk}" \
  -F "${scratch}/mpv/Libmpv.xcframework/ios-arm64" \
  -parse-as-library -typecheck \
  iosApp/iosApp/Player/MPVSoftwareVideoRenderer.swift \
  iosApp/iosApp/Player/MPVLegacyGLRenderer.swift

echo "Standalone iOS 15 Swift API checks passed."
