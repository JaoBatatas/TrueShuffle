package com.example.trueshuffle.di

import android.content.Context
import com.example.trueshuffle.data.local.AuthManager
import com.example.trueshuffle.data.local.TrueShuffleDao
import com.example.trueshuffle.data.remote.SpotifyWebApi
import com.example.trueshuffle.domain.CycleGeneratorUseCase
import com.example.trueshuffle.player.SpotifyLocalController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AppDatabase private constructor(context: Context) {
    private val dao = TrueShuffleDao(context.applicationContext)

    fun trueShuffleDao(): TrueShuffleDao = dao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                AppDatabase(context).also { INSTANCE = it }
            }
        }
    }
}

object ServiceLocator {
    private const val CLIENT_ID = "64cdb57bf6534c7a8a2d86ac6b21dcaf"
    @Volatile private var controller: SpotifyLocalController? = null

    val webApi: SpotifyWebApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.spotify.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SpotifyWebApi::class.java)
    }

    fun getAuthManager(context: Context): AuthManager {
        return AuthManager(context.applicationContext)
    }

    fun getCycleGenerator(context: Context): CycleGeneratorUseCase {
        val dao = AppDatabase.getInstance(context).trueShuffleDao()
        return CycleGeneratorUseCase(webApi, dao)
    }

    suspend fun getValidAccessToken(context: Context): String? {
        val authManager = getAuthManager(context)
        val token = authManager.getAccessToken()
        if (!token.isNullOrEmpty()) return token

        val refreshToken = authManager.getRefreshToken()
        if (!refreshToken.isNullOrEmpty()) {
            return refreshAccessToken(authManager, refreshToken)
        }
        return null
    }

    suspend fun refreshAccessToken(authManager: AuthManager, refreshToken: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://accounts.spotify.com/api/token")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            val body = "client_id=${URLEncoder.encode(CLIENT_ID, "UTF-8")}" +
                    "&grant_type=refresh_token" +
                    "&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}"

            conn.outputStream.use { it.write(body.toByteArray()) }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val newAccessToken = json.getString("access_token")
                authManager.saveAccessToken(newAccessToken)
                if (json.has("refresh_token")) {
                    authManager.saveRefreshToken(json.getString("refresh_token"))
                }
                newAccessToken
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun getSpotifyController(context: Context): SpotifyLocalController {
        return controller ?: synchronized(this) {
            val dao = AppDatabase.getInstance(context).trueShuffleDao()
            val generator = getCycleGenerator(context)
            SpotifyLocalController(
                context = context.applicationContext,
                dao = dao,
                cycleGenerator = generator,
                tokenProvider = {
                    getValidAccessToken(context) ?: ""
                }
            ).also { controller = it }
        }
    }
}
