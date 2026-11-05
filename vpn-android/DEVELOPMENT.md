# Developing the Android VPN modules

Both Android hosts use the same `vpn-core` and `vpn-android` sources. Keep the modules in sync when changing routing, profile storage, or the native transport. The host-specific Kotlin adapters own player, settings, and Setup Sync integration.

## Building

Use a full JDK 21, Go 1.27.3 and Android NDK 29.0.14206865. Set `NUVIO_GO` if Go is outside PATH. Generated native libraries belong in the build directory. Build the standalone module and the host serially since both use the same `vpn-core` jar.

From either repository root:

```sh
./gradlew -p vpn-android :lintDebug :testDebugUnitTest :vpn-core:check :checkHttpTunnel
./gradlew -p vpn-android :device-tests:assembleDebug :device-tests:assembleDebugAndroidTest
```

The instrumentation harness uses its own package and Keystore. Install both harness APKs on a test device, then run:

```sh
adb shell am instrument -w com.nuvio.vpn.android.test.test/androidx.test.runner.AndroidJUnitRunner
```

For host builds use `:app:assembleFullDebug` and `:app:assembleFullRelease` on TV, and `:androidApp:assembleFullDebug` and `:androidApp:assembleFullRelease` on Mobile. Configure development signing as documented by the host. Use a debug signing key for local release validation.

## Regression checks

- Play the same HTTPS source with HTTP-only and HTTP + HTTPS selected. Verify picture, sound, and whether WireGuard actually received traffic. A successful video start alone cannot establish the route.
- Test a plain HTTP byte range through the host media factory, then compare it with the reference bytes. Exercise a seek and automatic next-episode playback with preloading enabled.
- Verify Full app consent and confirm the Android VPN is restricted to the host package.
- Test a fresh sign-in with Setup Sync enabled. The selected profile and scope should restore and the destination should be able to connect using its own Keystore. Backups predating the VPN entry should leave local settings intact.
- Check a minified APK as well as a debug APK. Verify that WireGuard statistics can be constructed and the JNI callback and native methods survive shrinking. The native transport targets four ABIs with 16 KiB LOAD alignment.

The TV host uses JVM target 17 for WireGuard record desugaring. Mobile uses AGP 9.2.1 to package the required record support. The Go tests exercise a paired WireGuard tunnel, DNS, byte-range reads and opaque HTTPS CONNECT. Use synthetic credentials in tests and keep real profile data, account details and stream URLs out of logs.
