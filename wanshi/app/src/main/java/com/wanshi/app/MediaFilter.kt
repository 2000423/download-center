package com.wanshi.app

import java.util.concurrent.ConcurrentHashMap

/**
 * 识别过滤规则。
 *
 * 万视只关心「视频」，而系统的媒体会话把音乐、播客、有声书也一并暴露出来，
 * 所以需要三道理：
 *   1) 音乐/音频类 App 整包忽略（黑名单 + 包名关键字）；
 *   2) 只有真正在播放（STATE_PLAYING）的会话才允许**新建**记录；
 *   3) 某个 App 只要暴露了媒体会话，就不再采信它的媒体通知——
 *      会话能给出准确的播放状态，通知只能说明「它发过一条媒体通知」，
 *      桌面音乐组件正是靠这一条被误抓的。
 */
object MediaFilter {

    /** 常见音乐 / 音频类应用包名 */
    private val MUSIC_PACKAGES = setOf(
        "com.netease.cloudmusic",              // 网易云音乐
        "com.tencent.qqmusic",                 // QQ 音乐
        "com.kugou.android",                   // 酷狗音乐
        "cn.kuwo.player",                      // 酷我音乐
        "com.luna.music",                      // 汽水音乐
        "com.ximalaya.ting.android",           // 喜马拉雅
        "fm.qingting.qtradio",                 // 蜻蜓 FM
        "com.tencent.karaoke",                 // 全民 K 歌
        "com.changba",                         // 唱吧
        "com.google.android.apps.youtube.music",
        "com.spotify.music",
        "com.apple.android.music",
        "com.amazon.mp3",
        "com.shazam.android",
        "com.dragon.read",                     // 番茄小说（听书）
        "com.tencent.weread",                  // 微信读书（听书）
        "com.duowan.kiwi",                     // 虎牙纯音频场景兜底
        "com.migu.music"
    )

    /** 包名里带这些关键字的，一律按音乐处理 */
    private val MUSIC_KEYWORDS = listOf("music", "qqmusic", "kugou", "kuwo", "ximalaya", "audio")

    /** 视频类关键字：命中这些就放过，避免误杀（如 com.xxx.musicvideo） */
    private val VIDEO_KEYWORDS = listOf("video", "tv", "bili", "douyin", "kuaishou", "youtube")

    /** 本轮扫描中「暴露过媒体会话」的包名，供通知链路判断是否重复采信 */
    private val sessionPackages = ConcurrentHashMap.newKeySet<String>()

    fun isMusic(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (MUSIC_PACKAGES.contains(pkg)) return true
        val lower = pkg.lowercase()
        if (VIDEO_KEYWORDS.any { lower.contains(it) }) return false
        return MUSIC_KEYWORDS.any { lower.contains(it) }
    }

    fun resetSessions() {
        sessionPackages.clear()
    }

    fun markSession(pkg: String?) {
        if (!pkg.isNullOrBlank()) sessionPackages.add(pkg)
    }

    /** 该 App 在最近一次扫描里有媒体会话 → 通知链路不再重复采信 */
    fun hasSession(pkg: String?): Boolean =
        if (pkg.isNullOrBlank()) false else sessionPackages.contains(pkg)
}
