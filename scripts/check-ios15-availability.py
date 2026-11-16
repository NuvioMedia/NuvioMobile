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

# Check the build's explicit optimization fallback and artifact convention.
build_script = (root / "scripts/build-ios-ipa.sh").read_text()
release_workflow = (root / ".github/workflows/ios15-release.yml").read_text()
if 'KOTLIN_FRAMEWORK_BUILD_TYPE="${kotlin_framework_build_type}"' not in build_script:
    errors.append("IPA builder must explicitly set Kotlin framework build type")
if '-kotlin-debug' not in build_script or '*-full-release-kotlin-debug.ipa' not in release_workflow:
    errors.append("IPA filename and CI validation must agree on Kotlin Debug suffix")
if "IOS_KOTLIN_FRAMEWORK_BUILD_TYPE: Debug" not in release_workflow:
    errors.append("7 GB iOS 15 runner must use the unoptimized Kotlin/Native framework")
if "bash scripts/preflight-ios15-swift.sh" not in release_workflow:
    errors.append("CI must typecheck standalone Swift files before Kotlin compilation")
if errors:
    raise SystemExit("iOS 15 compatibility preflight failed:\n- " + "\n- ".join(errors))
print("iOS 15 compatibility preflight passed (full Xcode build still required)")
