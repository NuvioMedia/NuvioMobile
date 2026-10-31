# Android libmpv sync-object backport

Android uses `composeApp/libs/mpv-android-lib-0.1.12-sync-fix.aar`, based on
`io.github.abdallahmehiz:mpv-android-lib:0.1.12`. Libplacebo is statically
linked into `libmpv.so`. The only source change is upstream
[`c93aa134ab62365ce1177efff99b8e1e66a818e7`](https://github.com/haasn/libplacebo/commit/c93aa134ab62365ce1177efff99b8e1e66a818e7):
delete completed OpenGL sync objects before invoking callbacks.

Only `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libmpv.so` is replaced.
All other AAR entries remain byte-identical to the published artifact.
Renderer selection and JNI interfaces are unchanged. Original Maven coordinate
has no newer release; the secozzi fork omits required companion libraries.

## Sources and integrity

| Component | Revision |
| --- | --- |
| Android build scripts (`v0.1.12`) | `96c3507c5fc8eedbf5fff458101cad794392eca7` |
| MPV | `76a5eba991733f41310912c79c60f6c565a77cc9` |
| libplacebo (`v7.360.0`) | `b2ea27dceb6418aabfe9121174c6dbb232942998` |

Remaining Git revisions are pinned in `scripts/rebuild-mpv-sync.sh`;
versioned tarballs come from the pinned upstream `include/depinfo.sh`.
The script applies `scripts/libplacebo-sync.patch`, reuses upstream
Meson/Ninja build infrastructure, and builds all four ABIs with NDK r29.

SHA-256:

- Original AAR: `bb1a007c545cc7ac3304293ae79866b5361a48449ee7648c4030d5355869effc`
- Patched AAR: `98ac952ad7b20b0ae74ace61716cc0ce65dca8474d37e2307b5391e0aaba6afb`

## Rebuild

Requires Linux, Python 3, a host C compiler, Git, Meson, Ninja, pkg-config,
autoconf, automake, libtool, nasm, wget, and Linux NDK **29.0.14206865**.
No host tools are installed automatically. Use a new build directory:

```bash
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk-r29
bash scripts/rebuild-mpv-sync.sh /absolute/path/to/new-build-directory
```

Optional `ORIGINAL_MPV_AAR=/path/to/original.aar` avoids downloading the
published AAR again; its checksum is verified in either case. Sources and
`SOURCE_DATE_EPOCH` are pinned. Host tools and absolute paths can affect bytes;
bit-for-bit reproducibility across hosts is not guaranteed.

## Verify

The rebuild requires the actual upstream callback function to fail the host
regression before patching and pass afterward. Coverage includes completed
waits, delete-before-callback ordering, timeouts, failed waits, and no double
deletion. The packager checks each ELF ABI and verifies archive entry names
and every unrelated entry's bytes against the checksum-verified original.

To repeat packaging checks after a native build, without replacing the AAR:

```bash
python3 scripts/package-mpv-sync.py /path/to/new-build-directory/native/buildscripts /tmp/mpv-sync.aar /path/to/original.aar
cmp /tmp/mpv-sync.aar composeApp/libs/mpv-android-lib-0.1.12-sync-fix.aar
```

Use Java 21–25 for the pinned Robolectric SDK 36/ASM setup:

```bash
./gradlew :androidApp:assembleFullDebug :composeApp:testAndroidHostTest -Pnuvio.android.distribution=full
./gradlew :androidApp:assemblePlaystoreDebug -Pnuvio.android.distribution=playstore
git diff --check
```

Both debug distributions built successfully; all four packaged MPV libraries
match the patched AAR. Against the original Full APK, only those four native
libraries differ. Baseline and patched host runs each executed 1,715 tests
with the same three failures: ratings visibility, external subtitle sharing,
and legacy watch-progress identity. No new host-test failures were observed.
