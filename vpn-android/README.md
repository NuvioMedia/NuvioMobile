# Android playback VPN

Shared by the TV and Mobile Android hosts. The main Settings entry keeps its icon;
inside the VPN page, grouped rows use the host theme without decorative icons.

## Protection choices

- Off preserves the normal playback connection.
- HTTP streams only protects remote HTTP media requests, redirects, playlist children,
  subtitles, probes and preloads. HTTPS stays on its normal connection.
- HTTP + HTTPS streams protects both web playback protocols. TLS remains end to end;
  the proxy neither decrypts HTTPS nor changes the host certificate policy.
- Full app uses Android's app-restricted WireGuard VPN for all host-app network traffic.
  It requires Android VPN consent; the stream scopes use a private userspace netstack.

Matching playback automatically waits for a recent handshake, received traffic and a
successful tunneled HTTPS probe. No manual Connect button is needed. Imported or selected profiles do
not connect by themselves. Local playback proxies stay local. Scope changes retire
playback connection pools; required protection never deliberately falls back to direct.
This is not an Android always-on VPN or a process-death/permission-revocation kill switch.

## Profiles and backup

WireGuard is the supported connection protocol. OpenVPN files can be imported but cannot
yet connect. Inputs are bounded and preflighted; WireGuard also uses the official parser.
Only supported single-peer, full-route configurations with DNS are accepted for connection.
The Full app adapter replaces imported package filters with the current host package.

Profiles, names and protocol metadata are encrypted with Android Keystore AES-256-GCM
under noBackupFilesDir. Selection and scope share an atomic local snapshot. Unreadable
profiles are preserved for explicit removal. Limits: 32 profiles, 1 MiB per import.

The existing HomelessBrian Setup Sync/backup payload carries the portable
`vpn_profiles_v1` entry: configurations, stable profile IDs, selected ID and scope.
It uses the existing account-authenticated backup service, not Nuvio backend storage.
The destination validates the payload and re-encrypts profiles with its own Keystore.
Playback pauses during restore; failure keeps the previous saved protection intent. Missing VPN fields in older backups leave
the device's VPN setup untouched. Local sync comparison caches encrypt the VPN entry.
The portable backup contains private configuration material, just as the existing backup
contains playlist credentials; exported backup files must be treated as private.
Android consent remains per device and cannot be restored from a backup.

## Integration and build

Keep vpn-core and vpn-android shared sources identical between the repositories.
Playback adapters preserve baseline TLS, headers, cookies and timeouts. Media3 factories
route each open, including ranges/preloads. Native mpv holds an authenticated loopback
proxy lease and applies it before loading; HTTPS CONNECT is opaque. A proxy shell can
serve HTTPS in HTTP-only mode without starting WireGuard.

The Android SDK adapter is pinned to `com.wireguard.android:tunnel:1.0.20260102`.
The userspace backend is built from pinned Go source in native/go.mod, using Go 1.27.3
and Android NDK 29.0.14206865. No generated native binaries are checked in. Four ABIs are
built with a 16 KiB alignment linker option. Native dependency notices are packaged in
assets/vpn-licenses. NUVIO_GO can select the Go executable; GOCACHE and GOMODCACHE can
select caches. The host app retains its own AGP/UI versions.

With a full JDK 21, Android SDK and Go available:

```
./gradlew -p vpn-android :assembleDebug :lintDebug :testDebugUnitTest :vpn-core:check :checkHttpTunnel
./gradlew -p vpn-android :device-tests:assembleDebug :device-tests:assembleDebugAndroidTest
```

The isolated device-check app never clears host-app data. Its checks cover real Keystore,
atomic recovery, profile parsing, record packaging, scope guards, JNI lifecycle, two Go
runtimes in one process and portable backup restore. Go tests use a real paired WireGuard
netstack for DNS, HTTP ranges and opaque CONNECT. These checks do not replace real
provider playback, failure-path or physical-device validation. See DEVELOPMENT.md.
