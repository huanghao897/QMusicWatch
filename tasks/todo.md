# QMusic Watch checklist

## Direct QQ Music migration

- [x] Existing server QQ and WeChat QR creation/polling retained
- [x] Direct musicu, search and profile transport
- [x] Direct official stream, cover and avatar URLs
- [x] Current favorite contract with directory-201 fallback
- [x] Existing server feature switches still gate client behavior
- [x] No post-login QQ credential or business payload reaches `heyboxlite.xyz`
- [x] Full tests, signing build and source audit pass

## Current client quality

- [x] 方屏首页、搜索、歌单、歌词和播放器的紧凑布局
- [x] QQ/微信扫码登录及账号隔离缓存
- [x] Media3 后台播放、队列、耳机按键和播放状态恢复
- [x] 单行歌词高亮、翻译开关、时间校准和点击跳转
- [x] 离线缓存、存储检查、失败重试和缓存锁定
- [x] 播放列表拖动、排序、逐首选择和重复项处理
- [x] 设置、日志导出、定时关闭和三态主题

## Verification

- [ ] API 24 and API 36 device smoke tests
- [ ] 480x480 screenshot and touch regression pass
- [ ] Wi-Fi/LTE handoff, process recreation and Bluetooth reconnect pass
- [x] Re-check signing configuration before any requested release

## Release policy

- [x] Keep Android version unchanged for ordinary fixes
- [x] Publish `0.9.9 (39)` after Ronan explicitly requested a server release
