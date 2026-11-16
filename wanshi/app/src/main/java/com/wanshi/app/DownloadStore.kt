package com.wanshi.app

import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList

/** 下载任务的内存仓库（不持久化，重启后清空） */
object DownloadStore {

    const val QUEUED = 0
    const val RUNNING = 1
    const val DONE = 2
    const val FAILED = 3

    class Task(val id: Long, val name: String, val url: String) {
        @Volatile
        var status: Int = QUEUED

        @Volatile
        var pct: Int = 0

        @Volatile
        var note: String = ""

        @Volatile
        var display: String = ""

        @Volatile
        var error: String = ""

        @Volatile
        var uri: Uri? = null

        @Volatile
        var mime: String = "video/mp4"
    }

    private val items = CopyOnWriteArrayList<Task>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var seq = 0L

    fun observe(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun unobserve(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun list(): List<Task> = items.toList()

    fun add(name: String, url: String): Task {
        seq += 1
        val task = Task(seq, if (name.isBlank()) "视频" else name.trim(), url)
        items.add(0, task)
        while (items.size > 50) {
            items.removeAt(items.size - 1)
        }
        notifyChanged()
        return task
    }

    fun markRunning(task: Task) {
        task.status = RUNNING
        task.note = "正在连接"
        notifyChanged()
    }

    fun progress(task: Task, pct: Int, note: String) {
        task.pct = pct
        task.note = note
        notifyChanged()
    }

    fun finish(task: Task, outcome: Downloader.Outcome) {
        if (outcome.ok) {
            task.status = DONE
            task.pct = 100
            task.display = outcome.display
            task.uri = outcome.uri
            task.mime = outcome.mime
            task.note = "已完成"
        } else {
            task.status = FAILED
            task.error = outcome.error
            task.note = ""
        }
        notifyChanged()
    }

    fun clearFinished() {
        val removed = items.filter { it.status == DONE || it.status == FAILED }
        if (removed.isEmpty()) return
        items.removeAll(removed)
        notifyChanged()
    }

    private fun notifyChanged() {
        for (l in listeners) {
            try {
                l()
            } catch (_: Exception) {
            }
        }
    }
}
