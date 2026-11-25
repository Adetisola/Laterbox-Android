package com.laterbox.benchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal object BenchmarkNavigationHelper {
    const val TARGET_PACKAGE = "com.laterbox.app"
    const val DEFAULT_TIMEOUT_MS = 15_000L

    fun dismissOnboardingIfPresent(scope: MacrobenchmarkScope) {
        val device = scope.device
        val skip = device.wait(Until.findObject(By.text("Skip")), 2_000L)
        if (skip != null) {
            skip.click()
            device.waitForIdle()
        }
    }

    fun navigateToMoviesHome(scope: MacrobenchmarkScope) {
        val device = scope.device
        dismissOnboardingIfPresent(scope)

        // Direct activity launch intent into Movies & TV when drawer isn't already open
        if (device.findObject(By.res("movies_home_screen")) == null) {
            val menuBtn = device.findObject(By.desc("Open navigation menu"))
                ?: device.findObject(By.res("inbox_menu_button"))
            if (menuBtn != null) {
                menuBtn.click()
                val moviesRow = device.wait(Until.findObject(By.res("drawer_movies_row")), 4_000L)
                    ?: device.wait(Until.findObject(By.text("Movies & TV")), 4_000L)
                moviesRow?.click()
                val discoverBtn = device.wait(Until.findObject(By.text("Discover")), 2_000L)
                discoverBtn?.click()
            } else {
                val intent = Intent().apply {
                    setClassName(TARGET_PACKAGE, "com.laterbox.app.MainActivity")
                    putExtra("com.laterbox.app.extra.OPEN_DESTINATION", "movies")
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                scope.startActivityAndWait(intent)
            }
        }

        device.wait(Until.hasObject(By.res("movies_home_list")), DEFAULT_TIMEOUT_MS)
        device.waitForIdle()
    }

    fun scrollMoviesHome(scope: MacrobenchmarkScope) {
        val device = scope.device
        val feedList = device.wait(Until.findObject(By.res("movies_home_list")), DEFAULT_TIMEOUT_MS)
            ?: error("movies_home_list not found; ensure Movies & TV feed loaded on target device")
        feedList.setGestureMargin(device.displayWidth / 8)
        feedList.fling(Direction.DOWN)
        device.waitForIdle()
        feedList.fling(Direction.DOWN)
        device.waitForIdle()
        feedList.fling(Direction.UP)
        device.waitForIdle()
    }

    fun openFirstMovieDetailFromSearch(scope: MacrobenchmarkScope) {
        val device = scope.device
        val searchBtn = device.wait(Until.findObject(By.res("movies_search_button")), DEFAULT_TIMEOUT_MS)
            ?: error("movies_search_button not found on Movies home")
        searchBtn.click()

        device.wait(Until.hasObject(By.res("movies_catalogue_screen")), DEFAULT_TIMEOUT_MS)
        val firstCard = device.wait(
            Until.findObject(By.res(Pattern.compile("movie_card_.*"))),
            DEFAULT_TIMEOUT_MS
        ) ?: error("No movie_card_* rendered in search/catalogue grid")
        firstCard.click()

        device.wait(Until.hasObject(By.res("movie_detail_list")), DEFAULT_TIMEOUT_MS)
        device.waitForIdle()
    }
}
