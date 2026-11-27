package com.laterbox.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.laterbox.app.data.local.entity.IntelligenceSignalEntity
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
          AND include_in_assistant_context = 1
          AND (expires_at_ms IS NULL OR expires_at_ms > :nowMs)
        ORDER BY score DESC, last_reinforced_at_ms DESC
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

    @Query("SELECT COUNT(*) FROM intelligence_signals")
    fun observeSignalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM intelligence_signals")
    suspend fun countSignals(): Int

    @Query("SELECT * FROM intelligence_signals ORDER BY created_at_ms DESC LIMIT :limit")
    suspend fun getRecentSignalsSnapshot(limit: Int = 50): List<IntelligenceSignalEntity>

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
}
