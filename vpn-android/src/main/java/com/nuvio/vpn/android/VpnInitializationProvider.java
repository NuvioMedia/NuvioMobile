package com.nuvio.vpn.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/** Installs context only. Does not connect, read settings, or perform network/file IO. */
public final class VpnInitializationProvider extends ContentProvider {
    @Override public boolean onCreate() {
        if (getContext() == null) return false;
        VpnPlaybackProtection.initialize(getContext());
        return true;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
}
