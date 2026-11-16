package com.wanshi.app

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/**
 * 下载引擎：
 * - 直链（mp4/webm/mov...）边下边写；
 * - m3u8 播放列表：自动选最高码率，按顺序把所有分片拼成一个 .ts 文件。
 * 文件统一落到 手机存储/Download/万视/。
 */
object Downloader {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val TIMEOUT = 20000
    private const val FOLDER = "万视"

    interface Listener {
        fun onProgress(pct: Int, note: String)
    }

    class Outcome(
        val ok: Boolean,
        val display: String = "",
        val uri: Uri? = null,
        val mime: String = "",
        val error: String = ""
    )

    fun download(
        context: Context,
        rawUrl: String,
        preferredName: String,
        listener: Listener
    ): Outcome {
        val url = normalizeUrl(rawUrl) ?: return Outcome(false, error = "链接格式不正确")
        return try {
            val conn = connect(url)
            val type = conn.contentType?.lowercase().orEmpty()
            val isHls = url.contains(".m3u8", true) ||
                type.contains("mpegurl") ||
                type.contains("m3u8")
            if (isHls) {
                conn.disconnect()
                downloadHls(context, url, preferredName, listener)
            } else {
                downloadDirect(context, conn, url, preferredName, listener)
            }
        } catch (e: Exception) {
            Outcome(false, error = readable(e))
        }
    }

    // ---------------------------------------------------------------- 直链

    private fun downloadDirect(
        context: Context,
        conn: HttpURLConnection,
        url: String,
        preferredName: String,
        listener: Listener
    ): Outcome {
        val rawType = conn.contentType?.lowercase().orEmpty()
        if (rawType.startsWith("text/html")) {
            conn.disconnect()
            return Outcome(false, error = "这个链接返回的是网页，不是视频文件")
        }

        val total = conn.contentLengthLong
        val mime = mimeOf(conn.contentType, url)
        val ext = extFor(mime, url)
        val name = buildName(preferredName, url, ext)
        val sink = openSink(context, name, mime)
        if (sink == null) {
            conn.disconnect()
            return Outcome(false, error = "无法创建文件，请检查存储权限")
        }

        try {
            BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
                BufferedOutputStream(sink.stream, 64 * 1024).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        done += read
                        val pct = if (total > 0L) ((done * 100L) / total).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            val note = if (total > 0L) {
                                Fmt.size(done) + " / " + Fmt.size(total)
                            } else {
                                Fmt.size(done)
                            }
                            listener.onProgress(pct, note)
                        }
                    }
                    out.flush()
                    if (done <= 0L) throw IllegalStateException("下载到的内容为空")
                }
            }
        } catch (e: Exception) {
            closeSink(sink, false)
            conn.disconnect()
            return Outcome(false, error = readable(e))
        }
        conn.disconnect()
        closeSink(sink, true)
        return Outcome(true, sink.display, sink.uri, mime)
    }

    // ---------------------------------------------------------------- HLS

    private fun downloadHls(
        context: Context,
        playlistUrl: String,
        preferredName: String,
        listener: Listener
    ): Outcome {
        var text = fetchText(playlistUrl)
        var baseUrl = playlistUrl

        if (!text.contains("#EXTM3U")) {
            return Outcome(false, error = "这不是有效的 m3u8 播放列表")
        }

        // 主播放列表：挑码率最高的一路
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variants = ArrayList<Pair<Long, String>>()
            val lines = text.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)")
                        .find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) {
                        j++
                    }
                    if (j < lines.size) {
                        variants.add(bw to resolve(baseUrl, lines[j].trim()))
                    }
                    i = j
                }
                i++
            }
            val best = variants.maxByOrNull { it.first }
                ?: return Outcome(false, error = "无法解析播放列表")
            baseUrl = best.second
            text = fetchText(baseUrl)
            listener.onProgress(0, "已选择清晰度，开始下载分片")
        }

        if (text.contains("METHOD=AES-128") || text.contains("METHOD=SAMPLE-AES")) {
            return Outcome(false, error = "该视频是加密流（AES），暂不支持保存")
        }

        val segments = ArrayList<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            segments.add(resolve(baseUrl, line))
        }
        if (segments.isEmpty()) {
            return Outcome(false, error = "播放列表里没有找到视频分片")
        }

        val mime = "video/mp2t"
        val name = buildName(preferredName, playlistUrl, ".ts")
        val sink = openSink(context, name, mime)
            ?: return Outcome(false, error = "无法创建文件，请检查存储权限")

        try {
            BufferedOutputStream(sink.stream, 64 * 1024).use { out ->
                val buf = ByteArray(64 * 1024)
                for ((index, segUrl) in segments.withIndex()) {
                    val conn = connect(segUrl)
                    try {
                        conn.inputStream.use { input ->
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    val pct = ((index + 1) * 100) / segments.size
                    listener.onProgress(pct, "分片 " + (index + 1) + "/" + segments.size)
                }
                out.flush()
            }
        } catch (e: Exception) {
            closeSink(sink, false)
            return Outcome(false, error = readable(e))
        }
        closeSink(sink, true)
        return Outcome(true, sink.display, sink.uri, mime)
    }

    // ---------------------------------------------------------------- 网络

    private fun connect(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = TIMEOUT
        conn.readTimeout = TIMEOUT
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        val code = conn.responseCode
        if (code >= 400) {
            conn.disconnect()
            throw IllegalStateException("服务器返回 " + code)
        }
        return conn
    }

    private fun fetchText(url: String): String {
        val conn = connect(url)
        return try {
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(ref).toString()
    } catch (e: Exception) {
        ref
    }

    // ---------------------------------------------------------------- 文件

    private class Sink(
        val stream: OutputStream,
        val display: String,
        val uri: Uri?,
        val resolver: ContentResolver?,
        val pendingUri: Uri?,
        val file: File?
    )

    private fun openSink(context: Context, name: String, mime: String): Sink? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                val out = context.contentResolver.openOutputStream(uri) ?: return null
                Sink(
                    out,
                    "下载/" + FOLDER + "/" + name,
                    uri,
                    context.contentResolver,
                    uri,
                    null
                )
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FOLDER
                )
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, name)
                // 必须先拿到 FileProvider Uri 再开流，避免 FileUriExposedException
                val uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    f
                )
                val out = FileOutputStream(f)
                Sink(out, f.absolutePath, uri, null, null, f)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun closeSink(sink: Sink, success: Boolean) {
        try {
            sink.stream.close()
        } catch (_: Exception) {
        }
        val resolver = sink.resolver
        val uri = sink.pendingUri
        if (resolver != null && uri != null) {
            try {
                if (success) {
                    val cv = ContentValues()
                    cv.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, cv, null, null)
                } else {
                    resolver.delete(uri, null, null)
                }
            } catch (_: Exception) {
            }
        }
        if (!success) {
            val f = sink.file
            if (f != null) {
                try {
                    if (f.exists()) f.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 从一段分享文案里抠出链接；没有协议头也能补全 */
    fun normalizeUrl(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        val found = Regex("https?://[^\\s\"'<>]+").find(s)
        if (found != null) {
            s = found.value
        } else if (!s.startsWith("http://") && !s.startsWith("https://")) {
            if (s.contains(' ') || !s.contains('.')) return null
            s = "https://" + s
        }
        return s
    }

    private fun mimeOf(contentType: String?, url: String): String {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (ct.contains('/') && ct != "application/octet-stream") return ct
        return when {
            url.contains(".m3u8", true) -> "video/mp2t"
            url.contains(".webm", true) -> "video/webm"
            url.contains(".mov", true) -> "video/quicktime"
            url.contains(".m4v", true) -> "video/x-m4v"
            else -> "video/mp4"
        }
    }

    private fun extFor(mime: String, url: String): String = when {
        mime.contains("mp4") -> ".mp4"
        mime.contains("webm") -> ".webm"
        mime.contains("quicktime") -> ".mov"
        mime.contains("mp2t") -> ".ts"
        mime.contains("mpegurl") -> ".ts"
        mime.contains("x-m4v") -> ".m4v"
        mime.startsWith("audio/") -> ".m4a"
        url.contains(".m3u8", true) -> ".ts"
        url.contains(".webm", true) -> ".webm"
        url.contains(".mov", true) -> ".mov"
        url.contains(".m4v", true) -> ".m4v"
        else -> ".mp4"
    }

    private fun buildName(preferred: String, url: String, ext: String): String {
        var base = preferred.trim()
        if (base.isEmpty()) {
            base = try {
                val path = URI(url).path.orEmpty()
                URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
            } catch (e: Exception) {
                ""
            }
            base = base.substringBefore('?').substringBeforeLast('.')
        }
        base = base.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ").trim()
        if (base.length > 60) base = base.substring(0, 60)
        if (base.isEmpty()) base = "video_" + System.currentTimeMillis()
        if (!base.lowercase(Locale.US).endsWith(ext.lowercase(Locale.US))) {
            base += ext
        }
        return base
    }

    private fun readable(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "连接超时，换个网络或稍后再试"
        is java.net.UnknownHostException -> "无法解析域名，请检查网络"
        is java.net.MalformedURLException -> "链接格式不正确"
        is java.net.SocketException -> "网络中断"
        is javax.net.ssl.SSLException -> "证书校验失败，链接可能不安全"
        is IllegalStateException -> e.message ?: "下载失败"
        else -> e.message ?: e.javaClass.simpleName
    }
}
