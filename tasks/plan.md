# Reliability batch plan

1. 先建立错误分类与可测试的重试决策，再把重试接入 ViewModel/Media3 错误回调。
2. 扩展播放快照，使 `MediaSessionService` 可独立恢复媒体项；随后声明 `MediaButtonReceiver`。
3. 在现有队列 UI 上增加边缘滚动和触觉反馈，不替换当前拖动实现。
4. 复用 DataStore 存储账号隔离的首页/资料库快照，网络成功时更新，失败时保留。
5. 用现有 OkHttp 调 GitHub Releases API，设置页只展示并打开 HTTPS 链接。
6. 添加轻量帧性能日志和手工 Baseline Profile，最后统一做 Release 验证。

风险：QQ 音乐地址失败发生在播放器层，而重新签发需要当前账号 Cookie。前台播放通过控制器错误事件回到 ViewModel 重签；系统媒体恢复由服务读取同一账号会话，地址失效时直接重签。
