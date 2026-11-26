# System Tool

一款基于 Shizuku 的 Android 系统工具箱，集成应用隐藏、无障碍保活、通知历史、局域网远控、SIM 国家码、BLE 心率与温度监控等功能。

## ⬇️ 下载

[![Download APK](https://img.shields.io/github/v/release/czeyu31/RecentsHider?label=下载最新版&style=for-the-badge)](https://github.com/czeyu31/RecentsHider/releases/latest)

或访问 [Releases 页面](https://github.com/czeyu31/RecentsHider/releases) 查看所有版本。

## 功能

### 🏠 主页面
- Shizuku 服务连接状态
- 已隐藏应用数量、守护中的服务数量
- 后台保活 / 状态通知 / 后台卡片三个开关
- 左滑进入无障碍管理页面

### 🧰 工具箱
工具箱里的九个工具，全部在本机执行。

#### 👁️ 应用管理
- 🔍 搜索应用
- 从最近任务中隐藏/恢复应用
- 🗑️ 卸载应用（保留数据/删除数据）
- 📱 查看应用详情（包名、版本、权限）
- 📦 提取 APK
- 📌 长按置顶常用应用
- 仅显示用户应用过滤

#### 💻 ADB 命令
- 以 shell 身份执行常用命令
- 内置常用命令目录，也支持自定义命令
- 命令执行结果直接回显

#### 📋 历史通知
- 记录所有应用的通知
- 按应用筛选通知记录
- 左滑单条菜单：查看详情 / 删除
- 详情浮层可复制通知内容
- 一键清空历史

#### 📱 APK 安装器
- 本地 APK 安装，带签名校验
- 查看应用详情（包名、版本、权限）

#### 📂 文件传输
- 局域网内收发文件
- 支持多种文件类型

#### 📲 手机远控
- 局域网内把本机屏幕共享给另一台手机
- 也可以反过来查看对端画面
- 仅传输画面，不走公网

#### 📶 SIM 国家码
- 通过 Shizuku 修改系统读到的运营商国家（`CarrierConfig` 的 `sim_country_iso_override_string`）
- 不改动 SIM 卡本身，双卡可分别设置，随时还原
- 覆盖前后对照、调用记录与脱敏日志导出
- 部分 ROM 直接以 shell 身份写会被拒，自动降级到 Instrumentation 借权限身份写入

#### ❤️ 心率广播
- 通过 BLE 接收手环、手表的实时心率
- 按需录制并查看心率曲线
- 数据只保存在本机

#### 🏝️ 原生岛
- 心率岛与温度岛，各自独立开关
- 状态常驻通知栏与灵动岛样式的浮层

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

### 🌡️ 温度监控
- 实时显示本机温度
- 历史温度曲线
- 历史数据回放

### 📊 状态通知
- 通知栏显示隐藏应用数和保活服务数
- 可在设置中开关

### ⚙️ 其他功能
- 隐藏后台卡片（从多任务列表隐藏本软件）
- 后台保活（通过 Shizuku 设置电池优化白名单等）
- 开机自启动
- 快捷设置磁贴（无障碍开关等）

## 系统要求

- Android 8.0+ (API 26+)
- 已安装并启动 Shizuku 服务（应用隐藏、无障碍保活、ADB 命令依赖它）

## 安装

1. 从 [Releases](https://github.com/czeyu31/RecentsHider/releases/latest) 下载最新 APK
2. 安装到手机
3. 授权 Shizuku 权限

## 使用说明

- 主页面右上角「设置」打开设置
- 主页面点击「工具箱」进入工具箱
- **主页面左滑**进入无障碍管理，无障碍管理页面**右滑**返回主页
- 点击应用开关隐藏/恢复应用
- **长按**应用或服务可置顶、查看详情、设置防回收保护

## 工作原理

- 应用隐藏：通过 Shizuku 的 `removeTask` API 将指定应用从最近任务列表中移除
- 无障碍保活：通过 Shizuku 的 `settings put secure` 命令写入安全设置，ContentObserver + 定时检查 + 解锁广播三重保活
- 后台保活：通过 Shizuku 设置 `RUN_IN_BACKGROUND`、电池优化白名单等系统权限
- 手机远控：局域网内基于 scrcpy 协议传输 H.264 画面与触控事件

## 技术栈

- Kotlin
- Jetpack Compose
- Shizuku API
- NanoHTTPD

## 致谢

- SIM 国家码的实现思路参考了 [Nrfr](https://github.com/Ackites/Nrfr)（Apache-2.0）：
  调 `ICarrierConfigLoader.overrideConfig` 改运营商配置，以及用一次 Instrumentation 启动
  取得 shell 权限身份的兜底方式。本项目的代码是按自身架构重写的，未直接复制其源码。

## 许可证

MIT License