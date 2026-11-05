plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

val verifyVpnCore by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs VPN import and routing checks without Android or third-party dependencies."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.nuvio.vpn.core.VpnCoreChecks")
}

tasks.check { dependsOn(verifyVpnCore) }

// This dependency-free suite has a main entry point, not JUnit test methods. Gradle 9
// rejects a Test task with sources but no discovered tests; verifyVpnCore runs them.
tasks.test { enabled = false }
