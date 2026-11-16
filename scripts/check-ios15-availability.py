#!/usr/bin/env python3
"""Fast iOS 15 regression checks before the costly Kotlin/Native IPA build.

This catches the specific newer-API mistakes observed in the release CI logs.
It is deliberately not a replacement for Xcode's availability type-checker.
"""
from pathlib import Path

root = Path(__file__).resolve().parent.parent
player = root / "iosApp/iosApp/Player/MPVPlayerBridge.swift"
metal = root / "iosApp/iosApp/Player/MetalLayer.swift"
content = root / "iosApp/iosApp/ContentView.swift"
project = root / "iosApp/iosApp.xcodeproj/project.pbxproj"
errors = []

player_lines = player.read_text().splitlines()
edr_calls = [
    index
    for index, line in enumerate(player_lines)
    if "metalLayer.wantsExtendedDynamicRangeContent =" in line
]
if len(edr_calls) != 2:
    errors.append(f"Expected two player EDR assignments; found {len(edr_calls)}")
for index in edr_calls:
    preceding = "\n".join(player_lines[max(0, index - 3):index])
    if "if #available(iOS 16.0, *) {" not in preceding:
        errors.append(
            f"{player.relative_to(root)}:{index + 1}: "
            "EDR assignment needs an iOS 16 availability guard"
        )

metal_source = metal.read_text()
if "@available(iOS 16.0, *)\n    override var wantsExtendedDynamicRangeContent" not in metal_source:
    errors.append("MetalLayer EDR override needs iOS 16 availability annotation")

content_source = content.read_text()
if "@available(iOS 16.0, *)\nprivate enum NuvioNativeTabIcon" not in content_source:
    errors.append("NuvioNativeTabIcon must be iOS 16+ only")

project_source = project.read_text()
if project_source.count("IPHONEOS_DEPLOYMENT_TARGET = 15.0;") != 6:
    errors.append("App, widget and test deployment targets must all be iOS 15.0")

# The Kotlin integration task uses Xcode's CONFIGURATION to select Debug or
# Release. A mismatched override was silently ignored by Gradle, causing OOM.
# Enforce a consistent Debug compatibility build and artifact name.
build_script = (root / "scripts/build-ios-ipa.sh").read_text()
release_workflow = (root / ".github/workflows/ios15-release.yml").read_text()
if 'KOTLIN_FRAMEWORK_BUILD_TYPE="${kotlin_framework_build_type}"' not in build_script:
    errors.append("IPA builder must explicitly set Kotlin framework build type")
if '*-full-debug.ipa' not in release_workflow or 'full-${configuration_slug}.ipa' not in build_script:
    errors.append("IPA filename and CI validation must agree on Debug output")
if 'IOS_CONFIGURATION: Debug' not in release_workflow:
    errors.append("CI must build the iOS app in Debug to select the Debug Kotlin linker")
if 'if [[ "${configuration}" != "${kotlin_framework_build_type}" ]]; then' not in build_script:
    errors.append("IPA builder must reject mismatched Xcode/Kotlin configuration")
if "IOS_KOTLIN_FRAMEWORK_BUILD_TYPE: Debug" not in release_workflow:
    errors.append("7 GB iOS 15 runner must use the Debug Kotlin/Native framework")
if "bash scripts/preflight-ios15-swift.sh" not in release_workflow:
    errors.append("CI must typecheck standalone Swift files before Kotlin compilation")
gradle_build = (root / "composeApp/build.gradle.kts").read_text()
if 'officialNuvioBackendUrl = "https://api.nuvio.tv"' not in gradle_build:
    errors.append("Official Nuvio Cloud backend fallback is missing")
if 'officialNuvioPublishableKey = "sb_publishable_' not in gradle_build:
    errors.append("Official Nuvio Cloud public client key fallback is missing")
if "python3 scripts/verify-nuvio-auth-config.py" not in release_workflow:
    errors.append("Login configuration verification must precede native compilation")
# Pin official libmpv instead of the limited iOS 15 AVPlayer fallback.
player_source = (root / "iosApp/iosApp/Player/MPVPlayerBridge.swift").read_text()
registry = player_source.split("enum NuvioPlayerRegistration")[-1]
if "NuvioPlayerBridgeFactory.shared.registerFactory(creator: MPVPlayerBridgeCreator())" not in registry:
    errors.append("libmpv must be registered on iOS 15")
if "LegacyAVPlayerBridgeCreator()" in registry:
    errors.append("AVPlayer must not replace libmpv in this build")
if "if #available(iOS 16.0, *) {\n            let pip" not in player_source:
    errors.append("Nuvio custom PiP is untested on iOS 15; guard its setup")
if "https://github.com/mpvkit/MPVKit.git" not in (root / ".gitmodules").read_text():
    errors.append("Use official MPVKit instead of Nuvio's customized libmpv")
if "Verify pinned upstream MPVKit supporting iOS 15" not in release_workflow:
    errors.append("Pin the iOS 15 MPVKit commit in CI")

if errors:
    raise SystemExit("iOS 15 compatibility preflight failed:\n- " + "\n- ".join(errors))
print("iOS 15 compatibility preflight passed (full Xcode build still required)")
