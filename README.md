# QMusic Watch

面向 480×480 方屏、完整 Android 7.0+ 手表的第三方非官方音乐客户端。项目采用
Kotlin、Jetpack Compose 和 Media3，使用 Apache-2.0 协议，
仅用于开源、非商业学习与个人测试。

> 本项目与腾讯或 QQ 音乐无隶属、赞助或认可关系，不使用官方图标，不绕过会员、
> 地区、付费或 DRM 限制。公开发布前必须自行核对服务条款、商标和内容缓存合规性。

## 已实现

- QQ/微信官方网页直接扫码，登录 Cookie 仅加密保存在手表本机
- 发现/我的横向分页首页，每日推荐 5/10 首与“换一换”
- 歌曲和专辑完整搜索、分页加载，歌单/歌手搜索与本地搜索历史
- 我喜欢、歌单创建/改名/删除、加歌和本地最近播放
- Media3 后台播放、四种播放模式、完整队列/进度恢复、系统媒体通知和耳机控制
- LRC/QRC/翻译与纯文本歌词回退、居中逐字高亮、原文/翻译开关、时间校准和点击跳转
- 封面双击播放/暂停、上下滑音量、播放器左滑歌词、表冠控制、防误触锁定和 AMOLED 低功耗播放器
- 标准、HQ、SQ 与 Hi-Res 官方档位展示；已验证档位按歌曲和账号实际返回自动降级
- Wi-Fi 下载、按歌单缓存、容量/失效清理和账号隔离锁定
- 队列拖动、正倒序、搜索、去重、歌单批量导入及保存为新歌单
- 模块化设置、增强定时关闭、账号/VIP/歌单分区、蓝牙入口和脱敏日志导出
- 应用更新检查、公告、功能开关和用户确认后的二次脱敏诊断提交

## 当前运行模式

扫码页面、首页、搜索、详情、歌词、播放地址、收藏和歌单请求均由手表直接连接
QQ 音乐 HTTPS 接口。应用更新、公告和诊断提交使用单独的 HTTPS 服务契约；该服务
不会接收账号 Cookie、QQ/微信票据、搜索词或音乐播放地址，也不代理音频流。
服务不可用时，登录、搜索、缓存与音乐播放继续直连运行。
QQ 音乐并未为第三方客户端提供稳定公开的完整 API，接口变化时需要更新客户端；
“最近播放”因此只使用本地记录。

服务端实现和管理页面不在本公开客户端仓库中维护。

## 构建 Android

要求 Android SDK 36；普通构建可使用 JDK 17，运行包含 API 36 Robolectric 的完整测试建议使用 JDK 21：

```powershell
.\gradlew.bat :app:assembleDebug
```

正式构建：

```powershell
Copy-Item release-signing.properties.example release-signing.properties
# 编辑 release-signing.properties，指向仓库外的正式私钥
.\gradlew.bat :app:assembleRelease
```

Release 构建不再使用 Gradle 默认调试密钥。打包前会校验证书 SHA-256 必须为
`fbd5642c3c1b5882545f6f1227cf2dc38a54bcd18609203935eedbef408d1382`；
缺少配置、私钥或指纹不一致时构建会直接失败。真实 `release-signing.properties`、
私钥和口令不得提交仓库；CI 可使用 `QMUSICWATCH_RELEASE_*` 环境变量提供同一配置。

签名兼容说明：`v0.9.0–v0.9.2` 与 `v0.9.5+` 使用上述正式证书，可直接覆盖安装。
`v0.9.3/v0.9.4` 曾被错误的沙箱调试证书签名，从这两版迁移到 `v0.9.5` 必须先卸载一次；
Android 不允许在没有旧私钥签名轮换证明的情况下合并两条独立签名链。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

开源研究来源和许可证边界见 [THIRD_PARTY.md](THIRD_PARTY.md)。
