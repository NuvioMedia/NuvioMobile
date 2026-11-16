package com.nuvio.app.features.player

// The libass module (based on Plezy by edde746, forked from peerless2012's
// libass-android) always renders via the atlas SurfaceView pipeline with
// vsync-pinned swap. The old render type selection from peerless2012's library
// (CUES/EFFECTS_CANVAS/EFFECTS_OPEN_GL/OVERLAY_CANVAS/OVERLAY_OPEN_GL) is no
// longer needed.

enum class LibassRenderType {
    // Legacy values kept for backward compatibility with persisted preferences.
    CUES,
    EFFECTS_CANVAS,
    EFFECTS_OPEN_GL,
    OVERLAY_CANVAS,
    OVERLAY_OPEN_GL
}
