package com.example.trueshuffle.data.local

data class ShuffledTrackEntity(
    val positionIndex: Int, // 0 até N-1 (ex: 0 a 899)
    val trackUri: String,   // ex: "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
    val title: String,
    val artist: String
)

data class CycleStateEntity(
    val id: Int = 1,
    val playlistId: String,
    val playlistUri: String,
    val currentIndex: Int,
    val totalTracks: Int,
    val isPlaying: Boolean = false
)