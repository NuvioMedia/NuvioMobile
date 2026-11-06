#!/usr/bin/env bash
# Linux native rebuild; installs no host tools. See LIBMPV_SYNC_BACKPORT.md.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Linux NDK 29.0.14206865}"
work="${1:?Usage: rebuild-mpv-sync.sh NEW_BUILD_DIRECTORY}"
if [[ -e "$work" ]]; then
    echo "Refusing to overwrite existing build directory: $work" >&2
    exit 1
fi
for tool in git python3 cc meson ninja pkg-config autoconf automake libtoolize nasm wget; do
    command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done
grep -q '29.0.14206865' "$ANDROID_NDK_HOME/source.properties" || {
    echo 'ANDROID_NDK_HOME must contain Linux NDK 29.0.14206865' >&2
    exit 1
}
mkdir -p "$work"
work="$(cd "$work" && pwd)"
clone_at() {
    git clone "$1" "$2"
    git -C "$2" checkout --detach "$3"
    git -C "$2" submodule update --init --recursive
}
clone_at https://github.com/abdallahmehiz/mpv-android "$work/native" \
    96c3507c5fc8eedbf5fff458101cad794392eca7
cd "$work/native/buildscripts"
mkdir -p deps sdk
ln -s "$(cd "$ANDROID_NDK_HOME" && pwd)" sdk/android-ndk-r29
# Pin repositories which upstream's downloader otherwise takes from moving HEAD.
clone_at https://github.com/videolan/dav1d deps/dav1d \
    daef39627713a3e09873e78df65b268386cb4c20
clone_at https://github.com/FFmpeg/FFmpeg deps/ffmpeg \
    a7522f3fefa719be687f4627631ac6e5e481ef4c
clone_at https://gitlab.freedesktop.org/freetype/freetype.git deps/freetype2 \
    526ec5c47b9ebccc4754c85ac0c0cdf7c85a5e9b
clone_at https://github.com/libass/libass deps/libass \
    fadc390583f24eb5cf98f16925fd3adee50bca88
clone_at https://github.com/haasn/libplacebo deps/libplacebo \
    b2ea27dceb6418aabfe9121174c6dbb232942998
clone_at https://github.com/mpv-player/mpv deps/mpv \
    76a5eba991733f41310912c79c60f6c565a77cc9
# Remaining tarball versions come from pinned upstream depinfo.sh.
./include/download-deps.sh
if python3 "$root/scripts/test-libplacebo-sync.py" deps/libplacebo/src/opengl/utils.c; then
    echo 'Expected original source to fail sync-object regression test' >&2
    exit 1
fi
git -C deps/libplacebo apply "$root/scripts/libplacebo-sync.patch"
python3 "$root/scripts/test-libplacebo-sync.py" deps/libplacebo/src/opengl/utils.c
export cores="${cores:-6}"
export SOURCE_DATE_EPOCH=1770940800
for arch in arm64 armv7l x86 x86_64; do
    ./buildall.sh --arch "$arch" mpv
done
python3 "$root/scripts/package-mpv-sync.py" "$PWD" \
    "$root/composeApp/libs/mpv-android-lib-0.1.12-sync-fix.aar" "${ORIGINAL_MPV_AAR:-}"
