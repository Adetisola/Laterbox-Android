package com.laterbox.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.laterbox.app.data.local.dao.ActivityEventDao
import com.laterbox.app.data.local.dao.ChatDao
import com.laterbox.app.data.local.dao.CollectionDao
import com.laterbox.app.data.local.dao.NotificationHistoryDao
import com.laterbox.app.data.local.dao.SavedLinkDao
import com.laterbox.app.data.local.entity.ActivityEventEntity
import com.laterbox.app.data.local.entity.ChatConversationEntity
import com.laterbox.app.data.local.entity.ChatMessageEntity
import com.laterbox.app.data.local.entity.CollectionEntity
import com.laterbox.app.data.local.entity.NotificationHistoryEntity
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.media.data.db.dao.DownloadedAssetDao
import com.laterbox.app.media.data.db.dao.DownloadTaskDao
import com.laterbox.app.media.data.db.dao.MediaInspectionDao
import com.laterbox.app.media.data.db.entity.DownloadedAssetEntity
import com.laterbox.app.media.data.db.entity.DownloadTaskEntity
import com.laterbox.app.media.data.db.entity.MediaInspectionEntity
import com.laterbox.app.movies.data.local.dao.MoviesDao
import com.laterbox.app.movies.data.local.entity.CanonicalMediaEntity
import com.laterbox.app.movies.data.local.entity.MediaCollectionCrossRef
import com.laterbox.app.movies.data.local.entity.MediaProviderLinkEntity
import com.laterbox.app.movies.data.local.entity.MovieCacheEntryEntity
import com.laterbox.app.movies.data.local.entity.MovieRecentlyViewedEntity
import com.laterbox.app.movies.data.local.entity.MovieSearchHistoryEntity
import com.laterbox.app.movies.data.local.entity.MovieSeriesPreferencesEntity
import com.laterbox.app.movies.data.local.entity.MovieViewingReminderEntity
import com.laterbox.app.movies.data.local.entity.MovieWatchHistoryEntity
import com.laterbox.app.movies.data.local.entity.MovieWatchlistEntity

@Database(
    entities = [
        SavedLinkEntity::class,
        CollectionEntity::class,
        com.laterbox.app.data.local.entity.LinkCollectionCrossRef::class,
        NotificationHistoryEntity::class,
        DownloadedAssetEntity::class,
        DownloadTaskEntity::class,
        MediaInspectionEntity::class,
        ChatConversationEntity::class,
        ChatMessageEntity::class,
        ActivityEventEntity::class,
        com.laterbox.app.data.local.entity.ChatActionPermissionEntity::class,
        MovieCacheEntryEntity::class,
        MovieWatchlistEntity::class,
        MovieWatchHistoryEntity::class,
        MovieSearchHistoryEntity::class,
        CanonicalMediaEntity::class,
        MediaProviderLinkEntity::class,
        MovieRecentlyViewedEntity::class,
        MovieSeriesPreferencesEntity::class,
        MediaCollectionCrossRef::class,
        MovieViewingReminderEntity::class
    ],
    version = 16,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class LaterboxDatabase : RoomDatabase() {

    abstract fun savedLinkDao(): SavedLinkDao
    abstract fun collectionDao(): CollectionDao
    abstract fun notificationHistoryDao(): NotificationHistoryDao
    abstract fun downloadedAssetDao(): DownloadedAssetDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun mediaInspectionDao(): MediaInspectionDao
    abstract fun chatDao(): ChatDao
    abstract fun activityEventDao(): ActivityEventDao
    abstract fun moviesDao(): MoviesDao

    companion object {
        private const val DATABASE_NAME = "laterbox.db"

        /**
         * Non-destructive migration from version 1 to version 2:
         * 1. Safely merges any pre-existing duplicate rows sharing the same normalized_url
         *    (combining personal notes, preserving collection assignments, descriptions, and images).
         * 2. Replaces the non-unique index on normalized_url with a UNIQUE index.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val dupCursor = db.query(
                    "SELECT normalized_url FROM saved_links GROUP BY normalized_url HAVING COUNT(*) > 1"
                )
                val duplicateUrls = mutableListOf<String>()
                dupCursor.use { c ->
                    while (c.moveToNext()) {
                        duplicateUrls.add(c.getString(0))
                    }
                }

                for (normUrl in duplicateUrls) {
                    val rowCursor = db.query(
                        """
                        SELECT id, title, domain, description, site_name, favicon_url, 
                               preview_image_url, personal_note, collection_id, is_read, is_archived,
                               metadata_status, content_type
                        FROM saved_links
                        WHERE normalized_url = ?
                        ORDER BY id ASC
                        """.trimIndent(),
                        arrayOf(normUrl)
                    )

                    var keeperId: Long? = null
                    var bestTitle: String? = null
                    var domain: String = ""
                    var bestDescription: String? = null
                    var bestSiteName: String? = null
                    var bestFavicon: String? = null
                    var bestImage: String? = null
                    val notes = mutableListOf<String>()
                    var bestCollectionId: Long? = null
                    var anyRead = false
                    var anyUnarchived = false
                    var bestStatus = "FAILED"
                    var bestContentType = "WEBSITE"
                    val duplicateIdsToDelete = mutableListOf<Long>()

                    fun statusRank(status: String?): Int = when (status) {
                        "SUCCESS" -> 4
                        "PARTIAL" -> 3
                        "PENDING" -> 2
                        "FETCHING" -> 1
                        else -> 0
                    }

                    rowCursor.use { rc ->
                        while (rc.moveToNext()) {
                            val id = rc.getLong(0)
                            val title = rc.getString(1)
                            val rowDomain = rc.getString(2) ?: ""
                            val desc = rc.getString(3)
                            val siteName = rc.getString(4)
                            val favicon = rc.getString(5)
                            val image = rc.getString(6)
                            val note = rc.getString(7)
                            val colId = if (rc.isNull(8)) null else rc.getLong(8)
                            val isRead = rc.getInt(9) != 0
                            val isArchived = rc.getInt(10) != 0
                            val status = rc.getString(11) ?: "PENDING"
                            val contentType = rc.getString(12) ?: "WEBSITE"

                            if (keeperId == null) {
                                keeperId = id
                                domain = rowDomain
                                bestStatus = status
                                bestContentType = contentType
                            } else {
                                duplicateIdsToDelete.add(id)
                            }

                            if (!title.isNullOrBlank() && (bestTitle == null || bestTitle == domain)) {
                                bestTitle = title
                            }
                            if (bestDescription.isNullOrBlank() && !desc.isNullOrBlank()) {
                                bestDescription = desc
                            }
                            if (bestSiteName.isNullOrBlank() && !siteName.isNullOrBlank()) {
                                bestSiteName = siteName
                            }
                            if (bestFavicon.isNullOrBlank() && !favicon.isNullOrBlank()) {
                                bestFavicon = favicon
                            }
                            if (bestImage.isNullOrBlank() && !image.isNullOrBlank()) {
                                bestImage = image
                            }
                            if (!note.isNullOrBlank() && note.trim() !in notes) {
                                notes.add(note.trim())
                            }
                            if (bestCollectionId == null && colId != null) {
                                bestCollectionId = colId
                            }
                            if (isRead) anyRead = true
                            if (!isArchived) anyUnarchived = true
                            if (statusRank(status) > statusRank(bestStatus)) {
                                bestStatus = status
                            }
                            if (bestContentType == "WEBSITE" && contentType != "WEBSITE") {
                                bestContentType = contentType
                            }
                        }
                    }

                    val finalKeeperId = keeperId
                    if (finalKeeperId != null && duplicateIdsToDelete.isNotEmpty()) {
                        val mergedNote = if (notes.isEmpty()) null else notes.joinToString("\n\n")
                        db.execSQL(
                            """
                            UPDATE saved_links
                            SET title = ?,
                                description = ?,
                                site_name = ?,
                                favicon_url = ?,
                                preview_image_url = ?,
                                personal_note = ?,
                                collection_id = ?,
                                is_read = ?,
                                is_archived = ?,
                                metadata_status = ?,
                                content_type = ?
                            WHERE id = ?
                            """.trimIndent(),
                            arrayOf<Any?>(
                                bestTitle ?: domain,
                                bestDescription,
                                bestSiteName,
                                bestFavicon,
                                bestImage,
                                mergedNote,
                                bestCollectionId,
                                if (anyRead) 1 else 0,
                                if (anyUnarchived) 0 else 1,
                                bestStatus,
                                bestContentType,
                                finalKeeperId
                            )
                        )

                        for (delId in duplicateIdsToDelete) {
                            db.execSQL("DELETE FROM saved_links WHERE id = ?", arrayOf<Any>(delId))
                        }
                    }
                }

                db.execSQL("DROP INDEX IF EXISTS `index_saved_links_normalized_url`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_saved_links_normalized_url` ON `saved_links` (`normalized_url`)")
            }
        }

        /**
         * Consolidated atomic migration from version 2 to version 3:
         * 1. Creates link_collection_cross_ref table with foreign keys & indices.
         * 2. Migrates existing saved_links.collection_id entries into link_collection_cross_ref.
         * 3. Adds reminder_at, last_nudged_at, nudge_count, and nudge_dismissed columns with index.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create link_collection_cross_ref
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `link_collection_cross_ref` (
                        `link_id` INTEGER NOT NULL,
                        `collection_id` INTEGER NOT NULL,
                        PRIMARY KEY(`link_id`, `collection_id`),
                        FOREIGN KEY(`link_id`) REFERENCES `saved_links`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`collection_id`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_link_collection_cross_ref_collection_id` ON `link_collection_cross_ref` (`collection_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_link_collection_cross_ref_link_id` ON `link_collection_cross_ref` (`link_id`)")

                // 2. Migrate existing saved_links.collection_id
                db.execSQL("""
                    INSERT OR IGNORE INTO `link_collection_cross_ref` (`link_id`, `collection_id`)
                    SELECT `id`, `collection_id` FROM `saved_links` WHERE `collection_id` IS NOT NULL
                """.trimIndent())

                // 3. Add reminder & nudge columns
                db.execSQL("ALTER TABLE `saved_links` ADD COLUMN `reminder_at` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `saved_links` ADD COLUMN `last_nudged_at` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `saved_links` ADD COLUMN `nudge_count` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `saved_links` ADD COLUMN `nudge_dismissed` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_links_reminder_at` ON `saved_links` (`reminder_at`)")
            }
        }

        /**
         * Non-destructive migration from version 3 to version 4:
         * Creates persistent notification_history table and its indices.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `notification_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `link_id` INTEGER,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `origin` TEXT NOT NULL,
                        `domain` TEXT NOT NULL,
                        `site_name` TEXT,
                        `preview_image_url` TEXT,
                        `favicon_url` TEXT,
                        `is_read` INTEGER NOT NULL DEFAULT 0,
                        `created_at` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_history_created_at` ON `notification_history` (`created_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_history_is_read` ON `notification_history` (`is_read`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_history_link_id` ON `notification_history` (`link_id`)")
            }
        }

        /**
         * Migration from version 4 to version 5:
         * 1. Creates downloaded_asset table with foreign key to saved_links(id) (ON DELETE SET NULL),
         *    and indices on savedLinkId, checksum, and localPath.
         * 2. Creates download_task table with foreign key to saved_links(id) (ON DELETE CASCADE),
         *    and indices on savedLinkId and status.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `downloaded_asset` (
                        `id` TEXT NOT NULL,
                        `savedLinkId` INTEGER,
                        `sourcePlatform` TEXT NOT NULL,
                        `originalSourceUrl` TEXT NOT NULL,
                        `title` TEXT,
                        `mimeType` TEXT NOT NULL,
                        `mediaType` TEXT NOT NULL,
                        `localPath` TEXT NOT NULL,
                        `exportedUri` TEXT,
                        `fileStatus` TEXT NOT NULL DEFAULT 'AVAILABLE',
                        `fileSize` INTEGER NOT NULL,
                        `durationMs` INTEGER,
                        `width` INTEGER,
                        `height` INTEGER,
                        `qualityLabel` TEXT,
                        `checksum` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`savedLinkId`) REFERENCES `saved_links`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloaded_asset_savedLinkId` ON `downloaded_asset` (`savedLinkId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloaded_asset_checksum` ON `downloaded_asset` (`checksum`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloaded_asset_localPath` ON `downloaded_asset` (`localPath`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `download_task` (
                        `id` TEXT NOT NULL,
                        `savedLinkId` INTEGER NOT NULL,
                        `sourceUrl` TEXT NOT NULL,
                        `sourcePlatform` TEXT NOT NULL,
                        `candidateId` TEXT,
                        `formatSelector` TEXT,
                        `mediaType` TEXT NOT NULL,
                        `qualityLabel` TEXT,
                        `title` TEXT,
                        `assetId` TEXT,
                        `status` TEXT NOT NULL,
                        `progress` INTEGER NOT NULL DEFAULT 0,
                        `downloadedBytes` INTEGER NOT NULL DEFAULT 0,
                        `totalBytes` INTEGER,
                        `allowCellularOverride` INTEGER NOT NULL DEFAULT 0,
                        `supportsRange` INTEGER NOT NULL DEFAULT 1,
                        `retryCount` INTEGER NOT NULL DEFAULT 0,
                        `errorCode` TEXT,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`savedLinkId`) REFERENCES `saved_links`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_task_savedLinkId` ON `download_task` (`savedLinkId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_task_status` ON `download_task` (`status`)")
            }
        }

        /**
         * Migration from version 5 to version 6:
         * Adds music metadata columns (artist, album, artworkUrl, spotifyTrackId, versionTag)
         * to downloaded_asset and download_task tables.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloaded_asset` ADD COLUMN `artist` TEXT")
                db.execSQL("ALTER TABLE `downloaded_asset` ADD COLUMN `album` TEXT")
                db.execSQL("ALTER TABLE `downloaded_asset` ADD COLUMN `artworkUrl` TEXT")
                db.execSQL("ALTER TABLE `downloaded_asset` ADD COLUMN `spotifyTrackId` TEXT")
                db.execSQL("ALTER TABLE `downloaded_asset` ADD COLUMN `versionTag` TEXT")

                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `artist` TEXT")
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `album` TEXT")
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `artworkUrl` TEXT")
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `spotifyTrackId` TEXT")
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `versionTag` TEXT")
            }
        }

        /**
         * Migration from version 6 to version 7:
         * 1. Adds stage and stageDetail columns to download_task table for transparent progress stages.
         * 2. Adds thumbnailPath column to downloaded_asset table for local thumbnail rendering.
         * 3. Creates media_inspection table with indexes and foreign keys for persistent inspection.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `stage` TEXT")
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `stageDetail` TEXT")
                db.execSQL("ALTER TABLE `downloaded_asset` ADD COLUMN `thumbnailPath` TEXT")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `media_inspection` (
                        `id` TEXT NOT NULL,
                        `savedLinkId` INTEGER NOT NULL,
                        `url` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `resultJson` TEXT,
                        `errorCode` TEXT,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`savedLinkId`) REFERENCES `saved_links`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_inspection_savedLinkId` ON `media_inspection` (`savedLinkId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_inspection_status` ON `media_inspection` (`status`)")
            }
        }

        /**
         * Migration from version 7 to version 8:
         * Adds headersJson column to download_task table to preserve request headers (e.g., Referer, User-Agent)
         * across worker restarts and app lifecycles.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `headersJson` TEXT")
            }
        }

        /**
         * Migration from version 8 to version 9:
         * 1. Enforces single inspection row per saved link with savedLinkId as PRIMARY KEY.
         * 2. Adds generation column to track attempt generations.
         * 3. Deduplicates existing media_inspection rows, retaining the newest entry per link by updatedAt.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create temporary new table with savedLinkId as primary key and generation column
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `media_inspection_new` (
                        `savedLinkId` INTEGER NOT NULL,
                        `id` TEXT NOT NULL,
                        `generation` INTEGER NOT NULL DEFAULT 0,
                        `url` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `resultJson` TEXT,
                        `errorCode` TEXT,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`savedLinkId`),
                        FOREIGN KEY(`savedLinkId`) REFERENCES `saved_links`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())

                // 2. Insert rows into media_inspection_new ordered by updatedAt ASC so newest replaces older duplicates
                db.execSQL("""
                    INSERT OR REPLACE INTO `media_inspection_new` (
                        `savedLinkId`, `id`, `generation`, `url`, `status`, `resultJson`, `errorCode`, `errorMessage`, `createdAt`, `updatedAt`
                    )
                    SELECT `savedLinkId`, `id`, 0, `url`, `status`, `resultJson`, `errorCode`, `errorMessage`, `createdAt`, `updatedAt`
                    FROM `media_inspection`
                    ORDER BY `updatedAt` ASC
                """.trimIndent())

                // 3. Drop old table and rename new table
                db.execSQL("DROP TABLE `media_inspection`")
                db.execSQL("ALTER TABLE `media_inspection_new` RENAME TO `media_inspection`")

                // 4. Create index on status
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_inspection_status` ON `media_inspection` (`status`)")
            }
        }

        /**
         * Non-destructive migration from version 9 to version 10:
         * Creates persistent chat_conversations, chat_messages, and activity_events tables and indices.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_conversations` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `last_message_preview` TEXT,
                        `is_pinned` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_conversations_updated_at` ON `chat_conversations` (`updated_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `id` TEXT NOT NULL,
                        `conversation_id` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `actions_json` TEXT,
                        `status` TEXT NOT NULL,
                        `error_message` TEXT,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`conversation_id`) REFERENCES `chat_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_conversation_id` ON `chat_messages` (`conversation_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_created_at` ON `chat_messages` (`created_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `activity_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `event_type` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `detail` TEXT,
                        `link_id` INTEGER,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_events_created_at` ON `activity_events` (`created_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_events_event_type` ON `activity_events` (`event_type`)")
            }
        }

        /**
         * Non-destructive migration from version 10 to version 11:
         * Adds per-conversation assistant action permission grants (cascade-deleted with the conversation).
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_action_permissions` (
                        `conversation_id` TEXT NOT NULL,
                        `action_type` TEXT NOT NULL,
                        `granted_at` INTEGER NOT NULL,
                        PRIMARY KEY(`conversation_id`, `action_type`),
                        FOREIGN KEY(`conversation_id`) REFERENCES `chat_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_action_permissions_conversation_id` ON `chat_action_permissions` (`conversation_id`)")
            }
        }

        /**
         * Non-destructive migration from version 11 to version 12:
         * Adds dedicated Movies & TV tables (metadata cache, watchlist, watch progress history, search history)
         * completely isolated from saved_links.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `movie_cache_entries` (
                        `cache_key` TEXT NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `payload_json` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `expires_at` INTEGER NOT NULL,
                        PRIMARY KEY(`cache_key`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_cache_entries_provider_id` ON `movie_cache_entries` (`provider_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_cache_entries_expires_at` ON `movie_cache_entries` (`expires_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `movie_watchlist_items` (
                        `composite_id` TEXT NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `title_id` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `poster_url` TEXT,
                        `backdrop_url` TEXT,
                        `release_year` INTEGER,
                        `rating` REAL,
                        `genres_csv` TEXT NOT NULL,
                        `summary` TEXT,
                        `source_badge` TEXT NOT NULL,
                        `is_playable_hint` INTEGER NOT NULL,
                        `added_at` INTEGER NOT NULL,
                        PRIMARY KEY(`composite_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_watchlist_items_added_at` ON `movie_watchlist_items` (`added_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_watchlist_items_kind` ON `movie_watchlist_items` (`kind`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `movie_watch_history` (
                        `history_key` TEXT NOT NULL,
                        `composite_id` TEXT NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `title_id` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `episode_label` TEXT,
                        `season_number` INTEGER,
                        `episode_number` INTEGER,
                        `poster_url` TEXT,
                        `position_ms` INTEGER NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        `last_watched_at` INTEGER NOT NULL,
                        PRIMARY KEY(`history_key`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_watch_history_composite_id` ON `movie_watch_history` (`composite_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_watch_history_last_watched_at` ON `movie_watch_history` (`last_watched_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `movie_search_history` (
                        `query` TEXT NOT NULL,
                        `searched_at` INTEGER NOT NULL,
                        PRIMARY KEY(`query`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_search_history_searched_at` ON `movie_search_history` (`searched_at`)")
            }
        }

        /**
         * Non-destructive migration from version 12 to version 13:
         * Adds canonical_media and media_provider_links tables plus nullable canonical_id
         * columns on movie_watchlist_items and movie_watch_history for lazy backfill.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `canonical_media` (
                        `canonical_id` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `norm_title` TEXT NOT NULL,
                        `release_year` INTEGER,
                        `imdb_id` TEXT,
                        `tvmaze_id` TEXT,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`canonical_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_canonical_media_norm_title` ON `canonical_media` (`norm_title`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_canonical_media_imdb_id` ON `canonical_media` (`imdb_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_canonical_media_tvmaze_id` ON `canonical_media` (`tvmaze_id`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_provider_links` (
                        `canonical_id` TEXT NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `provider_title_id` TEXT NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`provider_id`, `provider_title_id`),
                        FOREIGN KEY(`canonical_id`) REFERENCES `canonical_media`(`canonical_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_provider_links_canonical_id` ON `media_provider_links` (`canonical_id`)")

                db.execSQL("ALTER TABLE `movie_watchlist_items` ADD COLUMN `canonical_id` TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_watchlist_items_canonical_id` ON `movie_watchlist_items` (`canonical_id`)")

                db.execSQL("ALTER TABLE `movie_watch_history` ADD COLUMN `canonical_id` TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_watch_history_canonical_id` ON `movie_watch_history` (`canonical_id`)")
            }
        }

        /**
         * Non-destructive migration from version 13 to version 14:
         * 1. Recreates download_task with nullable savedLinkId (allowing standalone Movies/TV downloads
         *    without polluting saved_links) and adds contentCategory, speedBps, etaSeconds, etag,
         *    urlExpiresAt, and sourceRef columns.
         * 2. Adds contentCategory column to downloaded_asset and backfills existing MovieBox/IA items.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_task_new` (
                        `id` TEXT NOT NULL,
                        `savedLinkId` INTEGER,
                        `sourceUrl` TEXT NOT NULL,
                        `sourcePlatform` TEXT NOT NULL,
                        `candidateId` TEXT,
                        `formatSelector` TEXT,
                        `mediaType` TEXT NOT NULL,
                        `qualityLabel` TEXT,
                        `title` TEXT,
                        `assetId` TEXT,
                        `status` TEXT NOT NULL,
                        `progress` INTEGER NOT NULL,
                        `downloadedBytes` INTEGER NOT NULL,
                        `totalBytes` INTEGER,
                        `allowCellularOverride` INTEGER NOT NULL,
                        `supportsRange` INTEGER NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `errorCode` TEXT,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `artist` TEXT,
                        `album` TEXT,
                        `artworkUrl` TEXT,
                        `spotifyTrackId` TEXT,
                        `versionTag` TEXT,
                        `stage` TEXT,
                        `stageDetail` TEXT,
                        `headersJson` TEXT,
                        `contentCategory` TEXT,
                        `speedBps` INTEGER,
                        `etaSeconds` INTEGER,
                        `etag` TEXT,
                        `urlExpiresAt` INTEGER,
                        `sourceRef` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`savedLinkId`) REFERENCES `saved_links`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `download_task_new` (
                        `id`, `savedLinkId`, `sourceUrl`, `sourcePlatform`, `candidateId`, `formatSelector`,
                        `mediaType`, `qualityLabel`, `title`, `assetId`, `status`, `progress`,
                        `downloadedBytes`, `totalBytes`, `allowCellularOverride`, `supportsRange`,
                        `retryCount`, `errorCode`, `errorMessage`, `createdAt`, `updatedAt`,
                        `artist`, `album`, `artworkUrl`, `spotifyTrackId`, `versionTag`,
                        `stage`, `stageDetail`, `headersJson`,
                        `contentCategory`, `speedBps`, `etaSeconds`, `etag`, `urlExpiresAt`, `sourceRef`
                    )
                    SELECT
                        `id`, `savedLinkId`, `sourceUrl`, `sourcePlatform`, `candidateId`, `formatSelector`,
                        `mediaType`, `qualityLabel`, `title`, `assetId`, `status`, `progress`,
                        `downloadedBytes`, `totalBytes`, `allowCellularOverride`, `supportsRange`,
                        `retryCount`, `errorCode`, `errorMessage`, `createdAt`, `updatedAt`,
                        `artist`, `album`, `artworkUrl`, `spotifyTrackId`, `versionTag`,
                        `stage`, `stageDetail`, `headersJson`,
                        CASE
                            WHEN `sourcePlatform` LIKE '%MovieBox%' OR `sourcePlatform` LIKE '%Internet Archive%' THEN 'MOVIE'
                            ELSE NULL
                        END,
                        NULL, NULL, NULL, NULL, NULL
                    FROM `download_task`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `download_task`")
                db.execSQL("ALTER TABLE `download_task_new` RENAME TO `download_task`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_task_savedLinkId` ON `download_task` (`savedLinkId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_task_status` ON `download_task` (`status`)")

                db.execSQL("ALTER TABLE `downloaded_asset` ADD COLUMN `contentCategory` TEXT DEFAULT NULL")
                db.execSQL(
                    """
                    UPDATE `downloaded_asset`
                    SET `contentCategory` = 'MOVIE'
                    WHERE `contentCategory` IS NULL
                      AND (`sourcePlatform` LIKE '%MovieBox%' OR `sourcePlatform` LIKE '%Internet Archive%')
                    """.trimIndent()
                )
            }
        }

        /**
         * Non-destructive migration from version 14 to version 15:
         * Adds backdrop_url and is_trailer columns (plus index on is_trailer) to movie_watch_history
         * so Continue Watching renders crisp backdrop artwork and isolates trailer views from full-title progress.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `movie_watch_history` ADD COLUMN `backdrop_url` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `movie_watch_history` ADD COLUMN `is_trailer` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_watch_history_is_trailer` ON `movie_watch_history` (`is_trailer`)")
            }
        }

        /**
         * Non-destructive migration from version 15 to version 16:
         * 1. Extends movie_watch_history with completion, rewatch tracking, Continue Watching hide, and title hide flags.
         * 2. Adds sourceRef column + index to downloaded_asset for exact offline Movies/Episodes matching.
         * 3. Creates movie_recently_viewed, movie_series_preferences, media_collection_cross_ref, and movie_viewing_reminders.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloaded_asset` ADD COLUMN `sourceRef` TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloaded_asset_sourceRef` ON `downloaded_asset` (`sourceRef`)")

                db.execSQL("ALTER TABLE `movie_watch_history` ADD COLUMN `is_completed` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `movie_watch_history` ADD COLUMN `completed_at` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `movie_watch_history` ADD COLUMN `rewatch_count` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `movie_watch_history` ADD COLUMN `hidden_from_continue_watching` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `movie_watch_history` ADD COLUMN `is_hidden_title` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_watch_history_is_completed` ON `movie_watch_history` (`is_completed`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_watch_history_is_hidden_title` ON `movie_watch_history` (`is_hidden_title`)")
                db.execSQL(
                    """
                    UPDATE `movie_watch_history`
                    SET `is_completed` = 1, `completed_at` = `last_watched_at`
                    WHERE `is_trailer` = 0 AND `duration_ms` > 0 AND (`position_ms` * 1.0 / `duration_ms`) >= 0.96
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `movie_recently_viewed` (
                        `media_key` TEXT NOT NULL,
                        `composite_id` TEXT NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `title_id` TEXT NOT NULL,
                        `canonical_id` TEXT,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `poster_url` TEXT,
                        `backdrop_url` TEXT,
                        `release_year` INTEGER,
                        `rating` REAL,
                        `genres_csv` TEXT NOT NULL,
                        `source_badge` TEXT NOT NULL,
                        `is_playable_hint` INTEGER NOT NULL DEFAULT 0,
                        `viewed_at` INTEGER NOT NULL,
                        PRIMARY KEY(`media_key`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_recently_viewed_viewed_at` ON `movie_recently_viewed` (`viewed_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_recently_viewed_canonical_id` ON `movie_recently_viewed` (`canonical_id`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `movie_series_preferences` (
                        `media_key` TEXT NOT NULL,
                        `preferred_audio_language` TEXT,
                        `preferred_audio_label` TEXT,
                        `preferred_subtitle_language` TEXT,
                        `preferred_subtitle_label` TEXT,
                        `subtitles_disabled` INTEGER NOT NULL DEFAULT 0,
                        `reminders_muted` INTEGER NOT NULL DEFAULT 0,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`media_key`)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_collection_cross_ref` (
                        `media_key` TEXT NOT NULL,
                        `collection_id` INTEGER NOT NULL,
                        `composite_id` TEXT NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `title_id` TEXT NOT NULL,
                        `canonical_id` TEXT,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `poster_url` TEXT,
                        `backdrop_url` TEXT,
                        `release_year` INTEGER,
                        `rating` REAL,
                        `genres_csv` TEXT NOT NULL,
                        `source_badge` TEXT NOT NULL,
                        `is_playable_hint` INTEGER NOT NULL DEFAULT 0,
                        `added_at` INTEGER NOT NULL,
                        PRIMARY KEY(`media_key`, `collection_id`),
                        FOREIGN KEY(`collection_id`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_collection_cross_ref_collection_id` ON `media_collection_cross_ref` (`collection_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_collection_cross_ref_media_key` ON `media_collection_cross_ref` (`media_key`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_collection_cross_ref_added_at` ON `media_collection_cross_ref` (`added_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `movie_viewing_reminders` (
                        `media_key` TEXT NOT NULL,
                        `composite_id` TEXT NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `title_id` TEXT NOT NULL,
                        `canonical_id` TEXT,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `season_number` INTEGER,
                        `episode_number` INTEGER,
                        `poster_url` TEXT,
                        `note` TEXT,
                        `trigger_at_millis` INTEGER NOT NULL,
                        `is_dismissed` INTEGER NOT NULL DEFAULT 0,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`media_key`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_viewing_reminders_trigger_at_millis` ON `movie_viewing_reminders` (`trigger_at_millis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_viewing_reminders_is_dismissed` ON `movie_viewing_reminders` (`is_dismissed`)")
            }
        }

        @Volatile
        private var INSTANCE: LaterboxDatabase? = null

        fun getInstance(context: Context): LaterboxDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LaterboxDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
