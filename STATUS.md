# Geekgram 工程状态

当前交付：v9（output/Geekgram-13.2.3-arm64-v9.apk）
versionCode 73099 / versionName 13.2.3 / 包名 com.geekrem.messenger
签名 SHA256 d221217b…526fd4（v2 起未变，可覆盖安装）

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
