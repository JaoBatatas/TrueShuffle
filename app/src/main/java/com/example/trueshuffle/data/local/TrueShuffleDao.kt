package com.example.trueshuffle.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class TrueShuffleDao(context: Context) : SQLiteOpenHelper(context, "true_shuffle.db", null, 1) {

    private val stateFlow = MutableStateFlow<CycleStateEntity?>(null)

    init {
        stateFlow.value = readCycleStateSync()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE shuffled_tracks (
                positionIndex INTEGER PRIMARY KEY,
                trackUri TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE cycle_state (
                id INTEGER PRIMARY KEY,
                playlistId TEXT NOT NULL,
                playlistUri TEXT NOT NULL,
                currentIndex INTEGER NOT NULL,
                totalTracks INTEGER NOT NULL,
                isPlaying INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS shuffled_tracks")
        db.execSQL("DROP TABLE IF EXISTS cycle_state")
        onCreate(db)
    }

    fun observeCycleState(): Flow<CycleStateEntity?> = stateFlow.asStateFlow()

    suspend fun getCycleState(): CycleStateEntity? = withContext(Dispatchers.IO) {
        readCycleStateSync()
    }

    fun readCycleStateSync(): CycleStateEntity? {
        readableDatabase.rawQuery("SELECT * FROM cycle_state WHERE id = 1", null).use { cursor ->
            if (cursor.moveToFirst()) {
                return CycleStateEntity(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    playlistId = cursor.getString(cursor.getColumnIndexOrThrow("playlistId")),
                    playlistUri = cursor.getString(cursor.getColumnIndexOrThrow("playlistUri")),
                    currentIndex = cursor.getInt(cursor.getColumnIndexOrThrow("currentIndex")),
                    totalTracks = cursor.getInt(cursor.getColumnIndexOrThrow("totalTracks")),
                    isPlaying = cursor.getInt(cursor.getColumnIndexOrThrow("isPlaying")) == 1
                )
            }
        }
        return null
    }

    suspend fun saveCycleState(state: CycleStateEntity) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", 1)
            put("playlistId", state.playlistId)
            put("playlistUri", state.playlistUri)
            put("currentIndex", state.currentIndex)
            put("totalTracks", state.totalTracks)
            put("isPlaying", if (state.isPlaying) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("cycle_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        stateFlow.value = state
    }

    suspend fun updatePointer(newIndex: Int, isPlaying: Boolean) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("currentIndex", newIndex)
            put("isPlaying", if (isPlaying) 1 else 0)
        }
        writableDatabase.update("cycle_state", values, "id = 1", null)
        stateFlow.value = readCycleStateSync()
    }

    suspend fun updatePlayingStateOnly(isPlaying: Boolean) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("isPlaying", if (isPlaying) 1 else 0)
        }
        writableDatabase.update("cycle_state", values, "id = 1", null)
        stateFlow.value = readCycleStateSync()
    }

    suspend fun getTrackAtIndex(index: Int): ShuffledTrackEntity? = withContext(Dispatchers.IO) {
        getTrackAtIndexSync(index)
    }

    fun getTrackAtIndexSync(index: Int): ShuffledTrackEntity? {
        readableDatabase.rawQuery(
            "SELECT * FROM shuffled_tracks WHERE positionIndex = ? LIMIT 1",
            arrayOf(index.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return ShuffledTrackEntity(
                    positionIndex = cursor.getInt(cursor.getColumnIndexOrThrow("positionIndex")),
                    trackUri = cursor.getString(cursor.getColumnIndexOrThrow("trackUri")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    artist = cursor.getString(cursor.getColumnIndexOrThrow("artist"))
                )
            }
        }
        return null
    }

    suspend fun getTrackByUri(uri: String, currentIndex: Int = 0): ShuffledTrackEntity? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT * FROM shuffled_tracks WHERE trackUri = ? ORDER BY ABS(positionIndex - ?) LIMIT 1",
            arrayOf(uri, currentIndex.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                ShuffledTrackEntity(
                    positionIndex = cursor.getInt(cursor.getColumnIndexOrThrow("positionIndex")),
                    trackUri = cursor.getString(cursor.getColumnIndexOrThrow("trackUri")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    artist = cursor.getString(cursor.getColumnIndexOrThrow("artist"))
                )
            } else null
        }
    }

    suspend fun getTrackByTitle(title: String, artist: String? = null, currentIndex: Int = 0): ShuffledTrackEntity? = withContext(Dispatchers.IO) {
        val sql = if (!artist.isNullOrEmpty()) {
            "SELECT * FROM shuffled_tracks WHERE LOWER(title) = LOWER(?) AND LOWER(artist) LIKE '%' || LOWER(?) || '%' ORDER BY ABS(positionIndex - ?) LIMIT 1"
        } else {
            "SELECT * FROM shuffled_tracks WHERE LOWER(title) = LOWER(?) ORDER BY ABS(positionIndex - ?) LIMIT 1"
        }
        val args = if (!artist.isNullOrEmpty()) {
            arrayOf(title, artist, currentIndex.toString())
        } else {
            arrayOf(title, currentIndex.toString())
        }
        readableDatabase.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) {
                ShuffledTrackEntity(
                    positionIndex = cursor.getInt(cursor.getColumnIndexOrThrow("positionIndex")),
                    trackUri = cursor.getString(cursor.getColumnIndexOrThrow("trackUri")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    artist = cursor.getString(cursor.getColumnIndexOrThrow("artist"))
                )
            } else null
        }
    }

    suspend fun getTrackByTitleAndArtist(title: String, artist: String, currentIndex: Int = 0): ShuffledTrackEntity? =
        withContext(Dispatchers.IO) {
            readableDatabase.rawQuery(
                "SELECT * FROM shuffled_tracks WHERE LOWER(title) = LOWER(?) AND LOWER(artist) LIKE '%' || LOWER(?) || '%' ORDER BY ABS(positionIndex - ?) LIMIT 1",
                arrayOf(title.trim(), artist.trim(), currentIndex.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    ShuffledTrackEntity(
                        positionIndex = cursor.getInt(cursor.getColumnIndexOrThrow("positionIndex")),
                        trackUri = cursor.getString(cursor.getColumnIndexOrThrow("trackUri")),
                        title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                        artist = cursor.getString(cursor.getColumnIndexOrThrow("artist"))
                    )
                } else null
            }
        }

    suspend fun replaceFullCycle(state: CycleStateEntity, tracks: List<ShuffledTrackEntity>) =
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("shuffled_tracks", null, null)
                for (track in tracks) {
                    val trackValues = ContentValues().apply {
                        put("positionIndex", track.positionIndex)
                        put("trackUri", track.trackUri)
                        put("title", track.title)
                        put("artist", track.artist)
                    }
                    db.insertWithOnConflict("shuffled_tracks", null, trackValues, SQLiteDatabase.CONFLICT_REPLACE)
                }

                val stateValues = ContentValues().apply {
                    put("id", 1)
                    put("playlistId", state.playlistId)
                    put("playlistUri", state.playlistUri)
                    put("currentIndex", state.currentIndex)
                    put("totalTracks", state.totalTracks)
                    put("isPlaying", if (state.isPlaying) 1 else 0)
                }
                db.insertWithOnConflict("cycle_state", null, stateValues, SQLiteDatabase.CONFLICT_REPLACE)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            stateFlow.value = state
        }
}