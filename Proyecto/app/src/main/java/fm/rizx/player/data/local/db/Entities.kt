package fm.rizx.player.data.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A favorited track/album/artist. Identity/dedup is `(type, provider, sourceId)` — the entity's
 * `ProviderRef` split into columns (NUCLEAR_UPSTREAM_STUDY.md §7.1); [json] holds the serialized
 * domain entity. Re-adding the same key is an idempotent no-op (DAO inserts with `IGNORE`), so the
 * original [addedAtIso] is preserved.
 */
@Entity(tableName = "favorites", primaryKeys = ["type", "provider", "sourceId"])
data class FavoriteEntity(
    val type: String, // "TRACK" | "ALBUM" | "ARTIST"
    val provider: String,
    val sourceId: String,
    val json: String,
    val addedAtIso: String,
)

/** A user (or imported) playlist. Items live in [PlaylistItemEntity], deleted by cascade. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val createdAtIso: String,
    val lastModifiedIso: String,
    val isReadOnly: Boolean,
    val parentId: String?,
    val originProvider: String?,
    val originId: String?,
    /**
     * The playlist's cover (v3). Stored as a single URL rather than a serialized [ArtworkSet]: a cover is
     * displayed at one size, and a plain column keeps the list projection cheap. Remote and re-fetchable,
     * so it is a cache, never durable truth.
     */
    val artworkUrl: String? = null,
)

/**
 * One entry in a playlist. [id] is the entry's own identity (distinct from track identity), so the
 * same track may appear multiple times. [trackJson] is the serialized (resolution-stripped) track;
 * [sortOrder] drives ordering.
 */
@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistItemEntity(
    @PrimaryKey val id: String,
    val playlistId: String,
    val sortOrder: Int,
    val trackJson: String,
    val note: String?,
    val addedAtIso: String,
)

/**
 * A recently-played track (Phase 15). Dedup/identity is the track's `ProviderRef` split into
 * `(provider, sourceId)`, so replaying a track updates its [playedAtIso] in place (DAO upserts with
 * `REPLACE` — safe here: no child tables). [trackJson] is the serialized, resolution-stripped track.
 */
@Entity(tableName = "recently_played", primaryKeys = ["provider", "sourceId"])
data class RecentlyPlayedEntity(
    val provider: String,
    val sourceId: String,
    val trackJson: String,
    val playedAtIso: String,
)

/** Lightweight list projection (§7.2 two-tier): playlist meta + item count, no items loaded. */
data class PlaylistSummaryRow(
    val id: String,
    val name: String,
    val description: String?,
    val isReadOnly: Boolean,
    val itemCount: Int,
    val artworkUrl: String? = null,
)
