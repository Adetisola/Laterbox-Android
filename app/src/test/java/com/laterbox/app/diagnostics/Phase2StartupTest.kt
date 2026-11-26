package com.laterbox.app.diagnostics

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class Phase2StartupTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        SplashTimingLogger.resetForTest()
        tempDir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "splash_test_${UUID.randomUUID()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun splashGate_releasesImmediatelyOnPrefsLoadedAndHonors500msCeiling() {
        val start = 1_000L
        assertTrue(SplashTimingLogger.shouldKeepSplashOnScreen(prefsLoaded = false, splashStartElapsedMs = start, nowElapsedMs = 1_000L))
        assertTrue(SplashTimingLogger.shouldKeepSplashOnScreen(prefsLoaded = false, splashStartElapsedMs = start, nowElapsedMs = 1_499L))
        assertFalse(SplashTimingLogger.shouldKeepSplashOnScreen(prefsLoaded = false, splashStartElapsedMs = start, nowElapsedMs = 1_500L))
        assertFalse(SplashTimingLogger.shouldKeepSplashOnScreen(prefsLoaded = true, splashStartElapsedMs = start, nowElapsedMs = 1_012L))
    }

    @Test
    fun splashTelemetry_capturesDataStoreFirstEmissionDistributionSample() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val durations = mutableListOf<Long>()

        repeat(5) { idx ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val dsFile = File(tempDir, "prefs_$idx.preferences_pb")
                val ds = PreferenceDataStoreFactory.create(scope = scope) { dsFile }
                val repo = UserPreferencesRepository(context, ds)

                val startNs = System.nanoTime()
                val splashStartMs = SplashTimingLogger.nowElapsedMs()
                repo.userPreferences.first()
                val deltaMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
                val prefsEmitMs = splashStartMs + deltaMs

                val sample = SplashTimingLogger.recordRelease(
                    splashStartElapsedMs = splashStartMs,
                    prefsFirstEmissionElapsedMs = prefsEmitMs,
                    prefsLoadedBeforeCeiling = deltaMs < SplashTimingLogger.SAFETY_CEILING_MS,
                    releasedAtElapsedMs = prefsEmitMs
                )
                durations += sample.elapsedMs
            } finally {
                scope.cancel()
            }
        }

        val sorted = durations.sorted()
        val median = sorted[sorted.size / 2]
        val samples = SplashTimingLogger.recentSamples()
        assertEquals(5, samples.size)
        assertTrue(samples.all { it.prefsLoadedBeforeCeiling })
        println(
            "PHASE2_SPLASH_DISTRIBUTION: count=${sorted.size} minMs=${sorted.first()} " +
                "medianMs=$median maxMs=${sorted.last()} samplesMs=$durations"
        )
    }
}
