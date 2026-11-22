package com.laterbox.app.remoteconfig

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.laterbox.app.ai.RemoteCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class RemoteConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sampleValidPayload(
        revision: Int = 4,
        expiresAt: String? = "2099-01-01T00:00:00Z",
        minRefresh: Int = 900,
    ): String = """
        {
          "schemaVersion": 1,
          "revision": $revision,
          "publishedAt": "2026-11-21T01:00:00Z",
          "expiresAt": ${if (expiresAt == null) "null" else "\"$expiresAt\""},
          "general": {
            "minRefreshIntervalSeconds": $minRefresh,
            "staleCacheMaxAgeSeconds": 86400
          },
          "versionPolicy": {
            "minimumSupportedVersionCode": 50,
            "recommendedVersionCode": 65,
            "enforcementEnabled": false,
            "recommendedTag": "v1.0.65",
            "updateMessage": "New streaming stability improvements available."
          },
          "maintenance": {
            "enabled": true,
            "mode": "partial",
            "title": "Streaming Maintenance",
            "message": "MovieBox streaming is undergoing scheduled maintenance.",
            "allowLocalOfflineAccess": true
          },
          "announcements": [
            {
              "id": "ann-1",
              "title": "Maintenance Notice",
              "message": "Local Inbox & Downloads continue normally.",
              "severity": "warning",
              "surface": "inbox",
              "dismissible": true,
              "actionLabel": "Open Downloads",
              "actionDestination": "downloads",
              "minVersionCode": 60,
              "maxVersionCode": 0,
              "enabled": true
            },
            {
              "id": "ann-old-only",
              "title": "Old App Only",
              "message": "Should be excluded on v62",
              "severity": "info",
              "surface": "inbox",
              "dismissible": true,
              "actionLabel": "",
              "actionDestination": "",
              "minVersionCode": 1,
              "maxVersionCode": 55,
              "enabled": true
            }
          ],
          "features": {
            "movies_tv_enabled": true,
            "provider_moviebox_enabled": false,
            "unknown_future_flag": false
          },
          "ui": {
            "components": [
              {
                "id": "rail-watch",
                "type": "content_rail",
                "surface": "movies",
                "visible": true,
                "order": 20,
                "railId": "watchlist",
                "title": "Your Queue",
                "maxItems": 10
              },
              {
                "id": "callout-good",
                "type": "featured_callout_card",
                "surface": "movies",
                "visible": true,
                "order": 5,
                "title": "Offline Vault",
                "subtitle": "Watch saved downloads anytime",
                "actionLabel": "Browse",
                "actionDestination": "downloads"
              },
              {
                "id": "unknown-future-widget",
                "type": "experimental_3d_carousel",
                "surface": "movies",
                "visible": true,
                "order": 1
              },
              {
                "id": "bad-xss-callout",
                "type": "featured_callout_card",
                "surface": "movies",
                "visible": true,
                "order": 2,
                "title": "Tap",
                "actionDestination": "javascript:alert(1)"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun validator_parsesValidPayload_andFiltersUnknownAndOutOfRangeItems() {
        val nowMs = 1_795_200_000_000L
        val result = RemoteConfigValidator.parseAndValidate(
            rawJson = sampleValidPayload(),
            clientVersionCode = 62,
            source = RemoteConfigSource.NETWORK,
            etag = "\"rev-4\"",
            fetchedAtEpochMs = nowMs,
            nowEpochMs = nowMs,
        )
        assertTrue(result is RemoteConfigParseResult.Valid)
        val snap = (result as RemoteConfigParseResult.Valid).snapshot

        assertEquals(4, snap.revision)
        assertTrue(snap.maintenance.isPartialActive)
        assertFalse(snap.isFeatureEnabled(RemoteCapabilities.KEY_PROVIDER_MOVIEBOX))
        assertTrue(snap.isFeatureEnabled(RemoteCapabilities.KEY_MOVIES_TV))
        // Unknown feature key ignored safely, returns fallback default
        assertTrue(snap.isFeatureEnabled("unknown_future_flag", defaultValue = true))

        // Only ann-1 matches versionCode 62
        assertEquals(1, snap.announcements.size)
        assertEquals("ann-1", snap.announcements.first().id)

        // Unknown widget and javascript: callout rejected; remaining components sorted by order
        assertEquals(2, snap.ui.components.size)
        assertEquals("callout-good", snap.ui.components[0].id)
        assertEquals("rail-watch", snap.ui.components[1].id)
    }

    @Test
    fun validator_rejectsMalformedJsonUnsupportedSchemaAndExpiredConfig() {
        val nowMs = 1_795_200_000_000L

        val malformed = RemoteConfigValidator.parseAndValidate("{not valid json", 62, RemoteConfigSource.NETWORK)
        assertTrue(malformed is RemoteConfigParseResult.Rejected)

        val badSchema = RemoteConfigValidator.parseAndValidate(
            rawJson = """{"schemaVersion": 99, "revision": 1}""",
            clientVersionCode = 62,
            source = RemoteConfigSource.NETWORK,
        )
        assertTrue(badSchema is RemoteConfigParseResult.Rejected)

        val expired = RemoteConfigValidator.parseAndValidate(
            rawJson = sampleValidPayload(expiresAt = "2020-01-01T00:00:00Z"),
            clientVersionCode = 62,
            source = RemoteConfigSource.NETWORK,
            fetchedAtEpochMs = nowMs,
            nowEpochMs = nowMs,
            enforceExpiry = true,
        )
        assertTrue(expired is RemoteConfigParseResult.Rejected)
    }

    @Test
    fun client_handles200_304_appCheckRetry_andInvalidJsonSafely() = runBlocking {
        // 1) Initial 200 with ETag
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("ETag", "\"rev-4-abc\"")
                .setBody(sampleValidPayload(revision = 4))
        )
        val tokenCalls = AtomicInteger(0)
        val client = RemoteConfigClient(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            appCheckTokenProvider = { force ->
                tokenCalls.incrementAndGet()
                if (force) "fresh-appcheck-token" else "initial-appcheck-token"
            },
            clientVersionCode = 62,
            clientVersionName = "1.0.62",
        )

        val outcome1 = client.fetchPublishedConfig(cachedEtag = null)
        assertTrue(outcome1 is RemoteConfigFetchOutcome.Updated)
        val req1 = server.takeRequest()
        assertEquals("/config/v1/published", req1.requestUrl?.encodedPath)
        assertEquals("62", req1.requestUrl?.queryParameter("versionCode"))
        assertEquals("1.0.62", req1.requestUrl?.queryParameter("versionName"))
        assertTrue(req1.requestUrl?.queryParameter("capabilities").orEmpty().contains("feature:movies_tv_enabled"))
        assertEquals("initial-appcheck-token", req1.getHeader("X-Firebase-AppCheck"))

        // 2) Conditional 304 Not Modified
        server.enqueue(
            MockResponse()
                .setResponseCode(304)
                .addHeader("ETag", "\"rev-4-abc\"")
        )
        val outcome2 = client.fetchPublishedConfig(cachedEtag = "\"rev-4-abc\"")
        assertTrue(outcome2 is RemoteConfigFetchOutcome.NotModified)
        val req2 = server.takeRequest()
        assertEquals("\"rev-4-abc\"", req2.getHeader("If-None-Match"))

        // 3) 401 triggers single force-refresh App Check retry
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("ETag", "\"rev-5-xyz\"")
                .setBody(sampleValidPayload(revision = 5))
        )
        val outcome3 = client.fetchPublishedConfig(cachedEtag = null)
        assertTrue(outcome3 is RemoteConfigFetchOutcome.Updated)
        assertEquals(5, (outcome3 as RemoteConfigFetchOutcome.Updated).snapshot.revision)
        server.takeRequest()
        val retryReq = server.takeRequest()
        assertEquals("fresh-appcheck-token", retryReq.getHeader("X-Firebase-AppCheck"))

        // 4) Malformed 200 payload rejected cleanly
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>bad</html>"))
        val outcome4 = client.fetchPublishedConfig(cachedEtag = null, forceRefresh = true)
        assertTrue(outcome4 is RemoteConfigFetchOutcome.Failed)
    }

    @Test
    fun repository_cachesOfflineStartup_throttlesRefresh_andFallsBackWhenNetworkFails() = runBlocking {
        val dsFile = File(tempFolder.newFolder("rc_ds"), "test_rc.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var nowMs = 1_795_200_000_000L
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { dsFile }
            val cache = RemoteConfigCache(dataStore)
            val client = RemoteConfigClient(
                okHttpClient = OkHttpClient(),
                baseUrlProvider = { server.url("/").toString().trimEnd('/') },
                appCheckTokenProvider = { "token-1" },
                clientVersionCode = 62,
                clientVersionName = "1.0.62",
                clockMs = { nowMs },
            )
            val repo = RemoteConfigRepository(cache, client, clientVersionCode = 62, clockMs = { nowMs })

            // Empty cache initially -> safe defaults
            assertNull(repo.loadFromCache())
            assertTrue(repo.state.value.activeConfig.isFeatureEnabled(RemoteCapabilities.KEY_AI_ASSISTANT))

            // Network refresh stores rev 7 in DataStore
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("ETag", "\"rev-7\"")
                    .setBody(sampleValidPayload(revision = 7, minRefresh = 900))
            )
            val stateAfterFetch = repo.refreshIfNeeded(forceRefresh = false)
            assertEquals(7, stateAfterFetch.activeConfig.revision)
            assertEquals(RemoteConfigSource.NETWORK, stateAfterFetch.activeConfig.source)

            // Throttled when called within minRefreshIntervalSeconds (no HTTP request issued)
            nowMs += 30_000L
            val throttledState = repo.refreshIfNeeded(forceRefresh = false)
            assertEquals(7, throttledState.activeConfig.revision)
            assertEquals(1, server.requestCount)

            // New repository instance cold boots offline from cached DataStore without network
            val coldBootRepo = RemoteConfigRepository(cache, client, clientVersionCode = 62, clockMs = { nowMs })
            val hydrated = coldBootRepo.loadFromCache()
            assertNotNull(hydrated)
            assertEquals(7, hydrated?.revision)
            assertEquals(RemoteConfigSource.CACHE, hydrated?.source)

            // Even if network fails later after interval expires, cached snapshot remains active
            nowMs += 1_000_000L
            server.enqueue(MockResponse().setResponseCode(503).setBody("Unavailable"))
            val failedRefreshState = coldBootRepo.refreshIfNeeded(forceRefresh = false)
            assertEquals(7, failedRefreshState.activeConfig.revision)
            assertNotNull(failedRefreshState.lastError)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun checkpoint3_resolvesHeroSlides_multiSurfaceOptionalTitleNotices_andNormalBannerRoundTrip() {
        val rawJson = """
            {
              "schemaVersion": 1,
              "revision": 15,
              "publishedAt": "2026-11-22T01:00:00Z",
              "expiresAt": null,
              "general": { "minRefreshIntervalSeconds": 900, "staleCacheMaxAgeSeconds": 86400 },
              "versionPolicy": { "minimumSupportedVersionCode": 0, "recommendedVersionCode": 0, "enforcementEnabled": false, "recommendedTag": "", "updateMessage": "" },
              "maintenance": {
                "enabled": false,
                "mode": "none",
                "title": "",
                "message": "Everything healthy.",
                "allowLocalOfflineAccess": true,
                "showBannerInNormalMode": true
              },
              "announcements": [
                {
                  "id": "ann_multi",
                  "title": "",
                  "message": "Collections & Downloads sync tip",
                  "severity": "warning",
                  "surface": "collections",
                  "surfaces": ["collections", "downloads", "assistant"],
                  "dismissible": true,
                  "actionLabel": "Open",
                  "actionDestination": "assistant",
                  "enabled": true
                }
              ],
              "features": { "movies_tv_enabled": true },
              "ui": {
                "components": [
                  {
                    "id": "movies_hero_default",
                    "type": "hero_carousel",
                    "surface": "movies",
                    "visible": true,
                    "order": 10,
                    "autoAdvanceMs": 7000,
                    "slides": [
                      {
                        "id": "slide_a",
                        "imageUrl": "/config/v1/assets/hero_1234567890abcdef",
                        "heading": "Weekend Spotlight",
                        "subtitle": "Hand-picked films",
                        "actionLabel": "Open",
                        "actionDestination": "collections",
                        "enabled": true,
                        "order": 1
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = RemoteConfigValidator.parseAndValidate(
            rawJson = rawJson,
            clientVersionCode = 199,
            source = RemoteConfigSource.NETWORK,
            backendBaseUrl = "https://laterbox-moviebox-api.vercel.app/",
        )
        assertTrue(parsed is RemoteConfigParseResult.Valid)
        val snap = (parsed as RemoteConfigParseResult.Valid).snapshot
        assertTrue(snap.maintenance.showBannerInNormalMode)
        assertTrue(snap.maintenance.shouldShowStatusStrip)
        assertEquals(listOf(RemoteUiSurface.COLLECTIONS, RemoteUiSurface.DOWNLOADS, RemoteUiSurface.ASSISTANT), snap.announcements[0].surfaces)
        val slide = snap.ui.components[0].slides[0]
        assertEquals("https://laterbox-moviebox-api.vercel.app/config/v1/assets/hero_1234567890abcdef", slide.imageUrl)

        // Round-trip through serializeToJson retains slides, normal-mode banner toggle, and multi-surface array
        val serialized = RemoteConfigValidator.serializeToJson(snap)
        val reparsed = RemoteConfigValidator.parseAndValidate(serialized, clientVersionCode = 199, source = RemoteConfigSource.CACHE)
        assertTrue(reparsed is RemoteConfigParseResult.Valid)
        val cachedSnap = (reparsed as RemoteConfigParseResult.Valid).snapshot
        assertTrue(cachedSnap.maintenance.showBannerInNormalMode)
        assertEquals(3, cachedSnap.announcements[0].surfaces.size)
        assertEquals("https://laterbox-moviebox-api.vercel.app/config/v1/assets/hero_1234567890abcdef", cachedSnap.ui.components[0].slides[0].imageUrl)
    }

    @Test
    fun repository_exponentialBackoffOnFailures_andBypassThrottlePropagatesNewRevision() = runBlocking {
        val dsFile = File(tempFolder.newFolder("rc_backoff_ds"), "test_backoff.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var nowMs = 1_795_200_000_000L
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { dsFile }
            val cache = RemoteConfigCache(dataStore)
            val client = RemoteConfigClient(
                okHttpClient = OkHttpClient(),
                baseUrlProvider = { server.url("/").toString().trimEnd('/') },
                appCheckTokenProvider = { "token-1" },
                clientVersionCode = 200,
                clientVersionName = "1.0.67",
                clockMs = { nowMs },
            )
            val repo = RemoteConfigRepository(cache, client, clientVersionCode = 200, clockMs = { nowMs })

            // Failure 1 -> backoff 10s (does NOT wait 900s!)
            server.enqueue(MockResponse().setResponseCode(503).setBody("Down"))
            val f1 = repo.refreshIfNeeded(forceRefresh = false, bypassThrottle = true)
            assertEquals(1, f1.consecutiveFailures)
            assertEquals(1, server.requestCount)

            // Before 10s elapsed -> respects backoff
            nowMs += 5_000L
            repo.refreshIfNeeded(forceRefresh = false, bypassThrottle = true)
            assertEquals(1, server.requestCount)

            // At 10s -> retries and fails -> backoff 30s
            nowMs += 5_000L
            server.enqueue(MockResponse().setResponseCode(503).setBody("Down"))
            val f2 = repo.refreshIfNeeded(forceRefresh = false, bypassThrottle = true)
            assertEquals(2, f2.consecutiveFailures)
            assertEquals(2, server.requestCount)

            nowMs += 20_000L
            repo.refreshIfNeeded(forceRefresh = false, bypassThrottle = true)
            assertEquals(2, server.requestCount)

            // At 30s -> succeeds even though server sent minRefreshIntervalSeconds=900!
            nowMs += 10_000L
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("ETag", "\"rev-10\"")
                    .setBody(sampleValidPayload(revision = 10, minRefresh = 900))
            )
            val recovered = repo.refreshIfNeeded(forceRefresh = false, bypassThrottle = true)
            assertEquals(0, recovered.consecutiveFailures)
            assertEquals(10, recovered.activeConfig.revision)
            assertEquals(3, server.requestCount)

            // Lifecycle/poll with bypassThrottle=true after 60s sends If-None-Match despite minRefresh=900
            nowMs += 60_000L
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("ETag", "\"rev-11\"")
                    .setBody(sampleValidPayload(revision = 11, minRefresh = 900))
            )
            val updated = repo.refreshIfNeeded(forceRefresh = false, bypassThrottle = true)
            assertEquals(11, updated.activeConfig.revision)
            val condReq = server.takeRequest()
            val condReq2 = server.takeRequest()
            val condReq3 = server.takeRequest()
            val pollReq = server.takeRequest()
            assertNotNull(condReq)
            assertNotNull(condReq2)
            assertNotNull(condReq3)
            assertEquals("\"rev-10\"", pollReq.getHeader("If-None-Match"))
            assertNull(pollReq.requestUrl?.queryParameter("refresh"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun manager_foregroundPollingStartsOnForegroundAndStopsOnBackground() = runBlocking {
        val dsFile = File(tempFolder.newFolder("rc_poll_ds"), "test_poll.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var nowMs = 1_795_200_000_000L
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { dsFile }
            val cache = RemoteConfigCache(dataStore)
            val client = RemoteConfigClient(
                okHttpClient = OkHttpClient(),
                baseUrlProvider = { server.url("/").toString().trimEnd('/') },
                appCheckTokenProvider = { "token-1" },
                clientVersionCode = 200,
                clientVersionName = "1.0.67",
                clockMs = { nowMs },
            )
            val repo = RemoteConfigRepository(cache, client, clientVersionCode = 200, clockMs = { nowMs })
            val manager = RemoteConfigManager(
                repository = repo,
                applicationScope = scope,
                foregroundPollIntervalMs = 40L,
            )

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("ETag", "\"rev-20\"")
                    .setBody(sampleValidPayload(revision = 20, minRefresh = 60))
            )
            server.enqueue(MockResponse().setResponseCode(304).addHeader("ETag", "\"rev-20\""))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("ETag", "\"rev-21\"")
                    .setBody(sampleValidPayload(revision = 21, minRefresh = 60))
            )

            manager.onAppForegrounded()
            val deadline1 = System.currentTimeMillis() + 3_000L
            while (manager.currentConfig.revision != 20 && System.currentTimeMillis() < deadline1) {
                kotlinx.coroutines.delay(25L)
            }
            assertEquals(20, manager.currentConfig.revision)

            // Advance simulated clock past 5s dedup so subsequent poll ticks issue network checks
            val deadline2 = System.currentTimeMillis() + 3_000L
            while (manager.currentConfig.revision != 21 && System.currentTimeMillis() < deadline2) {
                nowMs += 10_000L
                kotlinx.coroutines.delay(50L)
            }
            assertEquals(21, manager.currentConfig.revision)

            // Stop polling cleanly on background
            manager.onAppBackgrounded()
            kotlinx.coroutines.delay(60L)
            val countAtBackground = server.requestCount
            nowMs += 10_000L
            kotlinx.coroutines.delay(150L)
            assertEquals(countAtBackground, server.requestCount)
        } finally {
            scope.cancel()
        }
    }
}
