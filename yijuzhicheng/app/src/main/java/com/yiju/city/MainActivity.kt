package com.yiju.city

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 一局之城：WebView 壳，加载打包在 assets 里的本地游戏页面。
 * 锁横屏、沉浸全屏、保持屏幕常亮；游戏本身纯离线，不申请任何权限。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var lastBackAt = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式全屏：隐藏状态栏和导航栏，下滑临时唤出
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(web)

        web.setBackgroundColor(0xFF0E1116.toInt())
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true        // 游戏内设置用 localStorage 存偏好
            allowFileAccess = true          // 加载 android_asset 本地资源
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        // 游戏完全自包含：只允许访问应用内资源，外链一律拦截
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                return !(url.scheme == "file" && (url.path ?: "").startsWith("/android_asset/"))
            }
        }
        web.webChromeClient = WebChromeClient()

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            // clean=1：隐藏右侧说明栏，手机上只留游戏画面
            web.loadUrl("file:///android_asset/www/preview-app.html?clean=1")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2000L) {
            finish()
        } else {
            lastBackAt = now
            Toast.makeText(this, "再按一次返回键退出游戏", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
