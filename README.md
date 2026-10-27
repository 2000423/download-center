# 应用下载中心

公开的应用发布与下载站点：https://2000423.github.io/download-center/

## 结构

- `index.html` — 下载中心页面（读取 `files.json` 渲染文件卡片）
- `files.json` — 文件清单：名称、版本、大小、说明、下载路径等展示属性
- `files/` — 安装包本体（APK / EXE）

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
