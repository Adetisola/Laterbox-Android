package com.laterbox.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.laterbox.app.data.local.entity.IntelligenceSignalEntity
import com.laterbox.app.data.local.entity.SuggestionDismissalEntity
import com.laterbox.app.data.local.entity.TitleFeedbackEntity
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IntelligenceDao {

    @Query("SELECT * FROM user_taste_facets ORDER BY score DESC, last_reinforced_at_ms DESC")
    fun observeAllFacets(): Flow<List<UserTasteFacetEntity>>

    @Query("SELECT * FROM user_taste_facets ORDER BY score DESC, last_reinforced_at_ms DESC")
    suspend fun getAllFacetsSnapshot(): List<UserTasteFacetEntity>

    @Query("SELECT * FROM user_taste_facets WHERE facet_key = :facetKey LIMIT 1")
    suspend fun getFacetByKey(facetKey: String): UserTasteFacetEntity?

    @Query(
        """
        SELECT * FROM user_taste_facets
        WHERE is_suppressed_by_user = 0
          AND (expires_at_ms IS NULL OR expires_at_ms > :nowMs)
          AND (surface_scope = :scope OR surface_scope = 'ALL')
        ORDER BY score DESC, last_reinforced_at_ms DESC
        """
    )
    suspend fun getActiveFacetsForScope(scope: String, nowMs: Long = System.currentTimeMillis()): List<UserTasteFacetEntity>

    @Query(
        """
        SELECT * FROM user_taste_facets
        WHERE is_suppressed_by_user = 0
          AND (expires_at_ms IS NULL OR expires_at_ms > :nowMs)
          AND (
              facet_type IN ('EXPLICIT_LIKE', 'EXPLICIT_DISLIKE')
              OR (has_behavioral_evidence = 1 AND score >= 0.30 AND confidence >= 0.40)
          )
        ORDER BY score DESC, confidence DESC, last_reinforced_at_ms DESC
        LIMIT :limit
        """
    )
    suspend fun getAssistantEligibleFacets(limit: Int = 8, nowMs: Long = System.currentTimeMillis()): List<UserTasteFacetEntity>

    @Query("SELECT COUNT(*) FROM user_taste_facets")
    suspend fun countFacets(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFacet(facet: UserTasteFacetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFacets(facets: List<UserTasteFacetEntity>)

    @Query("UPDATE user_taste_facets SET is_suppressed_by_user = :suppressed WHERE facet_key = :facetKey")
    suspend fun setFacetSuppressed(facetKey: String, suppressed: Boolean)

    @Query("UPDATE user_taste_facets SET include_in_assistant_context = :include WHERE facet_key = :facetKey")
    suspend fun setFacetIncludeInAssistant(facetKey: String, include: Boolean)

    @Query("DELETE FROM user_taste_facets WHERE facet_key = :facetKey")
    suspend fun deleteFacetByKey(facetKey: String)

    @Query("DELETE FROM user_taste_facets WHERE expires_at_ms IS NOT NULL AND expires_at_ms <= :nowMs")
    suspend fun deleteExpiredFacets(nowMs: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM user_taste_facets")
    suspend fun clearAllFacets()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignal(signal: IntelligenceSignalEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignals(signals: List<IntelligenceSignalEntity>)

    @Query("SELECT COUNT(*) FROM intelligence_signals")
    fun observeSignalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM intelligence_signals")
    suspend fun countSignals(): Int

    @Query("SELECT * FROM intelligence_signals ORDER BY created_at_ms DESC LIMIT :limit")
    suspend fun getRecentSignalsSnapshot(limit: Int = 500): List<IntelligenceSignalEntity>

    @Query(
        """
        SELECT * FROM intelligence_signals
        WHERE is_media_cue = 1
          AND created_at_ms >= :sinceMs
        ORDER BY created_at_ms DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentMediaCueSignals(sinceMs: Long, limit: Int = 30): List<IntelligenceSignalEntity>

    @Query(
        """
        UPDATE intelligence_signals
        SET target_key = 'search:sanitized:legacy'
        WHERE signal_type = 'SEARCH_QUERY'
          AND target_key LIKE 'search:%'
          AND target_key NOT LIKE 'search:sanitized:%'
        """
    )
    suspend fun sanitizeLegacySearchSignals(): Int

    @Query("DELETE FROM intelligence_signals WHERE created_at_ms < :cutoffMs")
    suspend fun deleteSignalsOlderThan(cutoffMs: Long): Int

    @Query(
        """
        DELETE FROM intelligence_signals
        WHERE id NOT IN (
            SELECT id FROM intelligence_signals
            ORDER BY created_at_ms DESC, id DESC
            LIMIT :keepLimit
        )
        """
    )
    suspend fun trimSignalsToMaxRows(keepLimit: Int = 500): Int

    @Query("DELETE FROM intelligence_signals")
    suspend fun clearAllSignals()

    @Query("SELECT * FROM title_feedback ORDER BY updated_at_ms DESC")
    fun observeAllTitleFeedback(): Flow<List<TitleFeedbackEntity>>

    @Query(
        """
        SELECT * FROM title_feedback
        WHERE media_key = :mediaKey OR canonical_id = :mediaKey OR composite_id = :compositeId
        ORDER BY updated_at_ms DESC
        LIMIT 1
        """
    )
    fun observeTitleFeedback(mediaKey: String, compositeId: String): Flow<TitleFeedbackEntity?>

    @Query("SELECT * FROM title_feedback ORDER BY updated_at_ms DESC")
    suspend fun getAllTitleFeedbackSnapshot(): List<TitleFeedbackEntity>

    @Query(
        """
        SELECT * FROM title_feedback
        WHERE media_key = :mediaKey OR canonical_id = :mediaKey OR composite_id = :compositeId
        ORDER BY updated_at_ms DESC
        LIMIT 1
        """
    )
    suspend fun getTitleFeedback(mediaKey: String, compositeId: String): TitleFeedbackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTitleFeedback(feedback: TitleFeedbackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllTitleFeedback(items: List<TitleFeedbackEntity>)

    @Query("DELETE FROM title_feedback WHERE media_key = :mediaKey OR canonical_id = :mediaKey OR composite_id = :compositeId")
    suspend fun deleteTitleFeedback(mediaKey: String, compositeId: String)

    @Query(
        """
        SELECT * FROM suggestion_dismissals
        WHERE suppress_until_ms IS NULL OR suppress_until_ms > :nowMs
        ORDER BY dismissed_at_ms DESC
        """
    )
    fun observeActiveDismissals(nowMs: Long = System.currentTimeMillis()): Flow<List<SuggestionDismissalEntity>>

    @Query("SELECT * FROM suggestion_dismissals ORDER BY dismissed_at_ms DESC")
    suspend fun getAllDismissalsSnapshot(): List<SuggestionDismissalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDismissal(dismissal: SuggestionDismissalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDismissals(dismissals: List<SuggestionDismissalEntity>)

    @Query("DELETE FROM suggestion_dismissals WHERE suppress_until_ms IS NOT NULL AND suppress_until_ms <= :nowMs")
    suspend fun deleteExpiredDismissals(nowMs: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM suggestion_dismissals WHERE dismissal_key = :dismissalKey")
    suspend fun deleteDismissalByKey(dismissalKey: String)

    @Query("DELETE FROM suggestion_dismissals")
    suspend fun clearAllDismissals()
}
