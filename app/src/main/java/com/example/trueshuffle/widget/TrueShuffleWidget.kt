package com.example.trueshuffle.widget

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.palette.graphics.Palette
import androidx.glance.*
import androidx.glance.ColorFilter
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import com.example.trueshuffle.R
import com.example.trueshuffle.di.AppDatabase
import com.example.trueshuffle.di.ServiceLocator
import java.io.File

class TrueShuffleWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = AppDatabase.getInstance(context).trueShuffleDao()
        val dbState = dao.getCycleState()
        val dbTrack = dbState?.let { dao.getTrackAtIndex(it.currentIndex) }

        provideContent {
            val prefs = currentState<Preferences>()
            val title = prefs[KEY_TITLE] ?: dbTrack?.title ?: "Pronto para tocar"
            val artist = prefs[KEY_ARTIST] ?: dbTrack?.artist ?: "Toque em ▶ para iniciar"
            val progress = prefs[KEY_PROGRESS] ?: if (dbState != null) {
                "${dbState.currentIndex + 1} / ${dbState.totalTracks}"
            } else "0 / 0"
            val isPlaying = prefs[KEY_IS_PLAYING] ?: dbState?.isPlaying ?: false
            val isInCycle = prefs[KEY_IN_CYCLE] ?: true

            val artFile = File(context.cacheDir, "album_art.png")
            val albumBitmap = if (artFile.exists()) {
                BitmapFactory.decodeFile(artFile.absolutePath)
            } else null

            val bgColorInt = prefs[KEY_BG_COLOR] ?: 0xFF566B8A.toInt()
            val widgetBgColor = Color(bgColorInt)

            Widget5x1Layout(
                title = title,
                artist = artist,
                progressText = progress,
                isPlaying = isPlaying,
                albumBitmap = albumBitmap,
                isInCycle = isInCycle,
                backgroundColor = widgetBgColor
            )
        }
    }

    @Composable
    private fun Widget5x1Layout(
        title: String,
        artist: String,
        progressText: String,
        isPlaying: Boolean,
        albumBitmap: android.graphics.Bitmap?,
        isInCycle: Boolean,
        backgroundColor: Color = Color(0xFF566B8A)
    ) {
        // Container inteiro clicável para abrir o Player do Spotify em qualquer área livre
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(day = backgroundColor, night = backgroundColor))
                .cornerRadius(24.dp)
                .clickable(actionRunCallback<OpenSpotifyAction>())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Capa da Música à esquerda
            Box(
                modifier = GlanceModifier
                    .size(52.dp)
                    .background(ColorProvider(day = Color(0xFF39485E), night = Color(0xFF39485E)))
                    .cornerRadius(12.dp),
                    //.clickable(actionRunCallback<OpenSpotifyAction>()),
                contentAlignment = Alignment.Center
            ) {
                if (albumBitmap != null) {
                    Image(
                        provider = ImageProvider(albumBitmap),
                        contentDescription = "Capa do Álbum",
                        modifier = GlanceModifier.fillMaxSize().cornerRadius(12.dp),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Image(
                        provider = ImageProvider(R.drawable.ic_spotify),
                        contentDescription = "Música",
                        modifier = GlanceModifier.size(26.dp),
                        colorFilter = ColorFilter.tint(ColorProvider(day = Color.White, night = Color.White))
                    )
                }
            }

            Spacer(modifier = GlanceModifier.width(10.dp))

            // 2. Título, Artista e Progresso no Ciclo
            Column(
                modifier = GlanceModifier
                    .defaultWeight(),
                    //.clickable(actionRunCallback<OpenSpotifyAction>()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(day = Color.White, night = Color.White),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = artist,
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(day = Color(0xFFDCE3EC), night = Color(0xFFDCE3EC)),
                        fontSize = 12.sp
                    )
                )
                Text(
                    text = progressText,
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(day = Color(0xFFA5F3B6), night = Color(0xFFA5F3B6)),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            Spacer(modifier = GlanceModifier.width(8.dp))

            // 3. Controles e Logo (Agrupados em Row interna para respeitar o limite de 10 filhos do Glance)
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Botão Retroceder (Ícone Vetorial)
                Box(
                    modifier = GlanceModifier
                        .size(44.dp)
                        .clickable(actionRunCallback<SkipPreviousAction>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_skip_previous),
                        contentDescription = "Anterior",
                        modifier = GlanceModifier.size(44.dp)
                    )
                }

                Spacer(modifier = GlanceModifier.width(4.dp))

                // Botão Central Play / Pause (Círculo branco com ícone vetorial)
                Box(
                    modifier = GlanceModifier
                        .size(44.dp)
                        .background(ColorProvider(day = Color.White, night = Color.White))
                        .cornerRadius(22.dp)
                        .clickable(actionRunCallback<PlayPauseAction>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (isPlaying) "Pausar" else "Reproduzir",
                        modifier = GlanceModifier.size(24.dp)
                    )
                }

                Spacer(modifier = GlanceModifier.width(4.dp))

                // Botão Avançar (Ícone Vetorial)
                Box(
                    modifier = GlanceModifier
                        .size(44.dp)
                        .clickable(actionRunCallback<SkipNextAction>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_skip_next),
                        contentDescription = "Próxima",
                        modifier = GlanceModifier.size(44.dp)
                    )
                }

                Spacer(modifier = GlanceModifier.width(2.dp))

                // Se estiver fora da playlist do True Shuffle, exibe o botão de voltar para a última música salva
                if (!isInCycle) {
                    Box(
                        modifier = GlanceModifier
                            .size(32.dp)
                            .clickable(actionRunCallback<ResumeCycleAction>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_sync_playlist),
                            contentDescription = "Voltar ao True Shuffle",
                            modifier = GlanceModifier.size(32.dp)
                        )
                    }
                } else {
                    // Logo Oficial do Spotify no canto direito
                    Box(
                        modifier = GlanceModifier
                            .size(32.dp)
                            .clickable(actionRunCallback<OpenSpotifyHomeAction>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_spotify),
                            contentDescription = "Abrir Spotify",
                            modifier = GlanceModifier.size(32.dp),
                            colorFilter = ColorFilter.tint(
                                ColorProvider(
                                    day = Color.White,
                                    night = Color.White
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    companion object {
        val KEY_TITLE = stringPreferencesKey("title")
        val KEY_ARTIST = stringPreferencesKey("artist")
        val KEY_PROGRESS = stringPreferencesKey("progress")
        val KEY_IS_PLAYING = booleanPreferencesKey("is_playing")
        val KEY_TIMESTAMP = longPreferencesKey("timestamp")
        val KEY_IN_CYCLE = booleanPreferencesKey("in_cycle")
        val KEY_BG_COLOR = intPreferencesKey("bg_color")

        suspend fun pushUpdate(
            context: Context,
            isInTrueShuffle: Boolean = true,
            externalTitle: String? = null,
            externalArtist: String? = null,
            externalIsPlaying: Boolean? = null,
            backgroundColor: Int? = null
        ) {
            val dao = AppDatabase.getInstance(context).trueShuffleDao()
            val state = dao.getCycleState()
            val savedTrack = state?.let { dao.getTrackAtIndex(it.currentIndex) }

            val displayTitle = if (isInTrueShuffle) {
                savedTrack?.title ?: "Pronto para tocar"
            } else {
                externalTitle ?: savedTrack?.title ?: "Spotify"
            }

            val displayArtist = if (isInTrueShuffle) {
                savedTrack?.artist ?: "Toque em ▶ para iniciar"
            } else {
                externalArtist ?: ""
            }

            // Se estiver no ciclo: "240 / 963"
            // Se estiver fora: "Salvo (240/963): Nome da Música"
            val displayProgress = if (state != null) {
                if (isInTrueShuffle) {
                    "${state.currentIndex + 1} / ${state.totalTracks}"
                } else {
                    "Salvo (${state.currentIndex + 1}/${state.totalTracks}): ${savedTrack?.title ?: ""}"
                }
            } else "0 / 0"

            val displayPlaying = externalIsPlaying ?: state?.isPlaying ?: false

            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(TrueShuffleWidget::class.java)
            val colorToSave = backgroundColor ?: calculateColorFromCache(context)

            glanceIds.forEach { glanceId ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                    prefs.toMutablePreferences().apply {
                        this[KEY_TITLE] = displayTitle
                        this[KEY_ARTIST] = displayArtist
                        this[KEY_PROGRESS] = displayProgress
                        this[KEY_IS_PLAYING] = displayPlaying
                        this[KEY_IN_CYCLE] = isInTrueShuffle
                        this[KEY_TIMESTAMP] = System.currentTimeMillis()
                        this[KEY_BG_COLOR] = colorToSave
                    }
                }
                TrueShuffleWidget().update(context, glanceId)
            }
        }

        private fun calculateColorFromCache(context: Context): Int {
            val artFile = File(context.cacheDir, "album_art.png")
            if (!artFile.exists()) return 0xFF566B8A.toInt()
            return try {
                val bitmap = BitmapFactory.decodeFile(artFile.absolutePath) ?: return 0xFF566B8A.toInt()
                val palette = Palette.from(bitmap).generate()
                val swatch = palette.dominantSwatch
                    ?: palette.darkVibrantSwatch
                    ?: palette.darkMutedSwatch

                val color = swatch?.rgb ?: 0xFF566B8A.toInt()
                val luminance = ColorUtils.calculateLuminance(color)
                if (luminance > 0.4) {
                    ColorUtils.blendARGB(color, android.graphics.Color.BLACK, 0.5f)
                } else {
                    color
                }
            } catch (_: Exception) {
                0xFF566B8A.toInt()
            }
        }
    }
}

@Keep
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ServiceLocator.getSpotifyController(context).togglePlayPauseOrResume()
    }
}

@Keep
class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ServiceLocator.getSpotifyController(context).skipToNext()
    }
}

@Keep
class SkipPreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ServiceLocator.getSpotifyController(context).skipToPrevious()
    }
}

@Keep
class OpenSpotifyAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ServiceLocator.getSpotifyController(context).openSpotifyPlayerView()
    }
}

@Keep
class OpenSpotifyHomeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ServiceLocator.getSpotifyController(context).openSpotifyHome()
    }
}

@Keep
class TrueShuffleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrueShuffleWidget()
}

@Keep
class ResumeCycleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ServiceLocator.getSpotifyController(context).resumeTrueShuffleCycle()
    }
}