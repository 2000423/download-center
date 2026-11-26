# 一局之城 · Android 端

横屏单机城建游戏 APK，Web 版游戏（`preview-app.html`）经原生 Android WebView 壳打包。

## 构建

```bash
export JAVA_HOME=<本工具链>/jdk17
export ANDROID_HOME=<本工具链>/sdk
gradle assembleRelease
```

- 产物：`app/build/outputs/apk/release/app-release.apk`，复制为 `files/yijuzhicheng-vX.Y.Z.apk`
- 签名：`keystore/yiju.jks`，alias `yiju`，口令 `yiju2026`，必须保留（覆盖安装依赖同一签名）
- 版本号：`app/build.gradle.kts` 的 `versionCode` / `versionName`

## 结构

- `app/src/main/assets/www/`：游戏本体（`preview-app.html` + `utils/render3d.js` + `assets/sprites/` 30 张 2D 降级贴图），来自微信小程序工程的 Web 版，不要把小程序 `pages/` 代码混进来
- `MainActivity.kt`：WebView 壳，锁横屏、沉浸全屏、DOM storage 开启、加载 `?clean=1`（隐藏说明栏）、仅允许 file:///android_asset 导航
- `screenshot-city.png`：项目页展示图（3D 城市画面）
- `index.html`：下载中心项目页

## 注意

- 游戏画布固定 940×380，勿改 VIEW_W/VIEW_H 常量，否则拾取偏移
- 游戏逻辑内联在 `preview-app.html` 的 IIFE 副本里；改模拟规则需同时改 `utils/sim.js` 与该副本（打包只跑副本）
