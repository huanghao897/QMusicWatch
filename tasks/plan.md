# QMusic Watch 0.9.5 signing recovery plan

1. 审计全部 GitHub Release 与本地密钥，确认签名从 `v0.9.3` 发生变化并找回原始私钥。
2. 将原始私钥复制到仓库外固定目录，Gradle 改为显式读取签名配置并校验规范指纹。
3. 增加配置模板、忽略规则、构建文档和历史兼容说明；版本升级到 `0.9.5`。
4. 运行完整测试、Android 测试源码编译、Release Lint、R8、签名和 APK 哈希门禁。
5. 直接推送 `main`，发布 `v0.9.5`，回读标签与资产 digest，并下载验证远端 APK 证书。

# QMusic Watch 0.9.0 delivery plan

1. 完成账号隔离、下载时效、歌词解密、搜索竞态和交互确认修复。
2. 用真实 QRC 加密分组向量验证 QQ DES 变体，用合成歌词验证完整解压与逐字解析，避免提交第三方歌词。
3. 编译 Android 仪器测试，覆盖数据库迁移与方屏主界面启动；没有设备时明确保留真机执行项。
4. 运行全部 JVM 单测、Release lint、R8 Release 构建，检查签名、版本和 SHA-256。
5. 更新开源署名、规格、版本与发布说明，提交并推送 `main`。
6. 创建带 APK 和 SHA-256 的 GitHub Release `v0.9.0`，再通过 GitHub API 回读验证。
