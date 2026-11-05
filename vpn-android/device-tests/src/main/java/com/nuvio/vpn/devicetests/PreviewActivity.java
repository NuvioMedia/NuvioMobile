package com.nuvio.vpn.devicetests;

import android.app.Activity;
import android.os.Bundle;
import com.nuvio.vpn.android.VpnProfilesActivity;

/** Standalone local preview; this application is not included in either host build. */
public final class PreviewActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        VpnProfilesActivity.open(this);
        finish();
    }
}
