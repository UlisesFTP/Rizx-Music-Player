package fm.rizx.player.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's Room database: favorites, playlists, and recently-played (§7.3). Cached metadata and an
 * optional persisted queue are later additions. Settings/active-providers live in DataStore, not here.
 */
@Database(
    entities = [FavoriteEntity::class, PlaylistEntity::class, PlaylistItemEntity::class, RecentlyPlayedEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class RizxDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun recentlyPlayedDao(): RecentlyPlayedDao
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
