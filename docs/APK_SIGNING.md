# APK 签名与覆盖升级

Android 覆盖安装要求 applicationId 相同、签名兼容且 versionCode 不倒退。本项目固定使用 `com.fnvideo.app`；每次交付仍需递增 Gradle 中的版本。

GitHub Actions 的 push 构建使用持久 PKCS12 签名，缺少签名配置会直接失败，不允许悄悄生成临时 Debug 签名后发布。PR 构建不取得签名材料，只用于测试和临时 APK，不作为可覆盖安装的发布包。

仓库 Actions Secrets：

- `ANDROID_SIGNING_KEYSTORE`：PKCS12 文件的 Base64 内容。
- `ANDROID_SIGNING_PASSWORD`：密钥库和别名密码。
- `ANDROID_SIGNING_CERT_SHA256`：预期签名证书 SHA-256 指纹。

固定 alias 为 `fnvideo`。证书公开指纹：

```text
d17e246c48a41a07e06829f4d49cb4e93fb2e8544472edc59f4aedad3572acf0
```

Workflow 只将密钥临时恢复到 runner 临时目录；Gradle 从环境变量取得签名参数；构建后用 `apksigner verify --print-certs` 比对证书指纹，然后清理临时文件。私钥、密码和密钥库不得进入 Git、日志或 Actions artifacts。维护者必须保留仓库之外受限权限的签名备份，不要为后续版本重新生成密钥。

旧版 CI 每次生成临时 Debug 签名，现有 Gradle 缓存不包含该签名。没有旧私钥时不能给新包补签为旧身份，也不能通过修改版本号绕过 Android 校验。从旧包迁移到固定签名可能仍需最后一次卸载；卸载会清除本地设置，应由用户决定。固定签名后的版本保持相同身份，后续版本可使用覆盖安装；最终仍需真机以 `adb install -r` 或系统安装器验证。
