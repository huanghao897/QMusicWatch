# QMusic Watch 0.9.0 delivery plan

1. 完成账号隔离、下载时效、歌词解密、搜索竞态和交互确认修复。
2. 用真实 QRC 加密分组向量验证 QQ DES 变体，用合成歌词验证完整解压与逐字解析，避免提交第三方歌词。
3. 编译 Android 仪器测试，覆盖数据库迁移与方屏主界面启动；没有设备时明确保留真机执行项。
4. 运行全部 JVM 单测、Release lint、R8 Release 构建，检查签名、版本和 SHA-256。
5. 更新开源署名、规格、版本与发布说明，提交并推送 `main`。
6. 创建带 APK 和 SHA-256 的 GitHub Release `v0.9.0`，再通过 GitHub API 回读验证。
