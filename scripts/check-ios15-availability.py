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
    # The CAMetalLayer EDR assignment can sit several statements inside a
    # single #available block. The old three-line scan rejected valid guards.
    preceding = "\n".join(player_lines[max(0, index - 12):index])
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
# iOS 15: Prefer libmpv's OpenGL ES render API (with VideoToolbox), and
# keep the working SW renderer as fallback. Never run Vulkan/MoltenVK on 15.
player_source = (root / "iosApp/iosApp/Player/MPVPlayerBridge.swift").read_text()
software_source = (root / "iosApp/iosApp/Player/MPVSoftwareVideoRenderer.swift").read_text()
gl_source = (root / "iosApp/iosApp/Player/MPVLegacyGLRenderer.swift").read_text()
player_registry = player_source.split("enum NuvioPlayerRegistration")[-1]
if "MPVPlayerBridgeCreator()" not in player_registry:
    errors.append("iOS 15 playback must use internal libmpv")
if "LegacyAVPlayerBridgeCreator()" in player_registry:
    errors.append("Do not replace internal libmpv with AVPlayer")
if 'mpv_set_option_string(mpv, "vo", "libmpv")' not in player_source:
    errors.append("iOS 15 must use libmpv's embedded render output")
if "MPVLegacyGLRenderer(frame:" not in player_source or "glView.start(mpv: mpv)" not in player_source:
    errors.append("iOS 15 must first attempt internal full-resolution OpenGL ES rendering")
if 'mpv_set_option_string(mpv, "hwdec", "videotoolbox-copy")' not in player_source:
    errors.append("iOS 15 must use VideoToolbox copy-back decoding to avoid unverified GLES interop")
if not all(setting in player_source for setting in (
    'mpv_set_option_string(mpv, "target-prim", "bt.709")',
    'mpv_set_option_string(mpv, "target-trc", "srgb")',
    'mpv_set_option_string(mpv, "tone-mapping", "mobius")'
)):
    errors.append("iOS 15 needs explicit SDR color output and HDR tone mapping")
if '"gpu-api", "opengl"' not in player_source:
    errors.append("iOS 15 OpenGL path must not select Vulkan/MoltenVK")
if "private lazy var metalLayer = MetalLayer()" not in player_source:
    errors.append("iOS 15 must not instantiate a Metal layer before checking availability")
if "if usesSoftwareRenderer {" not in player_source or "MPVSoftwareVideoRenderer(imageView:" not in player_source:
    errors.append("iOS 15 needs the internal software renderer")
if not (player_source.find("softwareRenderer?.stop()") < player_source.find("mpv_terminate_destroy(ctx)")):
    errors.append("Software context must be destroyed before the mpv core")
if "mpv_render_context_create(" not in software_source or "mpv_render_context_render(" not in software_source:
    errors.append("Software video rendering must use the libmpv render API")
if "MPV_RENDER_PARAM_SW_POINTER" not in software_source:
    errors.append("Software renderer needs an aligned output surface")
if "MPV_RENDER_PARAM_API_TYPE" not in software_source:
    errors.append("Software renderer must select its API type")
if "MPV_RENDER_PARAM_API_TYPE" not in software_source:
    errors.append("Software renderer needs the SW API declaration")
if "MPV_RENDER_PARAM_SW_STRIDE" not in software_source:
    errors.append("Software renderer must specify pixel stride")
if not all(k in gl_source for k in (
    "CAEAGLLayer", "EAGLContext", "mpv_render_context_create",
    "MPV_RENDER_PARAM_OPENGL_INIT_PARAMS", "MPV_RENDER_PARAM_OPENGL_FBO",
    "mpv_render_context_render", "mpv_render_context_report_swap"
)):
    errors.append("OpenGL ES renderer must attach libmpv render API and present frames")
if not all(parameter in gl_source for parameter in (
    "MPV_RENDER_PARAM_FLIP_Y", "var flipY: CInt = 1",
    "MPV_RENDER_PARAM_DEPTH", "var framebufferDepth: CInt = 8"
)):
    errors.append("iOS 15 OpenGL output needs correct Y orientation and 8-bit framebuffer depth")
if not (player_source.find("legacyGLView?.stop()") <
        player_source.find("mpv_terminate_destroy(ctx)")):
    errors.append("OpenGL render context must close before the mpv core")
if "MPVLegacyGLRenderer.swift" not in (root / "scripts/preflight-ios15-swift.sh").read_text():
    errors.append("Main preflight must typecheck the OpenGL ES renderer")
settings_source = (root / "composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsRepository.kt").read_text()
if 'safeLegacyPlayback && externalPlayerId != null' in settings_source:
    errors.append("Never auto-enable an external player on iOS 15")
if "Typechecking libmpv software video output" not in (root / "scripts/preflight-ios15-swift.sh").read_text():
    errors.append("CI must typecheck the iOS 15 libmpv renderer before Kotlin compilation")

if errors:
    raise SystemExit("iOS 15 compatibility preflight failed:\n- " + "\n- ".join(errors))
print("iOS 15 compatibility preflight passed (full Xcode build still required)")
