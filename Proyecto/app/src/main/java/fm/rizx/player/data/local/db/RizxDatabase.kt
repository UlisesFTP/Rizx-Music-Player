package fm.rizx.player.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's Room database: favorites, playlists, and recently-played (§7.3). Cached metadata and an
 * optional persisted queue are later additions. Settings/active-providers live in DataStore, not here.
 *
 * Schema JSONs are exported to `app/schemas/` (committed — versions 1–3 predate the export, so the
 * history starts at 4). Every version bump must ship a `Migration` and its exported schema together.
 */
@Database(
    entities = [
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        RecentlyPlayedEntity::class,
        RecognitionHistoryEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class RizxDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun recentlyPlayedDao(): RecentlyPlayedDao
    abstract fun recognitionHistoryDao(): RecognitionHistoryDao
}

/** v1 → v2: adds the `recently_played` table (Phase 15). Preserves favorites/playlists. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recently_played` " +
                "(`provider` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `trackJson` TEXT NOT NULL, " +
                "`playedAtIso` TEXT NOT NULL, PRIMARY KEY(`provider`, `sourceId`))",
        )
    }
}

/**
 * v2 → v3: adds `playlists.artworkUrl` so an imported playlist keeps its cover. Nullable with no default,
 * so existing rows simply read back null and get backfilled on next open — nothing is rewritten here.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `playlists` ADD COLUMN `artworkUrl` TEXT")
    }
}

/**
 * v3 → v4: `recently_played` becomes a real listening log — play/completion/skip counts, listened time,
 * the first play, and plays per part of the day.
 *
 * Every column is `NOT NULL DEFAULT`, which is what `ALTER TABLE ADD COLUMN` requires and what lets the
 * existing rows survive untouched. Two deliberate choices about that existing history:
 *
 * - `playCount` defaults to **1**, not 0: every row in the table is there *because* it was played.
 * - `firstPlayedAtIso` is backfilled from `playedAtIso`, so an upgraded install has real dates from the
 *   first launch instead of a blank field that would make everything look brand new.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recently_played` ADD COLUMN `playCount` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `recently_played` ADD COLUMN `completedCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `recently_played` ADD COLUMN `skipCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `recently_played` ADD COLUMN `msListened` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `recently_played` ADD COLUMN `firstPlayedAtIso` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `recently_played` ADD COLUMN `partNight` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `recently_played` ADD COLUMN `partMorning` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `recently_played` ADD COLUMN `partAfternoon` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `recently_played` ADD COLUMN `partEvening` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `recently_played` SET `firstPlayedAtIso` = `playedAtIso`")
    }
}

/**
 * v4 → v5: adds `recognition_history` for songs identified from the microphone.
 *
 * Purely additive — a new table and its indices, nothing touched — so favorites, playlists and the
 * listening log come through byte for byte. That is asserted rather than assumed: `RizxMigrationTest`
 * writes rows into a v4 database, migrates, and reads them back.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recognition_history` (" +
                "`id` TEXT NOT NULL, `provider` TEXT NOT NULL, `providerTrackId` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, `isrc` TEXT, " +
                "`artworkUrl` TEXT, `genre` TEXT, `releaseDate` TEXT, `label` TEXT, " +
                "`externalUrl` TEXT, `appleTrackId` TEXT, `resolvedProvider` TEXT, " +
                "`resolvedSourceId` TEXT, `resolvedTrackJson` TEXT, `recognizedAtIso` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recognition_history_recognizedAtIso` " +
                "ON `recognition_history` (`recognizedAtIso`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recognition_history_provider_providerTrackId` " +
                "ON `recognition_history` (`provider`, `providerTrackId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recognition_history_resolvedProvider_resolvedSourceId` " +
                "ON `recognition_history` (`resolvedProvider`, `resolvedSourceId`)",
        )
    }
}
