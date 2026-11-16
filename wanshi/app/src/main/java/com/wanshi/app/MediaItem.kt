package com.wanshi.app

/**
 * 一条识别到的播放记录。
 * 只描述「哪个 App、在播什么、播到哪」，不涉及任何视频地址。
 */
data class MediaItem(
    val id: String,
    val title: String,
    val artist: String = "",
    val packageName: String = "",
    val appLabel: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
    val fromNotification: Boolean = false
)
