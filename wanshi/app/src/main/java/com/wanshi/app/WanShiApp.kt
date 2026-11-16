package com.wanshi.app

import android.app.Application

class WanShiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WatchingState.init(this)
        MediaRepository.init(this)
        NotificationHelper.createChannels(this)
    }
}
