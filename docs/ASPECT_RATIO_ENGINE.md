# Player aspect-ratio modes

The player keeps five user-facing modes. The selected mode is stored in common Kotlin and mapped to the active playback engine; changing it must not recreate the player or decoder.

| Mode | Behaviour |
|---|---|
| Best Fit | Preserve the source display aspect ratio (including sample-aspect-ratio metadata); fit the complete picture in the viewport. |
| Fit | Use coded width/height (square-pixel interpretation); preserve the complete picture and do not upscale below-native-size sources. |
| Full | Fill the viewport with independent horizontal and vertical scaling. Distortion is intentional. |
| Zoom | Preserve proportions and crop the excess to fill the viewport. |
| 16:9 | Apply the existing fixed 16:9 display-ratio behaviour. Keep this mode unchanged unless separately requested. |

## Engine mapping

### Android / ExoPlayer
Use Media3's existing PlayerView content frame:
- Best Fit: corrected video display ratio and `RESIZE_MODE_FIT`.
- Fit: coded video ratio and a centered, downscale-only content-frame transform.
- Full: `RESIZE_MODE_FILL`.
- Zoom: `RESIZE_MODE_ZOOM`.
- 16:9: fixed 16:9 content-frame ratio with `RESIZE_MODE_FIT`.

Reapply the frame ratio after video-size changes and viewport/layout changes. Keep bitmap subtitle and libass bounds synchronized with the actual transformed content frame, especially in Fit and Zoom.

### Android libmpv and iOS libmpv
Apply the corresponding mpv properties in-place:
- Best Fit: preserve container aspect, `panscan=0`.
- Fit: ignore display-aspect metadata, `video-unscaled=downscale-big`, `panscan=0`.
- Full: `keepaspect=no`, `panscan=0`.
- Zoom: preserve container aspect, `panscan=1`.
- 16:9: retain the existing `video-aspect-override=16:9` behaviour.

Reset properties changed by the previous mode when applying a new mode. Do not reload the stream or recreate the playback surface.

## Invariants
- Best Fit and Fit remain distinct.
- Only Full intentionally distorts proportions; Zoom crops rather than stretches.
- Preserve existing 16:9 behaviour.
- Invalid or unavailable video dimensions must fall back safely.
- Do not add a second, unused geometry calculator: the active engine adapters are the source of playback geometry.
