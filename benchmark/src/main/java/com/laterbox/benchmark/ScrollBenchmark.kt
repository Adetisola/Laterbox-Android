package com.laterbox.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollMoviesHomeFeedNoCompilation() = scrollHomeFeed(CompilationMode.None())

    @Test
    fun scrollMoviesHomeFeed() = scrollHomeFeed(CompilationMode.Partial())

    private fun scrollHomeFeed(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = BenchmarkNavigationHelper.TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            BenchmarkNavigationHelper.navigateToMoviesHome(this)
        }
    ) {
        BenchmarkNavigationHelper.scrollMoviesHome(this)
    }
}
