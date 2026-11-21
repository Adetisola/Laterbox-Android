package com.laterbox.app.remoteconfig

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.remoteConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "laterbox_remote_config"
)

class RemoteConfigCache(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.remoteConfigDataStore)

    private object Keys {
        val PAYLOAD_JSON = stringPreferencesKey("rc_payload_json")
        val ETAG = stringPreferencesKey("rc_etag")
        val FETCHED_AT_MS = longPreferencesKey("rc_fetched_at_ms")
        val LAST_ATTEMPT_MS = longPreferencesKey("rc_last_attempt_ms")
        val DISMISSED_ANNOUNCEMENTS = stringSetPreferencesKey("rc_dismissed_announcement_ids")
    }

    private val safePrefs: Flow<Preferences> = dataStore.data.catch { err ->
        if (err is IOException) {
            emit(emptyPreferences())
        } else {
            throw err
        }
    }

    val dismissedAnnouncementIdsFlow: Flow<Set<String>> = safePrefs.map { prefs ->
        prefs[Keys.DISMISSED_ANNOUNCEMENTS] ?: emptySet()
    }

    suspend fun readCachedConfig(
        clientVersionCode: Int,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): RemoteConfigSnapshot? {
        val prefs = safePrefs.first()
        val rawJson = prefs[Keys.PAYLOAD_JSON]?.takeIf { it.isNotBlank() } ?: return null
        val etag = prefs[Keys.ETAG]?.takeIf { it.isNotBlank() }
        val fetchedAtMs = prefs[Keys.FETCHED_AT_MS] ?: 0L

        val parsed = RemoteConfigValidator.parseAndValidate(
            rawJson = rawJson,
            clientVersionCode = clientVersionCode,
            source = RemoteConfigSource.CACHE,
            etag = etag,
            fetchedAtEpochMs = fetchedAtMs,
            nowEpochMs = nowEpochMs,
            enforceExpiry = true,
        )
        val snapshot = (parsed as? RemoteConfigParseResult.Valid)?.snapshot ?: return null
        if (snapshot.isStale(nowEpochMs)) {
            return null
        }
        return snapshot
    }

    suspend fun saveCachedConfig(
        rawJson: String,
        etag: String?,
        fetchedAtEpochMs: Long,
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.PAYLOAD_JSON] = rawJson
            if (etag.isNullOrBlank()) {
                prefs.remove(Keys.ETAG)
            } else {
                prefs[Keys.ETAG] = etag
            }
            prefs[Keys.FETCHED_AT_MS] = fetchedAtEpochMs
            prefs[Keys.LAST_ATTEMPT_MS] = fetchedAtEpochMs
        }
    }

    suspend fun updateFetchTimestamp(fetchedAtEpochMs: Long, etag: String? = null) {
        dataStore.edit { prefs ->
            prefs[Keys.FETCHED_AT_MS] = fetchedAtEpochMs
            prefs[Keys.LAST_ATTEMPT_MS] = fetchedAtEpochMs
            if (!etag.isNullOrBlank()) {
                prefs[Keys.ETAG] = etag
            }
        }
    }

    suspend fun recordFetchAttempt(attemptEpochMs: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_ATTEMPT_MS] = attemptEpochMs
        }
    }

    suspend fun readLastAttemptEpochMs(): Long {
        return safePrefs.first()[Keys.LAST_ATTEMPT_MS] ?: 0L
    }

    suspend fun readEtag(): String? {
        return safePrefs.first()[Keys.ETAG]?.takeIf { it.isNotBlank() }
    }

    suspend fun dismissAnnouncement(id: String) {
        val clean = id.trim()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[Keys.DISMISSED_ANNOUNCEMENTS] ?: emptySet()
            prefs[Keys.DISMISSED_ANNOUNCEMENTS] = (current + clean).take(100).toSet()
        }
    }
}
