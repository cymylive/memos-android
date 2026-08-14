# 架构与开发文档

OwnDiary（原 Memos 离线版）是一个"离线优先"的安卓备忘录应用：内置完整的 Memos 服务端（Go），
通过 WebView 加载其官方 Web 前端，数据 100% 存储在本机，可一键备份/恢复，并支持局域网访问。

## 目录结构

```
android/            Android 壳应用（Kotlin + WebView）
  app/src/main/java/com/usememos/mobile/
    MainActivity.kt   服务启动、端口/字体设置、备份/恢复入口
    ZipUtils.kt       备份打包 / 恢复解压（zip4j，ZIP64）
    MemosService.kt   前台服务（局域网共享）
  app/libs/mobile.aar  由 Go 层 gomobile bind 构建，运行时替换
server/            内嵌的 Memos 服务端（Go，fork 自 usememos/memos）
  router/fileserver/  静态文件与附件服务（含大文件上传端点）
  router/api/v1/      业务 API（256MB 请求体上限所在层）
mobile/             gomobile bind 入口（编译成 mobile.aar 供 Android 调用）
web/               Memos 官方 Web 前端（React + Vite，构建产物打进 assets）
```

## 大文件上传管线（v0.32.0）

Memos 原版附件走 JSON/base64 上传，服务端对请求体有 256MB 硬上限
（`server/router/api/v1/v1.go` 的 `MaxAPIRequestBytes`），且解码过程整体驻留内存，
实际只能上传约 190MB 的附件。v0.32.0 引入了第二条上传通道，彻底移除该限制：

### 双轨上传（前端 `web/src/components/MemoEditor/services/uploadService.ts`）

| 附件大小 | 通道 | 行为 |
|----------|------|------|
| < 32MB | 原 JSON/base64 通道 | 保留 EXIF 剥离与动图（motion）检测 |
| ≥ 32MB | 新流式通道 | multipart 流式直落磁盘，不做 EXIF/动图处理 |

阈值常量：`STREAMING_UPLOAD_THRESHOLD`（32MB）。

### 流式上传端点

```
POST /upload/attachments
Content-Type: multipart/form-data   (file 字段)
```

- 实现：`server/router/fileserver/upload_attachment.go`
- 独立前缀 `/upload/attachments`，不在 `/api/v1/*`、`/file/*` 之下，因此不受
  `MaxAPIRequestBytes`（256MB）限制
- 服务端用 `r.MultipartReader()` 手动流式解析（安卓环境没有可靠的系统临时目录），
  数据先写 `Profile.Data/.upload_tmp/`，完成后再原子改名到最终位置
- 防呆上限：1TiB（`maxUploadBytes`），超出即拒绝并清理
- 文件名校验：仅允许普通文件名（拒绝绝对路径、`..`、控制字符），MIME 归一化
- 落盘位置：`assets/{timestamp}_{uid}_{filename}`，写入 attachment 表时
  `storage_type = LOCAL`、`reference = 相对数据目录的路径`、`blob = NULL`

### 附件读取

读取端（`server/router/fileserver/fileserver.go`）原生支持 LOCAL 附件：
- 播放地址格式 `/file/attachments/{uid}/{filename}`
- 大文件视频经 `http.ServeFile` 走 HTTP Range 流式播放，可拖动进度条，内存恒定

### 孤儿清理

服务启动时（`RegisterRoutes` 内 goroutine）扫描 `assets/` 下所有无数据库记录的
LOCAL 引用文件并删除，同时清空残留的 `.upload_tmp/` 临时目录。

## 备份 / 恢复（v0.32.0 起基于 zip4j）

原实现用 JDK `ZipOutputStream`，单个文件超过 4GB 会写出非法 zip，导致大备份不可用。
v0.32.0 迁移到 [zip4j](https://github.com/srikanth-lingala/zip4j)（`net.lingala.zip4j:zip4j:2.1.1`）：

- **ZIP64**：单文件与总包大小上限提升至 16EB（实际只受磁盘空间约束）
- **存储策略**（`android/app/src/main/java/com/usememos/mobile/ZipUtils.kt`）：
  - ≥ 50MB 或媒体扩展名（视频/音频）的条目 → `STORE`（免压缩，接近拷贝速度；
    写入前先流式预计算 CRC，zip4j 的 STORE 条目必须预置 size/CRC）
  - 其余文件 → `DEFLATE` 常规压缩
- **恢复流程**（`MainActivity.restoreFromUri`）：
  1. 用户选择的备份文件先流式复制到应用缓存目录（zip4j 读取需要随机访问中央目录）
  2. 解压到 `restore_tmp/`（解压前按全部条目的解压总大小做磁盘空间预检，预留 10% 余量）
  3. 校验数据库文件 `memos_prod.db`（应用固定以 prod 模式运行，见
     `server/internal/profile/profile.go` 的 `memos_<mode>.db` 命名规则）非空
  4. 数据目录原子交换（`filesDir → restore_old`，`restore_tmp → filesDir`），失败自动回滚
  5. 清理临时文件
- **安全**：保留 zip-slip 路径穿越防护（逐条校验解压目标必须在数据目录内）；
  备份包加密与否均可被正确恢复，旧版（zip32）备份同样兼容

## 本地构建

需要：JDK 17、Android SDK、Node.js 18+、Go 1.22+、gomobile。

```bash
# 1. 前端产物（web/dist 会打进 APK）
cd web && pnpm install && pnpm build

# 2. Go 服务端测试与 AAR
go build ./server/... && go test ./server/...
cd mobile && gomobile bind -target=android -o ../android/app/libs/mobile.aar ./server

# 3. Android 打包（需配置签名，见 .github/workflows/release-apk.yml）
cd android && ./gradlew assembleRelease
```

发布流程：推送 `v*` 标签后，GitHub Actions（**Release Android APK**）自动完成
mobile.aar 构建 → Kotlin 编译 → 签名 → 发布 Release。

## 关键版本记录

| 版本 | 说明 |
|------|------|
| v0.31.0 | 自定义端口、界面字体（内置 + 导入）、OwnDiary 品牌化、图片拖动修复 |
| v0.31.1 | 备份竞态修复（并发触发） |
| v0.31.2 | 恢复校验修正（`memos_prod.db`）、备份/恢复防重入 |
| v0.32.0 | 大文件支持：流式上传 + 本地存储 + ZIP64 备份/恢复 |
