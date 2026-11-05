# JNI entry points use these class and method names.
-keep class com.nuvio.vpn.android.HttpStreamTunnel {
    private static native <methods>;
    private static boolean preparePlayback(java.lang.String, long);
}

-keep class com.nuvio.vpn.android.OpenVpnSession {
    native <methods>;
    private int establish(java.lang.String[], java.lang.String[], int);
    private boolean protectSocket(int);
}
