package com.laterbox.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMetricApi::class)
@LargeTest
@RunWith(AndroidJUnit4::class)
class DetailLoadBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun navigateSearchToDetailNoCompilation() = measureDetailLoad(CompilationMode.None())

    @Test
    fun navigateSearchToDetail() = measureDetailLoad(CompilationMode.Partial())

    private fun measureDetailLoad(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = BenchmarkNavigationHelper.TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric("LaterboxDetailTTID", TraceSectionMetric.Mode.First),
            TraceSectionMetric("LaterboxDetailTTFD", TraceSectionMetric.Mode.First),
        ),
        compilationMode = compilationMode,
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            BenchmarkNavigationHelper.navigateToMoviesHome(this)
        }
    ) {
        BenchmarkNavigationHelper.openFirstMovieDetailFromSearch(this)
    }
}
