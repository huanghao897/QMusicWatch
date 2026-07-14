# Watch UI interaction plan

1. 先把队列导入选择状态放入现有 `AppUiState`，复用资料库和歌单详情接口。
2. 将队列拖动改成跨越半行即调用现有 `moveQueue`，其他项继续使用 `animateItem` 实时补位。
3. 用自定义紧凑 Row 替换队列的方形 `ListItem`，保留 48dp 左右触控区域。
4. 压缩搜索框、标签和列表间距，并删除离线提示 UI，不删除离线数据。
5. 请求可用 QRC，扩展歌词行字级时间；渲染时当前行放大并逐字高亮。
6. 统一运行单测、lint、Release、APK 与 Git 验证。

风险：QQ 音乐并非每首歌都返回 QRC。解析器必须无条件回退到原有 LRC，并以相邻行时长做平滑高亮。
