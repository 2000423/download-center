package com.wanshi.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {

    const val CH_WATCH = "wanshi_watch"
    const val CH_DOWNLOAD = "wanshi_download"
    const val CH_FLOAT = "wanshi_float"

    const val ID_WATCH = 1101
    const val ID_DOWNLOAD = 1201
    const val ID_FLOAT = 1301

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = nm(ctx)
        if (nm.getNotificationChannel(CH_WATCH) == null) {
            val ch = NotificationChannel(CH_WATCH, "识别服务", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.description = "后台识别前台播放的视频时显示的常驻通知"
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CH_DOWNLOAD) == null) {
            val ch = NotificationChannel(CH_DOWNLOAD, "下载进度", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.description = "视频保存到本地时的进度通知"
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CH_FLOAT) == null) {
            val ch = NotificationChannel(CH_FLOAT, "悬浮球", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            ch.description = "悬浮球开启时的常驻通知，可随时关闭"
            nm.createNotificationChannel(ch)
        }
    }

    private fun nm(ctx: Context): NotificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun contentIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            ctx, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun watch(ctx: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat_wanshi)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(ctx))
            .build()

    fun updateWatch(ctx: Context, title: String, text: String) {
        try {
            nm(ctx).notify(ID_WATCH, watch(ctx, title, text))
        } catch (_: Exception) {
        }
    }

    /** 悬浮球服务的前台通知（尽量安静，只占一个位置） */
    fun float(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_FLOAT)
            .setSmallIcon(R.drawable.ic_stat_wanshi)
            .setContentTitle("万视 · 悬浮球已开启")
            .setContentText("点悬浮球可暂停识别或回到万视")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(contentIntent(ctx))
            .build()

    fun download(ctx: Context, name: String, pct: Int): Notification {
        val b = NotificationCompat.Builder(ctx, CH_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat_wanshi)
            .setContentTitle("正在保存：" + name.take(20))
            .setContentText("万视 · 后台下载中 " + pct + "%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, pct.coerceIn(0, 100), pct <= 0)
            .setContentIntent(contentIntent(ctx))
        return b.build()
    }

    fun updateDownload(ctx: Context, name: String, pct: Int) {
        try {
            nm(ctx).notify(ID_DOWNLOAD, download(ctx, name, pct))
        } catch (_: Exception) {
        }
    }

    fun notifyDownloadDone(ctx: Context, notifId: Int, name: String, ok: Boolean, detail: String) {
        val b = NotificationCompat.Builder(ctx, CH_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat_wanshi)
            .setContentTitle(if (ok) "已保存：" + name.take(20) else "保存失败：" + name.take(20))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setContentIntent(contentIntent(ctx))
        try {
            nm(ctx).notify(notifId, b.build())
        } catch (_: Exception) {
        }
    }
}
