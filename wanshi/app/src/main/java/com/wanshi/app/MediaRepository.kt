package com.wanshi.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 识别记录仓库：内存 + 本地持久化（最多 60 条），对外只暴露只读快照。
 *
 * 写入方有两个线程（会话轮询协程 + 通知监听 binder 线程），读方是 UI 线程，
 * 所以内部所有对 items 的访问都走 lock，回调与落盘放在锁外，避免持锁回调界面。
 */
object MediaRepository {

    private const val PREF = "wanshi_media"
    private const val KEY_ITEMS = "items"
    private const val MAX = 60

    private val items = LinkedHashMap<String, MediaItem>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private var context: Context? = null
    private var currentId: String? = null

    fun init(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        load()
    }

    fun observe(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun unobserve(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 正在播放的排在最前，其余按最近识别时间倒序 */
    fun snapshot(): List<MediaItem> = synchronized(lock) {
        items.values.sortedWith(
            compareByDescending<MediaItem> { it.playing }.thenByDescending { it.updatedAt }
        )
    }

    fun currentPlaying(): MediaItem? = synchronized(lock) { currentId?.let { items[it] } }

    fun contains(id: String): Boolean = synchronized(lock) { items.containsKey(id) }

    fun upsert(item: MediaItem) {
        if (item.title.isBlank()) return
        val changed = synchronized(lock) { applyUpsert(item) }
        if (changed) {
            notifyChanged()
            persist()
        }
    }

    fun remove(id: String) {
        val changed = synchronized(lock) {
            val had = items.remove(id) != null
            if (had && currentId == id) currentId = null
            had
        }
        if (changed) {
            notifyChanged()
            persist()
        }
    }

    fun clear() {
        val changed = synchronized(lock) {
            if (items.isEmpty()) {
                false
            } else {
                items.clear()
                currentId = null
                true
            }
        }
        if (changed) {
            notifyChanged()
            persist()
        }
    }

    private fun applyUpsert(item: MediaItem): Boolean {
        var changed = false
        val old = items[item.id]

        if (old == null) {
            items[item.id] = item
            if (item.playing) currentId = item.id
            changed = true
            if (items.size > MAX) {
                val it2 = items.keys.iterator()
                while (items.size > MAX && it2.hasNext()) {
                    val key = it2.next()
                    if (key != currentId) it2.remove()
                }
            }
        } else {
            val same = old.playing == item.playing &&
                old.title == item.title &&
                old.artist == item.artist &&
                old.durationMs == item.durationMs
            // 保留首次识别时间，避免列表顺序每次轮询都乱跳
            items[item.id] = item.copy(updatedAt = old.updatedAt)
            if (item.playing) {
                if (currentId != item.id) {
                    currentId = item.id
                    changed = true
                }
            } else if (currentId == item.id) {
                currentId = null
                changed = true
            }
            if (!same) changed = true
        }
        return changed
    }

    private fun notifyChanged() {
        for (l in listeners) {
            try {
                l()
            } catch (_: Exception) {
            }
        }
    }

    private fun load() {
        val ctx = context ?: return
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return
        try {
            val arr = JSONArray(raw)
            synchronized(lock) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    items[id] = MediaItem(
                        id = id,
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        packageName = o.optString("packageName"),
                        appLabel = o.optString("appLabel"),
                        playing = false,
                        positionMs = 0L,
                        durationMs = o.optLong("durationMs"),
                        updatedAt = o.optLong("updatedAt"),
                        fromNotification = o.optBoolean("fromNotification")
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun persist() {
        val ctx = context ?: return
        val text = synchronized(lock) {
            val arr = JSONArray()
            for (m in items.values) {
                arr.put(
                    JSONObject().apply {
                        put("id", m.id)
                        put("title", m.title)
                        put("artist", m.artist)
                        put("packageName", m.packageName)
                        put("appLabel", m.appLabel)
                        put("durationMs", m.durationMs)
                        put("updatedAt", m.updatedAt)
                        put("fromNotification", m.fromNotification)
                    }
                )
            }
            arr.toString()
        }
        io.execute {
            try {
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_ITEMS, text)
                    .apply()
            } catch (_: Exception) {
            }
        }
    }
}
