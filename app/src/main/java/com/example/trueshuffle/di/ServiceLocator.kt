package com.example.trueshuffle.di

import android.content.Context
import com.example.trueshuffle.data.local.AuthManager
import com.example.trueshuffle.data.local.TrueShuffleDao
import com.example.trueshuffle.data.remote.SpotifyWebApi
import com.example.trueshuffle.domain.CycleGeneratorUseCase
import com.example.trueshuffle.player.SpotifyLocalController
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

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

    fun getSpotifyController(context: Context): SpotifyLocalController {
        return controller ?: synchronized(this) {
            val dao = AppDatabase.getInstance(context).trueShuffleDao()
            val generator = getCycleGenerator(context)
            val authManager = getAuthManager(context)
            SpotifyLocalController(
                context = context.applicationContext,
                dao = dao,
                cycleGenerator = generator,
                tokenProvider = {
                    authManager.getAccessToken() ?: ""
                }
            ).also { controller = it }
        }
    }
}