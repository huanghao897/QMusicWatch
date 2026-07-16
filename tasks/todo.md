# QMusic Watch 0.9.0 checklist

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
