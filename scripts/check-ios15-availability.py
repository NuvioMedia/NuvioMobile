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

if errors:
    raise SystemExit("iOS 15 compatibility preflight failed:\n- " + "\n- ".join(errors))
print("iOS 15 compatibility preflight passed (full Xcode build still required)")
