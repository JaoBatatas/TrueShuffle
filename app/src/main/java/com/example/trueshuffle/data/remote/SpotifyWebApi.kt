package com.example.trueshuffle.data.remote

import retrofit2.http.*

interface SpotifyWebApi {
    @GET("v1/me")
    suspend fun getCurrentUser(@Header("Authorization") auth: String): UserResponse

    @GET("v1/me/playlists?limit=50")
    suspend fun getUserPlaylists(
        @Header("Authorization") auth: String,
        @Query("offset") offset: Int = 0
    ): UserPlaylistsResponse

    @GET("v1/me/tracks?limit=50")
    suspend fun getSavedTracks(
        @Header("Authorization") auth: String,
        @Query("offset") offset: Int
    ): SavedTracksResponse

    @POST("v1/users/{user_id}/playlists")
    suspend fun createPlaylist(
        @Header("Authorization") auth: String,
        @Path("user_id") userId: String,
        @Body body: CreatePlaylistRequest
    ): PlaylistResponse

    @PUT("v1/playlists/{playlist_id}/tracks")
    suspend fun replacePlaylistTracks(
        @Header("Authorization") auth: String,
        @Path("playlist_id") playlistId: String,
        @Body body: AddTracksRequest
    )

    @POST("v1/playlists/{playlist_id}/tracks")
    suspend fun appendPlaylistTracks(
        @Header("Authorization") auth: String,
        @Path("playlist_id") playlistId: String,
        @Body body: AddTracksRequest
    )
}

data class UserResponse(val id: String)
data class UserPlaylistsResponse(val items: List<PlaylistSimpleDto>)
data class PlaylistSimpleDto(val id: String, val name: String, val uri: String)
data class SavedTracksResponse(val items: List<SavedTrackItem>, val total: Int, val next: String?)
data class SavedTrackItem(val track: TrackDto)
data class TrackDto(val uri: String, val name: String, val artists: List<ArtistDto>)
data class ArtistDto(val name: String)
data class CreatePlaylistRequest(
    val name: String = "Curtidas [True Shuffle]",
    val public: Boolean = false,
    val description: String = "Gerada pelo app True Shuffle sem repetições."
)
data class PlaylistResponse(val id: String, val uri: String)
data class AddTracksRequest(val uris: List<String>)