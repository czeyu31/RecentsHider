# System Tool

一款基于 Shizuku 的 Android 系统工具箱，集成应用管理、无障碍保活、系统监控等功能。

## ⬇️ 下载

[![Download APK](https://img.shields.io/github/v/release/czeyu31/RecentsHider?label=下载最新版&style=for-the-badge)](https://github.com/czeyu31/RecentsHider/releases/latest)

或访问 [Releases 页面](https://github.com/czeyu31/RecentsHider/releases) 查看所有版本。

## 功能

### 🏠 主页面（系统监控）
- 实时显示 CPU、内存、电池、存储、网络等设备信息
- CPU 多核频率监控
- 高耗能应用排行榜
- 系统健康度检测（运行时间、进程数）
- Shizuku 连接状态
- 左滑进入无障碍管理页面

### 👁️ 应用管理（工具箱）
- 🔍 搜索应用
- 从最近任务中隐藏/恢复应用
- 🗑️ 卸载应用（保留数据/删除数据）
- 📱 查看应用详情（包名、版本、权限）
- 📦 提取 APK
- 📌 长按置顶常用应用
- 仅显示用户应用过滤

### ♿ 无障碍管理（主页面左滑进入）
- 管理所有已注册的无障碍服务
- 一键开关无障碍服务
- **防回收保活** — 防止系统自动关闭无障碍服务
  - ContentObserver 实时监听变化
  - 每 15 秒定时检查
  - 解锁屏幕时自动检查恢复
  - 开机自启动恢复
- 📌 长按置顶常用服务
- 🔍 搜索应用或服务
- 仅显示用户应用过滤
- 服务功能说明展示

### 💻 ADB 命令（工具箱）
- 常用 ADB 命令快捷执行
- 自定义命令输入
- 命令执行结果查看

### 📋 历史通知（工具箱）
- 记录所有应用通知
- 按应用筛选通知记录
- 一键清除通知历史

### 📂 文件中转（工具箱）
- 文件传输功能
- 支持多种文件类型

### 📱 APK 安装器（工具箱）
- 本地 APK 安装管理

### 📊 状态通知
- 通知栏显示隐藏应用数和保活服务数
- 可在设置中开关

### ⚙️ 其他功能
- 隐藏后台卡片（从多任务列表隐藏本软件）
- 后台保活（通过 Shizuku 设置电池优化白名单等）
- 开机自启动

## 系统要求

- Android 8.0+ (API 26+)
- 已安装并启动 Shizuku 服务

## 安装

1. 从 [Releases](https://github.com/czeyu31/RecentsHider/releases/latest) 下载最新 APK
2. 安装到手机
3. 授权 Shizuku 权限

## 使用说明

- 主页面右上角齿轮图标打开设置
- 主页面右上角工具箱图标打开工具箱
- **主页面左滑**进入无障碍管理
- 无障碍管理页面**右滑**返回主页
- 点击应用开关隐藏/恢复应用
- **长按**应用或服务可置顶、查看详情、设置防回收保护

## 工作原理

- 应用隐藏：通过 Shizuku 的 `removeTask` API 将指定应用从最近任务列表中移除
- 无障碍保活：通过 Shizuku 的 `settings put secure` 命令写入安全设置，ContentObserver + 定时检查 + 解锁广播三重保活
- 后台保活：通过 Shizuku 设置 `RUN_IN_BACKGROUND`、电池优化白名单等系统权限

## 技术栈

- Kotlin
- Shizuku API
- Material Design 3
- Glide
- NanoHTTPD

## 许可证

MIT License
