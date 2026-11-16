package com.wanshi.app

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 常驻前台服务：每 1.5 秒读一次系统的媒体会话列表，
 * 拿到「哪个 App 正在播什么」，写入识别记录并在通知栏显示。
 */
class MediaWatchService : Service() {

    companion object {
        private const val ACTION_START = "com.wanshi.app.action.WATCH_START"
        private const val ACTION_STOP = "com.wanshi.app.action.WATCH_STOP"
        private const val INTERVAL_MS = 1500L

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, MediaWatchService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, MediaWatchService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(i)
            } catch (_: Exception) {
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val labelCache = ConcurrentHashMap<String, String>()
    private var job: Job? = null
    private lateinit var sessionManager: MediaSessionManager
    private var lastNotice: String = ""
    private var foregroundStarted: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 用户主动停止，或开关已被关掉（含系统重启服务时 intent 为 null 的情况）
        if ((intent != null && intent.action == ACTION_STOP) || !WatchingState.enabled) {
            stopEverything()
            return START_NOT_STICKY
        }

        val notification = NotificationHelper.watch(this, "万视 · 后台识别中", "正在识别前台播放的视频")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.ID_WATCH,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.ID_WATCH, notification)
        }
        foregroundStarted = true

        running = true
        if (job == null) {
            job = scope.launch {
                while (isActive) {
                    try {
                        scanOnce()
                    } catch (_: Exception) {
                    }
                    delay(INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        running = false
        super.onDestroy()
    }

    private fun stopEverything() {
        job?.cancel()
        job = null
        running = false
        if (foregroundStarted) {
            foregroundStarted = false
            stopForegroundCompat()
        }
        stopSelf()
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    private fun scanOnce() {
        val component = ComponentName(this, SessionListenerService::class.java)
        val sessions = try {
            sessionManager.getActiveSessions(component)
        } catch (e: SecurityException) {
            // 通知使用权被撤销了：关掉开关、停服务，别空转
            WatchingState.setEnabled(this, false)
            stopEverything()
            return
        } catch (e: Exception) {
            return
        }

        val now = System.currentTimeMillis()
        var playingTitle: String? = null
        var playingApp: String? = null

        // 每一轮重新统计「哪些 App 暴露了媒体会话」，通知链路据此避免重复采信
        MediaFilter.resetSessions()

        for (controller in sessions) {
            val pkg = controller.packageName ?: continue
            MediaFilter.markSession(pkg)

            // 音乐 / 音频类应用整体忽略：万视只关心视频
            if (MediaFilter.isMusic(pkg)) continue

            val md = controller.metadata ?: continue
            val title = (
                md.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                )?.trim().orEmpty()
            if (title.isEmpty()) continue

            val label = labelOf(pkg)
            val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
            val isPlaying = state == PlaybackState.STATE_PLAYING
            val artist = (
                md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                )?.trim().orEmpty()
            val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
            val position = controller.playbackState?.position ?: 0L

            val id = "s|" + pkg + "|" + title
            // 没在播放、且历史上也没记录过 → 直接跳过。
            // 这样「桌面上摆着的播放控件」不会被当成正在看的视频抓进来。
            if (!isPlaying && !MediaRepository.contains(id)) continue

            MediaRepository.upsert(
                MediaItem(
                    id = id,
                    title = title,
                    artist = artist,
                    packageName = pkg,
                    appLabel = label,
                    playing = isPlaying,
                    positionMs = if (position > 0L) position else 0L,
                    durationMs = if (duration > 0L) duration else 0L,
                    updatedAt = now
                )
            )

            if (isPlaying && playingTitle == null) {
                playingTitle = title
                playingApp = label
            }
        }

        val notice = if (playingTitle.isNullOrEmpty()) {
            "正在识别前台播放的视频"
        } else {
            "正在播放：" + playingTitle + "（" + playingApp + "）"
        }
        if (notice != lastNotice) {
            lastNotice = notice
            NotificationHelper.updateWatch(this, "万视 · 后台识别中", notice)
        }
    }

    private fun labelOf(pkg: String): String {
        labelCache[pkg]?.let { return it }
        val label = try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg
        }
        labelCache[pkg] = label
        return label
    }
}
