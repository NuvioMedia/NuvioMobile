plugins { id("com.android.application") }

android {
    namespace = "com.nuvio.vpn.devicetests"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.nuvio.vpn.android.test"
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Match the hosts' Java 17 desugaring context for WireGuard's record classes.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("androidTest").java.srcDir("../src/sharedTest/java")
}

dependencies {
    implementation(project(":"))
    implementation("androidx.media3:media3-datasource:1.8.0")
    // Device tests exercise the library's package-private storage/settings classes.
    androidTestImplementation(project(":vpn-core"))
    androidTestImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("com.wireguard.android:tunnel:1.0.20260102")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
