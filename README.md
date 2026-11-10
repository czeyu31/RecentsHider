# System Tool

一款基于 Shizuku 的 Android 系统工具箱：应用管理、无障碍保活、通知历史、文件传输、ADB 命令。

> v30.0 起 UI 全面重写为 Jetpack Compose 双主题（纸本 / 石墨·雾玉），并移除了原有的系统监控页（CPU / 内存 / 电池 / 存储 / 网络 / 高耗能排行）。

## ⬇️ 下载

[![Download APK](https://img.shields.io/github/v/release/czeyu31/RecentsHider?label=下载最新版&style=for-the-badge)](https://github.com/czeyu31/RecentsHider/releases/latest)

或访问 [Releases 页面](https://github.com/czeyu31/RecentsHider/releases) 查看所有版本。

仓库根目录也提供一份可直接安装的构建产物：`RecentsHider-v30.0-redesign.apk`。

## 功能

### 🏠 首页
- Shizuku 连接状态（未运行 / 未授权 / 连接中 / 已连接）
- 已隐藏应用数、守护中的无障碍服务数、系统运行时长
- 已隐藏应用图标预览，点按进入应用管理
- 底部开关：后台保活 / 状态通知 / 后台卡片
- 右上角设置：外观主题、状态通知、后台保活
- **右滑**进入无障碍管理

### 🎨 双主题
- **纸本**：暖米白纸面 + 墨黑正文 + 朱红强调，横线组织信息，无卡片
- **石墨 · 雾玉**：冷石墨底 + 去饱和雾玉绿，软面板 + 顶部高光，非对称网格
- 设置里可选「跟随系统 / 纸本 / 石墨」，切换时颜色 320ms 过渡

### 🧰 工具箱
- **应用管理**：搜索、三档筛选（全部 / 用户应用 / 已隐藏）、隐藏与恢复、长按置顶 / 详情 / 提取 APK / 卸载
- **ADB 命令**：输入框置顶（键盘弹出不挡输出）、描边控制台、常用命令一键执行
- **历史通知**：按应用筛选、记录侧排除、一键清空
- **APK 安装器**：多选 APK、签名校验、批量串行安装、失败重试
- **文件传输**：NanoHTTPD 局域网收发、UDP 设备发现、六种分享入口
- **设置**：就地弹出设置面板，改完立即生效

### ♿ 无障碍管理
- 管理所有已注册的无障碍服务，一键开关
- **防回收保活**：ContentObserver 监听变化 + 定时检查 + 解锁广播 + 开机恢复
- 守护服务未启用时会如实提示，并提供「去开启」入口
- 长按置顶、搜索、三档筛选、右滑返回

### 📊 状态通知
- 通知栏显示已隐藏应用数与保活服务数，可在设置中开关

### ⚙️ 其他
- **应用本体保活**：常驻前台服务（START_STICKY，开机与划卡后自动拉起）
- 隐藏后台卡片（从多任务列表隐藏本软件）
- 开机自启动

## 系统要求

- Android 8.0+（API 26+）
- 已安装并启动 Shizuku 服务（应用管理 / 无障碍自动写入依赖它）

## 安装

1. 从 [Releases](https://github.com/czeyu31/RecentsHider/releases/latest) 或仓库根目录下载 APK
2. 安装到手机
3. 授权 Shizuku 权限

## 使用说明

- 首页右上角「设置」进入设置面板，可切换主题
- 首页点「工具箱」进入六项工具
- **首页右滑**进入无障碍管理；无障碍管理页**右滑**返回
- 列表点行即切换隐藏 / 启用，**长按**打开操作面板

## 已知限制

- **MIUI / 部分国产 ROM 会在应用安装或更新后撤销无障碍授权**（系统安全策略），更新后如提示「守护服务未启用」，进入无障碍管理点「去开启」手动开启一次
- 应用管理读取完整应用列表依赖 Shizuku：没有 Shizuku 时 MIUI 会拦截 `getInstalledApplications`，界面会明确提示，而不是显示不完整的列表

## 工作原理

- 应用隐藏：通过 Shizuku 的 `removeTask` / `setTaskInvisible` 将应用从最近任务列表移除
- 无障碍保活：通过 Shizuku 的 `settings put secure` 写入启用列表，配合 ContentObserver、定时检查、解锁广播恢复
- 后台保活：appops（RUN_IN_BACKGROUND / WAKE_LOCK 等）+ 电池优化白名单 + 常驻前台服务

## 技术栈

- Kotlin
- Jetpack Compose（BOM 2023.08.00）+ Material 3
- Shizuku API
- NanoHTTPD
- Glide

## 构建

```bash
# 依赖：JDK 17、Android SDK（platform 34）
./gradlew assembleRelease
```

`release` 使用 debug keystore 签名，便于覆盖安装已存在的 debug 包；正式发布请替换为你自己的签名配置。

## 许可证

MIT License
