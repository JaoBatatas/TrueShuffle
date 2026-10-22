package com.example.trueshuffle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.example.trueshuffle.data.local.AuthManager
import com.example.trueshuffle.di.AppDatabase
import com.example.trueshuffle.di.ServiceLocator
import com.example.trueshuffle.widget.TrueShuffleWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class MainActivity : ComponentActivity() {

    companion object {
        // Client ID público do Spotify para fluxo PKCE (sem chave secreta no APK)
        private const val CLIENT_ID = "64cdb57bf6534c7a8a2d86ac6b21dcaf"
        private const val REDIRECT_URI = "trueshuffle://callback"
        private const val SCOPES = "user-library-read playlist-read-private playlist-modify-private playlist-modify-public user-read-playback-state user-modify-playback-state"
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
            MaterialTheme {
                val dao = remember { AppDatabase.getInstance(this).trueShuffleDao() }
                val cycleState by dao.observeCycleState().collectAsState(initial = null)

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = cycleState?.let {
                                stringResource(R.string.cycle_current, it.currentIndex + 1, it.totalTracks)
                            } ?: stringResource(R.string.cycle_none)
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(
                            onClick = { startSpotifyLogin() },
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (isSpotifyConnected) stringResource(R.string.btn_spotify_connected)
                                else stringResource(R.string.btn_connect_spotify)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { generateNewCycle() },
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.btn_generate_cycle))
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (isListenerEnabled) stringResource(R.string.btn_background_active)
                                else stringResource(R.string.btn_background_inactive)
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        if (isLoading) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodyMedium
                        )
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

    private fun generateNewCycle() {
        val token = authManager.getAccessToken()

        if (token.isNullOrEmpty()) {
            statusMessage = getString(R.string.status_login_first)
            return
        }

        lifecycleScope.launch {
            try {
                isLoading = true
                statusMessage = getString(R.string.status_downloading)
                ServiceLocator.getCycleGenerator(this@MainActivity).generateNewShuffleCycle(token)
                TrueShuffleWidget.pushUpdate(this@MainActivity)
                ServiceLocator.getSpotifyController(this@MainActivity).togglePlayPauseOrResume()
                statusMessage = getString(R.string.status_playlist_generated)
            } catch (e: Exception) {
                statusMessage = getString(R.string.status_error, e.localizedMessage ?: "")
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
