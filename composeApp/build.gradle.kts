import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.util.Properties

abstract class GenerateRuntimeConfigsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val localPropertiesFile: RegularFileProperty

    @get:Input
    abstract val appVersionName: Property<String>

    @get:Input
    abstract val appVersionCode: Property<Int>

    @get:Input
    abstract val supabaseUrl: Property<String>

    @get:Input
    abstract val supabaseAnonKey: Property<String>

    @get:Input
    abstract val supabaseFallbackUrl: Property<String>

    @get:Input
    abstract val sentryDsn: Property<String>

    @get:Input
    abstract val sentryEnvironment: Property<String>

    @TaskAction
    fun generate() {
        val props = Properties()
        localPropertiesFile.asFile.orNull?.takeIf { it.exists() }?.inputStream()?.use { props.load(it) }

        val outDir = outputDir.get().asFile
        outDir.resolve("com/nuvio/app/core/network").apply {
            mkdirs()
            resolve("SupabaseConfig.kt").writeText(
                """
                |package com.nuvio.app.core.network
                |
                |object SupabaseConfig {
                |    const val URL = "${supabaseUrl.get()}"
                |    const val ANON_KEY = "${supabaseAnonKey.get()}"
                |    const val FALLBACK_URL = "${supabaseFallbackUrl.get()}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/diagnostics").apply {
            mkdirs()
            resolve("SentryConfig.kt").writeText(
                """
                |package com.nuvio.app.core.diagnostics
                |
                |object SentryConfig {
                |    const val DSN = "${sentryDsn.get()}"
                |    const val ENVIRONMENT = "${sentryEnvironment.get()}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/tmdb/TmdbConfig.kt").delete()

        outDir.resolve("com/nuvio/app/features/trakt").apply {
            mkdirs()
            resolve("TraktConfig.kt").writeText(
                """
                |package com.nuvio.app.features.trakt
                |
                |object TraktConfig {
                |    const val CLIENT_ID = "${props.getProperty("TRAKT_CLIENT_ID", "")}" 
                |    const val CLIENT_SECRET = "${props.getProperty("TRAKT_CLIENT_SECRET", "")}" 
                |    const val REDIRECT_URI = "${props.getProperty("TRAKT_REDIRECT_URI", "nuvio://auth/trakt")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/simkl").apply {
            mkdirs()
            resolve("SimklConfig.kt").writeText(
                """
                |package com.nuvio.app.features.simkl
                |
                |object SimklConfig {
                |    const val CLIENT_ID = "${props.getProperty("SIMKL_CLIENT_ID", "")}"
                |    const val REDIRECT_URI = "${props.getProperty("SIMKL_REDIRECT_URI", "nuvio://auth/simkl")}"
                |    const val APP_NAME = "${props.getProperty("SIMKL_APP_NAME", "nuvio")}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/mdblist").apply {
            mkdirs()
            resolve("MdbListConfig.kt").writeText(
                """
                |package com.nuvio.app.features.mdblist
                |
                |object MdbListConfig {
                |    const val CLIENT_ID = "${props.getProperty("MDBLIST_CLIENT_ID", "kMMZyv8qithmUSF5102U6HTAPEDqfHtNbn1W4gkz")}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/player/skip").apply {
            mkdirs()
            resolve("IntroDbConfig.kt").writeText(
                """
                |package com.nuvio.app.features.player.skip
                |
                |object IntroDbConfig {
                |    const val URL = "${props.getProperty("INTRODB_API_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/details").apply {
            mkdirs()
            resolve("ImdbEpisodeRatingsConfig.kt").writeText(
                """
                |package com.nuvio.app.features.details
                |
                |object ImdbEpisodeRatingsConfig {
                |    const val IMDB_RATINGS_API_BASE_URL = "${props.getProperty("IMDB_RATINGS_API_BASE_URL", "")}" 
                |    const val IMDB_TAPFRAME_API_BASE_URL = "${props.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/debrid").apply {
            mkdirs()
            resolve("PremiumizeConfig.kt").writeText(
                """
                |package com.nuvio.app.features.debrid
                |
                |object PremiumizeConfig {
                |    const val CLIENT_ID = "${props.getProperty("PREMIUMIZE_CLIENT_ID", "")}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/build").apply {
            mkdirs()
            resolve("AppVersionConfig.kt").writeText(
                """
                |package com.nuvio.app.core.build
                |
                |object AppVersionConfig {
                |    const val VERSION_NAME = "${appVersionName.get()}"
                |    const val VERSION_CODE = ${appVersionCode.get()}
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/settings").apply {
            mkdirs()
            resolve("CommunityConfig.kt").writeText(
                """
                |package com.nuvio.app.features.settings
                |
                |object CommunityConfig {
                |    const val CONTRIBUTIONS_URL = "${props.getProperty("CONTRIBUTIONS_URL", "")}" 
                |    const val SUPPORTERS_WALL_URL = "${props.getProperty("SUPPORTERS_WALL_URL", "https://nuvio.tv/api/supporters/wall")}"
                |    const val DONATIONS_BASE_URL = "${props.getProperty("DONATIONS_BASE_URL", "")}" 
                |    const val DONATIONS_DONATE_URL = "${props.getProperty("DONATIONS_DONATE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }
    }
}

fun readXcconfigValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex).trim() to line.substring(separatorIndex + 1).trim()
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

val supabaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val appVersionConfigFile = rootProject.file("iosApp/Configuration/Version.xcconfig")
val releaseAppVersionName = readXcconfigValue(appVersionConfigFile, "MARKETING_VERSION")
    ?: error("MARKETING_VERSION is missing from ${appVersionConfigFile.path}")
val releaseAppVersionCode = readXcconfigValue(appVersionConfigFile, "CURRENT_PROJECT_VERSION")
    ?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION is missing or invalid in ${appVersionConfigFile.path}")
val iosDistribution = (
    providers.gradleProperty("nuvio.ios.distribution").orNull
        ?: System.getenv("NUVIO_IOS_DISTRIBUTION")
        ?: supabaseProps.getProperty("NUVIO_IOS_DISTRIBUTION")
        ?: "appstore"
    ).trim().lowercase()
require(iosDistribution == "appstore" || iosDistribution == "full") {
    "NUVIO_IOS_DISTRIBUTION must be 'appstore' or 'full'."
}
val iosDistributionSourceDir = if (iosDistribution == "full") {
    "src/iosFull/kotlin"
} else {
    "src/iosAppStore/kotlin"
}
val iosFrameworkBundleId = "com.nuvio.media"
val nuvioEngineAppleFramework = rootProject.file("../nuvio-engine/platform/apple/NuvioEngine.xcframework")
val fullCommonSourceDir = project.file("src/fullCommonMain/kotlin")
val generatedRuntimeConfigDir = layout.buildDirectory.dir("generated/runtime-config/kotlin")
val requestedGradleTasks = gradle.startParameter.taskNames.map { taskName ->
    taskName.substringAfterLast(':').lowercase()
}
val requestedAndroidDistributions = requestedGradleTasks.mapNotNull { taskName ->
    when {
        "playstore" in taskName -> "playstore"
        "full" in taskName -> "full"
        else -> null
    }
}.toSet()
require(requestedAndroidDistributions.size <= 1) {
    "Build Android full and playstore distributions separately, or set -Pnuvio.android.distribution=full|playstore."
}
val configuredAndroidDistribution = providers.gradleProperty("nuvio.android.distribution").orNull
    ?: supabaseProps.getProperty("NUVIO_ANDROID_DISTRIBUTION")
val isAmbiguousAndroidPackageTask = requestedGradleTasks.any { taskName ->
    taskName == "build" ||
        taskName.startsWith("assemble") ||
        taskName.startsWith("bundle")
} && requestedAndroidDistributions.isEmpty()
require(configuredAndroidDistribution != null || !isAmbiguousAndroidPackageTask) {
    "Set -Pnuvio.android.distribution=full|playstore for aggregate Android assemble/bundle tasks."
}
val androidDistribution = (
    configuredAndroidDistribution
        ?: requestedAndroidDistributions.singleOrNull()
        ?: "playstore"
    ).trim().lowercase()
require(androidDistribution == "playstore" || androidDistribution == "full") {
    "nuvio.android.distribution must be 'playstore' or 'full'."
}
val androidDistributionSourceDir = if (androidDistribution == "full") {
    "src/androidFull/kotlin"
} else {
    "src/androidPlaystore/kotlin"
}
val runtimeLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun runtimeConfigValue(key: String, fallback: String = ""): String =
    runtimeLocalProperties.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(key).orNull?.trim()?.takeIf { it.isNotBlank() }
        ?: fallback

fun runtimeConfigBoolean(key: String, default: Boolean): Boolean =
    when (runtimeConfigValue(key).lowercase()) {
        "1", "true", "yes", "y", "on" -> true
        "0", "false", "no", "n", "off" -> false
        else -> default
    }

val generateRuntimeConfigs = tasks.register<GenerateRuntimeConfigsTask>("generateRuntimeConfigs") {
    outputDir.set(generatedRuntimeConfigDir)
    localPropertiesFile.set(rootProject.layout.projectDirectory.file("local.properties"))
    appVersionName.set(releaseAppVersionName)
    appVersionCode.set(releaseAppVersionCode)
    supabaseUrl.set(runtimeConfigValue("NUVIO_SUPABASE_URL"))
    supabaseAnonKey.set(runtimeConfigValue("NUVIO_SUPABASE_ANON_KEY"))
    supabaseFallbackUrl.set(runtimeConfigValue("NUVIO_SUPABASE_FALLBACK_URL"))
    sentryDsn.set(runtimeConfigValue("SENTRY_DSN"))
    sentryEnvironment.set(
        when {
            requestedGradleTasks.any { "benchmark" in it } -> "benchmark"
            requestedGradleTasks.any { "debug" in it } -> "debug"
            else -> "production"
        }
    )
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateRuntimeConfigs)
}

kotlin {
    android {
        namespace = "com.nuvio.app"
        compileSdk {
            version = release(libs.versions.android.compileSdk.get().toInt()) {
                minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
            }
        }
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources.enable = true
        withHostTest { isIncludeAndroidResources = true }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64()
    )

    iosTargets.forEach { iosTarget ->
        val nuvioEngineSlice = if (iosTarget.name == "iosArm64") {
            "ios-arm64"
        } else {
            "ios-arm64_x86_64-simulator"
        }
        val nuvioEngineSliceDirectory = nuvioEngineAppleFramework.resolve(nuvioEngineSlice)
        iosTarget.compilations.getByName("main") {
            cinterops {
                create("commoncrypto") {
                    defFile(project.file("src/nativeInterop/cinterop/commoncrypto.def"))
                    compilerOpts("-I${project.projectDir}/src/nativeInterop/cinterop")
                }
                create("appicon") {
                    defFile(project.file("src/nativeInterop/cinterop/appicon.def"))
                    compilerOpts("-I${project.projectDir}/src/nativeInterop/cinterop")
                }
                if (iosDistribution == "full") {
                    check(nuvioEngineSliceDirectory.resolve("libCNuvioEngine.a").isFile) {
                        "Build the local Nuvio Engine Apple XCFramework before compiling iOS Full."
                    }
                    create("nuvioengine") {
                        defFile(project.file("src/nativeInterop/cinterop/nuvioengine.def"))
                        compilerOpts("-I${nuvioEngineSliceDirectory.resolve("Headers").absolutePath}")
                        extraOpts("-libraryPath", nuvioEngineSliceDirectory.absolutePath)
                    }
                }
                configureEach {
                    extraOpts("-Xccall-mode", "direct")
                }
            }

            if (iosDistribution == "full") {
                defaultSourceSet.kotlin.srcDir(fullCommonSourceDir)
            }
            defaultSourceSet.kotlin.srcDir(project.file(iosDistributionSourceDir))
            defaultSourceSet.dependencies {
                implementation(libs.ktor.client.darwin)
                if (iosDistribution == "full") {
                    implementation(libs.quickjs.kt)
                    implementation(libs.ksoup)
                }
            }
        }

        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            freeCompilerArgs += listOf("-Xbinary=bundleId=$iosFrameworkBundleId")
            if (iosDistribution == "full") {
                linkerOpts(
                    "-lc++",
                    "-framework", "Security",
                    "-framework", "SystemConfiguration",
                    "-framework", "CoreFoundation",
                )
            }
        }

        if (iosTarget.name == "iosSimulatorArm64") {
            val testEntitlements = project.file("src/iosTest/resources/keychain-test.entitlements")
            iosTarget.binaries.withType<TestExecutable>().configureEach {
                linkerOpts("-sectcreate", "__TEXT", "__entitlements", testEntitlements.absolutePath)
                linkTaskProvider.configure { inputs.file(testEntitlements) }
            }
        }
    }
    
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedRuntimeConfigDir)
        }
        androidMain {
            kotlin.srcDir(project.file(androidDistributionSourceDir))
            if (androidDistribution == "full") {
                kotlin.srcDir(fullCommonSourceDir)
            }

            dependencies {
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.androidx.appcompat)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.splashscreen)
                implementation(libs.androidx.work.runtime)
                implementation(libs.coil.gif)
                implementation("androidx.recyclerview:recyclerview:1.4.0")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("com.google.code.gson:gson:2.11.0")
                implementation("io.github.peerless2012:ass-media:0.5.1")
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sentry.android)
                implementation(libs.androidx.media3.exoplayer.hls)
                implementation(libs.androidx.media3.exoplayer.dash)
                implementation(libs.androidx.media3.exoplayer.smoothstreaming)
                implementation(libs.androidx.media3.exoplayer.rtsp)
                implementation(libs.androidx.media3.datasource)
                implementation(libs.androidx.media3.datasource.okhttp)
                implementation(libs.androidx.media3.decoder)
                implementation(libs.androidx.media3.session)
                implementation(libs.androidx.media3.common)
                implementation(libs.androidx.media3.container)
                implementation(libs.androidx.media3.extractor)
                implementation(libs.mpv.android.lib)
                implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("lib-*.aar"))))
                if (androidDistribution == "full") {
                    implementation(files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
                    implementation(libs.ksoup)
                }
            }
        }
        val androidHostTest by getting {
            dependencies {
                implementation("org.robolectric:robolectric:4.16")
                implementation("androidx.compose.ui:ui-test-junit4:${libs.versions.composeMultiplatform.get()}")
                implementation("androidx.compose.ui:ui-test-manifest:${libs.versions.composeMultiplatform.get()}")
                implementation("androidx.work:work-testing:${libs.versions.androidx.work.get()}")
                implementation("com.squareup.okhttp3:mockwebserver:5.3.2")
            }
            if (androidDistribution == "full") {
                kotlin.srcDir(project.file("src/androidFullHostTest/kotlin"))
            }
        }
        commonMain.dependencies {
            implementation("io.coil-kt.coil3:coil-compose:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("io.coil-kt.coil3:coil-network-ktor3:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("io.coil-kt.coil3:coil-network-cache-control:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("io.coil-kt.coil3:coil-svg:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("dev.chrisbanes.haze:haze:1.7.2")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.materialRipple)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.savedstate)
            implementation(libs.androidx.savedstate.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kmpalette.core)
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.kermit)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.functions)
            implementation(libs.supabase.storage)
            implementation(libs.reorderable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinx.coroutines.get()}")
        }
    }
}

configurations.matching { it.name == "iosMainImplementation" }.configureEach {
    project.dependencies.add(name, libs.ktor.client.darwin)
}

configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-ui")
}
