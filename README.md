# Recents Hider

一款基于 Shizuku 的 Android 应用，可以将指定应用从最近任务列表中隐藏。

## 功能

- 🔍 搜索应用
- 🎨 12种主题颜色可切换
- 👁️ 从最近任务中隐藏/恢复应用
- 🗑️ 卸载应用（保留数据/删除数据）
- 📱 查看应用详情（包名、版本、权限）
- 📦 提取APK
- 💻 ADB命令执行
- 🔧 自动检测设备信息
- 📋 常用ADB命令快捷执行

## 系统要求

- Android 7.0+ (API 24+)
- 已安装并启动 Shizuku 服务

## 安装

1. 从 Releases 下载最新 APK
2. 安装到手机
3. 授权 Shizuku 权限

## 使用说明

- 点击右上角调色盘图标切换主题颜色
- 点击应用开关隐藏/恢复应用
- 长按应用图标查看详情
- 点击垃圾桶图标卸载应用
- 底部导航栏切换应用列表/ADB模式

## 工作原理

通过 Shizuku 的 `removeTask` API 将指定应用从最近任务列表中移除。

## 限制

- `removeTask` 会结束应用的 Activity（Android 16 的限制）
- 有前台服务的应用（如音乐播放器）后台服务不受影响
- 应用本身通过 `excludeFromRecents="true"` 自动隐藏

## 技术栈

- Kotlin
- Shizuku API
- Material Design 3
- Glide

## 许可证

MIT License
