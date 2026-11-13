package com.nuvio.baselineprofile

import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val LOG_TAG = "NuvioBaselineProfile"
private const val TARGET_PACKAGE = "com.nuviodebug.com"
private const val LAUNCH_TIMEOUT_MS = 15_000L
private const val HOME_SETTLE_MS = 6_000L
private const val DETAILS_SETTLE_MS = 3_000L
private const val MAX_ITERATIONS = 3
private const val STABLE_ITERATIONS = 2

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
            maxIterations = MAX_ITERATIONS,
            stableIterations = STABLE_ITERATIONS,
        ) {
            launchHome()
        }
        rule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = false,
            maxIterations = MAX_ITERATIONS,
            stableIterations = STABLE_ITERATIONS,
        ) {
            launchHome()
            runJourney("home-scroll") { homeScroll() }
            runJourney("hero-details") { openHeroDetails() }
            runJourney("poster-details") { openPosterDetails() }
            runJourney("tabs") { visitTabs() }
        }
    }
}

private val device: UiDevice
    get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

private fun MacrobenchmarkScope.launchHome() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
    Thread.sleep(HOME_SETTLE_MS)
}

private inline fun runJourney(name: String, block: () -> Unit) {
    try {
        block()
        Log.i(LOG_TAG, "Journey $name finished")
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "Journey $name failed: ${t.message}")
    }
}

private fun swipeVertical(up: Boolean, steps: Int = 25) {
    val x = device.displayWidth / 2
    val top = device.displayHeight / 4
    val bottom = device.displayHeight * 3 / 4
    if (up) device.swipe(x, bottom, x, top, steps) else device.swipe(x, top, x, bottom, steps)
    device.waitForIdle()
}

private fun homeScroll() {
    repeat(6) { swipeVertical(up = true) }
    val y = device.displayHeight / 2
    repeat(3) {
        device.swipe(device.displayWidth * 9 / 10, y, device.displayWidth / 10, y, 20)
        device.waitForIdle()
    }
    repeat(6) { swipeVertical(up = false) }
    device.waitForIdle()
}

private fun openHeroDetails() {
    val button = device.wait(Until.findObject(By.text("View Details")), 3_000L) ?: run {
        swipeVertical(up = true, steps = 40)
        device.wait(Until.findObject(By.text("View Details")), 3_000L)
    } ?: return
    button.click()
    Thread.sleep(DETAILS_SETTLE_MS)
    repeat(4) { swipeVertical(up = true) }
    repeat(4) { swipeVertical(up = false) }
    device.pressBack()
    Thread.sleep(1_500L)
}

private fun openPosterDetails() {
    swipeVertical(up = true)
    val poster = device.findObjects(By.clickable(true).longClickable(true))
        .firstOrNull { it.visibleCenter.y in device.displayHeight / 4..device.displayHeight * 3 / 4 }
        ?: return
    poster.click()
    Thread.sleep(DETAILS_SETTLE_MS)
    if (device.hasObject(By.text("Exit app"))) {
        device.pressBack()
        return
    }
    repeat(3) { swipeVertical(up = true) }
    repeat(3) { swipeVertical(up = false) }
    device.pressBack()
    Thread.sleep(1_500L)
}

private fun visitTabs() {
    listOf("Search", "Library", "Profile", "Home").forEach { tab ->
        val target = device.findObject(By.desc(tab)) ?: device.findObject(By.text(tab)) ?: return@forEach
        target.click()
        Thread.sleep(2_000L)
        swipeVertical(up = true)
        swipeVertical(up = false)
    }
}
