package com.example.trueshuffle.player

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import android.os.Build
import android.view.KeyEvent
import android.widget.Toast
import com.example.trueshuffle.data.local.TrueShuffleDao
import com.example.trueshuffle.domain.CycleGeneratorUseCase
import com.example.trueshuffle.widget.TrueShuffleWidget
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import androidx.core.net.toUri
import kotlin.time.Duration.Companion.milliseconds

class SpotifyLocalController(
    private val context: Context,
    private val dao: TrueShuffleDao,
    private val cycleGenerator: CycleGeneratorUseCase,
    private val tokenProvider: suspend () -> String
) {
    private var spotifyAppRemote: SpotifyAppRemote? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val CLIENT_ID = "64cdb57bf6534c7a8a2d86ac6b21dcaf"
        private const val REDIRECT_URI = "trueshuffle://callback"
    }

    // Botão Play/Pause Universal: controla qualquer música que esteja no Spotify
    suspend fun togglePlayPauseOrResume() {
        val mediaController = findSpotifyMediaController()
        val playbackState = mediaController?.playbackState?.state

        if (mediaController != null) {
            if (playbackState == PlaybackState.STATE_PLAYING) {
                mediaController.transportControls.pause()
            } else {
                mediaController.transportControls.play()
            }
            delay(250.milliseconds)
            return
        }

        // Se o Spotify estava fechado sem sessão ativa, retoma o ciclo do True Shuffle
        resumeTrueShuffleCycle()
    }

    // Botão Resetar/Voltar ao Ciclo (só aparece quando está fora da playlist do True Shuffle)
    suspend fun resumeTrueShuffleCycle() {
        val state = dao.getCycleState() ?: run {
            showToast("Gere um ciclo no app primeiro!")
            return
        }

        val remote = connectAppRemote()
        if (remote != null) {
            withContext(Dispatchers.Main) {
                remote.playerApi.setShuffle(false)
                remote.playerApi.skipToIndex(state.playlistUri, state.currentIndex)
                    .setResultCallback {
                        scope.launch {
                            dao.updatePointer(state.currentIndex, true)
                            TrueShuffleWidget.pushUpdate(context)
                        }
                    }
            }
            subscribeToAppRemoteChanges(remote)
            delay(500.milliseconds)
        } else {
            showToast("Abra o Spotify uma vez para reconectar")
            try {
                val uriIntent = Intent(Intent.ACTION_VIEW, state.playlistUri.toUri()).apply {
                    setPackage("com.spotify.music")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(uriIntent)
            } catch (e: Exception) {
                Log.e("TrueShuffle", "Erro ao abrir playlist do ciclo como fallback", e)
            }
        }
    }

    suspend fun skipToNext() {
        val mediaController = findSpotifyMediaController()
        if (mediaController != null) {
            mediaController.transportControls.skipToNext()
        } else {
            val remote = connectAppRemote()
            if (remote != null) {
                withContext(Dispatchers.Main) { remote.playerApi.skipNext() }
            } else {
                sendMediaButtonKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            }
        }
        delay(400.milliseconds)
    }

    suspend fun skipToPrevious() {
        val mediaController = findSpotifyMediaController()
        if (mediaController != null) {
            mediaController.transportControls.skipToPrevious()
        } else {
            val remote = connectAppRemote()
            if (remote != null) {
                withContext(Dispatchers.Main) { remote.playerApi.skipPrevious() }
            } else {
                sendMediaButtonKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
        }
        delay(400.milliseconds)
    }

    fun openSpotifyPlayerView() {
        val mediaController = findSpotifyMediaController()
        val notifIntent = SpotifyMediaTrackerService.instance?.lastSpotifyNotificationIntent
        val sessionIntent = mediaController?.sessionActivity

        // 1. Tenta a Action explícita do Player Expandido do Spotify com ComponentName ou Package
        try {
            val showPlayerIntent = Intent("com.spotify.mobile.android.ui.action.player.SHOW").apply {
                setPackage("com.spotify.music")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val resolved = context.packageManager.resolveActivity(showPlayerIntent, 0)

            if (resolved != null) {
                context.startActivity(showPlayerIntent)
                return
            }
        } catch (e: Exception) {
            Log.e("TrueShuffle", "Erro ao tentar action.player.SHOW", e)
        }

        // 2. Tenta disparar o contentIntent capturado diretamente da Notificação do Spotify
        val targetPendingIntent = notifIntent ?: sessionIntent
        if (targetPendingIntent != null) {
            try {
                sendPendingIntentSafely(targetPendingIntent)
                return
            } catch (e: Exception) {
                Log.e("TrueShuffle", "Erro ao enviar PendingIntent da sessão", e)
            }
        }

        // 3. Fallback: abre a playlist atual no Spotify
        scope.launch {
            val state = dao.getCycleState()
            if (state != null) {
                try {
                    val uriIntent = Intent(Intent.ACTION_VIEW, state.playlistUri.toUri()).apply {
                        setPackage("com.spotify.music")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(uriIntent)
                    return@launch
                } catch (e: Exception) {
                    Log.e("TrueShuffle", "Erro no fallback URI", e)
                }
            }
            openSpotifyHome()
        }
    }

    fun openSpotifyHome() {
        try {
            val homeIntent = Intent(Intent.ACTION_VIEW, "spotify:home".toUri()).apply {
                setPackage("com.spotify.music")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e("TrueShuffle", "Erro ao abrir spotify:home", e)
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
        }
    }

    private fun sendPendingIntentSafely(pendingIntent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
        } else {
            pendingIntent.send()
        }
    }

    private fun findSpotifyMediaController(): MediaController? {
        SpotifyMediaTrackerService.instance?.getActiveSpotifyController()?.let { return it }
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, SpotifyMediaTrackerService::class.java)
            manager.getActiveSessions(component).firstOrNull { it.packageName == "com.spotify.music" }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun sendMediaButtonKey(keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun subscribeToAppRemoteChanges(remote: SpotifyAppRemote) {
        remote.playerApi.subscribeToPlayerState().setEventCallback { playerState ->
            val currentTrack = playerState.track ?: return@setEventCallback
            val isPlaying = !playerState.isPaused

            scope.launch(Dispatchers.IO) {
                val cycleState = dao.getCycleState() ?: return@launch
                val matchedTrack = dao.getTrackByUri(currentTrack.uri, cycleState.currentIndex)

                if (matchedTrack != null) {
                    val isInSequence = kotlin.math.abs(matchedTrack.positionIndex - cycleState.currentIndex) <= 1

                    if (isInSequence) {
                        dao.updatePointer(matchedTrack.positionIndex, isPlaying)

                        if (matchedTrack.positionIndex >= cycleState.totalTracks - 1 && isPlaying) {
                            if (currentTrack.duration > 0 && playerState.playbackPosition >= (currentTrack.duration * 0.9)) {
                                val token = tokenProvider()
                                cycleGenerator.generateNewShuffleCycle(token)
                                togglePlayPauseOrResume()
                            }
                        }

                        TrueShuffleWidget.pushUpdate(context, isInTrueShuffle = true)
                    } else {
                        TrueShuffleWidget.pushUpdate(
                            context = context,
                            isInTrueShuffle = false,
                            externalTitle = matchedTrack.title,
                            externalArtist = matchedTrack.artist,
                            externalIsPlaying = isPlaying
                        )
                    }
                }
            }
        }
    }

    private suspend fun connectAppRemote(): SpotifyAppRemote? = withContext(Dispatchers.Main) {
        val currentRemote = spotifyAppRemote
        if (currentRemote != null && currentRemote.isConnected) {
            return@withContext currentRemote
        }

        withTimeoutOrNull(3500L.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                val connectionParams = ConnectionParams.Builder(CLIENT_ID)
                    .setRedirectUri(REDIRECT_URI)
                    .showAuthView(false) // false para não travar quando chamado pelo Widget em 2º plano
                    .build()

                SpotifyAppRemote.connect(context, connectionParams, object : Connector.ConnectionListener {
                    override fun onConnected(remote: SpotifyAppRemote) {
                        spotifyAppRemote = remote
                        if (continuation.isActive) continuation.resume(remote)
                    }

                    override fun onFailure(error: Throwable) {
                        Log.e("TrueShuffle", "AppRemote falhou (verifique a fingerprint SHA-1 no Spotify Dashboard): ${error.message}", error)
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }
        }
    }

    private fun showToast(message: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}