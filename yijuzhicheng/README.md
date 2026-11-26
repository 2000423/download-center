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
- `play/`：iPhone / 网页版（同样的游戏文件，静态托管在 GitHub Pages，入口 `play/?clean=1`），改游戏时与 APK assets 里的副本**同步更新**
- `MainActivity.kt`：WebView 壳，锁横屏、沉浸全屏、DOM storage 开启、加载 `?clean=1`（隐藏说明栏）、仅允许 file:///android_asset 导航
- `screenshot-city.png`：项目页展示图（3D 城市画面）
- `index.html`：下载中心项目页

## 移动端适配（2026-11-26，v0.1.1）

- `preview-app.html` 末尾的 fitStage：clean=1 模式下把固定 960×552 的 `.phone-land` 用 CSS transform 等比缩放进视口，不再滚动；拾取/手势走 getBoundingClientRect，缩放不影响坐标
- 双指缩放 3D 方向修复：`R3.zoomBy(touchGesture.dist/now.dist)`（手指张开 = 拉近）
- viewport 加 `maximum-scale=1, user-scalable=no`，防 iOS Safari 页面级缩放干扰游戏手势

## 注意

- 游戏画布固定 940×380，勿改 VIEW_W/VIEW_H 常量，否则拾取偏移
- 游戏逻辑内联在 `preview-app.html` 的 IIFE 副本里；改模拟规则需同时改 `utils/sim.js` 与该副本（打包只跑副本）
