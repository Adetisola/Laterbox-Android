package com.laterbox.app.ui.settings

import com.laterbox.app.ai.LaterboxKnowledgeBase
import com.laterbox.app.ui.navigation.LaterboxDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyNoticeAndSettingsRestructureTest {

    @Test
    fun settingsDestinations_consolidatedDataStorage_andRemovedDeprecatedRoutesFromAssistantSpec() {
        assertEquals("settings/data_storage", LaterboxDestination.SettingsDataStorage.route)
        assertFalse(LaterboxKnowledgeBase.ALLOWED_ACTION_SPEC.contains("settings/about_privacy"))
        assertFalse(LaterboxKnowledgeBase.ALLOWED_ACTION_SPEC.contains("settings/app_storage"))
        assertTrue(LaterboxKnowledgeBase.ALLOWED_ACTION_SPEC.contains("settings/data_storage"))
    }
}
