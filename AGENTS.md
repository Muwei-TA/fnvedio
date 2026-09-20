# 牛影随看：编程智能体接手规范

本文件是项目开发入口。用户当前指令优先；历史操作授权不是永久授权。

## 1. 接手顺序

1. 运行 `git status --short --branch`、`git log -5 --oneline`、`git remote -v`，确认分支、未提交改动和远程，保留其他人的工作。
2. 阅读 `README.md` 了解产品、`docs/PLAN.md` 确认任务、`docs/ARCHITECTURE.md` 确认边界；接口工作额外阅读 `docs/api-research.md`。
3. 阅读 `docs/VERIFICATION.md` 的最新记录，区分历史失败、已测和未测，重新核实环境状态。
4. 定位相关源码和测试，说明变更范围、架构影响与验证计划，再实施。

本文件保存长期规则；架构决策归 ARCHITECTURE，唯一任务清单归 PLAN，质量证据归 VERIFICATION。不要另建平行进度账本。

如宿主提供 `architecture-first-workflow`，开发、修复、重构和续做先遵循该技能；按需使用 clean-architecture、a-philosophy-of-software-design、clean-code、code-complete。技能路径由宿主发现，不写个人安装路径。必要技能缺失时明确报告，不能假称已执行。已有批准计划可以续做；新增功能或契约变化先展示设计和计划，按用户授权实施。

## 2. 产品与技术范围

- 现有飞牛影视的 Android 原生局域网客户端，固定竖屏，一屏一条，上滑下一条、下滑上一条。
- 默认完整显示，可裁剪铺满，不拉伸；支持官方登录、媒体库、分页、搜索、暂停、seek 和本机续播。
- NAS 是目录、元数据和播放源的权威来源；仅接现有 HTTP 服务，不直连数据库、不扫描 SMB、不重建片库。
- 不顺带增加下载、上传、删除、后台播放、远程组网、云端推荐或公开分享。
- 单 Gradle app 模块，Java 17、ViewPager2、Media3 ExoPlayer；minSdk 26，compile/target SDK 35。版本以 Gradle 文件为准，不无故迁移 Kotlin、Compose 或多模块。

## 3. 源码导航与职责

Java 源码目录：`app/src/main/java/com/fnvideo/app/`；测试目录：`app/src/test/java/com/fnvideo/app/`。

| 文件 | 责任与边界 |
| --- | --- |
| MainActivity.java | UI 组装、异步任务和单播放器生命周期，通过 MediaRepository 使用片库数据 |
| FeedAdapter.java | 页面、控件和手势回调，不访问接口或定义片库规则 |
| FeedPolicy.java | 可播放媒体类型的唯一规则来源 |
| FeedState.java | 分页去重、目录过滤和请求代数，保持无 Android 依赖 |
| MediaRepository.java | 内层模型与契约，普通和兼容播放源解析 |
| FnApi.java | 飞牛 JSON、签名、分页字段、媒体映射和播放 URL 解析的唯一归属 |
| PlaybackDataSource.java | Media3 传输、每次请求与重定向的同源鉴权限制 |
| LoginActivity.java | 官方同源 WebView 登录和会话交接 |
| SessionStore.java | Keystore 加密令牌，不保存密码 |

MainActivity 当前偏大，复杂新策略应按职责提取；不要为形式增加纯转发层。续播目前在 Activity 中，架构中的 ResumeStore 是设计职责，不是现有独立类。

## 4. 不可破坏的行为与接口

- 网络离开主线程；应用异步结果前校验请求代数、当前媒体与会话，防止快速滑动串片。
- 同时仅一个活动播放器；页面稳定后激活新视频，保存旧进度并停止旧播放，退出释放资源。
- 后台暂停，回前台不得覆盖用户主动暂停。续播按服务器和媒体 ID 隔离，不写回 NAS 观看记录。
- ViewPager2 根页面宽高必须 MATCH_PARENT，否则运行时崩溃。
- Source 的 URL、headers、mimeType 原子传递；临时 URL 不持久化，不依赖共享的“上次解析头信息”。
- 仅电影、普通视频、可播放分集进入 Feed，系列目录不能直接播放。
- 不无限重试。每页解码或容器格式不支持时最多自动尝试一次兼容流，切页重置；显式重试保留兼容模式。

当前服务实测的易错点，NAS 升级后需重新核实：

- item/list：`sort_type=DESC`、`sort_column=create_time`、数字 `exclude_grouped_video=1`，不随意改变大小写或类型。
- HEVC Main10 可能超出容器解码能力；FnApi 负责 H.264/AAC 兼容请求，UI 不拼装转码 JSON。
- HLS 使用原始 play_link，不套 media/range；Media3 1.5.1 使用 MIME `application/x-mpegURL`。
- 申请直链和播放的 User-Agent 一致，直链不携带 NAS 认证头。
- 每次跨域请求（含不同端口）与重定向清除 Authorization、Play-Link、Cookie、authx，不只检查初始 URL。
- 区分网络、登录、服务端和解码错误，不能把服务异常一概提示为 IP 不可达。

## 5. 构建与验证

在仓库根目录用 PowerShell：

```powershell
# 仅首次缺少 SDK 时运行，会下载工具，需要可用 JDK。
.\tools\setup-android.ps1

# 可选：启用无凭据 LAN 签名检查；仅在测试 NAS 可达时设置。
$env:FNVIDEO_TEST_SERVER = 'http://192.0.2.1:5666'

# 单测、调试 APK、静态检查。
.\tools\build.ps1 -Task testDebugUnitTest,assembleDebug,lintDebug
```

build.ps1 使用 `.tools/android-sdk` 并生成被忽略的 local.properties；其他机器可配置自己的 JDK/SDK 使用 Gradle Wrapper，不提交本机路径。未设置 LAN 环境或测试跳过时必须如实报告。

- APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 单测 XML：`app/build/test-results/testDebugUnitTest/`。
- lint：`app/build/reports/lint-results-debug.html` 和 `.txt`。
- `dist/` 为忽略的交付目录；打包时重新复制当前 APK 并校验哈希，避免交付旧构建。

Feed 规则测 FeedStateTest；协议测 FnApiTest；鉴权范围测 PlaybackDataSourceTest；无凭据 NAS 签名测 NasContractSmokeTest。不得删断言、降标准或隐藏 lint 制造通过。纯文档修改检查路径、命令和 `git diff --check`，无需应用构建。

播放改动需设备检查：登录 → 片库 → 实际画面及进度推进 → 连续换片 → 暂停/恢复 → 完整/铺满 → 后台无声 → 前台保留用户状态 → 退出。按任务补测 seek、续播、断网、失效会话和兼容流；分别记录 PASS / FAIL / NOT_RUN。进程存在、HTTP 200、网页播放和构建成功均不替代原生播放证据。

## 6. NAS Android 测试

以下是已用开发环境，操作前重新确认可达性：

- 服务 `http://192.0.2.1:5666`，网页登录入口 `/v`。
- 容器 fn-redroid：Android 13，ADB `192.0.2.1:5555`。
- fn-ws-scrcpy 网页控制台：`http://192.0.2.1:8000/`。
- 本机曾配置 SSH 别名 nas-host，新机器不得假定存在，不读取或输出私钥内容。

用户要求设备验证且目标确认后，可执行：

```powershell
$adb = '.\.tools\android-sdk\platform-tools\adb.exe'
& $adb connect 192.0.2.1:5555
& $adb devices -l
& $adb -s 192.0.2.1:5555 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s 192.0.2.1:5555 shell am start -W -n com.fnvideo.app/.MainActivity
```

保留登录和应用数据，不自动 clear data、卸载、重建容器或删卷。普通开发不授权修改 NAS 网络、防火墙、数据库或媒体；重启和配置变更需当前任务覆盖，过去授权不自动延续。

不可达时先检查设备、Android 服务和路由；历史服务不完整曾导致网络故障，恢复后上述 LAN 地址已成功播放，不能仅凭错误文案换 IP。用户操作设备时避免抢占控制，也不能把用户操作计为自动验证。

## 7. 安全、协作与交接

- 不输出或提交密码、令牌、私钥、Cookie、签名播放链接、私人片库导出及未脱敏日志/截图。
- 登录只用官方同源页面，不绕过 TLS 校验；令牌 Keystore 加密，保持禁用备份。
- 保留 .gitignore 对 SDK、缓存、local.properties、research、dist 的排除，不使用 add -f 顺带上传生成物。
- 远程 `https://github.com/project-maintainer/fnvedio` 预期私有，推送前核验；提交/推送依用户授权，不自动改公开、不强推、不覆盖历史。
- 独立扫描、测试或窄范围修改可委派 luna_max_worker，fresh context（fork_turns=none），给完整任务、文件归属和验收，明确共享工作区且不得回滚别人改动。简单任务直接完成。
- 完成后复查 diff，更新 PLAN 接续点和 VERIFICATION 实测结果，报告变化、验证和未测范围，给出文件或提交；不得将 PARTIAL 写为全部完成。

历史基线为 13 项测试通过、lint 0 errors / 22 warnings，NAS 容器已有登录、片库、换片和 H.264/HLS 实际播放证据；不是后续改动的永久通过凭据。实体手机、完整生命周期、断网恢复及全部片源兼容性以最新验证记录为准。
