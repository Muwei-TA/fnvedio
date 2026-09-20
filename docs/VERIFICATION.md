# 验证记录

本文件只记录可公开复现的项目级验证，不包含真实服务地址、设备地址、账号、会话、片库或运维环境信息。

## 当前状态

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 单元测试 | PASS | `testDebugUnitTest` 已通过 |
| Debug APK 构建 | PASS | `assembleDebug` 已通过 |
| Lint | PASS | `lintDebug` 已完成；以 CI 最新结果为准 |
| 真实设备播放 | PARTIAL | 需要使用者在自己的 Android 设备和服务环境中复验 |

## 可复现命令

```powershell
.\\tools\\build.ps1 -Task testDebugUnitTest,assembleDebug,lintDebug
```

应用不会在仓库中保存账号、密码、Cookie、会话令牌、私有媒体内容或真实服务配置。真实环境验证应通过本地环境变量 `FNVIDEO_TEST_SERVER` 传入，并避免将其值写入日志或提交。
