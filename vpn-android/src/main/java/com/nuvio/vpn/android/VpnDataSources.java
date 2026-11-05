package com.nuvio.vpn.android;

import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import com.nuvio.vpn.core.ProtectionMode;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Keep the host's original transport outside HTTP-only mode, including its TLS behavior. */
@UnstableApi
public final class VpnDataSources {
    private VpnDataSources() { }
    public static HttpDataSource.Factory route(HttpDataSource.Factory baseline, HttpDataSource.Factory httpOnly) {
        return new HttpDataSource.Factory() {
            @Override public HttpDataSource.Factory setDefaultRequestProperties(Map<String, String> headers) {
                baseline.setDefaultRequestProperties(headers);
                httpOnly.setDefaultRequestProperties(headers);
                return this;
            }
            @Override public HttpDataSource createDataSource() { return new Routed(baseline.createDataSource(), httpOnly.createDataSource()); }
        };
    }
    private static final class Routed implements HttpDataSource {
        private final HttpDataSource baseline, httpOnly;
        private HttpDataSource active;
        Routed(HttpDataSource baseline, HttpDataSource httpOnly) { this.baseline = baseline; this.httpOnly = httpOnly; active = baseline; }
        @Override public long open(DataSpec spec) throws HttpDataSourceException {
            try {
                VpnPlaybackProtection.beforeRequest(spec.uri.toString());
                active = VpnPlaybackProtection.settings().getMode().isPlaybackScoped() ? httpOnly : baseline;
                return active.open(spec);
            } catch (IOException failure) { throw HttpDataSourceException.createForIOException(failure, spec, HttpDataSourceException.TYPE_OPEN); }
        }
        @Override public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException { return active.read(buffer, offset, length); }
        @Override public void close() throws HttpDataSourceException { active.close(); }
        @Override public Uri getUri() { return active.getUri(); }
        @Override public int getResponseCode() { return active.getResponseCode(); }
        @Override public Map<String, List<String>> getResponseHeaders() { return active.getResponseHeaders(); }
        @Override public void addTransferListener(TransferListener listener) { baseline.addTransferListener(listener); httpOnly.addTransferListener(listener); }
        @Override public void setRequestProperty(String name, String value) { baseline.setRequestProperty(name, value); httpOnly.setRequestProperty(name, value); }
        @Override public void clearRequestProperty(String name) { baseline.clearRequestProperty(name); httpOnly.clearRequestProperty(name); }
        @Override public void clearAllRequestProperties() { baseline.clearAllRequestProperties(); httpOnly.clearAllRequestProperties(); }
    }
}
