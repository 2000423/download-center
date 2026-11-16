package com.wanshi.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {

    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val mdhm = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /** 1234567 -> 20:34 / 1:02:34 */
    fun duration(ms: Long): String {
        if (ms <= 0L) return ""
        val total = ms / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** 今天显示 HH:mm，否则 MM-dd HH:mm */
    fun time(ts: Long): String {
        if (ts <= 0L) return ""
        val now = Calendar.getInstance()
        val then = Calendar.getInstance()
        then.timeInMillis = ts
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val d = Date(ts)
        return if (sameDay) hm.format(d) else mdhm.format(d)
    }

    fun size(bytes: Long): String =
        if (bytes >= 1048576L) String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}
