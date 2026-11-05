import java.io.File

plugins {
    id("com.android.library")
}

// The standalone root and the host's library project have different AGP identities.
// Sharing merger state between them produces duplicate assets/native inputs on Windows.
if (rootProject.name == "nuvio-vpn-android") {
    layout.buildDirectory.set(layout.projectDirectory.dir("build/standalone"))
}

android {
    namespace = "com.nuvio.vpn.android"
    compileSdk = 36
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("main").jniLibs.setSrcDirs(listOf(layout.buildDirectory.dir("http-tunnel/jniLibs").get().asFile))
    }
}

dependencies {
    implementation(project(":vpn-core"))
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("androidx.media3:media3-datasource:1.8.0")
    testRuntimeOnly("androidx.media3:media3-datasource:1.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

// Build from pinned Go sources; native binaries stay in the ignored build directory.
val vpnGo = providers.environmentVariable("NUVIO_GO").orElse("go")
val vpnNdk = androidComponents.sdkComponents.ndkDirectory
val vpnHost = System.getProperty("os.name").lowercase()
val vpnHostTag = when {
    vpnHost.contains("windows") -> "windows-x86_64"
    vpnHost.contains("mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val vpnGoCache = providers.environmentVariable("GOCACHE")
    .orElse(File(gradle.gradleUserHomeDir, "caches/nuvio-vpn-go/build").path)
val vpnGoModules = providers.environmentVariable("GOMODCACHE")
    .orElse(File(gradle.gradleUserHomeDir, "caches/nuvio-vpn-go/modules").path)
val vpnNativeTasks = listOf(
    Triple("arm64-v8a", "arm64", "aarch64-linux-android"),
    Triple("armeabi-v7a", "arm", "armv7a-linux-androideabi"),
    Triple("x86_64", "amd64", "x86_64-linux-android"),
    Triple("x86", "386", "i686-linux-android")
).map { (abi, goArch, compiler) ->
    val nativeOutput = layout.buildDirectory.file("http-tunnel/jniLibs/$abi/libnuvio-http-vpn.so")
    tasks.register<Exec>("compileHttpTunnel${goArch.replaceFirstChar { it.uppercase() }}") {
        val selectedGo = vpnGo
        val selectedNdk = vpnNdk
        val selectedHost = vpnHost
        val selectedHostTag = vpnHostTag
        val selectedCache = vpnGoCache
        val selectedModules = vpnGoModules
        workingDir(layout.projectDirectory.dir("native"))
        inputs.files(fileTree("native") {
            include("*.go", "*.c", "go.mod", "go.sum")
            exclude("*_test.go")
        })
        inputs.property("goToolchain", "go1.27.3")
        inputs.property("ndkVersion", "29.0.14206865")
        outputs.file(nativeOutput)
        doLast { nativeOutput.get().asFile.resolveSibling("libnuvio-http-vpn.h").delete() }
        doFirst {
            val output = nativeOutput.get().asFile
            output.parentFile.mkdirs()
            val suffix = if (selectedHost.contains("windows")) ".cmd" else ""
            val cc = selectedNdk.get().asFile.resolve("toolchains/llvm/prebuilt/$selectedHostTag/bin/${compiler}24-clang$suffix")
            check(cc.isFile) { "Install Android NDK 29.0.14206865 before building the HTTP VPN transport." }
            environment("GOOS", "android")
            environment("GOARCH", goArch)
            environment("GOARM", "7")
            environment("CGO_ENABLED", "1")
            environment("CC", cc.absolutePath)
            environment("CGO_LDFLAGS", "-Wl,-z,max-page-size=16384")
            environment("GOTOOLCHAIN", "go1.27.3")
            environment("GOMAXPROCS", "2")
            environment("GOCACHE", selectedCache.get())
            environment("GOMODCACHE", selectedModules.get())
            commandLine(selectedGo.get(), "build", "-p", "2", "-trimpath", "-buildmode=c-shared",
                "-ldflags", "-s -w -buildid=", "-o", output.absolutePath, ".")
        }
    }
}
tasks.configureEach {
    if (name == "preBuild") dependsOn(vpnNativeTasks)
}
val checkHttpTunnel = tasks.register<Exec>("checkHttpTunnel") {
    group = "verification"
    workingDir(layout.projectDirectory.dir("native"))
    environment("GOTOOLCHAIN", "go1.27.3")
    environment("GOCACHE", vpnGoCache.get())
    environment("GOMODCACHE", vpnGoModules.get())
    commandLine(vpnGo.get(), "test", "-count=1", "-timeout", "60s", "./...")
}
tasks.configureEach {
    if (name == "check") dependsOn(checkHttpTunnel)
}
