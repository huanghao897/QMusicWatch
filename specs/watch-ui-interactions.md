# Spec: 方屏手表紧凑交互与逐字歌词

## Objective

让 480×480 方屏手表上的搜索、播放队列和歌词更紧凑、明确且可控。队列拖动时实时换位，喜欢与歌单按歌曲勾选导入；歌词按连续时间平滑填充，可选择靠左或居中，当前句使用弹簧聚焦动画，手动滚动时显示官方样式时间胶囊，任何单句都不得换行。

## Tech Stack

- 继续使用 Kotlin、Jetpack Compose、StateFlow 和现有 QQ 音乐直连接口
- 不新增 UI 或拖动依赖；使用 `animateItem`、`spring`、现有手势与震动 API
- 使用经 MIT 许可的 QQ DES 变体行为解码 QRC 精确逐字时间；解码或字段异常时不得覆盖普通 LRC，LRC 按相邻两行时间均匀高亮

## Commands

- 测试：`.\gradlew.bat testDebugUnitTest --no-daemon`
- lint：`.\gradlew.bat lintRelease --no-daemon`
- 构建：`.\gradlew.bat assembleRelease --no-daemon`

## Project Structure

- `MainActivity.kt`：紧凑搜索、实时队列拖动、选择导入、歌词对齐和动画绘制
- `AppViewModel.kt`：队列移动与选择导入状态
- `data/SettingsStore.kt`：歌词靠左/居中偏好持久化和非法值回退
- `lyrics/QqQrcDecoder.kt`、`lyrics/LrcParser.kt`：QRC 解密、LRC 解析、精确逐字时间与平滑回退
- `network/ApiClient.kt`、`model/Models.kt`：可靠歌词请求与歌词数据模型
- `app/src/test/`：拖动落点、QRC 和逐字进度测试

## Code Style

复用现有状态和 Compose 动画，不建立新的拖动框架：

```kotlin
workingQueue = moveQueuePreview(workingQueue, from, to)
// 松手后只提交一次最终顺序
```

## Testing Strategy

- 单元测试覆盖跨半行实时换位、连续跨多格、QRC 真实分组向量、合成完整 QRC、无效 QRC 回退、逐字进度和歌词对齐值校验
- Compose 编译验证选择导入、圆角队列和紧凑搜索
- 最终运行测试、Release lint、R8 构建、签名与 APK 哈希检查

## Boundaries

- Always：48dp 左右触控区域、账号数据不混用、筛选时禁用拖动、没有翻译时不显示占位文案
- Ask first：引入第三方拖动库、改变 QQ 音乐写接口、增加服务端
- Never：伪造翻译、绕过会员或版权限制、为动画持续高频刷新非歌词页面

## Success Criteria

- 顶部不再显示“离线内容/离线缓存”提示，但断网快照仍静默可用
- 队列越过半行只更新页面预览，其他歌曲以弹簧动画补位，被拖动项不叠加补位动画；松手后一次提交最终顺序
- 队列歌曲使用紧凑圆角卡片；首页搜索入口、搜索页和队列筛选框统一为深色圆角且不使用绿色描边
- “我喜欢”和任意歌单进入歌曲勾选页，只有确认选择的歌曲加入队列
- 默认靠左显示，设置页提供“靠左/居中”分段选项并立即持久化；两种模式都保持当前句在垂直视觉中心
- 当前歌词明显大于相邻行，以带阻尼的淡入、缩放、水平与垂直位移动画进入焦点；QRC 可用时按服务端字级时间连续填充，普通 LRC 按相邻行时间连续填充
- 自动跟随时不重复显示时间；用户触摸或表冠滚动歌词时，以屏幕中央候选句为焦点并显示“播放图标 + mm:ss”圆角胶囊，点击歌词或胶囊跳转，短暂停留后恢复当前句跟随
- 原文和翻译均为单行；长句自动缩小字号，极端长度只允许截断，不允许换到第二行
- 完全没有时间轴时按普通文本逐行展示且不显示伪造时间；未提供翻译时即使用户只开启翻译也必须保留原文
- “我喜欢”“我创建的歌单”“收藏歌单”入口分别打开独立分区；收藏页不得重复显示系统“我喜欢”，旧离线快照也执行相同过滤

## Open Questions

- 无。上述口径由用户本轮要求直接确认。
