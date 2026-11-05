package com.nuvio.vpn.android;

import android.content.Context;
import java.net.HttpURLConnection;
import java.net.URL;

final class AppVpnProbe {
    private AppVpnProbe() {}
    static boolean verify(Context context) {
        HttpURLConnection connection = null;
        try {
            connection = open(context,"https://www.gstatic.com/generate_204");
            return connection.getResponseCode() == 204;
        } catch (Exception failure) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }
    static String egress(Context context) {
        HttpURLConnection connection = null;
        try {
            connection = open(context,"https://api.ipify.org");
            if (connection.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream input = connection.getInputStream()) {
                int value;
                while ((value = input.read()) != -1) {
                    if (bytes.size() == 64) return null;
                    bytes.write(value);
                }
            }
            return com.wireguard.config.InetAddresses.parse(new String(bytes.toByteArray(),
                    java.nio.charset.StandardCharsets.US_ASCII).trim()).getHostAddress();
        } catch (Exception failure) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
    private static HttpURLConnection open(Context context, String address) throws Exception {
        android.net.ConnectivityManager manager = context.getSystemService(android.net.ConnectivityManager.class);
        android.net.Network vpn = manager.getActiveNetwork();
        android.net.NetworkCapabilities caps = manager.getNetworkCapabilities(vpn);
        if (caps == null || !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))
            throw new java.io.IOException("App VPN is unavailable.");
        HttpURLConnection connection = (HttpURLConnection) vpn.openConnection(new URL(address));
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }
}
