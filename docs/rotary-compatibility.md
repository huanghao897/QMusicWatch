# 表冠输入兼容

## 范围

仍为 API 24+ 完整 Android 手表应用，不要求 Wear OS、Google Play 服务或
特定厂商 SDK。按实际事件能力适配，不按 `Build.MODEL` 猜测硬件协议。

| 事件 | 处理 |
| --- | --- |
| `ACTION_SCROLL / AXIS_SCROLL` | 标准编码器，包括未标注 `SOURCE_ROTARY_ENCODER` 的厂商事件 |
| `ACTION_SCROLL / AXIS_VSCROLL` | 兼容鼠标式垂直滚轮事件 |
| `ACTION_SCROLL / AXIS_HSCROLL` | 兼容横轴滚轮事件 |
| `DPAD_UP/DOWN`、`PAGE_UP/DOWN`、`SYSTEM_NAVIGATION_UP/DOWN` | 前台列表滚动，播放器音量，歌词滚动 |
| `VOLUME_UP/DOWN` | 播放器音量；其他列表默认保留系统行为，可在设置中开启滚动 |

原始 `AXIS_SCROLL/VSCROLL` 转换为像素时取负，并使用系统的 scroll factor；
`HSCROLL` 使用横向 factor。统一后正像素表示向列表末尾滚动，与 Compose
的 `ScrollScope.scrollBy` 一致，不混用原始轴值与 Compose 像素。

## 交互与安全边界

- 每个 Activity 有独立分发器；只向最上层、已恢复的页面/窗口分发一次。
- 首页 Pager 仅当前停稳页面接收。选择窗口优先于页面；无滚动内容的窗口
  阻止事件穿透到播放器。窗口关闭后恢复当前页面，不广播、不重播。
- 不依赖控件焦点、不抢走搜索框焦点；输入法活动时方向键交还文本编辑，
  完整字母键盘的方向键不被当成表冠。返回、确认、媒体键不拦截。
- 列表滚动使用短动画；新样本取消并接续未完成的位移，积压最多 180dp。
  换向丢弃反方向余量，手势抢占或到边界时清除余量。
- 播放器每累计 16dp 位移至多调整一个系统音量级，键事件每次至多一级；
  仍保留播放器 55ms 单步节流，不设置绝对音量，不按大 delta 连跳多级。
- MediaController 未连接时立即调整系统音乐流，不排队等待连接后集中执行。
- 接管按键后也消费重复与松开事件，防止同一次操作又触发系统默认处理。
  持键期间换页不向新页面重播；暂停时清理持键状态。
- 播放器关闭“表冠调节音量”或锁定后，仍消费滚轮事件但不改变音量。
  音量浮层与节流震动保持原设计；歌词仍遵循手动浏览后三秒自动居中。
- “表冠方向反转”用于固件方向差异；真实音量键在播放器中保持加减语义。
- 日志仅为首次见到的 source/axis 或 source/keyCode 记录 `ROTARY`，每进程
  最多 16 类；不记录触摸位置、账号信息或每次刻度。

设置位于“设置 → 播放”，新增键默认关闭，与已有账号、缓存、队列及设置兼容。

## 播放器触控音量

- 原有胶囊操作栏中加入音量入口，保留队列、喜欢和更多操作。
- 点击打开居中的全屏音量页，不使用底部面板；百分比、进度反馈和加减按钮
  采用现有纯黑、单色、胶囊设计，圆屏留出安全边距。
- 按钮每次只调整一级系统音乐音量，与表冠共用 `deviceVolume`；不修改
  播放器软件增益，不用旧百分比设定绝对音量，连接中也不积压操作。
- 关闭“表冠调节音量”后，触控加减仍可使用。音量页会消费表冠事件，
  不让其穿透背后的播放器；开启时音量页也能直接用表冠调节。
- 面板内不再唤起系统音量浮层。仅在音量页处于前台时刷新系统读数，
  同步耳机和系统音量变化；关闭或暂停后停止刷新。

## 参考与许可

- Android 官方旋转输入说明：
  https://developer.android.com/training/wearables/compose/rotary-input
- AndroidX `AndroidComposeView.handleRotaryEvent` 的事件方向/像素规范：
  https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidComposeView.android.kt
- `Yuanzer/WatchAgents` 的 `WatchRotaryScroll.kt` 覆盖了标准轴、旧式轴、导航键
  及可选音量键，是真实可读的 GitHub 参考方案；其 README 只明确列出华为
  Watch 4/4 Pro 实机验证，其他机型不是逐台验证。本项目仅研究兼容思路，
  不复制其 PolyForm Noncommercial 代码，保持 Apache-2.0 发布边界。
- 专门面向小米 Watch 5 的 `Ghz114514/yuque-wear`（MIT）记录了 Wear Compose
  触觉代码依赖系统内 `com.google.wear.input.WearHapticFeedbackConstants`、
  在非 Wear OS 系统中可能缺类的问题。本项目继续使用 Android 标准触觉 API，
  不引入该 Wear 依赖，也不需要其兼容桩；仅作为机型兼容研究，未复制代码。

## 验证

自动化包含纯函数及 API 24/35 Robolectric 事件测试：方向/缩放、异常单轴、
无 source、三种滚动轴、导航键、按键重复/松开、弹窗优先、窗口卸载、页面
切换、输入法与非表冠事件。

音量控件另有 API 24/35 Compose Robolectric 测试，覆盖入口点击、单级加减、
上下限、未知音量、240dp 圆屏触控边界、320dp 方屏和弹窗事件隔离；
播放层测试验证关闭表冠后触控仍生效、系统读数同步，以及操作明确请求音乐流
并关闭系统音量浮层。

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

没有小米 Watch 5 或其他型号的连接实机，本轮不声称已完成这些型号的硬件验收。
安装 Release 后，应验证：播放器转动、点击按钮后再转、歌词浏览与自动回中、
首页/搜索/歌单/队列/设置滚动、弹窗、关闭开关、低音量快速转动与蓝牙输出。
若仍不响应，导出日志检查 `ROTARY` 的 source/axis/keyCode，再按真实事件补充。
