package com.laterbox.app.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class LaterboxBackendAuthProviderTest {

    @Test
    fun `verified App Check device stays on X-Firebase-AppCheck and never downgrades to guest`() = runBlocking {
        var failAppCheck = false
        val guestCalls = AtomicInteger(0)
        val provider = LaterboxBackendAuthProvider(
            appCheckFetcher = { forceRefresh ->
                if (failAppCheck) {
                    Result.failure(IllegalStateException("Play Integrity attestation quota exceeded"))
                } else {
                    Result.success(if (forceRefresh) "appcheck-refreshed" else "appcheck-initial")
                }
            },
            guestTokenFetcher = {
                guestCalls.incrementAndGet()
                Result.success("guest-token-should-never-be-used")
            },
            isNetworkValidatedOnline = { true },
            isEmulatorHint = { false },
        )

        val first = provider.resolveCredential(forceRefresh = false)
        assertTrue(first is LaterboxBackendCredential.AppCheck)
        assertEquals("X-Firebase-AppCheck" to "appcheck-initial", first!!.toHeaderPair())
        assertTrue(provider.isUsingVerifiedAppCheck)
        assertFalse(provider.isUsingGuestFallback)
        assertEquals(0, guestCalls.get())

        val refreshed = provider.resolveCredential(forceRefresh = true)
        assertEquals("X-Firebase-AppCheck" to "appcheck-refreshed", refreshed!!.toHeaderPair())
        assertEquals(0, guestCalls.get())

        // Even if a subsequent App Check fetch fails on a verified device, never downgrade to guest auth.
        failAppCheck = true
        val afterFailure = provider.resolveCredential(forceRefresh = true)
        assertNull(afterFailure)
        assertTrue(provider.isUsingVerifiedAppCheck)
        assertFalse(provider.isUsingGuestFallback)
        assertEquals(0, guestCalls.get())
    }

    @Test
    fun `offline state or transient network failure never triggers guest fallback`() = runBlocking {
        val guestCalls = AtomicInteger(0)
        val offlineProvider = LaterboxBackendAuthProvider(
            appCheckFetcher = { Result.failure(IllegalStateException("Play Integrity unavailable")) },
            guestTokenFetcher = {
                guestCalls.incrementAndGet()
                Result.success("guest-should-not-run-offline")
            },
            isNetworkValidatedOnline = { false },
            isEmulatorHint = { true },
        )
        assertNull(offlineProvider.resolveHeaderPair())
        assertFalse(offlineProvider.isUsingGuestFallback)
        assertEquals(0, guestCalls.get())

        val transientFailures = listOf(
            UnknownHostException("Unable to resolve host firebaseappcheck.googleapis.com"),
            SocketTimeoutException("connect timed out"),
            IllegalStateException("com.google.firebase.FirebaseException: A network error occurred"),
        )
        for (err in transientFailures) {
            val networkErrorProvider = LaterboxBackendAuthProvider(
                appCheckFetcher = { Result.failure(err) },
                guestTokenFetcher = {
                    guestCalls.incrementAndGet()
                    Result.success("guest-should-not-run-on-network-error")
                },
                isNetworkValidatedOnline = { true },
                isEmulatorHint = { true },
            )
            assertNull(networkErrorProvider.resolveHeaderPair())
            assertFalse(networkErrorProvider.isUsingGuestFallback)
            assertEquals(0, guestCalls.get())
        }
    }

    @Test
    fun `unsupported Play Integrity or emulator switches cleanly to X-Firebase-Auth guest session`() = runBlocking {
        val appCheckCalls = AtomicInteger(0)
        val guestCalls = AtomicInteger(0)
        val observedRefreshFlags = mutableListOf<Boolean>()

        val provider = LaterboxBackendAuthProvider(
            appCheckFetcher = {
                appCheckCalls.incrementAndGet()
                Result.failure(IllegalStateException("Play Services not available: attestation API_NOT_AVAILABLE"))
            },
            guestTokenFetcher = { forceRefresh ->
                guestCalls.incrementAndGet()
                observedRefreshFlags.add(forceRefresh)
                Result.success(if (forceRefresh) "guest-jwt-2" else "guest-jwt-1")
            },
            isNetworkValidatedOnline = { true },
            isEmulatorHint = { false },
        )

        val first = provider.resolveHeaderPair(forceRefresh = false)
        assertEquals("X-Firebase-Auth" to "guest-jwt-1", first)
        assertTrue(provider.isUsingGuestFallback)
        assertFalse(provider.isUsingVerifiedAppCheck)
        assertEquals(1, appCheckCalls.get())
        assertEquals(1, guestCalls.get())

        // Subsequent calls reuse guest mode directly without re-probing App Check, while passing forceRefresh.
        val refreshed = provider.resolveHeaderPair(forceRefresh = true)
        assertEquals("X-Firebase-Auth" to "guest-jwt-2", refreshed)
        assertEquals(1, appCheckCalls.get())
        assertEquals(2, guestCalls.get())
        assertEquals(listOf(false, true), observedRefreshFlags)

        // App-Check-only helper stays strictly isolated and never leaks guest tokens as App Check.
        assertNull(provider.resolveAppCheckOnlyToken())
    }

    @Test
    fun `concurrent resolution on emulator serializes fallback transition safely`() = runBlocking {
        val appCheckCalls = AtomicInteger(0)
        val guestCalls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val provider = LaterboxBackendAuthProvider(
            appCheckFetcher = {
                appCheckCalls.incrementAndGet()
                delay(20)
                Result.success(null) // null without exception + emulator hint triggers guest fallback
            },
            guestTokenFetcher = {
                guestCalls.incrementAndGet()
                gate.await()
                Result.success("guest-concurrent-jwt")
            },
            isNetworkValidatedOnline = { true },
            isEmulatorHint = { true },
        )

        val d1 = async { provider.resolveHeaderPair() }
        val d2 = async { provider.resolveHeaderPair() }
        val d3 = async { provider.resolveHeaderPair() }
        delay(50)
        gate.complete(Unit)

        val results = awaitAll(d1, d2, d3)
        assertEquals(1, appCheckCalls.get())
        assertEquals(3, guestCalls.get())
        results.forEach { header ->
            assertNotNull(header)
            assertEquals("X-Firebase-Auth" to "guest-concurrent-jwt", header)
        }
    }

    @Test
    fun `non-emulator unknown non-attestation error does not silently downgrade`() = runBlocking {
        val guestCalls = AtomicInteger(0)
        val provider = LaterboxBackendAuthProvider(
            appCheckFetcher = { Result.failure(IllegalArgumentException("unexpected internal error")) },
            guestTokenFetcher = {
                guestCalls.incrementAndGet()
                Result.success("guest-should-not-activate")
            },
            isNetworkValidatedOnline = { true },
            isEmulatorHint = { false },
        )

        assertNull(provider.resolveCredential())
        assertFalse(provider.isUsingGuestFallback)
        assertEquals(0, guestCalls.get())
    }
}
