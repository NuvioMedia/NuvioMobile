# Android playback VPN

TV and Mobile open the same settings page from **Settings > VPN profiles**. Matching playback waits for authentication and a tunneled HTTPS probe. Media requests, redirects, byte ranges, subtitles and preloads follow the selected mode.

- **Off** uses the ordinary playback connection.
- **HTTP streams only** protects remote HTTP media. HTTPS stays direct.
- **HTTP + HTTPS streams** protects both playback protocols. HTTPS keeps its original TLS session.
- **Full app** uses Android VPN consent and restricts the VPN interface to the host package.

## Profiles and sync

Import a WireGuard configuration or a self-contained OpenVPN TUN profile. OpenVPN supports UDP and TCP, inline CA and certificate material, and service credentials. Importing a profile with a bare `auth-user-pass` directive prompts for credentials. Use **OpenVPN service credentials** to update a previously imported profile. Desktop scripts, external credential files, interactive authentication challenges and external PKI are outside the supported profile format.

**Apply VPN settings to** defaults to **All profiles**. The selected VPN and protection mode apply to current and future Nuvio profiles. **Only this profile** starts a separate choice for the active Nuvio profile. Switching back to **All profiles** uses the current shared settings again. Setup Sync restores both the shared choice and individual overrides.

Profiles are encrypted with the device’s Android Keystore. HomelessBrian’s Setup Sync carries a portable `vpn_profiles_v1` entry with the configurations, selected profile and scope. Exported backups contain private VPN configuration, including saved OpenVPN service credentials. The destination validates and re-encrypts the profiles with its own key. Older backups without a VPN entry preserve the local setup.

## Integration and verification

Keep `vpn-core` and `vpn-android` source in sync across the two repositories. The Java core owns route selection, profile preflight, encryption envelopes and versioned settings. Android owns the Keystore, playback gate, settings UI, private playback proxy and app-scoped VPN service. Host adapters connect these to the players and Setup Sync.

Use JDK 21, Go 1.27.3, NDK 29.0.14206865 and SDK CMake 3.22.1. `NUVIO_GO` selects a non-default Go executable. The Go module pins WireGuard and its private IP stack. CMake builds OpenVPN 3 Core 3.11.8 with pinned mbed TLS, Asio and LZ4 sources. Dependency notices ship in the APK. Build the standalone module and its host sequentially within each checkout.

```sh
./gradlew -p vpn-android :lintDebug :testDebugUnitTest :vpn-core:check :checkHttpTunnel
./gradlew -p vpn-android :device-tests:assembleDebug :device-tests:assembleDebugAndroidTest
```

Install the two device-test APKs on an emulator and run:

```sh
adb shell am instrument -w com.nuvio.vpn.android.test.test/androidx.test.runner.AndroidJUnitRunner
```

The harness uses a separate application package and Keystore. For its optional OpenVPN server test, place a synthetic profile in its private `files/acceptance.ovpn` and supply the `openvpnScope` and `openvpnRangeUrl` instrumentation arguments. The test validates credential restore, the Android route, the returned range and received tunnel traffic. Run it with both UDP and TCP profiles.

Host builds use `:app:assembleFullDebug` and `:app:assembleFullRelease` on TV and `:androidApp:assembleFullDebug` and `:androidApp:assembleFullRelease` on Mobile. Check actual playback, HTTPS bypass in HTTP-only mode, a seek, next-episode handoff and fresh-login restore on a device.
