package com.nuvio.vpn.android;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

/** A snapshot of the host settings theme. Contains presentation values only, never profile data. */
public final class VpnAppearance {
    private static final String EXTRA = "com.nuvio.vpn.APPEARANCE";
    private final Bundle values = new Bundle();

    public VpnAppearance(boolean television) { values.putBoolean("tv", television); }

    public VpnAppearance colors(int background, int surface, int border, int text, int muted,
            int accent, int focus, int focusText, int focusRing, int error) {
        values.putIntArray("colors", new int[] {background, surface, border, text, muted,
                accent, focus, focusText, focusRing, error});
        return this;
    }

    public VpnAppearance typography(float headingSp, float titleSp, float bodySp) {
        values.putFloatArray("type", new float[] {headingSp, titleSp, bodySp});
        return this;
    }

    public VpnAppearance corners(float cardDp, float rowDp) {
        values.putFloatArray("radii", new float[] {cardDp, rowDp});
        return this;
    }

    public VpnAppearance fontResource(int resource) { values.putInt("font", resource); return this; }
    public VpnAppearance fontAsset(String asset) { values.putString("asset", asset); return this; }

    void putInto(Intent intent) { intent.putExtra(EXTRA, new Bundle(values)); }

    static VpnAppearance from(Context context, Intent intent) {
        boolean tv = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_TELEVISION;
        VpnAppearance appearance = new VpnAppearance(tv);
        Bundle stored = intent.getBundleExtra(EXTRA);
        if (stored != null) appearance.values.putAll(stored);
        return appearance;
    }

    boolean television() { return values.getBoolean("tv"); }
    int color(int index) {
        int[] fallback = {0xff0d0d0d, 0xff1a1a1a, 0xff333333, 0xfff5f7f8, 0xff969ca3,
                0xfff5f7f8, 0xff2d2d2d, 0xffffffff, 0xfff5f7f8, 0xffcf6679};
        int[] colors = values.getIntArray("colors");
        return colors != null && colors.length == fallback.length ? colors[index] : fallback[index];
    }
    float heading() { return dimension("type", 0, television() ? 28 : 26, 16, 48); }
    float title() { return dimension("type", 1, 16, 12, 28); }
    float body() { return dimension("type", 2, television() ? 14 : 13, 10, 24); }
    float cardRadius() { return dimension("radii", 0, television() ? 16 : 24, 0, 48); }
    float rowRadius() { return dimension("radii", 1, 12, 0, 32); }
    int fontResource() { return values.getInt("font"); }
    String fontAsset() { return values.getString("asset"); }

    private float dimension(String key, int index, float fallback, float min, float max) {
        float[] dimensions = values.getFloatArray(key);
        if (dimensions == null || dimensions.length <= index) return fallback;
        float value = dimensions[index];
        return !Float.isNaN(value) && value >= min && value <= max ? value : fallback;
    }
}
