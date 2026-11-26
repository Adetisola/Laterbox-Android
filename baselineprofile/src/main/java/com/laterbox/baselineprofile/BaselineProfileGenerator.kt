package com.laterbox.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Generates device-calibrated Baseline Profiles on Samsung Galaxy A02s (SM-A025F)
 * via `./tools/perf/run_a02s_benchmarks.sh --generate-profile`.
 * Covers cold startup, Movies & TV home scroll, and Search -> Detail open (A1).
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        val targetPackage = androidx.test.platform.app.InstrumentationRegistry
            .getArguments()
            .getString("targetAppId")
            ?: "com.laterbox.app"

        rule.collect(
            packageName = targetPackage,
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()
            dismissOnboardingIfPresent()
            navigateAndScrollMovies(targetPackage)
            openDetailFromSearchIfAvailable()
        }
    }

    private fun MacrobenchmarkScope.dismissOnboardingIfPresent() {
        val skip = device.wait(Until.findObject(By.text("Skip")), 2_000L)
        if (skip != null) {
            skip.click()
            device.waitForIdle()
        }
    }

    private fun MacrobenchmarkScope.navigateAndScrollMovies(targetPackage: String) {
        if (device.findObject(By.res("movies_home_screen")) == null) {
            val menuBtn = device.findObject(By.desc("Open navigation menu"))
                ?: device.findObject(By.res("inbox_menu_button"))
            if (menuBtn != null) {
                menuBtn.click()
                val moviesRow = device.wait(Until.findObject(By.res("drawer_movies_row")), 4_000L)
                    ?: device.wait(Until.findObject(By.text("Movies & TV")), 4_000L)
                moviesRow?.click()
                device.wait(Until.findObject(By.text("Discover")), 2_000L)?.click()
            } else {
                val intent = Intent().apply {
                    setClassName(targetPackage, "com.laterbox.app.MainActivity")
                    putExtra("com.laterbox.app.extra.OPEN_DESTINATION", "movies")
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivityAndWait(intent)
            }
        }

        val feedList = device.wait(Until.findObject(By.res("movies_home_list")), 15_000L)
        if (feedList != null) {
            feedList.setGestureMargin(device.displayWidth / 8)
            feedList.fling(Direction.DOWN)
            device.waitForIdle()
            feedList.fling(Direction.UP)
            device.waitForIdle()
        }
    }

    private fun MacrobenchmarkScope.openDetailFromSearchIfAvailable() {
        val searchBtn = device.wait(Until.findObject(By.res("movies_search_button")), 5_000L)
            ?: return
        searchBtn.click()
        if (!device.wait(Until.hasObject(By.res("movies_catalogue_screen")), 10_000L)) return
        val firstCard = device.wait(
            Until.findObject(By.res(Pattern.compile("movie_card_.*"))),
            10_000L
        ) ?: return
        firstCard.click()
        device.wait(Until.hasObject(By.res("movie_detail_list")), 10_000L)
        device.waitForIdle()
    }
}
