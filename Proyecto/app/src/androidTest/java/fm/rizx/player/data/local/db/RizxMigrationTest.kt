package fm.rizx.player.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves that upgrading an existing install does not cost anyone their library.
 *
 * A migration is the one piece of code whose bugs are unrecoverable: by the time a user notices their
 * playlists are gone, the old database is already overwritten. Until schemas were exported there was
 * no way to test one at all — the history starts at 4, so 4 → 5 is the first migration this project
 * can hold to that standard, and every one from here on should be added below.
 *
 * Instrumented because it opens a real SQLite database. Run with `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class RizxMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RizxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate4To5_keepsFavoritesPlaylistsAndListeningHistory() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO favorites (type, provider, sourceId, json, addedAtIso) " +
                    "VALUES ('TRACK', 'deezer', '67238735', '{\"title\":\"Get Lucky\"}', '2026-01-01T00:00:00Z')",
            )
            db.execSQL(
                "INSERT INTO playlists (id, name, description, createdAtIso, lastModifiedIso, isReadOnly, " +
                    "parentId, originProvider, originId, artworkUrl) " +
                    "VALUES ('p1', 'Road trip', NULL, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', 0, " +
                    "NULL, NULL, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO playlist_items (id, playlistId, sortOrder, trackJson, note, addedAtIso) " +
                    "VALUES ('i1', 'p1', 0, '{\"title\":\"Get Lucky\"}', NULL, '2026-01-01T00:00:00Z')",
            )
            db.execSQL(
                "INSERT INTO recently_played (provider, sourceId, trackJson, playedAtIso, playCount, " +
                    "completedCount, skipCount, msListened, firstPlayedAtIso, partNight, partMorning, " +
                    "partAfternoon, partEvening) " +
                    "VALUES ('deezer', '67238735', '{\"title\":\"Get Lucky\"}', '2026-01-02T00:00:00Z', " +
                    "7, 5, 1, 900000, '2026-01-01T00:00:00Z', 2, 1, 3, 1)",
            )
        }

        // `validateDroppedTables = true`: a migration that quietly leaves a stale table behind is a
        // migration that will surprise the *next* one.
        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        db.query("SELECT sourceId FROM favorites").use {
            assertTrue("the favorite is gone", it.moveToFirst())
            assertEquals("67238735", it.getString(0))
        }
        db.query("SELECT name FROM playlists").use {
            assertTrue("the playlist is gone", it.moveToFirst())
            assertEquals("Road trip", it.getString(0))
        }
        db.query("SELECT playlistId FROM playlist_items").use {
            assertTrue("the playlist lost its tracks", it.moveToFirst())
            assertEquals("p1", it.getString(0))
        }
        // The listening log is what the recommendations are built from; losing the counters would
        // silently reset someone's taste profile rather than visibly break anything.
        db.query("SELECT playCount, skipCount, msListened, firstPlayedAtIso FROM recently_played").use {
            assertTrue("the listening log is gone", it.moveToFirst())
            assertEquals(7, it.getInt(0))
            assertEquals(1, it.getInt(1))
            assertEquals(900_000L, it.getLong(2))
            assertEquals("2026-01-01T00:00:00Z", it.getString(3))
        }
        db.close()
    }

    @Test
    fun migrate4To5_addsAnEmptyRecognitionHistory() {
        helper.createDatabase(TEST_DB, 4).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        db.query("SELECT COUNT(*) FROM recognition_history").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.execSQL(
            "INSERT INTO recognition_history (id, provider, providerTrackId, title, artist, recognizedAtIso) " +
                "VALUES ('e1', 'shazam', '105842472', 'Get Lucky', 'Daft Punk', '2026-08-06T12:00:00Z')",
        )
        db.query("SELECT title FROM recognition_history").use {
            assertTrue(it.moveToFirst())
            assertEquals("Get Lucky", it.getString(0))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "rizx-migration-test.db"
    }
}
