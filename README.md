# 应用下载中心

公开的应用发布与下载站点：https://2000423.github.io/download-center/

## 结构

- `index.html` — 下载中心页面（读取 `files.json` 渲染文件卡片）
- `files.json` — 文件清单：名称、版本、大小、说明、下载路径等展示属性
- `files/` — 安装包本体（APK / EXE）
- `announcements.json` — **App 内公告数据源**（见下）

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
- 只改公告**不需要**改动 App 版本号，也不会触发「发现新版本」提示
- 若 JSON 写错（少逗号等），App 会自动回退到内置公告，不会白屏；改好后重新提交即可

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
