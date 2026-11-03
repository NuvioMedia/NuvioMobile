# CloudStream Runtime API snapshot

The Android full distribution embeds the `library` Android artifact built from
the official CloudStream repository:

- Repository: https://github.com/recloudstream/cloudstream
- Commit: `3496e5f8d2ebae4c1b5bdf264782f58375c1eb06`
- Upstream version at build time: `4.8.0` / library `1.0.1`
- Embedded artifact: `composeApp/libs/cloudstream-runtime-api-4.8.0-3496e5f.aar`
- SHA-256: `b67a4384bea1f4072123b86c5f164471422d9c6c12845d5067f12db44674d427`

CloudStream is licensed under GPL-3.0. Nuvio Enhanced is also distributed
under GPL-3.0; the repository root `LICENSE` contains the applicable license.
The artifact is used only by the sideload-oriented Android full build. The
Play Store build does not include or execute downloaded CloudStream DEX code.
