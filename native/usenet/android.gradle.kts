import groovy.json.JsonSlurper
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject

// Applied by androidApp/build.gradle.kts. Only source builds require Go, NDK and CMake.
// A PIE executable is named lib*.so solely so PackageManager installs it in the
// executable nativeLibraryDir. It is launched, never loaded through JNI.
// Task actions capture plain values only, for the configuration cache.
interface UsenetInjected {
    @get:Inject val exec: ExecOperations
}

val usenetInjected = objects.newInstance<UsenetInjected>()
val usenetNativeRoot: File = rootProject.file("native/usenet")
val usenetEngineSourceRoot: File? = providers.gradleProperty("usenetEngineSourceDir").orNull?.let { rootProject.file(it) }
val usenetSdk = File(extra["usenetSdkDirectory"] as String)
val usenetNdk = File(usenetSdk, "ndk/${extra["usenetNdkVersion"] as String}")
val usenetWindows = System.getProperty("os.name").startsWith("Windows")
val usenetHost = when {
    usenetWindows -> "windows-x86_64"
    System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val usenetCmakeBin = File(usenetSdk, "cmake/3.22.1/bin")
val usenetToolchain = File(usenetNdk, "toolchains/llvm/prebuilt/$usenetHost/bin")
val usenetGo: String = providers.gradleProperty("usenetGo").orElse(providers.environmentVariable("GO_EXECUTABLE")).orElse("go").get()
val usenetAbis = mapOf(
    "arm64-v8a" to Pair("arm64", "aarch64-linux-android"),
    "armeabi-v7a" to Pair("arm", "armv7a-linux-androideabi"),
    "x86_64" to Pair("amd64", "x86_64-linux-android"),
    "x86" to Pair("386", "i686-linux-android"),
)
val usenetBuildFromSource = providers.gradleProperty("buildUsenetFromSource")
    .map { it.equals("true", ignoreCase = true) || it == "1" }
    .orElse(false)
    .get()
val usenetOffline = gradle.startParameter.isOffline
val usenetManifest = File(usenetNativeRoot, "prebuilt/manifest.json")
val usenetMetadata: Map<*, *> = if (usenetBuildFromSource) emptyMap<Any?, Any?>()
    else JsonSlurper().parse(usenetManifest) as Map<*, *>
val usenetBaseUrl: String = usenetMetadata["downloadBaseUrl"] as? String ?: ""

val usenetBuildTasks = usenetAbis.map { (abi, target) ->
    tasks.register("buildUsenet${abi.replace("-", "").replace("_", "")}") {
        val output = layout.buildDirectory.file("generated/usenet/jniLibs/$abi/libnuvio_usenet.so")
        val nativeBuild = layout.buildDirectory.dir("usenet/$abi").get().asFile
        val prebuilt = File(usenetNativeRoot, "prebuilt/$abi/libnuvio_usenet.so")
        val expected = (usenetMetadata["binarySha256"] as? Map<*, *>)?.get(abi) as? String ?: ""
        val fromSource = usenetBuildFromSource
        val offline = usenetOffline
        val baseUrl = usenetBaseUrl
        val execOperations = usenetInjected.exec
        val sourceRoot = usenetEngineSourceRoot
        val cmake = File(usenetCmakeBin, "cmake${if (usenetWindows) ".exe" else ""}")
        val ninja = File(usenetCmakeBin, "ninja${if (usenetWindows) ".exe" else ""}")
        val ndk = usenetNdk
        val toolchain = usenetToolchain
        val go = usenetGo
        val windows = usenetWindows

        if (fromSource) {
            inputs.files(fileTree(requireNotNull(sourceRoot) { "Source builds require -PusenetEngineSourceDir=/path/to/nuvio-usenet-engine" }) {
                include("**/*.go", "**/*.mod", "**/*.sum", "**/*.cc", "**/*.h", "**/CMakeLists.txt")
                exclude("build/**", "**/*_test.go", "prebuilt/**")
            })
            inputs.file(File(usenetNativeRoot, "android.gradle.kts"))
            inputs.property("ndk", ndk.name)
        } else {
            inputs.file(usenetManifest)
            inputs.files(fileTree(prebuilt.parentFile) { include(prebuilt.name) })
        }
        outputs.file(output)

        doLast {
            fun sha256(file: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                return digest.digest().joinToString("") { "%02x".format(it) }
            }
            val outputFile = output.get().asFile
            outputFile.parentFile.mkdirs()
            if (!fromSource) {
                check(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid Usenet checksum for $abi" }
                if (!prebuilt.exists() || sha256(prebuilt) != expected) {
                    check(!offline) {
                        "Usenet $abi prebuilt is missing or corrupt. Run once online to download it, or use -PbuildUsenetFromSource=true."
                    }
                    check(baseUrl.matches(Regex("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[A-Za-z0-9_.-]+"))) {
                        "Invalid Usenet release URL"
                    }
                    prebuilt.parentFile.mkdirs()
                    val temporary = File.createTempFile("usenet-", ".part", prebuilt.parentFile)
                    try {
                        logger.lifecycle("Downloading verified Usenet native engine for $abi")
                        val connection = URI("$baseUrl/libnuvio_usenet-$abi.so").toURL().openConnection()
                        connection.connectTimeout = 30000
                        connection.readTimeout = 120000
                        connection.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                        check(sha256(temporary) == expected) { "Usenet $abi download failed SHA-256 verification" }
                        Files.move(temporary.toPath(), prebuilt.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        temporary.delete()
                    }
                }
                prebuilt.copyTo(outputFile, overwrite = true)
                return@doLast
            }

            val engine = requireNotNull(sourceRoot)
            check(cmake.exists()) { "Install Android SDK CMake 3.22.1 to build Usenet" }
            check(ndk.exists()) { "Install Android NDK ${ndk.name} to build Usenet" }
            execOperations.exec {
                commandLine(cmake, "-S", File(engine, "third_party/rapidyenc-native"), "-B", nativeBuild,
                    "-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=$ninja",
                    "-DCMAKE_TOOLCHAIN_FILE=${File(ndk, "build/cmake/android.toolchain.cmake")}",
                    "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-24", "-DANDROID_STL=c++_static",
                    "-DCMAKE_BUILD_TYPE=Release", "-DDISABLE_SHARED=ON", "-DDISABLE_TOOL=ON", "-DDISABLE_CRC=ON")
            }
            execOperations.exec { commandLine(cmake, "--build", nativeBuild, "--target", "rapidyenc_static", "-j", "4") }
            File(nativeBuild, "rapidyenc_static/librapidyenc.a")
                .copyTo(File(engine, "third_party/rapidyenc/librapidyenc_android_${target.first}.a"), overwrite = true)
            execOperations.exec {
                workingDir(engine)
                environment("GOOS", "android"); environment("GOARCH", target.first); environment("CGO_ENABLED", "1")
                if (target.first == "arm") environment("GOARM", "7")
                environment("CC", "\"${File(toolchain, "${target.second}24-clang${if (windows) ".cmd" else ""}").absolutePath}\"")
                environment("CXX", "\"${File(toolchain, "${target.second}24-clang++${if (windows) ".cmd" else ""}").absolutePath}\"")
                commandLine(go, "build", "-trimpath", "-buildvcs=false", "-buildmode=pie",
                    "-ldflags=-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384", "-o", outputFile, "./cmd/nuvio-usenet")
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(usenetBuildTasks) }
