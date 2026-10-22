package com.example.trueshuffle.player

import android.app.PendingIntent
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.trueshuffle.di.AppDatabase
import com.example.trueshuffle.widget.TrueShuffleWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.scale
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

class SpotifyMediaTrackerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var spotifyController: MediaController? = null

    @Volatile
    var lastSpotifyNotificationIntent: PendingIntent? = null
        private set

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            syncSpotifyState()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            syncSpotifyState()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        captureSpotifyNotificationIntent()
        attachToSpotifySession()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == "com.spotify.music") {
            sbn.notification?.contentIntent?.let {
                lastSpotifyNotificationIntent = it
            }
        }
    }

    private fun captureSpotifyNotificationIntent() {
        try {
            activeNotifications?.firstOrNull { it.packageName == "com.spotify.music" }
                ?.notification?.contentIntent?.let {
                    lastSpotifyNotificationIntent = it
                }
        } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        spotifyController?.unregisterCallback(mediaCallback)
        spotifyController = null
        instance = null
    }

    fun getActiveSpotifyController(): MediaController? {
        if (spotifyController == null) {
            attachToSpotifySession()
        }
        return spotifyController
    }

    private fun attachToSpotifySession() {
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, SpotifyMediaTrackerService::class.java)
            val controllers = manager.getActiveSessions(component)

            val found = controllers.firstOrNull { it.packageName == "com.spotify.music" }
            if (found != null && found.sessionToken != spotifyController?.sessionToken) {
                spotifyController?.unregisterCallback(mediaCallback)
                spotifyController = found
                found.registerCallback(mediaCallback)
                syncSpotifyState()
            }

            manager.addOnActiveSessionsChangedListener({ list ->
                val updated = list?.firstOrNull { it.packageName == "com.spotify.music" }
                if (updated != null && updated.sessionToken != spotifyController?.sessionToken) {
                    spotifyController?.unregisterCallback(mediaCallback)
                    spotifyController = updated
                    updated.registerCallback(mediaCallback)
                    syncSpotifyState()
                }
            }, component)
        } catch (e: SecurityException) {
            Log.e("TrueShuffle", "Permissão de Acesso a Notificações ainda não concedida", e)
        }
    }

    private fun syncSpotifyState() {
        captureSpotifyNotificationIntent()
        val controller = spotifyController ?: return
        val metadata = controller.metadata
        val playbackState = controller.playbackState

        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)

        scope.launch {
            val dao = AppDatabase.getInstance(applicationContext).trueShuffleDao()

            val extractedColor = if (bitmap != null) {
                saveAlbumArtToCache(bitmap)
                extractDominantColor(bitmap)
            } else null

            val cycleState = dao.getCycleState()
            val matchedTrack = when {
                !mediaId.isNullOrEmpty() && mediaId.startsWith("spotify:track:") ->
                    dao.getTrackByUri(mediaId)
                !title.isNullOrEmpty() ->
                    dao.getTrackByTitle(title)
                else -> null
            }

            // Verifica se a música atual está na vizinhança imediata do nosso ponteiro (-1, 0 ou +1)
            val isInSequence = cycleState != null && matchedTrack != null &&
                    kotlin.math.abs(matchedTrack.positionIndex - cycleState.currentIndex) <= 1

            if (isInSequence && matchedTrack != null) {
                // Está tocando a playlist do True Shuffle: avança o ponteiro e usa o modo padrão
                dao.updatePointer(matchedTrack.positionIndex, isPlaying)
                TrueShuffleWidget.pushUpdate(
                    context = applicationContext,
                    isInTrueShuffle = true,
                    backgroundColor = extractedColor
                )
            } else {
                // Está tocando outra música/playlist fora do ciclo:
                // Mantém o ponteiro intacto no banco e envia os dados da música avulsa para o Widget
                TrueShuffleWidget.pushUpdate(
                    context = applicationContext,
                    isInTrueShuffle = false,
                    externalTitle = title,
                    externalArtist = artist,
                    externalIsPlaying = isPlaying,
                    backgroundColor = extractedColor
                )
            }
        }
    }

    private fun saveAlbumArtToCache(bitmap: Bitmap) {
        try {
            val file = File(applicationContext.cacheDir, "album_art.png")
            val scaled = bitmap.scale(160, 160)
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (e: Exception) {
            Log.e("TrueShuffle", "Erro ao salvar capa", e)
        }
    }

    private fun extractDominantColor(bitmap: Bitmap): Int {
        val palette = Palette.from(bitmap).generate()
        // dominantSwatch returns the swatch with the highest pixel population (most common color)
        val swatch = palette.dominantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.darkMutedSwatch

        val color = swatch?.rgb ?: 0xFF566B8A.toInt()

        // If the most common color is too bright, blend it toward black so white text remains readable
        val luminance = ColorUtils.calculateLuminance(color)
        return if (luminance > 0.4) {
            ColorUtils.blendARGB(color, android.graphics.Color.BLACK, 0.5f)
        } else {
            color
        }
    }

    companion object {
        @Volatile
        var instance: SpotifyMediaTrackerService? = null
            private set
    }
}