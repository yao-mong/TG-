# Geekgram 工程状态

当前交付：v13（output/Geekgram-13.2.7-arm64-v13.apk）
versionCode 73149 / versionName 13.2.7 / 包名 com.geekrem.messenger
签名 SHA256 d221217b…526fd4（v2 起未变，可覆盖安装）

## v13 修复（用户反馈两条 + 上一轮未完成的收尾）

### 1. 内置内核启动不了 / 面板打开白屏
- **根因 A（内核）**：v11 把 `useLegacyPackaging` 改成 false（原生库不压缩、直接从 APK 映射），
  启动崩溃是修好了，但 nativeLibraryDir 里就没有真实文件了 —— 内核是要 exec 的独立可执行文件，
  映射进来的 .so 没有执行权限，所以「内核启动不了 / 内核没解出来」。
- **根因 B（面板）**：面板整页是网页渲染，网络一断就一片白；拉订阅那步被掐（connection abort）。
- 修复：
  - `useLegacyPackaging` 改回 true（内核要被解压成可执行文件）；
  - `ClashCoreManager` 重写：订阅下载接 DoH 兜底（GeekgramNet）、本地缓存兜底（刷新失败用旧缓存照跑）、
    内核二进制多路径查找、日志落 core.log 并在面板里可看；
  - 订阅支持离线导入：节点链接（ss/vmess/vless/trojan/hysteria2）、整份 YAML、剪贴板一键（NodeLinkParser）；
  - 面板换成原生界面 `CorePanelActivity`（不再依赖网页），已补 AndroidManifest 注册（v8 闪退教训）。

### 2. 撤回消息在时间旁边显示「已删除」
- 新增 `GeekgramRevokeMarks`：撤回标记集合，持久化在全局设置（`geekgram_revoked_marks`），重启还在；
- `GeekgramRevokeCache.filterDeletable / filterForUi` 在拦下删除时打标记；
- `ChatActivity` 收到 messagesDeleted 后把保留下来的消息 `deleted` 状态清掉（否则不绘制），
  并排一次 UI 队列把删除流程后置的 `deleted=true` 再压回去；
- `ChatMessageCell` 在时间字符串前拼「已删除 」（红色/灰色时间样式不变），
  `drawTimeInternal` 对打了标记的消息照常绘制时间。

## v13 已验证
- aapt2 badging：versionCode 73149 / versionName 13.2.7 / arm64-v8a
- apksigner：SHA-256 d221217b…526fd4，与 v12 一致，可覆盖安装
- dex 实测含 `geekgram_revoked_marks`、`已删除`（classes3.dex）、`CorePanelActivity`（清单已注册）
- 包体 63MB（原生库恢复压缩，v12 是 121MB）
- 未在真机运行（沙箱无设备）：内核/面板/撤回标记都只做了静态验证

## v12 修复（继承）

### 1. 后台删了版本，App 仍弹「必须更新」
- **根因**：启动时 `checkOnStart()` 先读本地 `PREF_INFO` 缓存，缓存里的 `force/hasUpdate`
  是上一次的记录，直接拿去弹强制框；后台删版本不会通知手机，所以删了照样拦人。
  实测云端正返回 `hasUpdate:false`（最新 73109），手机还在弹 = 纯粹本地缓存。
- 修复：
  - 启动时缓存只用于横幅，强制更新必须等这次联网校验回来再弹；
  - 检查间隔 10 分钟 → 60 秒（重开 App 基本都会重新问一次）；
  - 云端返回 `hasUpdate=false` / 版本 <= 当前 → 清缓存 + 清 force 标记 + 关掉正在挡路的弹窗；
  - 缓存加时间戳 `PREF_INFO_AT`，超过 24h 或没有时间戳（旧版遗留）一律作废；
  - 强制弹窗加「重新检查」按钮。

### 2. 防撤回无效
- **根因**：撤回走 `updateDeleteMessages` → `markMessagesAsDeleted(dialogId=0, ...)`，
  而 `capture()` 里 `if (dialogId == 0) return;` 直接跳过 → 既没记录，消息也照删。
- 修复：
  - `capture()` 支持 dialogId=0（按 mid 全局查，uid 从行里取）；
  - 新增 `filterDeletable`：存储层把「对方发来的」(out=0) 消息从待删列表剔除，不删库；
  - 就地过滤 messages 列表，让调用方的删除通知也拿不到这些 id；
  - `ChatActivity` 收到 `messagesDeleted` 时再过滤一遍（`filterForUi`），界面上的消息原地保留；
  - 自己发的不受保护（out=1 照删），超过 300 条的大批量（清空记录）不拦。

## v12 已验证
- aapt2 badging：versionCode 73139 / versionName 13.2.6 / arm64-v8a
- 签名 SHA256 D2:21:21:7B…52:6F:D4，与 v11 一致
- 原生库 Stored（不压缩），libtmessages.49.so / libclashcore.so 都在
- dex 实测含 `geekgram_update_info_at`、「重新检查」等新串
- 云端实测：vc=73119 → hasUpdate=false；vc=73139 → hasUpdate=false

## v11 说明（继承）
- 原生库改「不压缩 + 直接从安装包映射」，绕开覆盖安装后解压副本不完整导致的启动崩
- 包体 121MB（库不压缩的代价）；后台最新版本号小于 73119，不会反向提示更新

## v10 修复（继承）
- 后台接口统一 `{code,data}`，客户端 parse 先取 data 层，否则 hasUpdate 恒 false
- 升级安装后（versionCode 变了）绕过检查间隔，立刻查一次
- 服务器地址固定 `https://geekgram-update.pages.dev`

## 构建注意（踩过的坑）
- NDK 27.2.12479018 的 build/ 目录缺失会导致 cmake 找不到 android.toolchain.cmake，
  从 work/ndk27c.zip 里解出 `android-ndk-r27c/build/*` 补回即可
- 必须用完整 JDK（jdk-17.0.21+9 Temurin），jdk17local 那份加密组件被裁，buildSrc 会挂
- 编 App 用 `:TMessagesProj_App:assembleAfatRelease`，全量 assemble 会撞 HockeyApp 的 google-services
- gradle 离线模式会缺 AGP 缓存，直接联网编
