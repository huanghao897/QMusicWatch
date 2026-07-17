# QMusic Watch checklist

## 0.9.4

- [x] QRC 与 LRC 高亮改为连续浮点进度和像素遮罩渲染
- [x] 当前歌词淡入、缩放与轻微左移动画
- [x] 原文、翻译和时间单行布局与动态字号适配
- [x] JVM/Robolectric 测试、Android 仪器测试源码编译
- [x] Release lint、R8、签名和 SHA-256
- [x] 推送 `main` 并发布 GitHub Release `v0.9.4`

## 0.9.3

- [x] 纯文本歌词回退、无翻译时保留原文、首句开始前不误高亮
- [x] 通知权限、MediaSession 上一首/下一首、耳机按键与无界面自然续播
- [x] 会员缓存只在重新登录/手动检查/有效会员到期时刷新
- [x] 会员类型与到期时间来自同一最高等级权益
- [x] 独立收藏歌单读取、搜索原始页游标和写操作数字 ID 补全
- [x] 等待 Wi‑Fi 任务可见、256MB 加剩余文件空间检查、分组/锁定缓存删除
- [x] 扫码加载失败态、质量降级/登录失效提示和完整日志凭据脱敏
- [x] 63 项 JVM/Robolectric 测试与 Android 仪器测试源码编译
- [x] 0.9.3 Release lint、R8、签名和 SHA-256
- [x] 推送 `main` 并发布 GitHub Release `v0.9.3`

## 0.9.2

- [x] 播放器左滑歌词不再被封面切歌手势抢占
- [x] 登录渠道以 `tmeLoginType` 为准并清除跨账号内存态/Cookie
- [x] 喜欢、歌单、专辑、歌手详情超过 100 首时分页读取
- [x] 个性化每日推荐与新歌回退
- [x] 新旧搜索音质字段、媒体 MID、专辑和 VIP 标识兼容
- [x] 缓存百分比、暂停完成态和封面原子写入
- [x] 日志与扫码消息脱敏/校验
- [x] 0.9.2 全量测试、Release lint、R8、签名和 SHA-256
- [x] 推送 `main` 并发布 GitHub Release `v0.9.2`

## 0.9.1

- [x] MediaController 异常完成安全降级
- [x] 缓存暂停/删除等待 Worker 停止并设置 15 秒上限
- [x] API 24/36、480×480 Activity 启动测试实际执行
- [x] API 24/36 Room v2→v3 迁移测试实际执行
- [x] 0.9.1 Release lint、R8、签名和 SHA-256 校验
- [x] 推送 `main` 并发布 GitHub Release `v0.9.1`

## 0.9.0

- [x] 播放快照和最近播放按账号隔离
- [x] 退出时停止播放并清除内存队列，离线文件保留且锁定
- [x] 下载执行时重新签发 URL，最多并发 2 个任务
- [x] 离线缓存同时保存音频、封面和歌词
- [x] 播放恢复锁、MediaController 主线程等待和搜索竞态修复
- [x] 歌单删除确认、逐首选择目标歌单和歌词重试入口
- [x] MIT 许可 QRC 精确逐字解码与 LRC 回退
- [x] GitHub Release 404 友好处理
- [x] 版本升级到 0.9.0 (30)
- [x] JVM、Android 测试编译、Release lint 与 R8 构建全部通过
- [x] APK 签名、版本、SHA-256 与安装包内容校验
- [x] 提交推送到 `main` 并创建 GitHub Release `v0.9.0`
