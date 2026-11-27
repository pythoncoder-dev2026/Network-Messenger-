package com.network.messenger.di

import android.content.Context
import androidx.room.Room
import com.network.messenger.audio.AudioEngine
import com.network.messenger.crypto.AndroidKeystoreKeyStorage
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.crypto.KeyStorage
import com.network.messenger.data.local.AppDatabase
import com.network.messenger.data.local.BlockedUserDao
import com.network.messenger.data.local.MessageDao
import com.network.messenger.data.local.MutedUserDao
import com.network.messenger.data.local.NetworkDao
import com.network.messenger.data.local.UserReportDao
import com.network.messenger.data.preferences.SettingsStore
import com.network.messenger.data.repository.LogRepository
import com.network.messenger.transport.NearbyConnectionsTransport
import com.network.messenger.transport.TransportManager
import com.network.messenger.transport.WebSocketRelayTransport
import com.network.messenger.transport.WifiLanTransport
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Provides
    @Singleton
    fun provideKeyStorage(@ApplicationContext context: Context): KeyStorage =
        AndroidKeystoreKeyStorage(context)

    @Provides
    @Singleton
    fun provideCryptoManager(keyStorage: KeyStorage): CryptoManager =
        CryptoManager(keyStorage)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "network_messenger.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideNetworkDao(db: AppDatabase): NetworkDao = db.networkDao()

    @Provides
    fun provideBlockedUserDao(db: AppDatabase): BlockedUserDao = db.blockedUserDao()

    @Provides
    fun provideMutedUserDao(db: AppDatabase): MutedUserDao = db.mutedUserDao()

    @Provides
    fun provideUserReportDao(db: AppDatabase): UserReportDao = db.userReportDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore =
        SettingsStore(context)

    @Provides
    @Singleton
    fun provideNearbyTransport(
        @ApplicationContext context: Context,
        logRepository: LogRepository,
        scope: CoroutineScope
    ): NearbyConnectionsTransport = NearbyConnectionsTransport(context, logRepository, scope)

    @Provides
    @Singleton
    fun provideWifiLanTransport(
        @ApplicationContext context: Context,
        logRepository: LogRepository,
        scope: CoroutineScope
    ): WifiLanTransport = WifiLanTransport(context, logRepository, scope)

    @Provides
    @Singleton
    fun provideWebSocketTransport(
        okHttpClient: OkHttpClient,
        moshi: Moshi,
        logRepository: LogRepository,
        scope: CoroutineScope
    ): WebSocketRelayTransport = WebSocketRelayTransport(okHttpClient, moshi, logRepository, scope)

    @Provides
    @Singleton
    fun provideTransportManager(
        @ApplicationContext context: Context,
        nearby: NearbyConnectionsTransport,
        wifi: WifiLanTransport,
        ws: WebSocketRelayTransport,
        logRepository: LogRepository,
        scope: CoroutineScope
    ): TransportManager = TransportManager(context, nearby, wifi, ws, logRepository, scope)

    @Provides
    @Singleton
    fun provideAudioEngine(
        @ApplicationContext context: Context,
        logRepository: LogRepository,
        scope: CoroutineScope
    ): AudioEngine = AudioEngine(context, logRepository, scope)
}
