#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
version_file="${repository_root}/iosApp/Configuration/Version.xcconfig"
version="${1:-$(sed -nE 's/^[[:space:]]*MARKETING_VERSION[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\1/p' "${version_file}" | head -n 1)}"
configuration="${IOS_CONFIGURATION:-Release}"
case "${configuration}" in
    Debug)
        configuration_slug="debug"
        ;;
    Release)
        configuration_slug="release"
        ;;
    *)
        echo "Unsupported iOS configuration: ${configuration}" >&2
        exit 1
        ;;
esac
derived_data="${IOS_DERIVED_DATA_PATH:-${repository_root}/build/ios-derived-full-${configuration_slug}}"
output_directory="${IOS_IPA_OUTPUT_DIR:-${repository_root}/build/ios-ipa}"
clang_module_cache="${CLANG_MODULE_CACHE_PATH:-${derived_data}/ModuleCache.noindex}"
swiftpm_module_cache="${SWIFTPM_MODULECACHE_OVERRIDE:-${derived_data}/SwiftPMModuleCache.noindex}"

if [[ ! "${version}" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
    echo "Invalid IPA version: ${version}" >&2
    exit 1
fi

cd "${repository_root}"
build_environment=(
    env
    NUVIO_IOS_DISTRIBUTION=full
    CLANG_MODULE_CACHE_PATH="${clang_module_cache}"
    SWIFTPM_MODULECACHE_OVERRIDE="${swiftpm_module_cache}"
)
if [[ -n "${NUVIO_GRADLE_JVMARGS:-}" ]]; then
    build_environment+=("ORG_GRADLE_PROJECT_org.gradle.jvmargs=${NUVIO_GRADLE_JVMARGS}")
fi
if [[ -n "${NUVIO_KOTLIN_NATIVE_JVMARGS:-}" ]]; then
    build_environment+=("ORG_GRADLE_PROJECT_kotlin.native.jvmArgs=${NUVIO_KOTLIN_NATIVE_JVMARGS}")
fi
"${build_environment[@]}" \
    xcodebuild \
    -project iosApp/iosApp.xcodeproj \
    -scheme iosApp \
    -configuration "${configuration}" \
    -sdk iphoneos \
    -destination 'generic/platform=iOS' \
    -derivedDataPath "${derived_data}" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGN_IDENTITY= \
    build

app_path="${derived_data}/Build/Products/${configuration}-iphoneos/Lentra.app"
if [[ ! -d "${app_path}" ]]; then
    echo "iOS build did not produce ${app_path}." >&2
    exit 1
fi

built_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "${app_path}/Info.plist")"
if [[ "${built_version}" != "${version}" ]]; then
    echo "Built iOS version ${built_version} does not match ${version}." >&2
    exit 1
fi

executable="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "${app_path}/Info.plist")"
architectures="$(xcrun lipo -archs "${app_path}/${executable}")"
if [[ " ${architectures} " != *" arm64 "* ]]; then
    echo "Built iOS application does not contain arm64." >&2
    exit 1
fi
if ! launch_screen_plist="$(plutil -extract UILaunchScreen xml1 -o - "${app_path}/Info.plist" 2>/dev/null)"; then
    echo "Built iOS application does not contain UILaunchScreen." >&2
    exit 1
fi
if [[ "${launch_screen_plist}" == *"<key>UILaunchScreen</key>"* ]]; then
    echo "Built iOS application contains a nested UILaunchScreen." >&2
    exit 1
fi
if [[ -d "${app_path}/_CodeSignature" ]]; then
    echo "Built iOS application is unexpectedly signed." >&2
    exit 1
fi

widget_path="${app_path}/PlugIns/DownloadsWidgetExtension.appex"
if [[ ! -d "${widget_path}" ]]; then
    echo "Built iOS application does not contain the downloads widget." >&2
    exit 1
fi
widget_executable="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "${widget_path}/Info.plist")"
widget_architectures="$(xcrun lipo -archs "${widget_path}/${widget_executable}")"
if [[ " ${widget_architectures} " != *" arm64 "* ]]; then
    echo "Built downloads widget does not contain arm64." >&2
    exit 1
fi

mkdir -p "${output_directory}"
output_directory="$(cd "${output_directory}" && pwd -P)"
package_root="$(mktemp -d "${TMPDIR:-/tmp}/lentra-ios-ipa.XXXXXX")"
trap 'rm -rf "${package_root}"' EXIT
mkdir -p "${package_root}/Payload"
ditto "${app_path}" "${package_root}/Payload/Lentra.app"

ipa_path="${output_directory}/lentra-${version}-full-${configuration_slug}.ipa"
temporary_ipa="${package_root}/lentra-${version}-full-${configuration_slug}.ipa"
(
    cd "${package_root}"
    /usr/bin/zip -qry "${temporary_ipa}" Payload
)
unzip -tq "${temporary_ipa}"
mv "${temporary_ipa}" "${ipa_path}"

echo "Created ${ipa_path}"
