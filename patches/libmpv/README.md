# libmpv Audio Tap Patch

`0010-audio-tap.patch` adds a lock-free SPSC mono PCM tap to libmpv (`v0.41.0`) so libmpv handles
audio-based subtitle sync through continuous playback audio on both iOS (`MPVKit`) and Android
(`mpv-android-lib`), matching ExoPlayer without disturbing playback, volume/mute, or audio routing.

## Properties

- **Zero overhead when inactive:** `mpctx->audio_tap` is `NULL` when audio sync is not running;
  the audio path runs a single pointer check and allocates 0 bytes.
- **Pre-gain, AO-agnostic hook:** Hooks `mp_aframe` in `player/audio.c` (`ao_process`) before
  the AO queue and `ao_set_gain`, so volume boost (`softvol`) and `mute` never affect analysis
  and `ao-reload` / Bluetooth route switches do not break the tap.
- **Lock-free & real-time safe:** Fixed power-of-two mono float32 ring buffer (default 256 KiB),
  downmixed with center-channel weighting (`0.7 * FC + 0.15 * (FL + FR)`) in C. Never blocks,
  locks, or allocates on mpv's audio thread.
- **Safe teardown:** Atomically reference-counted between `MPContext` and the caller so
  `mpv_audio_tap_destroy()` is safe before or after `mpv_terminate_destroy()`.
- **Format & speed guards:** Skips non-PCM passthrough frames (`MPV_AUDIO_TAP_FLAG_NON_PCM`) and
  time-stretched playback (`MPV_AUDIO_TAP_FLAG_SPEED_MISMATCH`), letting audio sync degrade cleanly
  to spot sampling for passthrough streams (matching ExoPlayer's `PlaybackAudioTap`).

## Applying to iOS / macOS (`NuvioMedia/MPVKit`)

1. Copy `0010-audio-tap.patch` into `Sources/BuildScripts/patch/libmpv/` in `NuvioMedia/MPVKit`.
2. Rebuild `Libmpv.xcframework` with the existing `MPVKit` build script.
3. `0010-audio-tap.patch` retains all five `MPV_EXPORT` entrypoints from `mp_audio_tap_shutdown`
   so `ld64` dead-code stripping cannot drop them from statically linked `Libmpv.xcframework`.
   Note for archived Release IPAs: Xcode's default app `STRIP_STYLE` ("All Symbols") can still
   strip globals that have no direct call-site reference, which would cause `dlsym(RTLD_DEFAULT)`
   to return `nil` in archived Release builds even when Debug runs work. Once `MPVKit` ships
   `include/mpv/audio_tap.h`, either bind the five `mpv_audio_tap_*` functions directly through
   `import Libmpv` in `AudioSyncNativeBridge.swift` (recommended, removes `dlsym` entirely) or set
   `STRIP_STYLE = non-global` / `-Wl,-exported_symbol,_mpv_audio_tap_*` on the `iosApp` target
   and verify with `nm -gU` on the archived binary.

## Applying to Android (`mpv-android-lib`)

1. Apply `0010-audio-tap.patch` to `libmpv` when building `libmpv.so`.
2. Expose these five JNI methods on `is.xyz.mpv.MPV`:
   - `int audioTapApiVersion()` -> `mpv_audio_tap_api_version()`
   - `long audioTapCreate(int ringCapacitySamples)` -> `mpv_audio_tap_create(mpv, &cfg)`
   - `int audioTapRead(long tapHandle, float[] outSamples, int maxSamples, long[] outMeta)`
     writing `[sampleRate, sampleCount, generation, ptsUs, flags]` into `outMeta[0..4]`
   - `void audioTapSetPaused(long tapHandle, boolean paused)` -> `mpv_audio_tap_set_paused(...)`
   - `void audioTapDestroy(long tapHandle)` -> `mpv_audio_tap_destroy(...)`
