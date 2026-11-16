# 万视 · WanShi

挂在后台，自动识别你正在看的视频；需要保存时，把视频链接交给它下载到本地。

> 原生 Android（Kotlin）源码工程，用 Android Studio 打开即可编译成 APK。

---

## 一、它能做什么

| 能力 | 说明 |
| --- | --- |
| 后台识别 | 前台服务 + `MediaSessionManager` 每 1.5 秒读一次系统媒体会话，拿到「哪个 App、在播什么、是否正在播放、时长」 |
| 通知嗅探 | `NotificationListenerService` 兜底：不暴露 MediaSession 的 App，只要有播放通知也能识别到标题 |
| 记录列表 | 识别到的视频按「播放中 → 最近识别」排序，最多留 60 条，重启 App 仍在 |
| 保存到本地 | 直链（mp4 / webm / mov / m4v）流式下载；m3u8 播放列表自动选最高码率、按序合并分片为 `.ts` |
| 三种给链接的方式 | ① 其它 App「分享 → 万视」② 复制链接后回到万视，弹窗自动带入 ③ 手动粘贴 |
| 文件位置 | `手机存储/Download/万视/`，完成通知点一下即可播放 |

## 二、它做不到什么（重要，别被忽悠）

1. **不能凭空拿到视频地址。** 系统只提供「标题 / 播放状态」，不提供 URL。抖音、B 站、腾讯视频等平台的真实播放地址是 App 内部私有接口 + 签名防盗链，第三方 App 无法旁路获取。所以「保存」这一步需要你从播放 App 里**分享/复制链接**给它。
2. **加密流下不了。** 遇到 `#EXT-X-KEY:METHOD=AES-128` 的分片流会直接提示「加密流暂不支持」。
3. **不碰 DRM。** 受 Widevine 等保护的内容一律无法保存。
4. **不抓屏、不录屏、不读通知正文。** 只取媒体通知里的标题与副标题，用于识别「在播什么」，全部数据只留在本机。

## 三、编译

环境要求：

- Android Studio **Koala (2024.1)** 或更新（自带 JDK 17）
- Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24（工程里已配好）
- minSdk 26（Android 8.0），targetSdk 34

步骤：

1. Android Studio → `Open` → 选择本目录 `wanshi/`
2. 首次 Sync 会按 `gradle/wrapper/gradle-wrapper.properties` 自动下载 Gradle 8.7
3. 连接手机（开 USB 调试）→ 点 ▶ Run
4. 要装到别的手机：`Build → Build Bundle(s)/APK(s) → Build APK(s)`，产物在 `app/build/outputs/apk/debug/app-debug.apk`

> 想用命令行编译的话，先执行一次 `gradle wrapper` 生成 `gradlew` 与 `gradle-wrapper.jar`（本工程未附带该二进制文件）。

## 四、首次使用

1. 打开万视 → 横幅提示「还差一步：通知使用权」→ 点 **去授权**
2. 在系统列表里找到 **万视** 并打开开关 → 弹窗选 **允许**
3. 回到万视，点右上 **开始识别**（会有一条常驻通知，属于正常现象）
4. 去任意 App 播视频，回来就能看到记录

Android 13+ 首次启动还会申请通知权限，用于显示识别状态与下载进度，建议允许。

## 五、保存的三种姿势

- **分享法（最省事）**：在播放 App 里点「分享 → 复制链接」或直接分享到「万视」，会自动弹出保存窗口
- **剪贴板法**：复制链接后打开万视，点底部「＋ 粘贴链接保存」，链接已自动填好
- **手动法**：直接粘贴任意直链 / m3u8

文件名可以自己起，留空则按链接自动生成。分片流合并出来是 `.ts`（TS 是标准封装，主流播放器都能直接播；需要 mp4 可自行转封装）。

## 六、工程结构

```
wanshi/
├── build.gradle.kts                 根构建脚本（AGP / Kotlin 版本）
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml      权限、服务、分享入口、FileProvider
        ├── java/com/wanshi/app/
        │   ├── WanShiApp.kt                 初始化
        │   ├── MainActivity.kt              首页：状态、识别列表、保存弹窗
        │   ├── DownloadsActivity.kt         下载列表：进度、打开文件
        │   ├── MediaWatchService.kt         前台服务：轮询媒体会话
        │   ├── SessionListenerService.kt    通知使用权 + 媒体通知兜底
        │   ├── DownloadService.kt           前台下载服务
        │   ├── Downloader.kt                直链 / m3u8 下载与合并
        │   ├── MediaRepository.kt           识别记录仓库（带本地持久化）
        │   ├── DownloadStore.kt             下载任务内存队列
        │   ├── MediaItem.kt / Fmt.kt / WatchingState.kt
        │   ├── DetectAdapter.kt / DownloadAdapter.kt
        │   └── NotificationHelper.kt
        └── res/
            ├── layout/                      activity_main / activity_downloads / item_* / dialog_save
            ├── drawable/                    卡片、胶囊按钮、状态点、图标
            ├── values/                      colors / themes / strings
            ├── xml/file_paths.xml           FileProvider 路径（Android 9 及以下分享文件用）
            └── mipmap-anydpi-v26/           自适应图标
```

## 七、几个实现要点

- **为什么需要「通知使用权」**：`MediaSessionManager.getActiveSessions()` 只有在调用方持有通知使用权（或系统签名）时才可调用。这是系统给的唯一官方入口，不是偷权限。
- **两条识别链路**：会话轮询负责精确状态（播放/暂停、时长、进度），通知兜底负责覆盖面。两者用 `包名|标题` 去重。
- **文件落盘**：Android 10+ 走 `MediaStore.Downloads` + `IS_PENDING`（写完才可见，避免半截文件）；Android 8/9 直接写公共下载目录并用 `FileProvider` 授权播放。
- **断点续传**：当前版本未实现，中断即失败重来（README 的「待做」里有记录）。

## 八、可以继续加的东西

- 断点续传（`Range` 请求 + `.part` 临时文件）
- 后台剪贴板监听自动识别复制到的视频链接
- 分片下载后调用 `MediaMuxer`/ffmpeg 转封装成 mp4
- 识别记录按 App 分组、搜索
- 下载任务持久化，被杀进程后恢复
