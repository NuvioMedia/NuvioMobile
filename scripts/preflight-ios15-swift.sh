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

echo "Standalone iOS 15 Swift API checks passed."
