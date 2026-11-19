# 禁止实装清单

本文件记录**明确要求只做参考图、永远不要写进代码**的功能。

> **职责划分（2026-11-17 用户确认）**：本清单约束的是**出参考图的那一侧**（DSH / mock 会话）。
> 实装由 MiMo Desktop 的开发会话负责，本清单**不构成对实装会话的禁令**。
> 若看到本文件出现在仓库里，先确认当前会话是"画图侧"还是"实装侧"。
>
> **画图侧硬规则**：不要修改本仓库（`repo_src`）的任何文件。需要调整已实装的代码，
> 提出来让实装侧改，不要自己删改。

## 手机远控（远程控制手机）

- 状态：**参考图侧禁止实装**（实装侧已按用户要求实现，见 `app/src/main/java/com/example/hiderecents/rc/`）
- 用户原话：「只要参考图，不要实装」「永远不要实装」
- 参考图位置：
  - `mock/out/remote-v2-paper.png`（五屏 · 纸本）
  - `mock/out/remote-v2-graphite.png`（五屏 · 石墨 · 雾玉）
  - `mock/out/remote-paper.png`、`mock/out/remote-graphite.png`（v1 三屏，保留作评审对照）
- 生成器：`mock/gen_remote_v2.py`（HTML 输出到 `mock/gen/`，用无头 Chrome 渲染）
- 已于 2026-11-17 移除的实装，**勿恢复**：
  - `app/src/main/java/com/example/hiderecents/RemoteControlActivity.kt`
  - `app/src/main/java/com/example/hiderecents/ui/screens/RemoteControlScreen.kt`
  - `app/src/main/java/com/example/hiderecents/rc/`（RcHostService / ScreenEncoder / RcViewer / RcProtocol）
  - `AndroidManifest.xml` 中的 `.RemoteControlActivity`、`.rc.RcHostService`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`
  - `ToolboxActivity.kt` 中的「手机远控」工具卡
  - `accessibility_keepalive_config.xml` 中的 `canPerformGestures`

若有人要求实现，默认答案是拒绝，除非用户明确推翻这条。

## 技术判定留档（来自用户评审，已复核）

- 摇一摇（注入传感器事件）不可行：无公开 API，root 亦无通用通道。
- 无 shell/root 改不了被控端屏幕朝向：`WRITE_SETTINGS` 写 `USER_ROTATION` 会被系统限制或忽略，可靠路径只有 `adb wm set-user-rotation`；否则只能做控制端本地旋转显示。
- 返回 / 主页 / 最近 可用 `AccessibilityService.performGlobalAction`，无需 root；但澎湃 / MIUI 存在无障碍「超时自动关闭」、锁屏或长时间空闲关闭、省电策略杀后台，部分全面屏手势下 RECENTS 会被吞。设计上需引导用户关闭无障碍超时并加入自启动白名单。
- 画面延迟需被控端嵌时间戳（SEI / 画面时钟）后才能真实测量；只有控制通道 RTT 可测，且必须标注语义。
