package com.farah.phonesnitch

import android.content.Context
import androidx.room.Room
import com.farah.phonesnitch.data.local.LauncherDatabase
import com.farah.phonesnitch.data.local.MIGRATION_1_2
import com.farah.phonesnitch.data.local.MIGRATION_2_3
import com.farah.phonesnitch.data.system.UsageStatsRepositoryImpl
import com.farah.phonesnitch.data.system.UsageStatsSource
import com.farah.phonesnitch.domain.repository.UsageStatsRepository
import com.farah.phonesnitch.domain.usecase.BuildTimeStatsUseCase
import com.farah.phonesnitch.data.local.SettingsStore
import com.farah.phonesnitch.data.remote.GeminiAiService
import com.farah.phonesnitch.data.remote.GeminiApi
import com.farah.phonesnitch.data.repository.LauncherRepositoryImpl
import com.farah.phonesnitch.data.system.AppLauncher
import com.farah.phonesnitch.domain.repository.AiService
import com.farah.phonesnitch.domain.repository.LauncherRepository
import com.farah.phonesnitch.domain.repository.SettingsRepository
import com.farah.phonesnitch.domain.usecase.AskAssistantUseCase
import com.farah.phonesnitch.domain.usecase.BuildDashboardStatsUseCase
import com.farah.phonesnitch.domain.usecase.BuildHomeSectionsUseCase
import com.farah.phonesnitch.domain.usecase.ClassifyAppsUseCase
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Manual dependency injection: one place that wires every layer together. */
@OptIn(ExperimentalSerializationApi::class)
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val settings: SettingsRepository = SettingsStore(appContext)

    private val database = Room.databaseBuilder(appContext, LauncherDatabase::class.java, "launcher.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()
    val repository: LauncherRepository = LauncherRepositoryImpl(appContext, database.dao())

    val appLauncher = AppLauncher(appContext)

    private val api: GeminiApi = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(
            OkHttpClient.Builder()
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GeminiApi::class.java)

    val ai: AiService = GeminiAiService(api, json, BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_MODEL)

    val classifyApps = ClassifyAppsUseCase(repository, ai, settings)
    val askAssistant = AskAssistantUseCase(ai)
    val buildHomeSections = BuildHomeSectionsUseCase()
    val buildDashboardStats = BuildDashboardStatsUseCase()
    val buildTimeStats = BuildTimeStatsUseCase()

    val usageStats: UsageStatsRepository =
        UsageStatsRepositoryImpl(UsageStatsSource(appContext), repository, settings)
}
