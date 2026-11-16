# 呦呦剧场

给马呦呦定制的 Android TV 本地动画片播放器，不依赖腾讯视频会员和投屏。

## 已有功能

- 启动欢迎语：欢迎马呦呦小朋友。
- 三层片库：第一层文件夹是动画名，第二层是季，第三层是具体视频。
- 视频来源：支持 U 盘、移动硬盘或本机媒体库；系统缺少目录选择器时自动改读媒体库。
- U 盘索引：首次扫描会在所选目录根生成 `.yycartoon-index.db`，下次启动直接读取索引。
- 选集播放：先选动画、季、集；从选中集开始按顺序自动连播。
- 继续播放：首页可从上次播放位置继续；中断的集每 5 秒保存一次进度。
- 播放控制：左右键快进/后退 10 秒，确认键暂停/继续；1.8 秒内按两次返回键会保存进度并回到选片页面。
- 每日额度：默认每天最多 4 集，可调 1-12 集；一集完整播放结束才计入一集，额度用完自动结束。
- 观看记录：完整看完的集显示 `✓`，有断点的集显示 `▶`。
- 家长中心：设置 4-6 位密码，管理每日额度、重建索引、重置当天额度、清空记录和断点。
- 文件排序：按自然数字排序，`第2集` 会排在 `第10集` 前面。

## U 盘/移动硬盘目录

选择 `Cartoons` 这个总目录：

```text
Cartoons/
  小猪佩奇/
    第1季/
      第01集.mp4
      第02集.mp4
    第2季/
      第01集.mp4
  熊出没/
    第1季/
      第01集.mp4
      第02集.mp4
```

- 第一层：动画名
- 第二层：季名
- 第三层：视频文件
- 支持更深子目录，但仍按第二层的季归组。
- 支持扩展名：`mp4`、`mkv`、`avi`、`mov`、`webm`、`m4v`、`3gp`、`ts`。

## 安装

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android TV 首页会显示“呦呦剧场”的电视横幅图标。

## 模拟器测试

```bash
adb shell mkdir -p /sdcard/Movies/Cartoons/小猪佩奇/第1季
adb push /path/to/video.mp4 "/sdcard/Movies/Cartoons/小猪佩奇/第1季/"
adb shell cmd media_scanner scan /sdcard/Movies
```

如果模拟器提示没有目录选择器，App 会自动申请读取视频权限并读取本机媒体库。若 `adb push` 后仍提示没有视频，查询并发布 pending 文件：

```bash
adb shell content query --uri content://media/external_primary/video/media --projection _id:_display_name:is_pending
adb shell content update --uri content://media/external_primary/video/media/实际ID --bind is_pending:i:0
```

## 家长中心

1. 首次点击“家长中心”设置 4-6 位数字密码；建议家长先设置，再交给小朋友。
2. “每日最多集数”默认 4 集，可调 1-12 集。
3. “重置今日观看额度”只重置当天计数，不删除观看记录。
4. “清空观看记录和断点”会删除全部已看标记和播放进度。
5. “重建视频索引”在 U 盘新增、删除或改名视频后使用。
6. 密码遗忘只能清除应用数据或卸载重装。

## 语音

应用使用系统 TTS。若电视没有中文语音引擎，语音不报错，文字提示仍会显示。可在电视设置的无障碍/文字转语音中安装或启用中文语音包。
