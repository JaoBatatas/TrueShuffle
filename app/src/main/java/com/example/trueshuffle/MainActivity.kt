package com.example.trueshuffle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.example.trueshuffle.data.local.AuthManager
import com.example.trueshuffle.data.remote.AlbumSimpleDto
import com.example.trueshuffle.data.remote.PlaylistSimpleDto
import com.example.trueshuffle.di.AppDatabase
import com.example.trueshuffle.di.ServiceLocator
import com.example.trueshuffle.ui.theme.*
import com.example.trueshuffle.widget.TrueShuffleWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.HttpException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class MainActivity : ComponentActivity() {

    companion object {
        private const val CLIENT_ID = "64cdb57bf6534c7a8a2d86ac6b21dcaf"
        private const val REDIRECT_URI = "trueshuffle://callback"
        private const val SCOPES = "user-library-read playlist-read-private playlist-read-collaborative playlist-modify-private playlist-modify-public user-read-playback-state user-modify-playback-state"
    }

    private var statusMessage by mutableStateOf("")
    private var isLoading by mutableStateOf(false)
    private var isListenerEnabled by mutableStateOf(false)
    private var isSpotifyConnected by mutableStateOf(false)
    private lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authManager = ServiceLocator.getAuthManager(this)
        isSpotifyConnected = !authManager.getAccessToken().isNullOrEmpty()
        if (statusMessage.isEmpty()) {
            statusMessage = getString(R.string.status_ready)
        }
        handleAuthIntent(intent)

        setContent {
            TrueShuffleTheme {
                val dao = remember { AppDatabase.getInstance(this).trueShuffleDao() }
                val cycleState by dao.observeCycleState().collectAsState(initial = null)

                var showDisconnectDialog by remember { mutableStateOf(false) }
                var showGenerateConfirmDialog by remember { mutableStateOf(false) }

                var selectedSourceId by remember { mutableStateOf("liked") }
                var selectedSourceName by remember { mutableStateOf("Músicas Curtidas") }
                var selectedSourceType by remember { mutableStateOf("liked") }

                var userPlaylists by remember { mutableStateOf<List<PlaylistSimpleDto>>(emptyList()) }
                var userAlbums by remember { mutableStateOf<List<AlbumSimpleDto>>(emptyList()) }

                LaunchedEffect(isSpotifyConnected) {
                    if (isSpotifyConnected) {
                        try {
                            val token = authManager.getAccessToken()
                            if (!token.isNullOrEmpty()) {
                                // 1. Buscar Playlists
                                val allPlaylists = mutableListOf<PlaylistSimpleDto>()
                                var offsetP = 0
                                while (true) {
                                    val response = withContext(Dispatchers.IO) {
                                        ServiceLocator.webApi.getUserPlaylists("Bearer $token", offsetP)
                                    }
                                    allPlaylists.addAll(response.items)
                                    if (response.items.size < 50) break
                                    offsetP += 50
                                }
                                userPlaylists = allPlaylists.filter {
                                    !it.name.contains("[True Shuffle]", ignoreCase = true)
                                }

                                // 2. Buscar Álbuns Salvos
                                val allAlbums = mutableListOf<AlbumSimpleDto>()
                                var offsetA = 0
                                while (true) {
                                    val response = withContext(Dispatchers.IO) {
                                        ServiceLocator.webApi.getSavedAlbums("Bearer $token", offsetA)
                                    }
                                    allAlbums.addAll(response.items.map { it.album })
                                    if (response.items.size < 50) break
                                    offsetA += 50
                                }
                                userAlbums = allAlbums
                            }
                        } catch (e: Exception) {
                            Log.e("TrueShuffle", "Erro ao buscar biblioteca: ${e.message}", e)
                            if (e is HttpException && e.code() == 401) {
                                authManager.clearAuthData()
                                isSpotifyConnected = false
                                statusMessage = "Sessão do Spotify expirada. Faça login novamente."
                            }
                            userPlaylists = emptyList()
                            userAlbums = emptyList()
                        }
                    } else {
                        userPlaylists = emptyList()
                        userAlbums = emptyList()
                    }
                }

                if (showDisconnectDialog) {
                    AlertDialog(
                        onDismissRequest = { showDisconnectDialog = false },
                        title = { Text(stringResource(R.string.dialog_disconnect_title), color = TextPrimary) },
                        text = { Text(stringResource(R.string.dialog_disconnect_msg), color = TextSecondary) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showDisconnectDialog = false
                                    authManager.clearAuthData()
                                    isSpotifyConnected = false
                                    userPlaylists = emptyList()
                                    userAlbums = emptyList()
                                    statusMessage = getString(R.string.status_disconnected)
                                }
                            ) {
                                Text(
                                    stringResource(R.string.dialog_disconnect_confirm),
                                    color = StatusError,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDisconnectDialog = false }) {
                                Text(stringResource(R.string.dialog_cancel), color = TextSecondary)
                            }
                        },
                        containerColor = DarkSurface,
                        shape = RoundedCornerShape(16.dp)
                    )
                }

                if (showGenerateConfirmDialog) {
                    val currentTrackNum = (cycleState?.currentIndex ?: 0) + 1
                    val totalTrackNum = cycleState?.totalTracks ?: 0
                    AlertDialog(
                        onDismissRequest = { showGenerateConfirmDialog = false },
                        title = { Text(stringResource(R.string.dialog_generate_title), color = TextPrimary) },
                        text = {
                            Text(
                                stringResource(R.string.dialog_generate_msg, currentTrackNum, totalTrackNum),
                                color = TextSecondary
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showGenerateConfirmDialog = false
                                    generateNewCycle(selectedSourceId, selectedSourceName, selectedSourceType)
                                }
                            ) {
                                Text(
                                    stringResource(R.string.dialog_confirm),
                                    color = SpotifyGreenBright,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showGenerateConfirmDialog = false }) {
                                Text(stringResource(R.string.dialog_cancel), color = TextSecondary)
                            }
                        },
                        containerColor = DarkSurface,
                        shape = RoundedCornerShape(16.dp)
                    )
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 20.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Spacer(modifier = Modifier.height(20.dp))

                        // App Header
                        HeaderBar()

                        Spacer(modifier = Modifier.height(24.dp))

                        // Hero Card: Status do Ciclo
                        CycleHeroCard(
                            cycleState = cycleState,
                            onOpenSpotify = {
                                ServiceLocator.getSpotifyController(this@MainActivity).openSpotifyPlayerView()
                            }
                        )

                        Spacer(modifier = Modifier.height(28.dp))

                        Text(
                            text = "CONFIGURAÇÕES & PASSO A PASSO",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                        )

                        // Passo 1: Conexão Spotify
                        Step1SpotifyCard(
                            isConnected = isSpotifyConnected,
                            isLoading = isLoading,
                            onConnect = { startSpotifyLogin() },
                            onDisconnect = { showDisconnectDialog = true }
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Passo 2: Seleção da Biblioteca (Playlists & Álbuns) & Gerar Ciclo
                        Step2GenerateCycleCard(
                            isConnected = isSpotifyConnected,
                            isLoading = isLoading,
                            playlists = userPlaylists,
                            albums = userAlbums,
                            selectedSourceId = selectedSourceId,
                            selectedSourceName = selectedSourceName,
                            selectedSourceType = selectedSourceType,
                            onSelectSource = { id, name, type ->
                                selectedSourceId = id
                                selectedSourceName = name
                                selectedSourceType = type
                            },
                            onGenerate = {
                                val token = authManager.getAccessToken()
                                if (token.isNullOrEmpty()) {
                                    statusMessage = getString(R.string.status_login_first)
                                } else if (cycleState != null && cycleState!!.currentIndex + 1 < cycleState!!.totalTracks) {
                                    showGenerateConfirmDialog = true
                                } else {
                                    generateNewCycle(selectedSourceId, selectedSourceName, selectedSourceType)
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Passo 3: Monitoramento em Segundo Plano
                        Step3BackgroundListenerCard(
                            isEnabled = isListenerEnabled,
                            onOpenSettings = {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Status Banner
                        StatusBanner(
                            statusMessage = statusMessage,
                            isLoading = isLoading
                        )

                        Spacer(modifier = Modifier.height(28.dp))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isSpotifyConnected = !authManager.getAccessToken().isNullOrEmpty()
        isListenerEnabled = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)

        lifecycleScope.launch {
            TrueShuffleWidget.pushUpdate(this@MainActivity)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthIntent(intent)
    }

    private fun startSpotifyLogin() {
        val verifier = generateCodeVerifier()
        authManager.saveCodeVerifier(verifier)

        val challenge = generateCodeChallenge(verifier)
        val authUri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES)
            .build()

        startActivity(Intent(Intent.ACTION_VIEW, authUri))
    }

    private fun handleAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "trueshuffle" && uri.host == "callback") {
            val code = uri.getQueryParameter("code") ?: return
            val verifier = authManager.getCodeVerifier() ?: return

            lifecycleScope.launch {
                isLoading = true
                statusMessage = getString(R.string.status_getting_token)
                val token = exchangeCodeForToken(code, verifier)
                if (token != null) {
                    authManager.saveAccessToken(token)
                    isSpotifyConnected = true
                    statusMessage = getString(R.string.status_connected)
                } else {
                    statusMessage = getString(R.string.status_auth_failed)
                }
                isLoading = false
            }
        }
    }

    private fun generateNewCycle(sourceId: String, sourceName: String, sourceType: String) {
        val token = authManager.getAccessToken()

        if (token.isNullOrEmpty()) {
            statusMessage = getString(R.string.status_login_first)
            return
        }

        lifecycleScope.launch {
            try {
                isLoading = true
                statusMessage = getString(R.string.status_downloading)
                ServiceLocator.getCycleGenerator(this@MainActivity)
                    .generateNewShuffleCycle(token, sourceId, sourceName, sourceType)
                TrueShuffleWidget.pushUpdate(this@MainActivity)
                ServiceLocator.getSpotifyController(this@MainActivity).togglePlayPauseOrResume()
                statusMessage = getString(R.string.status_playlist_generated)
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 401) {
                    authManager.clearAuthData()
                    isSpotifyConnected = false
                    statusMessage = "Sessão do Spotify expirada. Reconecte a conta no passo 1."
                } else {
                    statusMessage = getString(R.string.status_error, e.localizedMessage ?: "")
                }
            } finally {
                isLoading = false
            }
        }
    }

    private suspend fun exchangeCodeForToken(code: String, verifier: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://accounts.spotify.com/api/token")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }

                val body = "client_id=${URLEncoder.encode(CLIENT_ID, "UTF-8")}" +
                        "&grant_type=authorization_code" +
                        "&code=${URLEncoder.encode(code, "UTF-8")}" +
                        "&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}" +
                        "&code_verifier=${URLEncoder.encode(verifier, "UTF-8")}"

                conn.outputStream.use { it.write(body.toByteArray()) }

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().readText()
                    JSONObject(response).getString("access_token")
                } else null
            } catch (e: Exception) {
                null
            }
        }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }
}

@Composable
private fun HeaderBar() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = "Logo True Shuffle",
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary
            )
            Text(
                text = stringResource(R.string.app_subtitle),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun CycleHeroCard(
    cycleState: com.example.trueshuffle.data.local.CycleStateEntity?,
    onOpenSpotify: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, DarkCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_sync_playlist),
                        contentDescription = null,
                        tint = SpotifyGreen,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.cycle_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                }

                StatusBadge(
                    text = if (cycleState != null) stringResource(R.string.badge_active) else "Sem Ciclo",
                    isPositive = cycleState != null
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (cycleState != null) {
                val currentTrack = cycleState.currentIndex + 1
                val totalTracks = cycleState.totalTracks
                val progressFraction = if (totalTracks > 0) currentTrack.toFloat() / totalTracks else 0f

                Text(
                    text = stringResource(R.string.cycle_current, currentTrack, totalTracks),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = SpotifyGreenBright
                )

                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = SpotifyGreen,
                    trackColor = DarkSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.cycle_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onOpenSpotify,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotifyGreen,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_spotify),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.btn_open_spotify),
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.cycle_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Conecte sua conta do Spotify abaixo e gere um ciclo para começar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun Step1SpotifyCard(
    isConnected: Boolean,
    isLoading: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, DarkCardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.step_1_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.step_1_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                StatusBadge(
                    text = if (isConnected) stringResource(R.string.badge_connected) else stringResource(R.string.badge_disconnected),
                    isPositive = isConnected
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (isConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = StatusError
                    ),
                    border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.btn_disconnect_spotify),
                        fontWeight = FontWeight.Bold,
                        color = StatusError
                    )
                }
            } else {
                Button(
                    onClick = onConnect,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotifyGreen,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_spotify),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.btn_connect_spotify),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun Step2GenerateCycleCard(
    isConnected: Boolean,
    isLoading: Boolean,
    playlists: List<PlaylistSimpleDto>,
    albums: List<AlbumSimpleDto>,
    selectedSourceId: String,
    selectedSourceName: String,
    selectedSourceType: String,
    onSelectSource: (id: String, name: String, type: String) -> Unit,
    onGenerate: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, DarkCardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.step_2_title),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.step_2_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Seletor de Origem da Biblioteca
            Text(
                text = stringResource(R.string.select_playlist_label),
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceVariant)
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
                    .clickable(enabled = isConnected) { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (selectedSourceType) {
                            "liked" -> stringResource(R.string.playlist_liked_songs)
                            "album" -> "💿 $selectedSourceName"
                            else -> "🎵 $selectedSourceName"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isConnected) TextPrimary else TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "▼",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(DarkSurfaceVariant)
                ) {
                    // 1. Músicas Curtidas
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.playlist_liked_songs),
                                color = TextPrimary,
                                fontWeight = if (selectedSourceType == "liked") FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onSelectSource("liked", "Músicas Curtidas", "liked")
                            expanded = false
                        }
                    )

                    // 2. Playlists
                    if (playlists.isNotEmpty()) {
                        HorizontalDivider(color = DarkCardBorder, modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = "PLAYLISTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        playlists.forEach { playlist ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "🎵 ${playlist.name}",
                                        color = TextPrimary,
                                        fontWeight = if (selectedSourceType == "playlist" && selectedSourceId == playlist.id) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    onSelectSource(playlist.id, playlist.name, "playlist")
                                    expanded = false
                                }
                            )
                        }
                    }

                    // 3. Álbuns Salvos na Biblioteca
                    if (albums.isNotEmpty()) {
                        HorizontalDivider(color = DarkCardBorder, modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = "ÁLBUNS SALVOS",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        albums.forEach { album ->
                            val artist = album.artists.firstOrNull()?.name
                            val title = if (!artist.isNullOrEmpty()) "${album.name} ($artist)" else album.name
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "💿 $title",
                                        color = TextPrimary,
                                        fontWeight = if (selectedSourceType == "album" && selectedSourceId == album.id) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    onSelectSource(album.id, title, "album")
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onGenerate,
                enabled = !isLoading && isConnected,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpotifyGreen,
                    contentColor = TextPrimary,
                    disabledContainerColor = SpotifyGreenDark
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = TextPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Embaralhando...",
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_sync_playlist),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.btn_generate_cycle),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun Step3BackgroundListenerCard(
    isEnabled: Boolean,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, DarkCardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.step_3_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.step_3_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                StatusBadge(
                    text = if (isEnabled) stringResource(R.string.badge_active) else stringResource(R.string.badge_inactive),
                    isPositive = isEnabled
                )
            }

            // Exibe o botão de atalho apenas se o monitoramento NÃO estiver ativo
            if (!isEnabled) {
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SpotifyGreenBright
                    ),
                    border = BorderStroke(1.dp, SpotifyGreen),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.btn_background_inactive),
                        fontWeight = FontWeight.Bold,
                        color = SpotifyGreenBright
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(
    statusMessage: String,
    isLoading: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceVariant,
        border = BorderStroke(1.dp, DarkCardBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = SpotifyGreenBright,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(SpotifyGreen)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    isPositive: Boolean
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (isPositive) SpotifyGreenDark else DarkSurfaceVariant,
        border = BorderStroke(1.dp, if (isPositive) SpotifyGreen else DarkCardBorder)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (isPositive) SpotifyGreenBright else TextSecondary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    TrueShuffleTheme {
        Surface(color = DarkBackground) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                HeaderBar()
                Spacer(modifier = Modifier.height(20.dp))
                CycleHeroCard(
                    cycleState = com.example.trueshuffle.data.local.CycleStateEntity(
                        id = 1,
                        playlistId = "test",
                        playlistUri = "spotify:playlist:test",
                        currentIndex = 14,
                        totalTracks = 120,
                        isPlaying = true
                    ),
                    onOpenSpotify = {}
                )
                Spacer(modifier = Modifier.height(16.dp))
                Step1SpotifyCard(
                    isConnected = true,
                    isLoading = false,
                    onConnect = {},
                    onDisconnect = {}
                )
                Spacer(modifier = Modifier.height(14.dp))
                Step2GenerateCycleCard(
                    isConnected = true,
                    isLoading = false,
                    playlists = emptyList(),
                    albums = emptyList(),
                    selectedSourceId = "liked",
                    selectedSourceName = "Músicas Curtidas",
                    selectedSourceType = "liked",
                    onSelectSource = { _, _, _ -> },
                    onGenerate = {}
                )
                Spacer(modifier = Modifier.height(14.dp))
                Step3BackgroundListenerCard(
                    isEnabled = true,
                    onOpenSettings = {}
                )
            }
        }
    }
}
