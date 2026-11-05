# Android HTTP stream VPN core

Status: routing policy, profile preflight and connection-scoped transport registry.
The companion `vpn-android` module provides encrypted storage and WireGuard Entire app
connection support. OpenVPN connections and HTTP-only transport integration remain
incomplete. An import or scope choice alone never proves playback protection.

The module is identical in NuvioPlusIPTV and NuvioPlusIPTV-Mobile. It uses Java 8 and
no third-party dependencies, Android plugin, or Kotlin plugin, so the two apps can
keep their own Android/Kotlin versions. Android library/service code will be separate.
Do not add it to Mobile's commonMain or iOS source sets.

## Included

- Bounded UTF-8 import/preflight for OpenVPN and WireGuard, with redacted diagnostics.
- Rejection of desktop hooks, external credential files, and common executable or
  management directives. This is not an exhaustive protocol parser or security boundary.
- HTTP-only route decisions, direct HTTPS, and literal loopback support for existing
  local playback proxies. Other private/LAN addresses receive no implicit exemption.
- App-wide mode routes both HTTP and HTTPS through the tunnel. Its Android backend
  must also cover other traffic from this app without affecting other apps.
- Versioned AES-GCM profile envelopes, bound to their profile identifier and protected
  against tampering. Android supplies the non-exportable encryption key.
- A connection-scoped resource registry that closes registered HTTP transports on
  invalidation and rejects transports registered against an old connection.
- Versioned selected-profile/scope settings with strict decoding and migration from
  scope-only settings. Clearing a selection also resets scope to Off.

## Integration boundaries

Keep HomelessBrian's player and HTTPS certificate handling as the baseline. Avoid
upgrading existing build tools, moving existing source files, or modifying native player
code just to accommodate VPN dependencies. Add the eventual Settings entry using a
module-owned Activity so the apps need only a small launch/navigation hook.

Future native engine adapters must use their real protocol parsers and explicitly
supported Android options, and validate imports before connecting. The Android screen
stores preflighted imports as pending validation, encrypts them with Android Keystore,
and excludes profile data from Android backup and account sync.
Preflight is deliberately conservative: for example, OpenVPN connection blocks and
external file references are currently rejected rather than partially interpreted.
Provider credentials and encrypted private-key prompts still need implementation.

Android's VpnService is not a URL-scheme router. A live VPN interface or an imported
profile is not proof an HTTP request used the tunnel. The playback transport must
implement selective routing before the feature can be enabled. Do not merely check
for a system VPN or route the entire app and call that HTTP-only protection.
Entire app is an explicit separate choice; it may use per-app VpnService routing.

The adapter must evaluate every request (including redirect hops and HLS/DASH child
requests), send TUNNEL decisions through the selected engine, bind DIRECT HTTPS to
the non-VPN network as required, and register HTTP sockets before connecting/sending.
Invalidate a session on connection loss, profile changes, revocation, or disconnect.
Never retry protected HTTP directly. Resolve provider hostnames through the tunnel
where possible; handle IPv4 and IPv6 without bypasses. Request fragments/user-info
must be normalized or rejected before this API. Do not assume port 80 means HTTP.

Map all paths before claiming coverage: Media3, MPV, live playback, recordings,
timeshift, range requests, and stream probes. External players and loopback engines
can open their own upstream sockets and need separate treatment; a loopback exemption
does not protect those upstream sockets.

## Checks

From the app checkout, check the core independently without configuring the Android
projects or requiring an Android SDK (the Gradle distribution must be available):

```
./gradlew -p vpn-core verifyVpnCore
```

The checks are also dependency-free Java sources and can be compiled/run directly with
`javac --release 8` and `java`, using a temporary output directory. They verify import
limits, malformed data, rejection of unsupported directives, mixed HTTPS/HTTP routing,
app-wide HTTPS routing, lookalike loopback names, stale sessions, registration/disconnect
races, encrypted-profile round trips, wrong keys/identifiers, corrupted ciphertext,
and settings migration/selection invariants. `verifyVpnCore` executes this main-based
suite; the JUnit-discovery `test` task is disabled because this module contains no JUnit
tests. The Android module has a separate Robolectric/JUnit suite.

Before exposing the feature, require on-device tests for both real tunnel engines,
HTTP-only routing on arbitrary ports, tunnel-side DNS, HTTPS bypass, redirect chains,
mixed-scheme playlists, IPv6, midstream tunnel loss, and network handoff. No tests in
this module demonstrate an encrypted VPN connection or actual leak protection.
