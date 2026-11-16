package com.wanshi.app

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** 前台下载服务：切走 App 也不会断；任务串行执行，保证进度通知不会被互相覆盖 */
class DownloadService : Service() {

    companion object {
        private const val ACTION_ADD = "com.wanshi.app.action.DL_ADD"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_NAME = "extra_name"

        fun enqueue(context: Context, url: String, name: String) {
            val i = Intent(context, DownloadService::class.java)
                .setAction(ACTION_ADD)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_NAME, name)
            ContextCompat.startForegroundService(context, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger(0)
    private val queueLock = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先落地前台通知：startForegroundService 之后必须尽快 startForeground，否则系统会抛异常杀进程
        startForegroundCompat(NotificationHelper.download(this, "准备中", 0))

        val url = intent?.getStringExtra(EXTRA_URL)
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        if (url.isNullOrBlank()) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val task = DownloadStore.add(name, url)
        active.incrementAndGet()

        scope.launch {
            try {
                queueLock.withLock {
                    DownloadStore.markRunning(task)
                    val outcome = Downloader.download(
                        this@DownloadService,
                        url,
                        name,
                        object : Downloader.Listener {
                            override fun onProgress(pct: Int, note: String) {
                                DownloadStore.progress(task, pct, note)
                                try {
                                    NotificationHelper.updateDownload(
                                        this@DownloadService,
                                        task.name,
                                        pct
                                    )
                                } catch (_: Exception) {
                                }
                            }
                        }
                    )
                    DownloadStore.finish(task, outcome)

                    val detail = if (outcome.ok) {
                        "已保存到 " + outcome.display
                    } else {
                        outcome.error
                    }
                    NotificationHelper.notifyDownloadDone(
                        this@DownloadService,
                        (2000 + task.id).toInt(),
                        task.name,
                        outcome.ok,
                        detail
                    )
                }
            } catch (e: Exception) {
                DownloadStore.finish(task, Downloader.Outcome(false, error = e.message ?: "下载出错"))
            } finally {
                if (active.decrementAndGet() <= 0) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.ID_DOWNLOAD,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.ID_DOWNLOAD, notification)
        }
    }

    private fun stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }
}
