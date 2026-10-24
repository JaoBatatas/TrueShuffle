package com.example.trueshuffle.domain

import com.example.trueshuffle.data.local.*
import com.example.trueshuffle.data.remote.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

class CycleGeneratorUseCase(
    private val api: SpotifyWebApi,
    private val dao: TrueShuffleDao
) {
    suspend fun generateNewShuffleCycle(
        accessToken: String,
        sourcePlaylistId: String? = null,
        sourcePlaylistName: String? = null,
        sourceType: String = "liked"
    ) = withContext(Dispatchers.IO) {
        val bearer = "Bearer $accessToken"
        val allTracks = mutableListOf<TrackDto>()
        var offset = 0

        // 1. Paginar músicas da origem (Músicas Curtidas, Playlist ou Álbum)
        when (sourceType) {
            "album" -> {
                if (!sourcePlaylistId.isNullOrEmpty()) {
                    while (true) {
                        val response = api.getAlbumTracks(bearer, sourcePlaylistId, offset)
                        val tracks = response.items.filter { it.uri.isNotEmpty() }
                        allTracks.addAll(tracks)
                        if (response.next == null || response.items.isEmpty()) break
                        offset += 50
                    }
                }
            }
            "playlist" -> {
                if (!sourcePlaylistId.isNullOrEmpty()) {
                    while (true) {
                        val response = api.getPlaylistTracks(bearer, sourcePlaylistId, offset)
                        val tracks = response.items.mapNotNull { it.track }.filter { it.uri.isNotEmpty() }
                        allTracks.addAll(tracks)
                        if (response.next == null || response.items.isEmpty()) break
                        offset += 50
                    }
                }
            }
            else -> { // "liked"
                while (true) {
                    val response = api.getSavedTracks(bearer, offset)
                    allTracks.addAll(response.items.map { it.track })
                    if (response.next == null || response.items.isEmpty()) break
                    offset += 50
                }
            }
        }

        if (allTracks.isEmpty()) return@withContext

        // 2. Embaralhamento Fisher-Yates com SecureRandom
        val secureRandom = SecureRandom()
        for (i in allTracks.size - 1 downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val temp = allTracks[i]
            allTracks[i] = allTracks[j]
            allTracks[j] = temp
        }

        val targetName = if (!sourcePlaylistName.isNullOrEmpty() && sourcePlaylistId != "liked") {
            "$sourcePlaylistName [True Shuffle]"
        } else {
            "Curtidas [True Shuffle]"
        }

        // 3. Reutilizar playlist existente (do banco local ou buscando na conta do Spotify)
        val existingState = dao.getCycleState()
        val playlistId: String
        val playlistUri: String

        if (existingState != null && existingState.playlistId.isNotEmpty()) {
            playlistId = existingState.playlistId
            playlistUri = existingState.playlistUri
        } else {
            val remoteExisting = api.getUserPlaylists(bearer).items.firstOrNull {
                it.name.equals(targetName, ignoreCase = true)
            }
            if (remoteExisting != null) {
                playlistId = remoteExisting.id
                playlistUri = remoteExisting.uri
            } else {
                val user = api.getCurrentUser(bearer)
                val created = api.createPlaylist(bearer, user.id, CreatePlaylistRequest(name = targetName))
                playlistId = created.id
                playlistUri = created.uri
            }
        }

        // 4. Sobrescrever as faixas da playlist existente em lotes de 100
        val trackUris = allTracks.map { it.uri }
        val batches = trackUris.chunked(100)

        batches.forEachIndexed { batchIndex, batchUris ->
            if (batchIndex == 0) {
                // O PUT limpa a ordem antiga e grava as primeiras 100 músicas da nova sequência
                api.replacePlaylistTracks(bearer, playlistId, AddTracksRequest(batchUris))
            } else {
                // O POST anexa os lotes restantes na mesma playlist
                api.appendPlaylistTracks(bearer, playlistId, AddTracksRequest(batchUris))
            }
        }

        // 5. Atualizar o banco local e reiniciar o ponteiro em 0
        val entities = allTracks.mapIndexed { index, dto ->
            ShuffledTrackEntity(
                positionIndex = index,
                trackUri = dto.uri,
                title = dto.name,
                artist = dto.artists.joinToString(", ") { it.name }
            )
        }

        val newState = CycleStateEntity(
            playlistId = playlistId,
            playlistUri = playlistUri,
            currentIndex = 0,
            totalTracks = entities.size,
            isPlaying = false
        )

        dao.replaceFullCycle(newState, entities)
    }
}