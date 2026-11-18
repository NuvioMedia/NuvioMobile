# Nuvio Auto: the unofficial Android Auto SDK relies on reflection and obfuscated names.
-keep class com.google.android.apps.auto.sdk.** { *; }
-keep class com.google.android.gms.car.** { *; }
-dontwarn com.google.android.apps.auto.sdk.**
-dontwarn com.google.android.gms.**
-keep class com.nuvio.app.car.** { *; }
