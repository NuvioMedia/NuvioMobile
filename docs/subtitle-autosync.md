# Subtitle AutoSync

Subtitle AutoSync compares an add-on subtitle with subtitle timing embedded in the
playing video. Confident results can correct a constant delay, frame-rate drift,
or the timing of individual cue groups. Failed analysis keeps the selected subtitle
and its timing. It does not analyze audio or use speech models.

Enable **Auto Sync Subtitle** in **Settings → Playback → Subtitle / Audio** after
choosing a preferred subtitle language. The switch is off by default. Its tolerance
and Thorough/Quick search controls appear only while the switch is on. Tolerance
keeps the original timing when the whole-film correction is within the chosen
100–500 ms limit; Off applies all confident corrections.

This port supports Android's internal ExoPlayer with HTTP(S) streams and subtitles
that its sidecar renderer supports. ASS/SSA selected with libass, libmpv, external
players, and iOS continue using their existing subtitle behavior. There is no audio
fallback when a video lacks usable embedded subtitle timing.

At startup, AutoSync can try other add-on subtitles in the same language. When the
user selects a subtitle manually, it checks only that subtitle. An automatic
replacement updates the subtitle selection but does not persist an automatic pick
as a new manual preference for later episodes.

Success is indicated by **Auto synced** on the selected subtitle card. Only failures
show toasts. Changing subtitles, selecting an embedded track, disabling subtitles,
manually adjusting delay, or leaving playback cancels pending analysis. Index
requests start only after an add-on subtitle is selected for AutoSync. Applying a
result swaps sidecar cues without reloading the media source or discarding its buffer.

AutoSync has no debug logging setting and does not emit diagnostic logs.

## Sources

- Mobile integration and retimer:
  [NuvioMobile-Reshaped](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped/tree/5332f76a).
- Reviewed matcher, parser, sampled-reference handling, and UX behavior:
  [NuvioTV PR #3703](https://github.com/NuvioMedia/NuvioTV/pull/3703), head `613dbd2d`.
- The extractor rewrapping preserves upstream libass handling underneath AutoSync's
  observation wrapper. Existing parsers, general player preferences, dependencies,
  manifests, branding, and release workflows are kept intact.

## Verification

Local validation covered offsets, drift, grouping, confidence rejection, tolerance,
dense dialogue, reference consistency, no-fit tracking, and subtitle formats. Local
network tests covered subtitle HTTP cancellation, shared index requests, and retrying
an index after its owning request is cancelled. The test files are kept outside
the PR at the author’s request.

Physical-device checks still required:

1. With AutoSync off, verify ordinary subtitle selection and manual delay on Android
   and iOS; verify libmpv and external-player behavior.
2. Enable AutoSync and select an offset or drifting subtitle against an MKV or MP4
   with embedded subtitles. Verify the badge appears without a success toast, and
   playback continues without rebuffering.
3. Check an already-aligned subtitle and a correction inside the selected tolerance.
4. Start analysis, then switch to an embedded subtitle, disable subtitles, choose
   another add-on subtitle, adjust delay, or leave the player. The old result must
   not return or overwrite the new choice.
5. Test a video without embedded subtitles and unsupported ASS/libass rendering.
   Verify a simple failure toast and the original timing.
6. Capture the settings rows and selected subtitle badge for PR review.
