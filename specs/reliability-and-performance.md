# Spec: 播放可靠性、离线体验与性能

## Objective

为方屏 Android 手表补齐播放自恢复、长队列拖动、系统媒体恢复、版本更新、离线快照、错误分类和首屏性能优化。成功标准是网络短暂失败或播放地址失效时不丢失歌曲和进度，无网时首页与“我的”仍有内容，长队列可以拖到屏幕外位置，用户能看到 GitHub 最新发布信息。

## Tech Stack

- Kotlin、Jetpack Compose、DataStore、Room、OkHttp
- Media3 1.10.1：`MediaSessionService`、`MediaController`、`MediaButtonReceiver`
- 继续保持单一 `app` 模块；GitHub Releases 使用现有 OkHttp 和 kotlinx.serialization
- Baseline Profile 使用 `app/src/main/baseline-prof.txt`，不增加生成模块

## Commands

- 单元测试：`.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain`
- Release lint：`.\gradlew.bat :app:lintRelease --no-daemon --console=plain`
- Release 构建：`.\gradlew.bat :app:assembleRelease --no-daemon --console=plain`
- APK 信息：`aapt dump badging <apk>`
- APK 签名：`apksigner verify --verbose --print-certs <apk>`

## Project Structure

- `app/src/main/java/.../playback/`：播放服务、控制器、错误恢复
- `app/src/main/java/.../network/`：QQ 音乐与 GitHub Release 请求
- `app/src/main/java/.../data/`：账号隔离快照和设置持久化
- `app/src/main/java/.../MainActivity.kt`：队列手势、设置与更新 UI
- `app/src/test/`：纯逻辑、缓存与错误分类测试
- `app/src/main/baseline-prof.txt`：单模块启动和常用交互规则

## Code Style

复用现有 StateFlow、DataStore 和 OkHttp，不增加仓储层或依赖注入框架：

```kotlin
runCatching { apiCall() }
    .onSuccess { value -> state.update { it.copy(value = value) } }
    .onFailure(::fail)
```

## Testing Strategy

- 单元测试：错误分类、重试判定、账号快照隔离、版本比较、队列边缘滚动方向
- 编译测试：Media3 恢复回调、Manifest receiver、Compose 更新页
- Release 验证：测试、lint、R8 构建、版本、签名、SHA-256
- 真机边界：自动恢复、重启后媒体键、480×480 边缘拖动和实际帧数据只能在设备上最终确认

## Boundaries

- Always：只重试一次；保持播放进度与队列；快照按账号校验；日志不写 Cookie、播放 URL 或令牌；更新链接只接受 HTTPS GitHub Release 资产
- Ask first：增加长期 Gradle 模块、引入崩溃上报服务、自动静默安装 APK
- Never：绕过会员/地区/DRM；后台无限重试；执行未知来源 APK；缓存跨账号解锁

## Success Criteria

- 网络/CDN/地址失效时自动重新签发一次播放地址并从原进度继续，失败后显示准确原因
- 长按队列拖动到上下边缘会滚动并提供触觉反馈，松手后落点正确
- 进程被回收或设备重启后，媒体键/系统媒体入口能恢复上次歌曲、队列和进度
- 首页与“我的”无网时静默显示同账号最后一次成功快照，不占用手表顶部空间提示离线状态
- 设置页能检查 GitHub 最新 Release，显示版本、说明、APK SHA-256 和打开下载页
- 日志记录启动首帧、主页分页和歌词页面的慢帧统计；Release APK 包含 Baseline Profile

## Open Questions

- 无。GitHub 仓库固定为 `huanghao897/QMusicWatch`；更新只跳转浏览器，不静默安装。
