package com.laterbox.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.network.LaterboxConnectivityObserver
import com.laterbox.app.network.OfflineToastStateTracker
import com.laterbox.app.ui.components.LaterboxToastController
import com.laterbox.app.ui.components.LaterboxToastHost
import com.laterbox.app.ui.components.LaterboxToastItem
import com.laterbox.app.ui.components.LaterboxToastVariant
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaterboxToastAndConnectivityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun controller_stacksNewestOnTop_capsAtThree_andAutoDismissesAfterFiveSeconds() = runTest {
        val controller = LaterboxToastController(scope = this, clockMs = { testScheduler.currentTime })

        val id1 = controller.showNeutral("First note")
        advanceTimeBy(100)
        val id2 = controller.showSuccess("Saved link")
        advanceTimeBy(100)
        val id3 = controller.showWarning("Slow response")
        advanceTimeBy(100)
        val id4 = controller.showError("Failed to sync")

        // Newest toast sits at index 0, capped at 3 visible, oldest transient evicted
        assertEquals(listOf(id4, id3, id2), controller.toasts.value.map { it.id })
        assertFalse(controller.toasts.value.any { it.id == id1 })

        // Just before 5s since id4 was added, id4 is still present; id2 & id3 already elapsed 5s?
        // Wait: id2 was added at t=100ms, id3 at 200ms, id4 at 300ms.
        // Advancing 4_750ms -> t = 5_050ms: id2 (elapsed 4_950ms) and id3 (4_850ms) and id4 (4_750ms) are all still active.
        advanceTimeBy(4_750)
        runCurrent()
        assertEquals(listOf(id4, id3, id2), controller.toasts.value.map { it.id })

        // Advancing another 300ms -> t = 5_350ms: all three transient toasts auto-dismiss after 5s.
        advanceTimeBy(300)
        runCurrent()
        assertTrue(controller.toasts.value.isEmpty())
    }

    @Test
    fun controller_dedupeUpdatesExistingToastMovesToTopAndResetsTimer() = runTest {
        val controller = LaterboxToastController(scope = this, clockMs = { testScheduler.currentTime })
        var dismissedCount = 0

        val firstId = controller.show(
            message = "Initial sync",
            variant = LaterboxToastVariant.NEUTRAL,
            onDismiss = { dismissedCount++ },
            dedupeKey = "sync_status"
        )
        advanceTimeBy(2_000)
        controller.showSuccess("Saved other item")
        advanceTimeBy(2_000)

        // Emit duplicate dedupeKey -> refreshes same card and promotes it to top without duplicating
        val updatedId = controller.show(
            message = "Final sync",
            variant = LaterboxToastVariant.SUCCESS,
            dedupeKey = "sync_status"
        )

        assertEquals(firstId, updatedId)
        assertEquals(1, dismissedCount)
        assertEquals(2, controller.toasts.value.size)
        assertEquals("Final sync", controller.toasts.value.first().message)
        assertEquals(LaterboxToastVariant.SUCCESS, controller.toasts.value.first().variant)

        // After 3_500ms, "Saved other item" (elapsed 5_500ms) auto-dismisses, but refreshed "Final sync" (3_500ms) stays
        advanceTimeBy(3_500)
        runCurrent()
        assertEquals(listOf("Final sync"), controller.toasts.value.map { it.message })

        // Another 2_000ms completes the 5s lifetime of the refreshed toast
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(controller.toasts.value.isEmpty())
    }

    @Test
    fun persistentOfflineToast_isNotEvictedByTransientToasts_andDoesNotAutoDismiss() = runTest {
        val controller = LaterboxToastController(scope = this, clockMs = { testScheduler.currentTime })

        val offlineId = controller.showOffline("You're offline")
        controller.showNeutral("Notice 1")
        controller.showSuccess("Notice 2")
        controller.showWarning("Notice 3")
        controller.showError("Notice 4")

        val visible = controller.toasts.value
        assertEquals(LaterboxToastController.MAX_VISIBLE_TOASTS, visible.size)
        assertTrue(visible.any { it.id == offlineId && it.persistent })
        assertEquals(listOf("Notice 4", "Notice 3", "You're offline"), visible.map { it.message })

        // Advancing far past 5s clears transient notices while keeping the persistent offline toast
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(offlineId), controller.toasts.value.map { it.id })
    }

    @Test
    fun controller_showAndAwaitAction_resolvesTrueOnActionAndFalseOnDismiss() = runTest {
        val controller = LaterboxToastController(scope = this, clockMs = { testScheduler.currentTime })

        val actionDeferred = async {
            controller.showAndAwaitAction(
                message = "Removed from Saved",
                variant = LaterboxToastVariant.NEUTRAL,
                actionLabel = "Undo"
            )
        }
        runCurrent()
        val toastId = controller.toasts.value.single().id
        controller.performAction(toastId)
        assertTrue(actionDeferred.await())

        val dismissDeferred = async {
            controller.showAndAwaitAction(
                message = "Removed from Saved",
                variant = LaterboxToastVariant.NEUTRAL,
                actionLabel = "Undo"
            )
        }
        runCurrent()
        val dismissId = controller.toasts.value.single().id
        controller.dismiss(dismissId, byUser = true)
        assertFalse(dismissDeferred.await())
    }

    @Test
    fun offlineTracker_showsOnOffline_suppressesUntilReconnect_andRearmsAfterReconnect() {
        val scope = TestScope(StandardTestDispatcher())
        val controller = LaterboxToastController(scope)
        val tracker = OfflineToastStateTracker(controller)

        // Initially online -> no offline toast
        tracker.onConnectivityEvaluated(true)
        assertTrue(tracker.isValidatedOnline.value)
        assertTrue(controller.toasts.value.isEmpty())

        // Go offline -> shows persistent "You're offline" toast
        tracker.onConnectivityEvaluated(false)
        assertFalse(tracker.isValidatedOnline.value)
        val offlineToast = controller.toasts.value.single()
        assertEquals("You're offline", offlineToast.message)
        assertEquals(LaterboxToastVariant.OFFLINE, offlineToast.variant)
        assertTrue(offlineToast.persistent)

        // User dismisses manually -> suppressed for current outage, even on repeated offline callbacks
        controller.dismiss(offlineToast.id, byUser = true)
        assertTrue(tracker.isSuppressedForCurrentOutage)
        assertTrue(controller.toasts.value.isEmpty())

        tracker.onConnectivityEvaluated(false)
        assertTrue(controller.toasts.value.isEmpty())

        // Reconnect -> resets suppression and clears offline state
        tracker.onConnectivityEvaluated(true)
        assertTrue(tracker.isValidatedOnline.value)
        assertFalse(tracker.isSuppressedForCurrentOutage)

        // Subsequent outage -> re-armed, shows "You're offline" again
        tracker.onConnectivityEvaluated(false)
        assertEquals("You're offline", controller.toasts.value.single().message)
    }

    @Test
    fun connectivityObserver_requiresBothInternetAndValidatedCapabilities() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)

        val defaultNetwork = cm.activeNetwork ?: ShadowNetwork.newInstance(42)

        // 1. Active network with INTERNET but NOT VALIDATED -> offline
        val unvalidatedCaps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(unvalidatedCaps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowCm.setNetworkCapabilities(defaultNetwork, unvalidatedCaps)
        assertFalse(LaterboxConnectivityObserver.isNetworkValidatedOnline(cm))

        // 2. Active network with INTERNET + VALIDATED -> online
        val validatedCaps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(validatedCaps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Shadows.shadowOf(validatedCaps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowCm.setNetworkCapabilities(defaultNetwork, validatedCaps)
        assertTrue(LaterboxConnectivityObserver.isNetworkValidatedOnline(cm))

        // 3. No active network -> offline
        @Suppress("DEPRECATION")
        shadowCm.setActiveNetworkInfo(null)
        assertFalse(LaterboxConnectivityObserver.isNetworkValidatedOnline(cm))
    }

    @Test
    fun toastHost_rendersAllVariantsNewestOnTop_andHandlesActionAndDismiss() {
        val scope = TestScope(StandardTestDispatcher())
        val controller = LaterboxToastController(scope)
        var actionTriggered = false
        var dismissedManually = false

        val idOld = controller.showSuccess("Saved to library")
        val idAction = controller.showError(
            message = "Download failed",
            actionLabel = "Retry",
            onAction = { actionTriggered = true }
        )
        val idNewest = controller.showOffline(
            message = "You're offline",
            onManualDismiss = { dismissedManually = true }
        )

        // Emit one warning variant separately after rendering to also exercise WARNING & NEUTRAL
        composeTestRule.setContent {
            LaterboxTheme(darkTheme = true) {
                val toasts by controller.toasts.collectAsState()
                LaterboxToastHost(
                    toasts = toasts,
                    onDismissToast = { controller.dismiss(it, byUser = true) },
                    onActionClick = { controller.performAction(it) },
                    reducedMotion = true,
                    toastController = controller
                )
            }
        }

        composeTestRule.onNodeWithTag("laterbox_toast_host").assertIsDisplayed()
        composeTestRule.onNodeWithText("You're offline").assertIsDisplayed()
        composeTestRule.onNodeWithText("Download failed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved to library").assertIsDisplayed()

        // Verify newest-on-top vertical order in rendered tree
        val newestTop = composeTestRule
            .onNodeWithTag("laterbox_toast_card_offline_$idNewest")
            .getBoundsInRoot()
            .top
        val actionTop = composeTestRule
            .onNodeWithTag("laterbox_toast_card_error_$idAction")
            .getBoundsInRoot()
            .top
        val oldestTop = composeTestRule
            .onNodeWithTag("laterbox_toast_card_success_$idOld")
            .getBoundsInRoot()
            .top

        assertTrue(newestTop < actionTop)
        assertTrue(actionTop < oldestTop)

        // Click "Retry" action button -> runs action callback and removes that toast
        composeTestRule.onNodeWithTag("laterbox_toast_action_$idAction").performClick()
        composeTestRule.waitForIdle()
        assertTrue(actionTriggered)
        composeTestRule.onNodeWithText("Download failed").assertDoesNotExist()

        // Click dismiss X on offline toast -> runs manual dismiss callback and removes toast
        composeTestRule.onNodeWithTag("laterbox_toast_dismiss_$idNewest").performClick()
        composeTestRule.waitForIdle()
        assertTrue(dismissedManually)
        composeTestRule.onNodeWithText("You're offline").assertDoesNotExist()

        // Also render WARNING and NEUTRAL variants in the host
        val idWarning = controller.showWarning("Battery saver active")
        val idNeutral = controller.showNeutral("Link copied")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("laterbox_toast_card_warning_$idWarning").assertIsDisplayed()
        composeTestRule.onNodeWithTag("laterbox_toast_card_neutral_$idNeutral").assertIsDisplayed()

        val unusedItem = LaterboxToastItem(id = "noop", message = "Check")
        assertEquals("Check", unusedItem.message)
    }

    @Test
    fun connectivityObserver_wifiOff_withValidatedCellular_staysOnline() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)

        val cellular = cm.activeNetwork ?: ShadowNetwork.newInstance(101)
        val cellularCaps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(cellularCaps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        Shadows.shadowOf(cellularCaps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Shadows.shadowOf(cellularCaps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowCm.setNetworkCapabilities(cellular, cellularCaps)

        val controller = LaterboxToastController(scope = this)
        val observer = LaterboxConnectivityObserver(
            context = context,
            toastController = controller,
            scope = this,
            offlineGracePeriodMs = 1_200L
        )
        observer.refresh()
        runCurrent()

        assertTrue(observer.tracker.isValidatedOnline.value)
        assertTrue(controller.toasts.value.isEmpty())
    }

    @Test
    fun connectivityObserver_networkHandoff_avoidsFalseOfflineFlash() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)

        val controller = LaterboxToastController(scope = this)
        val observer = LaterboxConnectivityObserver(
            context = context,
            toastController = controller,
            scope = this,
            offlineGracePeriodMs = 1_200L
        )
        val callback = observer.createDefaultNetworkCallback(cm)

        val wifi = ShadowNetwork.newInstance(201)
        val cellular = ShadowNetwork.newInstance(202)
        val wifiCaps = ShadowNetworkCapabilities.newInstance().apply {
            Shadows.shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val cellularUnvalidated = ShadowNetworkCapabilities.newInstance().apply {
            Shadows.shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val cellularValidated = ShadowNetworkCapabilities.newInstance().apply {
            Shadows.shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        // Start online on validated Wi-Fi
        shadowCm.setNetworkCapabilities(wifi, wifiCaps)
        callback.onCapabilitiesChanged(wifi, wifiCaps)
        assertTrue(observer.tracker.isValidatedOnline.value)
        assertTrue(controller.toasts.value.isEmpty())

        // Wi-Fi drops and cellular connects unvalidated before validating 500ms later
        @Suppress("DEPRECATION")
        shadowCm.setActiveNetworkInfo(null)
        callback.onLost(wifi)
        advanceTimeBy(150)
        runCurrent()
        assertTrue(controller.toasts.value.none { it.variant == LaterboxToastVariant.OFFLINE })

        shadowCm.setNetworkCapabilities(cellular, cellularUnvalidated)
        callback.onAvailable(cellular)
        callback.onCapabilitiesChanged(cellular, cellularUnvalidated)
        advanceTimeBy(500)
        runCurrent()
        assertTrue(controller.toasts.value.none { it.variant == LaterboxToastVariant.OFFLINE })

        // Cellular validates well before the 1200ms handoff grace elapses
        shadowCm.setNetworkCapabilities(cellular, cellularValidated)
        callback.onCapabilitiesChanged(cellular, cellularValidated)
        advanceTimeBy(1_500)
        runCurrent()

        assertTrue(observer.tracker.isValidatedOnline.value)
        assertTrue(controller.toasts.value.isEmpty())
    }

    @Test
    fun connectivityObserver_unvalidatedWifiAndOutage_reportsOfflineAfterGrace_andSyncsOnRefresh() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)

        val controller = LaterboxToastController(scope = this)
        val observer = LaterboxConnectivityObserver(
            context = context,
            toastController = controller,
            scope = this,
            offlineGracePeriodMs = 1_200L
        )
        val callback = observer.createDefaultNetworkCallback(cm)
        val wifi = cm.activeNetwork ?: ShadowNetwork.newInstance(301)

        val validWifi = ShadowNetworkCapabilities.newInstance().apply {
            Shadows.shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val noInternetWifi = ShadowNetworkCapabilities.newInstance().apply {
            Shadows.shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        shadowCm.setNetworkCapabilities(wifi, validWifi)
        callback.onCapabilitiesChanged(wifi, validWifi)
        assertTrue(observer.tracker.isValidatedOnline.value)

        // Wi-Fi loses internet validation (connected to router, no WAN)
        shadowCm.setNetworkCapabilities(wifi, noInternetWifi)
        callback.onCapabilitiesChanged(wifi, noInternetWifi)
        advanceTimeBy(1_199)
        runCurrent()
        assertTrue(controller.toasts.value.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertFalse(observer.tracker.isValidatedOnline.value)
        assertEquals("You're offline", controller.toasts.value.single().message)

        // User manually dismisses offline banner during the outage
        val offlineId = controller.toasts.value.single().id
        controller.dismiss(offlineId, byUser = true)
        assertTrue(controller.toasts.value.isEmpty())

        // Returning to foreground while still offline keeps manual dismissal suppressed
        @Suppress("DEPRECATION")
        shadowCm.setActiveNetworkInfo(null)
        observer.refresh()
        runCurrent()
        assertFalse(observer.tracker.isValidatedOnline.value)
        assertTrue(controller.toasts.value.isEmpty())

        // Restoring validated connectivity clears suppression & re-arms for next genuine outage
        shadowCm.setDefaultNetworkActive(true)
        shadowCm.setNetworkCapabilities(wifi, validWifi)
        observer.refresh()
        runCurrent()
        assertTrue(observer.tracker.isValidatedOnline.value)
        assertFalse(observer.tracker.isSuppressedForCurrentOutage)
    }
}

