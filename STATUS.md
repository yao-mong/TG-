# Geekgram 工程状态

当前交付：v10（output/Geekgram-13.2.4-arm64-v10.apk）
versionCode 73109 / versionName 13.2.4 / 包名 com.geekrem.messenger
签名 SHA256 d221217b…526fd4（v2 起未变，可覆盖安装）

## v10 修复（「后台发了新版，App 不提示更新」）
- **根因**：后台接口统一返回 `{code,message,data:{...}}`，客户端 `parse()` 直接在**顶层**
  读 `hasUpdate` / `versionCode` / `url`，字段全在 `data` 里 → 永远读不到 → hasUpdate 恒为 false。
  所以不管后台发多少版，App 都不弹、横幅也不出。**跟后台数据无关，是客户端解析写错了。**
- 修复：`parse()` 先取 `data` 对象再读字段；后台 `code!=0` 时抛错提示，
  本地缓存（扁平结构）仍然兼容。
- 顺带两处：
  1. 升级安装后（versionCode 变了）绕过 10 分钟检查间隔，立刻查一次；
  2. 横幅加 `versionCode > 当前` 判断，装上新版后不会残留旧横幅。
- 按用户要求：**服务器地址固定**。`getBaseUrl()` 直接返回 DEFAULT_BASE，不读本地覆盖值；
  设置页去掉「云更新服务器」行，代理页长按改地址的入口和对话框一起删掉。

## v10 已验证
- aapt2 badging：versionCode 73109 / versionName 13.2.4 / arm64-v8a
- 签名指纹与 v9 一致（d2:21:21:7b…:52:6f:d4），可直接覆盖安装
- dex 内含新解析串「服务返回错误 code=」→ 新代码确实打进包里
- 4 个 native so 齐全（libtmessages.49 / libclashcore / libtwidget / liblanguage_id / libdatastore）
- 后台接口：vc=73099 → hasUpdate=true / 73109 / 13.2.4 / force=true；
  vc=73109 → hasUpdate=false
- 直链实测：https://geekgram-dl.web.zaka.live/Geekgram-13.2.4-73109.apk
  md5=a257e0c24b02cb39dec855c947c7a1b3，与本地包一致（63,106,355 字节）
- 注意：latest.apk 走 CDN 有 4 小时缓存，所以新包用**带版本号的文件名**，发版别复用同名文件

## v9 修复（v8 的闪退）
- **根因**：v8 新增的 GeekgramSettingsActivity / GeekgramRevokeActivity **没有写进
  AndroidManifest.xml**。设置页 row 31 点进去 → ActivityNotFoundException → 闪退。
  编译期不报错（代码引用 class 不需要 manifest 声明），所以 v8 能编出来但一点就崩。
- 修复：在 AndroidManifest.xml 里给两个 Activity 补声明
  （theme=Theme.TMessages / configChanges / adjustResize，与 YoyoPanelActivity 同款）。
- 顺带确认：GeekgramRevokeActivity 同样漏注册，点「撤回记录」也会崩，一并补上。
- 云后台：DEFAULT_BASE=https://geekgram-update.pages.dev（未改）；
  后台版本记录已修正为 vc=73099 + 真实直链
  https://geekgram-dl.web.zaka.live/Geekgram-latest.apk（下载站=静态站 geekgram-dl）
- 构建：沙箱 DNS 会抽风，gradle.properties 里加了 systemProp.*.proxyHost=127.0.0.1:19995，
  构建前确认 19995 代理在跑，否则 buildSrc 解析依赖会失败。

## v9 已验证
- aapt2 badging：versionCode 73099 / versionName 13.2.3 / native-code arm64-v8a
- aapt2 xmltree：manifest 内已含 YoyoPanelActivity / GeekgramSettingsActivity /
  GeekgramRevokeActivity 三条
- apksigner：d221217b…526fd4，与 v8 一致
- 后台接口实测：vc=73099 → hasUpdate:false；vc=73089 → hasUpdate:true 指向真实 APK 直链，
  size/sha256/md5 与本地一致
- 直链实测：curl 下载 md5=94d87a57e2a4ae51d3c7fbf98373408a，与本地包一致（63106926 字节）

## v8 新增
- 官方更新横幅彻底移除：BuildVars.CHECK_UPDATES=false；MainTabsActivity 不再
  takeUpdateLayout()，updateLayoutWrapper 里换成 GeekgramUpdateBanner（只有新版本时才 VISIBLE）
- 设置页新增 Geekgram 入口（SettingsActivity row id 31）→ GeekgramSettingsActivity
  （检查更新 / 云更新服务器 / 防撤回开关 / 撤回记录）
- ProxyListActivity 的「检查更新」行隐藏（updateRow=-1）
- 防撤回：MessagesStorage.markMessagesAsDeletedInternal 删除前先把原文抓进
  GeekgramRevokeCache（files/geekgram/revoked.json），GeekgramRevokeActivity 列表查看

## v8 已验证
- :TMessagesProj:compileReleaseJavaWithJavac BUILD SUCCESSFUL
- :TMessagesProj_App:assembleAfatRelease BUILD SUCCESSFUL（4m33s / 3m41s）
- aapt2: versionCode 73089，icon res/BWP.xml
- apksigner: d221217b…526fd4 与 v7 一致
- dex 实测含：geekgram-update.pages.dev / api/v1/update/check / geekgram_revoke_enabled / revoked.json

## v7 新增
- 内置 YOYO 面板：`org.telegram.ui.YoyoPanelActivity`
  - WebView 打开 https://vpn.geeksv.com.cn/login（登录+注册都是面板原流程）
  - 注入脚本钩 XHR / fetch / localStorage / innerText，抓 sub_token
  - 抓到后底部出「导入并连接」→ ClashCoreManager.setSubscription + startAsync
  - ProxyListActivity 新增 yoyoRow 入口
- 云更新：`org.telegram.messenger.GeekgramUpdateChecker`
  - GET {base}/api/v1/update/check?app&vc&channel&abi&id
  - 启动静默检查（6h 一次）+ 代理页「检查更新」行（点击检查 / 长按改地址）
  - 后台地址存 pref `geekgram_update_base`，DEFAULT_BASE 可写死
- 修复 v6「原文+译文」闪退：MessageObject.getEntities() 永不返回 null

## 已验证
- :TMessagesProj_App:assembleAfatRelease BUILD SUCCESSFUL（4m58s）
- aapt2: versionCode 73079，application icon res/BWP.xml
- apksigner: 证书 d221217b…526fd4 与 v6 一致
- dex 实测含：YoyoPanelActivity / Bridge.onSubToken / 注入脚本(classes4) / vpn.geeksv.com.cn
- lib/arm64-v8a/libclashcore.so 64MB 完整

## 构建注意（踩过的坑）
- NDK 27.2.12479018 的 build/ 目录缺失会导致 cmake 找不到 android.toolchain.cmake，
  从 work/ndk27c.zip 里解出 build/ 补回即可
- 必须用完整 JDK（jdk-17.0.21+9 Temurin），jdk17local 那份加密组件被裁，buildSrc 会挂
- 网络走代理时必须显式 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=19995
- 编 App 用 `:TMessagesProj_App:assembleAfatRelease`，全量 assemble 会撞 HockeyApp 的 google-services
