package com.wanshi.app

import android.content.Context
import android.content.SharedPreferences

/** 是否开启识别（用户开关，持久化） */
object WatchingState {

    private const val PREF = "wanshi_settings"
    private const val KEY_WATCHING = "watching"

    private var prefs: SharedPreferences? = null

    var enabled: Boolean = false
        private set

    /** 通知监听服务是否已连接（系统绑定成功） */
    @Volatile
    var listenerReady: Boolean = false

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean(KEY_WATCHING, false)
    }

    fun setEnabled(ctx: Context, value: Boolean) {
        init(ctx)
        enabled = value
        prefs?.edit()?.putBoolean(KEY_WATCHING, value)?.apply()
    }
}
