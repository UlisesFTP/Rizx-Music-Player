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
 * A recently-played track (Phase 15), and — since v4 — **how** it has been played.
 *
 * Dedup/identity is the track's `ProviderRef` split into `(provider, sourceId)`, so replaying a track
 * updates this row in place rather than adding another. [trackJson] is the serialized,
 * resolution-stripped track.
 *
 * The counters are what turns a history into a taste: without them a song played thirty times and a
 * song played once are the same row with the same timestamp. They are summed in Kotlin and written
 * back through the DAO's `REPLACE` upsert (see `RecentlyPlayedRepositoryImpl`), never by SQL
 * arithmetic — the rules for what counts as a play belong where they can be unit-tested.
 */
@Entity(tableName = "recently_played", primaryKeys = ["provider", "sourceId"])
data class RecentlyPlayedEntity(
    val provider: String,
    val sourceId: String,
    val trackJson: String,
    /** When it was last played. The list projection and the recency weighting both read this. */
    val playedAtIso: String,
    /** How many times playback of this track has *started* (v4) — deduped per queue item, not per resume. */
    val playCount: Int = 1,
    /** Times it ran to the end (or was left near it). */
    val completedCount: Int = 0,
    /** Times the listener took it off early — the signal that keeps a disliked song out of the mixes. */
    val skipCount: Int = 0,
    val msListened: Long = 0,
    /** The very first play, kept so "you've had this since March" stays true however often it is replayed. */
    val firstPlayedAtIso: String = "",
    /** Plays per part of the day (night / morning / afternoon / evening) — the time-of-day context. */
    val partNight: Int = 0,
    val partMorning: Int = 0,
    val partAfternoon: Int = 0,
    val partEvening: Int = 0,
)

/** Lightweight list projection (§7.2 two-tier): playlist meta + item count, no items loaded. */
data class PlaylistSummaryRow(
    val id: String,
    val name: String,
    val description: String?,
    val isReadOnly: Boolean,
    val itemCount: Int,
    val artworkUrl: String? = null,
    /** Non-null when the playlist arrived via an import — what "your own playlists" filters out. */
    val originProvider: String? = null,
)
