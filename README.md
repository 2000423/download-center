# 应用下载中心

公开的应用发布与下载站点：https://2000423.github.io/download-center/

## 结构

- `index.html` — 下载中心页面（读取 `files.json` 渲染文件卡片）
- `files.json` — 文件清单：名称、版本、大小、说明、下载路径等展示属性
- `files/` — 安装包本体（APK / EXE）
- `announcements.json` — **App 内公告数据源**（见下）
- `app/` — **工资监视器 App 本体**（PWA，GitHub Pages 直接托管）

## App 在哪、怎么改

- 访问地址：https://2000423.github.io/download-center/app/
- 改代码：编辑 `app/index.html` → Commit → 约 1~2 分钟自动生效，**不需要任何构建或部署命令**
- 更新是全自动的：App 每次打开都会走网络优先拿最新页面，所以改完代码用户下次打开就是新版，没有任何版本号要维护
- 改公告：编辑根目录 `announcements.json`（见下节）
- 唯一需要手动改的：`app/sw.js` 里的 `CACHE` 版本号（如 `salary-monitor-v15` → `v16`）。只有当你改了 **sw.js 本身** 或 `icons/`、`manifest.webmanifest` 这些被缓存的静态资源时才需要 +1；只改 HTML 不用动它

> 注意：HTTPS + 无访问码，任何拿到链接的人都能打开；数据只存在各自手机本地，无后端。

## 发布 / 修改 App 公告（手机也能改）

1. 打开 `announcements.json`，点右上角铅笔图标编辑
2. 在 `announcements` 数组**最前面**插入一条，例如：

```json
{
  "id": "notice-20261027-1",
  "title": "公告标题",
  "date": "2026-10-27",
  "pinned": false,
  "content": "第一段。\n\n第二段。换行请写 \\n，不要直接按回车。"
}
```

3. 拉到下方点 **Commit changes** 提交，约 1~2 分钟后 App 端自动生效

**规则**

- 首页公告条显示数组**第 1 条**，所以新公告要放最前面
- `id` 必须唯一，是"已读"标记的依据；重复用旧 id 老用户不会看到红点，反过来改 id 能让已读用户重新看到红点
- `pinned: true` 只是列表里的「置顶」标签，不影响排序
- 数组写成 `[]` 表示清空所有公告
- 只改公告**不需要**改动任何版本号
- 若 JSON 写错（少逗号等），App 会继续显示上一次成功拉到的公告，不会白屏；改好后重新提交即可

## 如何新增 / 修改一个文件

1. 把安装包放进 `files/` 目录（建议英文文件名，如 `my-app-v1.2.0.exe`）
2. 编辑 `files.json`，在 `files` 数组中新增或修改条目：

```json
{
  "id": "my-app",
  "name": "我的应用",
  "type": "exe",            // apk 或 exe
  "version": "1.2.0",
  "size": "5.2 MB",
  "date": "2026-10-27",
  "tags": ["Windows", "工具"],
  "desc": "应用介绍",
  "file": "files/my-app-v1.2.0.exe",
  "install": "下载后安装说明（可选）"
}
```

3. 提交推送后，GitHub Pages 自动更新（约 1 分钟生效）

> 单文件上限 100MB（GitHub 硬性限制），超过请使用 Release 附件并在 `file` 字段填 Release 下载链接。
