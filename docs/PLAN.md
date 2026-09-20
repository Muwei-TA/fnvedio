# 实施计划

架构基线见 [ARCHITECTURE.md](ARCHITECTURE.md)。用户在审阅架构说明后于 2026-09-20 指示“继续”；沿既有局域网 Android 客户端范围推进，不修改 NAS 部署。

路由：RESUME / IN_PROGRESS；认证和外部 API 适配属于 HIGH 风险，新应用沿用 FULL 设计基线，当前处于 VERIFY 与缺陷修正阶段。项目已按用户授权提交并推送到私有仓库 project-maintainer/fnvedio 的 main 分支；当前状态以 git status 为准，后续提交和推送按当前任务授权执行。专业技能使用 architecture-first-workflow、clean-architecture、a-philosophy-of-software-design、clean-code、code-complete；专用上游调试技能未加载，使用工作流内置的证据驱动调试流程。任务清单以本表为唯一来源，质量结果以 VERIFICATION.md 为唯一来源。

接续基线：2026-09-20 Gradle 首次完整配置后退出码 1，原因是默认 Build Tools 34.0.0 未安装；本地 SDK 已有 35.0.0。修正为显式使用已安装的 35.0.0，重跑既定检查。此前网页播放证据不覆盖原生代码。

| 阶段 | 文件 / 边界 | 验收 | 状态 |
| --- | --- | --- | --- |
| 仓库和架构 | Git、AGENTS.md、ARCHITECTURE.md | 工作区初始化，需求、边界、风险可审阅 | 已完成 |
| 纵向切片 | LoginActivity、SessionStore、MediaRepository、FnApi | 官方登录 → 一页目录 → 一个原生播放源 | DONE：真实安卓登录、片库与播放通过 |
| 竖屏交互 | MainActivity、FeedAdapter、FeedState | 滑动换片、fit/zoom、seek、分页、换库、旧请求失效 | 已实现，测试中 |
| 传输安全 | PlaybackDataSource | NAS 认证头不随跨域重定向泄漏 | 已实现，测试中 |
| 构建验证 | Gradle Wrapper、tools、单元测试、lint | 单测与 APK 构建通过，静态错误解决 | DONE：13 测试通过，lint 0 errors，APK 签名校验通过 |
| 真实环境 | 用户指定 NAS fn-redroid Android 13 | 安装、登录、连续切换、退后台、失败恢复 | PARTIAL：安装、登录、片库、换片与实际 H.264/HLS 播放 PASS；生命周期及失败恢复待验收 |

交付包含源码、架构、构建脚本、调试 APK 和验证记录。不会把网页端播放成功等同于 Android 端通过。

2026-09-20 环境修复授权：用户在容器安装失败报告后明确回复“修复”，允许修复既有 fn-redroid，必要时重启并保留数据。路由为 OPERATE / RESUME，影响仅测试基础设施；应用架构不变。先查启动/网络日志，再做可回退修复，验收为稳定 ADB、APK 安装和原生启动，随后继续登录播放。删除数据卷、替换未知镜像或扩大网络暴露不在本次默认处置范围。

回退：没有 NAS 数据迁移；测试版可以在手机卸载。源码保留在本地 Git 工作区。构建 SDK 和缓存位于忽略目录，不属于交付源码。Git 作者身份已在仓库内配置，后续保持现有配置。

已知限制：飞牛非公开 API 有版本兼容风险；搜索接口分页和全部分集行为需真实账号确认；网盘直链和特定解码格式需按目标手机实测。首版不扩展后台播放、下载、上传或远程组网。

接续断点：整体 PARTIAL；NAS Android 已完成真实登录、片库、换片及影片播放。13 项测试和构建通过，实体手机、后台无声、续播、断网恢复与快速连续滑动仍待验收。开发基线已提交并推送；以 VERIFICATION.md 为质量记录。未修改检查阈值、删除断言或跳过要求的测试。


项目接手入口：根目录 AGENTS.md，包含模块边界、构建命令、NAS 验证和安全规范。本次仅整理文档，不改变业务行为；验证采用路径核对与 git diff --check。
