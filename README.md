# 牛影随看

连接现有飞牛影视的 Android 竖屏视频客户端。默认完整显示画面，可切换铺满；上下滑切换片库视频。

应用使用原生登录、局域网影视库发现和单实例播放器。构建与发布检查见 GitHub Actions；真实设备上的登录、发现和覆盖升级需单独验收。

## 安装与使用

1. 从 GitHub Releases 下载所需版本的 APK，在 Android 8.0 或以上手机安装。
2. 手机连接能访问 NAS 的 Wi-Fi，打开应用，点击登录。
3. 登录页自动查找同一局域网影视库，也可填写 IP、IP:端口或完整 URL；输入用户名与密码后连接。省略协议和端口时默认 HTTP / 5666。
4. 上下滑动换片，点击画面暂停/播放；横向或进度条拖动时显示目标时间 / 总时长，长按临时 2 倍速。
5. 顶部可切换媒体库、搜索全部片库、修改服务器或退出登录。

应用不内置账号或登录令牌。电脑浏览器的登录不会自动迁移到手机，需要在手机再登录一次。

## 设计

先阅读 [架构设计](docs/ARCHITECTURE.md)。服务地址可在登录页配置，手机必须能访问对应服务。

采用原生 Java、ViewPager2 和 Media3 ExoPlayer。原生表单通过 FnApi 调用影视登录接口；媒体目录和播放由独立适配器接入，APK 不包含账号或令牌。

## 开发

Android SDK / Build Tools 35、JDK 17 或兼容版本、Gradle 8.9、AGP 8.7.3、Media3 1.5.1。Windows 上可先运行 `tools/setup-android.ps1` 准备本地工具（需要有效 JAVA_HOME 或已安装 JDK），再构建：

```powershell
.\tools\build.ps1 -Task testDebugUnitTest,assembleDebug,lintDebug
```

包含 `assembleDebug` 的构建脚本调用成功后，自动从 APK 元数据读取版本，导出 `dist/fnvideo-v<versionName>-<versionCode>-debug.apk`。每次交付递增 `app/build.gradle` 的 `versionCode` 并更新 `versionName`，不交付无版本号文件。调试 APK 原始输出仍为 `app/build/outputs/apk/debug/app-debug.apk`。LAN 签名检查可在构建前设置当前进程的 `FNVIDEO_TEST_SERVER`；该检查不携带凭据，不读取私人片库。

测试和 lint 详情见 [验证记录](docs/VERIFICATION.md)，任务状态见 [实施计划](docs/PLAN.md)。

## 验证边界

飞牛网页端和 NAS Android 容器均已实际播放普通电影。容器不支持原始 HEVC 时，可自动尝试一次 H.264/AAC 兼容流。服务的非公开 API 可能随升级变化。应用不会直接读取 NAS 数据库，也不会修改媒体文件。

当前按电影、普通视频和可播放分集构建 Feed；可从分集的“剧集”入口浏览剧集目录。搜索使用飞牛现有全局搜索结果，尚无搜索翻页。网盘链接、转码、字幕和不同手机的解码兼容性需实测；不承诺任意片源都可播放。播放位置保存于本机，不同步写回飞牛观看记录。

## 登录与更新

局域网发现仅探测当前 Wi-Fi/有线私有 IPv4 网段中的最多一个 /24 范围，检查 5666/80 端口的影视页面，不发送凭据；非标准端口、其他网段或仅 IPv6 服务请手动填写地址。发现设备后仍由用户选择再登录。密码不会保存到本地或 Activity 状态；HTTPS 证书必须有效，登录请求不跟随重定向。

发布包使用固定签名，后续版本递增后可覆盖安装；旧版临时签名包无法保证直接升级。不要重新生成发布密钥。签名机制与首次迁移边界见 [APK 签名与覆盖升级](docs/APK_SIGNING.md)。
