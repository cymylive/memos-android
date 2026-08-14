# OwnDiary（原 Memos 离线版）

> 一款完全离线的安卓备忘录应用，数据 100% 保存在本机，支持局域网分享访问。

[![Release](https://img.shields.io/github/v/release/cymylive/memos-android?label=最新版本&style=flat-square&color=blue)](https://github.com/cymylive/memos-android/releases/latest)
[![License](https://img.shields.io/badge/许可证-MIT-green?style=flat-square)](LICENSE)
[![下载](https://img.shields.io/badge/⬇-下载%20APK-orange?style=flat-square)](https://github.com/cymylive/memos-android/releases/latest)

OwnDiary 基于开源项目 [Memos](https://github.com/usememos/memos) 构建，去掉服务器部署环节，直接以安卓应用的形式运行：打开即用，无需注册，无需联网，所有笔记、图片、数据都存在你的手机里。

## ✨ 功能

- **完全离线** — 无需任何账号和网络服务，断网也能正常使用
- **本地数据** — 笔记、附件、数据库全部保存在应用私有目录，可随时一键备份/恢复
- **局域网共享** — 同一 WiFi 下，手机/电脑浏览器输入地址即可访问你的日记
- **界面中文** — 默认中文界面，内置霞鹜文楷等清新字体，也可导入你喜欢的字体
- **自定义端口** — 服务端口可自由修改（默认 8081）
- **Markdown 原生** — 完整的 Markdown 编辑体验，支持图片、附件、标签、关系引用

## 📥 下载安装

1. 前往 [Releases 页面](https://github.com/cymylive/memos-android/releases/latest) 下载 `app-release.apk`
2. 安装到安卓手机（需要允许"安装未知来源应用"）
3. 打开即用，无需任何配置

> 升级版本时若提示"签名不一致无法覆盖安装"，请先备份数据，卸载旧版后重新安装。

## 📖 使用说明

### 局域网访问

打开应用后，顶部菜单可查看本机 IP，同一 WiFi 下的设备在浏览器输入 `http://<手机IP>:8081` 即可访问。

### 更换端口

右上角菜单 → **设置端口**，修改后服务自动重启（范围 1-65535）。

### 界面字体

右上角菜单 → **界面字体**：

- **系统默认** — 使用系统默认字体
- **霞鹜文楷** — 清新手写风格（内置两款字重）
- **自定义字体** — 导入本机 TTF/OTF/WOFF/WOFF2 字体文件（单个不超过 50MB）

### 备份与恢复

右上角菜单 → **备份数据**：生成 zip 备份文件保存到任意位置。
右上角菜单 → **恢复数据**：选择备份文件，一键还原全部笔记数据。

### 停止共享

右上角菜单 → **停止共享**：关闭局域网服务并退出应用。

## 🔗 相关链接

- [GitHub Releases](https://github.com/cymylive/memos-android/releases) — 下载与版本历史
- [Issues](https://github.com/cymylive/memos-android/issues) — 反馈问题与建议
- [Memos 上游项目](https://github.com/usememos/memos) — 本项目的基础开源项目

## 📜 许可

- 应用代码基于 [MIT License](LICENSE)
- 内置字体 [霞鹜文楷（LXGW WenKai）](https://github.com/lxgw/LxgwWenKai) v1.522，遵循 SIL Open Font License 1.1，可免费商用
