# 验证记录

日期：2026-09-20。整体状态 **PARTIAL**：NAS Android 13 已安装新版，真实登录、片库、滑动换片及原生影片播放已验证；实体手机与完整生命周期验收仍未完成。

工作目录 `D:\codex\fnvedio`；Git 无提交，源码为本任务未提交工作区。环境：Windows、OpenJDK 21.0.8、Gradle 8.9、AGP 8.7.3、SDK / Build Tools 35。

| 验收 / 检查 | 实际命令或操作 | 结果 | 证据与限制 |
| --- | --- | --- | --- |
| Git 仓库 | `git init`、`git status --short` | PASS | 已初始化；未提交、推送或发布 |
| NAS 网页账号登录 | 用户自行在官方网页登录 | PASS | 可见片库；未提取桌面登录令牌 |
| 网页实际视频播放 | 浏览器读取 video 状态 | PASS | readyState=4、currentTime=26.567 秒、1920×1038；仅证明网页链路 |
| 单测与构建 | 当前进程设置 `FNVIDEO_TEST_SERVER=http://192.0.2.1:5666`；`./tools/build.ps1 -Task testDebugUnitTest,assembleDebug,lintDebug` | PASS | 最终退出码 0，BUILD SUCCESSFUL；13 测试、0 失败、0 跳过 |
| Feed 规则 | `FeedStateTest` | PASS | 4 项：旧请求丢弃、分页去重、空库终止、目录不能进入播放队列 |
| API 模拟契约 | `FnApiTest` | PASS | 5 项：H.264/HLS 兼容契约、签名头及摘要、库范围与分页、直链 UA 一致且无 NAS 认证头、认证错误分类 |
| 跨域请求头 | `PlaybackDataSourceTest` | PASS | 3 项：同源保留、跨域清除敏感头、不同端口不信任；未模拟真实 CDN |
| 实际 NAS 签名 | `NasContractSmokeTest` | PASS | 1 项：无凭据调用受保护接口返回 code=-2，说明签名被接受；未读取私人目录 |
| Android lint | `lintDebug` | PASS | 0 errors、22 warnings；未关闭检查或建立忽略基线 |
| APK 签名 | `apksigner.bat verify --verbose app/build/outputs/apk/debug/app-debug.apk` | PASS | 退出码 0，APK Signature Scheme v2 验证通过 |
| APK 元数据 | `aapt.exe dump badging ...` | PASS | com.fnvideo.app / 1.0；minSdk 26、targetSdk 35；启动 MainActivity |
| NAS Android 原生路径 | ADB 安装、用户登录与实际播放 | PASS（部分） | 登录、片库、换片及 H.264/HLS 播放已验证；实体手机、续播、退后台及全部片源未验收 |

历史失败已修复：缺 Build Tools 34（显式使用已安装 35）、界面方法访问/返回类型错误、测试 HTTP server 不兼容（改配套 MockWebServer）、Media3 接口需显式 OptIn。最后修正了播放 User-Agent 与申请直链时保持一致，随后重新运行上述完整检查。

lint 提示保留可见：固定依赖存在更新、产品按用户要求锁定竖屏、官方登录页需要 JavaScript、旧版备份规则建议、全列表替换事件和中文文案国际化建议。未为了通过检查隐藏这些提示。

架构审查：FeedPolicy / FeedState 是无 Android 依赖的规则与状态；MediaRepository 是内层契约；FnApi 负责协议映射；PlaybackDataSource 负责传输鉴权范围。MainActivity 仍较大，是当前维护限制。子代理审查过登录与存储，根代理修复并复核；这不等同于独立安全审计。

## NAS Android 容器验证历史（2026-09-20，阻塞随后解除）

用户指定使用现有 NAS Android 容器。结果 **BLOCKED_ENVIRONMENT**，APK 尚未安装成功，登录和播放验收未运行。

- fnOS Docker 页面确认 `fn-redroid` 正在运行，镜像 `local/redroid-gapps:13.0.0-r83-webview109-soong-test`，映射 TCP 5555，1080×1920；`fn-ws-scrcpy` 映射 8000。未重建或重启容器。
- 初始 `adb connect 192.0.2.1:5555` 超时（10060）；网页控制台无设备。ws-scrcpy 历史日志含 `failed to connect to 'fn-redroid:5555': Connection refused`。
- 通过 fnOS 容器终端只读检查：Android boot_completed=1、adbd=running、监听 `*:5555`。`ip route get 172.20.0.3` 和 `ip route get 192.0.2.1` 均返回 `Network is unreachable`。
- 临时加入容器内 `ip rule add pref 31000 lookup main` 和 `ip route add 192.0.2.0/24 via 192.0.2.1` 后，ADB 成功连接；设备查询确认 Android 13。
- `adb install -r dist/牛影随看-debug.apk` 失败：`Failure calling service package: Broken pipe (32)`。非流式重试失败：`connect failed: closed`；随后设备 offline。再次查看时临时 31000 规则已消失，原因尚未确定，不能据此断言容器重启或应用崩溃。
- 容器内 `pm path com.fnvideo.app` 无路径、退出 1。crash buffer 末尾为 09-11 的旧 DeadSystemException，不能作为本次故障根因。
- 已删除临时 192.0.2.0/24 路由，并读取 ip rule/ip route 确认恢复原状态；未改 NAS 主机网络、防火墙或持久容器配置。未获得任何应用运行通过证据。

### SSH 修复与复验（同日 22:31 起）

用户明确授权修复/重启，并要求改用 SSH、建立密钥。已建立 Windows 专用 Ed25519 密钥，保留 NAS 原有 authorized_keys 内容，使用已记录的主机密钥校验。配置别名 `nas-host`；`ssh -o BatchMode=yes nas-host id` 成功。密码未写入项目或诊断文件。

修复前 SSH 直接检查：容器 restart count=0、OOMKilled=false，但 Android `service check connectivity` 找不到服务，system_server 仅部分服务启动，未能建立 eth0 的完整 policy rules。旧日志不足以证明具体根因。宿主 legacy iptables filter 模块未加载是兼容性疑点，不能当成已证实根因。

执行 `docker restart -t 20 fn-redroid`，保留同一镜像和 /data。没有加载内核模块、改变防火墙、添加永久路由或重建数据卷。重启后：

- boot_completed=1；connectivity/package 服务 found；系统自动恢复 eth0 policy rules。
- `ip route get 192.0.2.1` 正常经 192.0.2.1、table eth0。
- Windows ADB 连接成功；`adb install --no-streaming -r` 返回 Success；`pm path com.fnvideo.app` 返回 base.apk 路径。
- `am start -W -n com.fnvideo.app/.MainActivity` 返回 Status ok，冷启动 TotalTime=1518ms。
- 原生初始页、LoginActivity 和 NAS 官方登录表单已实际显示，截图 `dist/verification/android-login.png`。
- ws-scrcpy 重新执行 `adb connect fn-redroid:5555` 后 device 状态恢复，网页控制台可打开实时画面。

环境阻塞已解除；应用整体仍 PARTIAL，等待用户在安卓端登录后验证真实播放和交互。不能将启动通过等同于解码验收。真实令牌、密码和片库内容不写入记录。

### 登录后修复与实际播放（同日 23:00）

当前服务器仍为 `http://192.0.2.1:5666`。真实片库读取与影片播放证明容器已可访问该地址，无需替换 IP。

- 修正 item/list 参数：sort_type=DESC、sort_column=create_time、exclude_grouped_video=1；原服务端 Internal Error 不再误报为地址错误。
- 修正 ViewPager2 页面必须 MATCH_PARENT 的布局约束，解除打开片库后的崩溃。
- 容器 HEVC Main10 解码能力不足时，每页只自动尝试一次飞牛 H.264/AAC 兼容流。HLS 使用原始 play_link，并设置 Media3 1.5.1 识别的 application/x-mpegURL；不将 HLS 包进 media/range。
- 用户完成官方登录后，真实媒体库和电影列表可见；滑动已切换不同影片。普通电影原生画面实际显示，进度 00:10 → 00:21 → 00:39，时长 2:13:42；证据为 dist/verification/android-playback.png。
- 最终代码运行 testDebugUnitTest、assembleDebug、lintDebug 成功；13 测试、0 失败、0 错误、0 跳过，lint 0 errors / 22 warnings；APK v2 签名有效。
- 新版 APK 已覆盖安装并保留登录数据。暂停和显示模式控件有响应，但未完成隔离、持续的验收；后台无声、断网恢复、快速连续换片、续播、字幕及实体手机仍待测试。不能把一次成功播放扩大为全部片源兼容。

仅保存不含凭据的验证结果；源码包不含片库快照、诊断日志、登录数据或 SSH 私钥。
