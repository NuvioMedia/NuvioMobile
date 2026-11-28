# Nuvio Usenet engine dependency

The Go engine is maintained in https://github.com/NuvioMedia/nuvio-usenet-engine,
the same engine and release that NuvioTV uses. This directory contains only the
Android adapter, the pinned release metadata and the license notices. No Go
engine source is embedded.

Normal Android builds download the exact four ABI assets named by
`prebuilt/manifest.json` and verify their SHA-256 checksums. Cached assets work
offline. The APK packages the executable as `libnuvio_usenet.so`: it is
launched as a child process and never loaded through JNI, so iOS has no
Usenet engine.

To develop the engine, clone the engine repository separately and run:

```sh
./gradlew :androidApp:assembleFullDebug -PbuildUsenetFromSource=true -PusenetEngineSourceDir=/absolute/path/to/nuvio-usenet-engine
```

Source builds require Go, NDK and CMake as documented in the engine repository.
To update the dependency, publish and verify a new engine release, copy its
manifest here, update the source revision in `engine-source.json`, and refresh
the license notices.
