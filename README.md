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
- LRC/翻译歌词、居中高亮、原文/翻译开关、时间校准、动画强度和点击跳转
- 封面双击、滑动切歌/音量、表冠控制、防误触锁定和 AMOLED 低功耗播放器
- 128k/320k、Wi-Fi 下载、按歌单缓存、容量/失效清理和账号隔离锁定
- 队列拖动、正倒序、搜索、去重、歌单批量导入及保存为新歌单
- 模块化设置、增强定时关闭、账号/VIP/歌单分区、蓝牙入口和脱敏日志导出

## 当前运行模式

应用不需要自建网关。扫码页面、首页、搜索、详情、歌词、播放地址、收藏和歌单请求
均由手表直接连接 QQ 音乐 HTTPS 接口，账号 Cookie 不会发送到第三方服务器。
QQ 音乐并未为第三方客户端提供稳定公开的完整 API，接口变化时需要更新客户端；
“最近播放”因此只使用本地记录。

## 构建 Android

要求 JDK 17、Android SDK 36：

```powershell
.\gradlew.bat :app:assembleDebug
```

正式构建：

```powershell
.\gradlew.bat :app:assembleRelease
```

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

开源研究来源和许可证边界见 [THIRD_PARTY.md](THIRD_PARTY.md)。
