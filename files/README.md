# files 文件夹 = 安装包本体

这里放的是提供下载的安装包文件（安卓 APK、电脑 EXE 等）。**平时不用改这个文件夹。**

换新安装包的步骤：

1. 点右上角 **Add file → Upload files**，把新的安装包传进来（建议文件名用英文 + 版本号，如 `salary-monitor-v1.2.0.apk`）
2. 回到上一级，打开 `files.json`
3. 把对应条目的 `file` 改成新文件名、`version` 改成新版本号
4. Commit

> 单个文件上限 100 MB，超过的话改用 Release 附件。
