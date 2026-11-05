package com.nuvio.vpn.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** System consent only; the playback caller continues awaiting the verified connection. */
public final class VpnPlaybackConsentActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!VpnPlaybackProtection.hasRequest(getIntent().getStringExtra("request"))) { finish(); return; }
        if (state != null) return;
        Intent consent = android.net.VpnService.prepare(this);
        if (consent == null) {
            VpnPlaybackProtection.consentResult(getIntent().getStringExtra("request"), true);
            finish();
        } else startActivityForResult(consent, 1);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1) {
            VpnPlaybackProtection.consentResult(getIntent().getStringExtra("request"), result == RESULT_OK);
            finish();
        }
    }
}
