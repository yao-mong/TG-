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

## Geekgram 测试版 v19（2026-11-21）
- 13.3.3-beta / 73209，包名 com.geekrem.messenger.beta，分支 geekgram-beta（提交 a4387a36）
- 修：登录页「用账号文件登录」看得见但点不动（v18 只抬了位置，没解决层级）
  根因：importAccountView 在 ScrollView 之前 addView，全屏 ScrollView 盖在其上，
       dispatchTouchEvent 从后往前命中 children，触摸全被 ScrollView 拿走
  改法：构造末尾（radialProgressView addView 之后）importAccountView.bringToFront()；
       setClickable/setFocusable(true)，GradientDrawable 圆角底+描边，文字用蓝色 BlueText4
  验证：apktool 解包 → smali_classes3/org/telegram/ui/wf0.smali（LoginActivity）
       14009 行 setClickable、15897 行 bringToFront（iget wf0->d:TextView 之后）
  APK：output/Geekgram-13.3.3-beta-73209.apk，签名 Geekrem 同前，可覆盖安装
- 构建环境恢复（本轮从「之前的文件」取回）：
  ws/geekrem-13、ws/home（SDK+gradle 缓存）；NDK 从 work/ndk27c.zip 解到
  ws/home/sdk/ndk/27.2.12479018（含 build/ 目录，cmake 3.22.1）
  gradle wrapper distributionUrl 指向 ws/gradle-8.13-bin.zip（需放在该路径）
  构建：JAVA_HOME=/opt/deepbot-toolchain/jdk-17.0.21+9 GRADLE_USER_HOME=ws/home
        ANDROID_SDK_ROOT=ws/home/sdk ./gradlew :TMessagesProj_App:assembleAfatRelease

## Geekgram 测试版 v20(2026-11-21)
- 13.3.4-beta / 73219(APP_VERSION_CODE=7321,输出码 *10+9),包名 com.geekrem.messenger.beta,分支 geekgram-beta
- 修1 账号数据导入无效:importUserConfig 原对所有标签调 nextText(),Android prefs 的 int/long/float/boolean
  是 <long name="x" value="0"/> 属性写法 -> END_TAG expected -> 整份解析中断、commit 未执行、一项未写(只写了 .dat)
  改:优先读 value 属性,string/set 才 nextText();<set> 支持 putStringSet;单项失败只 bad++ 不中断;commit 失败提示清数据重导
- 修2 内核端口冲突(7899/9090 bind address already in use):
  ClashApi.API_PORT 保留默认值 + apiPort 可写;ClashCoreManager 加 PORT_FILE("mixed.port")、mixedPort、
  activePort()/portFree()/pickPort()/killStaleCore()(读 /proc/<pid>/cmdline 含 libclashcore 才杀,防误杀)/
  preparePorts();启动前清残留 + 动态挑端口,writeConfig/probe/getStatus/applyPort 全走 activePort()
- 修3 UI 统一 Telegram 原生风格:新增 GeekgramUi.java(Theme 取色 / ActionBar 高 54dp+16sp 标题 / 圆角卡片 12dp /
  16dp 分节 / 蓝色文字按钮),AccountDataImport、AccountDataExport、CorePanel 三页重搭;LoginActivity 入口改文字按钮
- 重启按钮改真重启:AlarmManager(RTC, +800ms)+PendingIntent 拉 LaunchActivity -> finishAffinity()+killProcess
- 构建:NDK 27.2.12479018 缺 build/ 目录,从 /opt/deepbot-toolchain/android-sdk 补回(NDK 完整副本在那里)
- 编译命令同前;BUILD SUCCESSFUL 6m34s;APK 63251114 字节
- 验证:aapt2 badging versionCode 73219 / versionName 13.3.4-beta / arm64-v8a;apksigner 证书 SHA-256
  d221217b39893021519b4efdd7e56c859ac541e80f1629a848543e8286526fd4(与 v19 一致,可覆盖安装)
  dex 内字符串核对:账号配置已写入 / 已选 / 内核 / 订阅 / mixed.port / libclashcore / 先清除应用数据再导入 全部命中
- APK:output/Geekgram-13.3.4-beta-73219.apk + 安装说明

## Geekgram 测试版 v21(2026-11-21)
- 13.3.6-beta / 73239(APP_VERSION_CODE=7323),包名 com.geekrem.messenger.beta,分支 geekgram-beta
- 用户要求:登录页直接显示「用账号文件登录」即可,验证码那几页不用管
- 问题:旧入口是贴在 sizeNotifierFrameLayout 底部的悬浮 TextView(BOTTOM|CENTER,bottomMargin 28+键盘高),
  在代码页与该页文字重叠渲染成一团乱码,且与页面内容抢位置
- 改法:删掉悬浮 TextView 及其 onMeasure 抬升、bringToFront 逻辑;
  在 PhoneView 构造里(手机号输入框之后、同步联系人之前)加实体按钮 fileLoginButton
  (MATCH_PARENT × 48dp、圆角 10dp、windowBackgroundGray 底 + divider 描边、BlueText4 加粗文字)
  + 一行提示 fileLoginHint(「选手机导出的账号文件，直接恢复到已登录」);
  点击统一走新增的 openAccountFileImport();updateColors() 里同步刷新按钮配色(夜间模式不冒白底)
- 构建环境(本轮重建,ws/home 缓存为空):
  local.properties sdk.dir=/opt/deepbot-toolchain/android-sdk + ndk.dir=.../ndk/27.2.12479018(含 build/)
  JAVA_HOME=/opt/deepbot-toolchain/jdk-17.0.21+9 GRADLE_USER_HOME=ws/home
  ANDROID_SDK_ROOT=/opt/deepbot-toolchain/android-sdk ./gradlew :TMessagesProj_App:assembleAfatRelease
  BUILD SUCCESSFUL 17m03s
- 验证:aapt2 badging versionCode 73239 / versionName 13.3.6-beta / arm64-v8a;
  apksigner SHA-256 d221217b39893021519b4efdd7e56c859ac541e80f1629a848543e8286526fd4(同前,可覆盖安装);
  dex 内命中「用账号文件登录」「选手机导出的账号文件，直接恢复到已登录」「打不开导入界面」
- APK:output/Geekgram-13.3.6-beta-73239.apk + 安装说明
- 未在真机运行(沙箱无设备)
