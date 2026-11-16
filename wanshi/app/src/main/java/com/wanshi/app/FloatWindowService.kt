package com.wanshi.app

import android.annotation.SuppressLint
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * 悬浮球：一个可以拖动的小圆点，点开是一个面板，面板里只有三件事——
 * 返回万视、开始/暂停识别、看识别到的视频（不带下载入口）。
 *
 * 用前台服务承载，否则切到别的 App 后进程很快会被回收，悬浮球就没了。
 */
class FloatWindowService : Service() {

    companion object {
        private const val ACTION_SHOW = "com.wanshi.app.action.FLOAT_SHOW"
        private const val ACTION_STOP = "com.wanshi.app.action.FLOAT_STOP"

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, FloatWindowService::class.java).setAction(ACTION_SHOW)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, FloatWindowService::class.java).setAction(ACTION_STOP)
            try {
                ctx.startService(i)
            } catch (_: Exception) {
            }
        }

        fun canShow(ctx: Context): Boolean =
            try {
                Settings.canDrawOverlays(ctx)
            } catch (_: Exception) {
                false
            }
    }

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelVisible = false
    private val main = Handler(Looper.getMainLooper())

    private val repoListener: () -> Unit = { main.post { refreshList() } }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        NotificationHelper.createChannels(this)
        WatchingState.init(this)
        MediaRepository.init(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_STOP) {
            teardown()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (!canShow(this)) {
            Toast.makeText(this, "没有悬浮窗权限，悬浮球无法显示", Toast.LENGTH_SHORT).show()
            teardown()
            return START_NOT_STICKY
        }
        if (bubble == null) addBubble()
        MediaRepository.observe(repoListener)
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        removeViews()
        running = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val n = NotificationHelper.float(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NotificationHelper.ID_FLOAT,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NotificationHelper.ID_FLOAT, n)
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ 悬浮球

    private fun addBubble() {
        val v = LayoutInflater.from(this).inflate(R.layout.float_bubble, null)
        val dm = resources.displayMetrics
        val size = dp(54)
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - size - dp(6)
            y = dm.heightPixels / 3
        }
        attachDrag(v, lp)
        try {
            wm.addView(v, lp)
        } catch (_: Exception) {
            teardown()
            return
        }
        bubble = v
        bubbleParams = lp
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: View, lp: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downRawX).toInt()
                    val dy = (e.rawY - downRawY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    try {
                        wm.updateViewLayout(v, lp)
                    } catch (_: Exception) {
                    }
                    // 面板开着时实时跟着球走
                    if (panelVisible) positionPanel()
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                }
            }
            true
        }
    }

    // ------------------------------------------------------------ 面板

    private fun togglePanel() {
        if (panelVisible) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (panel != null) return
        val v = LayoutInflater.from(this).inflate(R.layout.float_panel, null)
        v.findViewById<TextView>(R.id.btnFloatClose).setOnClickListener { hidePanel() }
        v.findViewById<TextView>(R.id.btnFloatBack).setOnClickListener {
            hidePanel()
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                )
            } catch (_: Exception) {
            }
        }
        v.findViewById<TextView>(R.id.btnFloatToggle).setOnClickListener { toggleWatch() }

        val dm = resources.displayMetrics
        val margin = dp(12)
        // 面板宽度固定 304dp，窄屏收缩；高度交给系统量
        val panelW = minOf(dp(304), dm.widthPixels - margin * 2)
        val lp = WindowManager.LayoutParams(
            panelW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = margin
            y = margin
        }
        try {
            wm.addView(v, lp)
        } catch (_: Exception) {
            return
        }
        panel = v
        panelParams = lp
        panelVisible = true

        // 高度量出来后贴到悬浮球旁边；之后拖动悬浮球时由 positionPanel() 实时跟随
        v.post { positionPanel() }
        refreshList()
    }

    /**
     * 把面板摆在悬浮球旁边（球下方优先，放不下翻到球上方），左右对齐气泡右缘。
     * 拖动悬浮球时每帧调用，面板跟着球走，且永远不压住球。
     */
    private fun positionPanel() {
        val p = panel ?: return
        val lp = panelParams ?: return
        val bp = bubbleParams ?: return
        val v = bubble ?: return
        val dm = resources.displayMetrics
        val margin = dp(12)
        val gap = dp(10)
        val pw = lp.width
        // 面板还没量出高度时先给个估计值，量出来后下一帧自然修正
        val ph = if (p.height > 0) p.height else dp(420)

        var x = bp.x + dp(54) - pw
        if (x < margin) x = margin
        if (x + pw > dm.widthPixels - margin) x = dm.widthPixels - margin - pw

        var y = bp.y + dp(54) + gap
        if (y + ph > dm.heightPixels - margin) y = bp.y - ph - gap
        if (y < margin) y = margin

        if (lp.x != x || lp.y != y) {
            lp.x = x
            lp.y = y
            try {
                wm.updateViewLayout(p, lp)
            } catch (_: Exception) {
            }
        }
        // 顺带把球也夹在屏幕内，别拖出屏幕找不回来
        val size = dp(54)
        val bx = bp.x.coerceIn(margin / 2, dm.widthPixels - size - margin / 2)
        val by = bp.y.coerceIn(margin / 2, dm.heightPixels - size - margin / 2)
        if (bx != bp.x || by != bp.y) {
            bp.x = bx
            bp.y = by
            try {
                wm.updateViewLayout(v, bp)
            } catch (_: Exception) {
            }
        }
    }

    private fun hidePanel() {
        val p = panel ?: return
        panel = null
        panelParams = null
        panelVisible = false
        try {
            wm.removeView(p)
        } catch (_: Exception) {
        }
    }

    private fun refreshList() {
        val p = panel ?: return
        val list = p.findViewById<LinearLayout>(R.id.floatList) ?: return
        val empty = p.findViewById<TextView>(R.id.floatEmpty)
        val items = MediaRepository.snapshot().take(12)

        list.removeAllViews()
        empty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val title = TextView(this).apply {
                text = item.title
                setTextColor(getColor(R.color.text))
                textSize = 13f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val meta = TextView(this).apply {
                val app = if (item.appLabel.isNotBlank()) item.appLabel else item.packageName
                text = app + if (item.playing) " · 播放中" else " · 已暂停"
                setTextColor(getColor(R.color.muted))
                textSize = 11f
            }
            row.addView(title)
            row.addView(meta)
            list.addView(row)
        }

        p.findViewById<TextView>(R.id.btnFloatToggle)?.text =
            if (WatchingState.enabled) "结束识别" else "开始识别"
    }

    // ------------------------------------------------------------ 开关

    private fun toggleWatch() {
        if (!hasListenerAccess()) {
            Toast.makeText(this, "请先在万视里开启「通知使用权」", Toast.LENGTH_SHORT).show()
            return
        }
        val next = !WatchingState.enabled
        WatchingState.setEnabled(this, next)
        if (next) MediaWatchService.start(this) else MediaWatchService.stop(this)
        Toast.makeText(this, if (next) "已开始识别" else "已结束识别", Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun hasListenerAccess(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val target = ComponentName(this, SessionListenerService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    // ------------------------------------------------------------ 收尾

    private fun teardown() {
        MediaRepository.unobserve(repoListener)
        removeViews()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        running = false
        stopSelf()
    }

    private fun removeViews() {
        hidePanel()
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubble = null
        bubbleParams = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
