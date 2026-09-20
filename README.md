# 牛影随看

连接现有飞牛影视的 Android 竖屏视频客户端。默认完整显示画面，可切换铺满；上下滑切换片库视频。

当前交付为可安装调试版。13 项自动测试通过，已在 NAS Android 13 容器验证登录、片库、滑动换片和实际影片播放；实体手机及完整生命周期验收仍待完成，整体状态为 PARTIAL。

## 安装与使用

1. 将 `dist/fnvideo-v1.0.1-2-debug.apk` 传到 Android 8.0 或以上手机并安装。
2. 手机连接能访问 NAS 的 Wi-Fi，打开应用，点击登录。
3. 默认服务地址是 `http://192.0.2.1:5666`，使用飞牛官方页面登录。
4. 上滑下一条，下滑上一条；点击画面暂停/播放；拖动进度；“完整/铺满”切换显示方式。
5. 顶部可切换媒体库、搜索全部片库、修改服务器或退出登录。

应用不内置账号或登录令牌。电脑浏览器的登录不会自动迁移到手机，需要在手机再登录一次。

## 设计

先阅读 [架构设计](docs/ARCHITECTURE.md)。服务地址默认 `http://192.0.2.1:5666`，可在登录页修改。手机必须能访问 NAS 服务。

采用原生 Java、ViewPager2 和 Media3 ExoPlayer。官方登录页仅用于登录；媒体目录和播放由独立适配器接入，APK 不包含账号或令牌。

## 开发

Android SDK / Build Tools 35、JDK 17 或兼容版本、Gradle 8.9、AGP 8.7.3、Media3 1.5.1。Windows 上可先运行 `tools/setup-android.ps1` 准备本地工具（需要有效 JAVA_HOME 或已安装 JDK），再构建：

```powershell
.\tools\build.ps1 -Task testDebugUnitTest,assembleDebug,lintDebug
```

包含 `assembleDebug` 的构建脚本调用成功后，自动从 APK 元数据读取版本，导出 `dist/fnvideo-v<versionName>-<versionCode>-debug.apk`。每次交付递增 `app/build.gradle` 的 `versionCode` 并更新 `versionName`，不交付无版本号文件。调试 APK 原始输出仍为 `app/build/outputs/apk/debug/app-debug.apk`。LAN 签名检查可在构建前设置当前进程的 `FNVIDEO_TEST_SERVER=http://192.0.2.1:5666`；该检查不携带凭据，不读取私人片库。

测试和 lint 详情见 [验证记录](docs/VERIFICATION.md)，任务状态见 [实施计划](docs/PLAN.md)。

## 验证边界

飞牛网页端和 NAS Android 容器均已实际播放普通电影。容器不支持原始 HEVC 时，可自动尝试一次 H.264/AAC 兼容流。服务的非公开 API 可能随升级变化。应用不会直接读取 NAS 数据库，也不会修改媒体文件。

当前按电影、普通视频和可播放分集构建 Feed；没有季/剧目录浏览器。搜索使用飞牛现有全局搜索结果，尚无搜索翻页。网盘链接、转码、字幕和不同手机的解码兼容性需实测；不承诺任意片源都可播放。播放位置保存于本机，不同步写回飞牛观看记录。
