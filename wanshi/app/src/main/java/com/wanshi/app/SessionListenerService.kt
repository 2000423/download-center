package com.wanshi.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * 只做两件事：
 * 1) 让本 App 拥有「通知使用权」，从而可以调用 MediaSessionManager.getActiveSessions()；
 * 2) 从媒体类通知里补一条识别记录（有些 App 不暴露 MediaSession，但会发播放通知）。
 *
 * 不读取、不保存任何通知正文内容，只取标题/副标题用于识别「在播什么」。
 */
class SessionListenerService : NotificationListenerService() {

    companion object {
        private val labelCache = ConcurrentHashMap<String, String>()
    }

    override fun onCreate() {
        super.onCreate()
        WatchingState.init(this)
        MediaRepository.init(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        WatchingState.init(this)
        MediaRepository.init(this)
        WatchingState.listenerReady = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        WatchingState.listenerReady = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn0 = sbn ?: return
        if (!WatchingState.enabled) return

        val pkg = sbn0.packageName ?: return
        if (pkg == packageName) return

        val n = sbn0.notification ?: return
        val extras = n.extras ?: return

        val template = extras.getCharSequence(Notification.EXTRA_TEMPLATE)?.toString().orEmpty()
        val isMedia = n.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            template.contains("MediaStyle", true)
        if (!isMedia) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (title.isEmpty()) return

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val artist = if (sub.isNotEmpty()) sub else text

        // 如果会话轮询已经记过同一条（id 前缀 s|），就不再用通知兜底，避免重复
        if (MediaRepository.contains("s|" + pkg + "|" + title)) return

        MediaRepository.upsert(
            MediaItem(
                id = "n|" + pkg + "|" + title,
                title = title,
                artist = artist,
                packageName = pkg,
                appLabel = labelOf(pkg),
                // 通知只能说明「这个 App 有媒体通知」，无法确认播放/暂停，所以不标记为播放中
                playing = false,
                updatedAt = System.currentTimeMillis(),
                fromNotification = true
            )
        )
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
