# Spec: 固定 Release 签名与历史兼容

## Objective

QMusic Watch 的所有后续正式 APK 必须由同一私钥签名，避免 Android 覆盖安装时出现“签名不一致”。唯一正式证书为最早用于 `v0.9.0`、`v0.9.1`、`v0.9.2` 的 SHA-256 指纹 `fbd5642c3c1b5882545f6f1227cf2dc38a54bcd18609203935eedbef408d1382`。

## Tech Stack

- Gradle Kotlin DSL 与 Android Gradle Plugin 现有签名配置
- Java `KeyStore` 和 `MessageDigest` 在配置阶段校验证书
- 仓库外 PKCS12 私钥；仓库内只保存无秘密的配置模板和期望指纹

## Commands

- 签名报告：`.\gradlew.bat signingReport --no-daemon --console=plain`
- 测试：`.\gradlew.bat testDebugUnitTest compileDebugAndroidTestKotlin --no-daemon --console=plain`
- 发布门禁：`.\gradlew.bat lintRelease assembleRelease --no-daemon --console=plain`
- APK 校验：`C:\opencode\android-sdk\build-tools\36.0.0\apksigner.bat verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk`

## Project Structure

- `app/build.gradle.kts`：读取显式签名配置并校验证书指纹
- `release-signing.properties.example`：无秘密的配置格式示例
- `release-signing.properties`：本机路径和口令，必须被 Git 忽略
- `README.md`：发布者配置、证书指纹和历史兼容说明
- `tasks/`：实施计划与发布门禁

## Code Style

Release 不允许静默回退到调试签名：

```kotlin
check(actualCertificateSha256 == expectedReleaseCertificateSha256) {
    "Release signing certificate does not match the canonical certificate"
}
```

## Testing Strategy

- 比对 GitHub 历史 Release 的 APK 证书，确认签名变化边界。
- `signingReport` 必须显示 Release 使用固定仓库外密钥。
- 完整 JVM/Robolectric 测试、Android 仪器测试源码编译、Release Lint 与 R8 构建必须通过。
- 最终 APK 必须为 v2 签名，且证书 SHA-256 精确等于规范值。
- GitHub Release 上传后回读资产 digest，并再次下载验证证书。

## Boundaries

- Always：私钥放在仓库外；构建前校验完整证书指纹；记录 APK SHA-256。
- Ask first：更换正式证书、变更应用 ID、建立 Android 签名轮换 lineage。
- Never：提交私钥或真实口令；回退到 Gradle 默认 debug key；伪造可跨独立签名覆盖安装的承诺。

## Success Criteria

- `v0.9.5` 与 `v0.9.0–v0.9.2` 的证书 SHA-256 都是 `fbd5642c...408d1382`。
- 改变、删除或漏配私钥时，Release 打包在产出 APK 前失败并给出明确错误。
- Gradle 的 `user.home`、Codex 沙箱账户或默认 `debug.keystore` 变化不再影响 Release 签名。
- `v0.9.0–v0.9.2` 可直接覆盖安装 `v0.9.5`；错误签名的 `v0.9.3/v0.9.4` 明确要求卸载一次。

## Open Questions

- 无。正式签名以最早发布链为准。
