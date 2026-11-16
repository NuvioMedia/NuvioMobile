#!/usr/bin/env bash

set -euo pipefail

version_file="${VERSION_FILE:-iosApp/Configuration/Version.xcconfig}"
target_ref="${1:-HEAD}"

if ! git cat-file -e "${target_ref}^{commit}" 2>/dev/null; then
    echo "Unknown release target: ${target_ref}" >&2
    exit 1
fi

read_value() {
    local commit="$1"
    local key="$2"
    git show "${commit}:${version_file}" \
        | sed -nE "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\\1/p" \
        | head -n 1
}

current_version=""
current_build=""
current_bump=""
previous_version=""
previous_build=""
previous_bump=""

while IFS= read -r commit; do
    version="$(read_value "$commit" MARKETING_VERSION)"
    [[ -n "$version" ]] || continue

    if [[ -z "$current_version" ]]; then
        current_version="$version"
        current_build="$(read_value "$commit" CURRENT_PROJECT_VERSION)"
        current_bump="$commit"
    elif [[ "$version" != "$current_version" ]]; then
        previous_version="$version"
        previous_build="$(read_value "$commit" CURRENT_PROJECT_VERSION)"
        previous_bump="$commit"
        break
    fi
done < <(git log "$target_ref" --format='%H' -- "$version_file")

if [[ -z "$current_bump" || -z "$previous_bump" ]]; then
    echo "Could not find two distinct version bumps in ${version_file}." >&2
    exit 1
fi

if [[ ! "$current_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
    echo "Invalid release version: ${current_version}" >&2
    exit 1
fi

if [[ ! "$current_build" =~ ^[0-9]+$ || ! "$previous_build" =~ ^[0-9]+$ ]]; then
    echo "Could not read CURRENT_PROJECT_VERSION from ${version_file}." >&2
    exit 1
fi

if (( 10#$current_build <= 10#$previous_build )); then
    echo "CURRENT_PROJECT_VERSION must increase from ${previous_build} to ${current_build}." >&2
    exit 1
fi

release_title="$current_version"
version_core="${current_version%%-*}"
version_suffix=""
if [[ "$current_version" == *-* ]]; then
    version_suffix="${current_version#*-}"
fi
if [[ "$version_suffix" == "beta" ]]; then
    release_title="Beta ${version_core}"
elif [[ "$version_suffix" == beta.* ]]; then
    release_title="Beta ${version_core} (${version_suffix#beta.})"
elif [[ "$version_suffix" == "rc" ]]; then
    release_title="Release Candidate ${version_core}"
elif [[ "$version_suffix" == rc.* ]]; then
    release_title="Release Candidate ${version_core} (${version_suffix#rc.})"
fi
release_prerelease="false"
version_major="${current_version%%.*}"
if [[ -n "$version_suffix" ]] && (( 10#$version_major >= 1 )); then
    release_prerelease="true"
fi
current_bump_subject="$(git log -1 --format='%s' "$current_bump" | tr '[:upper:]' '[:lower:]')"
if [[ "$current_bump_subject" == *hotfix* ]]; then
    release_title="${release_title} Hotfix"
fi

printf 'version=%s\n' "$current_version"
printf 'build=%s\n' "$current_build"
printf 'tag=%s\n' "$current_version"
printf 'title=%s\n' "$release_title"
printf 'prerelease=%s\n' "$release_prerelease"
printf 'release_commit=%s\n' "$(git rev-parse "${target_ref}^{commit}")"
printf 'current_bump=%s\n' "$current_bump"
printf 'previous_version=%s\n' "$previous_version"
printf 'previous_build=%s\n' "$previous_build"
printf 'previous_bump=%s\n' "$previous_bump"
