package com.network.messenger

import android.app.Application
import com.google.crypto.tink.config.TinkConfig
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NetworkMessengerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { TinkConfig.register() }
    }
}
