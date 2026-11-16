package com.wanshi.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: DetectAdapter
    private lateinit var rv: RecyclerView
    private lateinit var emptyView: View
    private lateinit var permBanner: View
    private lateinit var dot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvNow: TextView
    private lateinit var tvCount: TextView
    private lateinit var btnToggle: TextView

    private val repoListener: () -> Unit = { runOnUiThread { render() } }

    private val askNotiPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val askStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WatchingState.init(this)
        MediaRepository.init(this)
        NotificationHelper.createChannels(this)

        rv = findViewById(R.id.rv)
        emptyView = findViewById(R.id.emptyView)
        permBanner = findViewById(R.id.permBanner)
        dot = findViewById(R.id.dot)
        tvStatus = findViewById(R.id.tvStatus)
        tvNow = findViewById(R.id.tvNow)
        tvCount = findViewById(R.id.tvCount)
        btnToggle = findViewById(R.id.btnToggle)

        adapter = DetectAdapter { item, action -> onItemAction(item, action) }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnToggle.setOnClickListener { toggleWatch() }
        findViewById<View>(R.id.btnGrant).setOnClickListener { promptAccess() }
        findViewById<View>(R.id.btnClear).setOnClickListener { confirmClear() }
        findViewById<View>(R.id.btnDownloads).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        findViewById<View>(R.id.btnSaveLink).setOnClickListener {
            showSaveDialog("", "")
        }

        askNotificationPermission()
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        MediaRepository.observe(repoListener)
    }

    override fun onStop() {
        MediaRepository.unobserve(repoListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    // ------------------------------------------------------------ 渲染

    private fun render() {
        val access = hasListenerAccess()
        permBanner.visibility = if (access) View.GONE else View.VISIBLE

        val items = MediaRepository.snapshot()
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        tvCount.text = if (items.isEmpty()) "" else items.size.toString()

        val watching = WatchingState.enabled && access
        dot.setBackgroundResource(if (watching) R.drawable.dot_on else R.drawable.dot_off)
        tvStatus.text = when {
            !access -> "等待授权"
            WatchingState.enabled -> "识别中"
            else -> "已停止"
        }

        val now = MediaRepository.currentPlaying()
        tvNow.text = if (now != null) "正在播放 · " + now.title else "暂无播放中的视频"
        btnToggle.text = if (WatchingState.enabled) "停止识别" else "开始识别"
    }

    // ------------------------------------------------------------ 权限

    private fun hasListenerAccess(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val target = ComponentName(this, SessionListenerService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    private fun openListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun promptAccess() {
        AlertDialog.Builder(this)
            .setTitle("开启「通知使用权」")
            .setMessage(
                "1. 点下面的「去设置」\n" +
                    "2. 在列表里找到「万视」并打开开关\n" +
                    "3. 弹窗提示「允许读取通知」时选允许\n\n" +
                    "万视只用它读取正在播放的内容标题，不保存、不上传任何通知正文。"
            )
            .setPositiveButton("去设置") { _, _ -> openListenerSettings() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) askNotiPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Android 9 及以下写公共下载目录需要存储权限
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) askStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // ------------------------------------------------------------ 开关

    private fun toggleWatch() {
        if (!hasListenerAccess()) {
            promptAccess()
            return
        }
        val next = !WatchingState.enabled
        WatchingState.setEnabled(this, next)
        if (next) {
            MediaWatchService.start(this)
            Toast.makeText(this, "已开始识别，可以切到别的 App 看视频了", Toast.LENGTH_SHORT).show()
        } else {
            MediaWatchService.stop(this)
            Toast.makeText(this, "已停止识别", Toast.LENGTH_SHORT).show()
        }
        render()
    }

    // ------------------------------------------------------------ 列表操作

    private fun onItemAction(item: MediaItem, action: Int) {
        when (action) {
            DetectAdapter.ACTION_COPY -> {
                copyToClipboard(item.title)
                Toast.makeText(this, "标题已复制，可去搜索原视频", Toast.LENGTH_SHORT).show()
            }
            else -> showSaveDialog("", item.title, item.packageName)
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("title", text))
        } catch (_: Exception) {
        }
    }

    private fun confirmClear() {
        if (MediaRepository.snapshot().isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("清空识别记录？")
            .setMessage("只清除本机的识别历史，正在播放的会在下次识别时重新出现。")
            .setPositiveButton("清空") { _, _ ->
                MediaRepository.clear()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 保存

    private fun showSaveDialog(url: String, name: String, fromPackage: String? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_save, null)
        val etUrl = view.findViewById<EditText>(R.id.etUrl)
        val etName = view.findViewById<EditText>(R.id.etName)

        val startUrl = if (url.isNotBlank()) url else clipboardUrl()
        etUrl.setText(startUrl)
        etName.setText(name)
        if (startUrl.isNotBlank()) etName.requestFocus()

        val dialog = AlertDialog.Builder(this)
            .setTitle("保存到本地")
            .setView(view)
            .setPositiveButton("开始下载", null)
            .setNegativeButton("取消", null)
            .create()

        // 从识别记录进来时，给一个「跳回原 App 复制链接」的入口
        if (!fromPackage.isNullOrBlank() && fromPackage != packageName) {
            val launch = packageManager.getLaunchIntentForPackage(fromPackage)
            if (launch != null) {
                dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "打开原 App") { _, _ ->
                    try {
                        startActivity(launch)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = Downloader.normalizeUrl(etUrl.text.toString())
                if (normalized == null) {
                    etUrl.error = "请粘贴有效的视频链接"
                } else {
                    DownloadService.enqueue(this, normalized, etName.text.toString().trim())
                    dialog.dismiss()
                    Toast.makeText(this, "已加入下载列表", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, DownloadsActivity::class.java))
                }
            }
        }
        dialog.show()
    }

    private fun clipboardUrl(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount <= 0) return ""
            val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
            Downloader.normalizeUrl(text) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------ 分享进入

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        if (!type.startsWith("text/")) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = Downloader.normalizeUrl(shared)
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "分享的内容里没有找到视频链接", Toast.LENGTH_SHORT).show()
            return
        }
        rv.post {
            showSaveDialog(url, "")
        }
        // 标记已消费，避免旋转屏幕/重建时重复弹窗
        intent.action = null
    }
}
