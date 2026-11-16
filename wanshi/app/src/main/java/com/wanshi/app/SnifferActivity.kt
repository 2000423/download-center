package com.wanshi.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * 网页嗅探：内置浏览器加载网页，shouldInterceptRequest 拦下所有资源请求，
 * 命中媒体地址（mp4 / m3u8 / flv / webm…）就收进列表，点一下直接下载。
 *
 * 说明：这条链路只对「网页里播放的视频」有效——只要是在浏览器内核里播的，
 * 地址一定会从这里过一遍。App 内部私有播放器（抖音、拼多多之类）不走网页内核，
 * 依然抓不到，那是系统边界，不是本页的缺陷。
 */
class SnifferActivity : AppCompatActivity() {

    companion object {
        /** 命中即抓取的媒体扩展名；ts/m4s 是 m3u8 的分片，量太大且都跟着 m3u8 走，不单独收 */
        private val MEDIA_RE =
            Regex("\\.(mp4|m3u8|flv|webm|mov|m4v|mkv|avi)([?#].*)?$", RegexOption.IGNORE_CASE)
    }

    private lateinit var web: WebView
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var etUrl: EditText

    /** 抓到的地址，按发现顺序展示 */
    private val captured = java.util.Collections.synchronizedList(ArrayList<String>())
    private val seen = ConcurrentHashMap.newKeySet<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sniffer)

        web = findViewById(R.id.webSniffer)
        list = findViewById(R.id.sniffList)
        empty = findViewById(R.id.sniffEmpty)
        etUrl = findViewById(R.id.etSniffUrl)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                val url = request.url?.toString().orEmpty()
                if (MEDIA_RE.containsMatchIn(url)) {
                    val base = url.substringBefore('#')
                    if (base !in seen) {
                        seen.add(base)
                        synchronized(captured) {
                            captured.add(0, base)
                            if (captured.size > 60) captured.removeAt(captured.size - 1)
                        }
                        runOnUiThread { renderList() }
                    }
                }
                return null // 放行，网页照常播放
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // 地址栏跟着页面跳走，用户不用手动复制
                if (url.startsWith("http")) etUrl.setText(url)
            }
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnGo).setOnClickListener { openInput() }
        findViewById<View>(R.id.btnSniffClear).setOnClickListener {
            synchronized(captured) { captured.clear() }
            seen.clear()
            renderList()
        }
        etUrl.setOnEditorActionListener { _, _, _ -> openInput(); true }

        // 别处带来的链接（分享 / 剪贴板）直接开
        val seed = intent?.getStringExtra("url").orEmpty()
        if (seed.isNotBlank()) {
            etUrl.setText(seed)
            web.loadUrl(Downloader.normalizeUrl(seed) ?: seed)
        } else {
            renderList()
        }
    }

    private fun openInput() {
        val raw = etUrl.text.toString().trim()
        val url = Downloader.normalizeUrl(raw)
        if (url == null) {
            Toast.makeText(this, "请输入有效网址", Toast.LENGTH_SHORT).show()
            return
        }
        web.loadUrl(url)
    }

    private fun renderList() {
        val items = synchronized(captured) { captured.toList() }
        list.removeAllViews()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        for (url in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { enqueue(url) }
            }
            val kind = url.substringBefore('?').substringAfterLast('.', "").lowercase()
            val title = TextView(this).apply {
                text = "[" + kind.uppercase() + "] " + nameOf(url)
                setTextColor(getColor(R.color.accent))
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val full = TextView(this).apply {
                text = url
                setTextColor(getColor(R.color.muted))
                textSize = 11f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            row.addView(title)
            row.addView(full)
            list.addView(row)
        }
    }

    private fun enqueue(url: String) {
        val name = nameOf(url)
        DownloadService.enqueue(this, url, name)
        Toast.makeText(this, "已加入下载列表：" + name, Toast.LENGTH_SHORT).show()
    }

    private fun nameOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val seg = path.substringAfterLast('/')
        return if (seg.isNotBlank() && seg.contains('.')) seg else "video_" + System.currentTimeMillis() % 100000
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
