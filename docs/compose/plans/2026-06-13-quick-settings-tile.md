# Quick Settings Tile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [`) syntax for tracking.

**Goal:** 在 Android 快速设置面板中添加一个磁贴开关，点击可切换隐藏/恢复所有已标记的应用。

**Architecture:** 使用 Android `TileService` API 创建快速设置磁贴。磁贴通过 `SharedPreferences` 读取已隐藏的应用列表，通过 Shizuku 调用 `removeTask` API 执行隐藏操作。磁贴状态（激活/未激活）反映当前是否有应用被隐藏。

**Tech Stack:** Kotlin, Android TileService API, Shizuku API, SharedPreferences

---

## File Structure

| 文件 | 操作 | 说明 |
|------|------|------|
| `app/src/main/java/com/example/hiderecents/QuickSettingsService.kt` | 新建 | 快速设置磁贴服务 |
| `app/src/main/AndroidManifest.xml` | 修改 | 注册 QuickSettingsService |
| `app/src/main/res/drawable/ic_qs_visibility.xml` | 新建 | 磁贴图标（可见状态） |
| `app/src/main/res/drawable/ic_qs_visibility_off.xml` | 新建 | 磁贴图标（隐藏状态） |

---

### Task 1: 创建磁贴图标资源

**Covers:** 磁贴视觉设计

**Files:**
- Create: `app/src/main/res/drawable/ic_qs_visibility.xml`
- Create: `app/src/main/res/drawable/ic_qs_visibility_off.xml`

- [ ] **Step 1: 创建 visibility 图标（眼睛睁开）**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z"/>
</vector>
```

- [ ] **Step 2: 创建 visibility_off 图标（眼睛闭合）**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2zM12,9c-1.66,0 -3,1.34 -3,3 0,0.79 0.31,1.5 0.81,2.04l1.23,1.23c0.05,-0.02 0.1,-0.04 0.15,-0.06l1.55,1.55c-0.17,0.04 -0.35,0.06 -0.54,0.06 -0.79,0 -1.53,-0.2 -2.2,-0.53L8.53,15.54C9.26,16.2 10.1,16.62 11,16.82l1.23,1.23C12.5,18.04 12.25,18 12,18c-0.79,0 -1.53,-0.2 -2.2,-0.53l-1.55,1.55C8.67,18.69 9.23,18.5 9.8,18.5c1.66,0 3,-1.34 3,-3 0,-0.22 -0.03,-0.44 -0.08,-0.65l1.55,1.55c0.33,-0.67 0.53,-1.41 0.53,-2.2 0,-2.76 -2.24,-5 -5,-5z"/>
</vector>
```

- [ ] **Step 3: Commit 图标资源**

```bash
git add app/src/main/res/drawable/ic_qs_visibility.xml app/src/main/res/drawable/ic_qs_visibility_off.xml
git commit -m "feat: add quick settings tile icons"
```

---

### Task 2: 创建 QuickSettingsService

**Covers:** 磁贴核心逻辑

**Files:**
- Create: `app/src/main/java/com/example/hiderecents/QuickSettingsService.kt`

- [ ] **Step 1: 创建 QuickSettingsService 类**

```kotlin
package com.example.hiderecents

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class QuickSettingsService : TileService() {

    private var taskHideService: IBinder? = null
    private var serviceReady = false
    private lateinit var prefs: SharedPreferences

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            serviceReady = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null
            serviceReady = false
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        updateTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
        try {
            Shizuku.unbindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java)),
                serviceConnection, true
            )
        } catch (_: Exception) {}
        taskHideService = null
        serviceReady = false
    }

    override fun onClick() {
        super.onClick()

        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            return
        }

        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先授权 Shizuku", Toast.LENGTH_SHORT).show()
            return
        }

        if (!serviceReady) {
            bindService()
        }

        val hiddenApps = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()

        if (hiddenApps.isEmpty()) {
            Toast.makeText(this, "没有已标记的应用", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            if (!serviceReady) {
                Thread.sleep(500)
            }

            val binder = taskHideService
            if (binder == null) {
                runOnUi { Toast.makeText(this, "服务连接失败", Toast.LENGTH_SHORT).show() }
                return@Thread
            }

            val allHidden = prefs.getBoolean("all_hidden", false)

            if (allHidden) {
                for (pkg in hiddenApps) {
                    callSetInvisible(binder, pkg, false)
                }
                prefs.edit().putBoolean("all_hidden", false).apply()
                runOnUi { Toast.makeText(this, "已恢复 ${hiddenApps.size} 个应用", Toast.LENGTH_SHORT).show() }
            } else {
                for (pkg in hiddenApps) {
                    callSetInvisible(binder, pkg, true)
                }
                prefs.edit().putBoolean("all_hidden", true).apply()
                runOnUi { Toast.makeText(this, "已隐藏 ${hiddenApps.size} 个应用", Toast.LENGTH_SHORT).show() }
            }

            updateTileState()
        }.start()
    }

    private fun bindService() {
        try {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("taskhide").debuggable(true).version(1),
                serviceConnection
            )
        } catch (_: Exception) {}
    }

    private fun callSetInvisible(binder: IBinder, packageName: String, invisible: Boolean): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(packageName)
            data.writeByte(if (invisible) 1 else 0)
            binder.transact(ITaskHideService.TRANSACTION_setTaskInvisible, data, reply, 0)
            reply.readException()
            return reply.readInt() != 0
        } catch (_: Exception) {
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val hiddenApps = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        val allHidden = prefs.getBoolean("all_hidden", false)

        if (hiddenApps.isEmpty()) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "隐藏最近"
            tile.icon = com.android.internal.R.drawable.ic_qs_visibility_off
        } else if (allHidden) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "已隐藏"
            tile.icon = com.android.internal.R.drawable.ic_qs_visibility
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "隐藏最近"
            tile.icon = com.android.internal.R.drawable.ic_qs_visibility_off
        }

        tile.updateTile()
    }

    private fun runOnUi(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }
}
```

- [ ] **Step 2: Commit QuickSettingsService**

```bash
git add app/src/main/java/com/example/hiderecents/QuickSettingsService.kt
git commit -m "feat: add QuickSettingsService for tile toggle"
```

---

### Task 3: 更新 AndroidManifest.xml

**Covers:** 服务注册

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 添加 QSG_TILE 权限和服务声明**

在 `<uses-permission>` 标签后添加：

```xml
<uses-permission android:name="android.permission.BIND_QUICK_SETTINGS_TILE" />
```

在 `<provider>` 标签后、`</application>` 前添加：

```xml
<service
    android:name=".QuickSettingsService"
    android:icon="@drawable/ic_qs_visibility"
    android:label="隐藏最近"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>
```

- [ ] **Step 2: Commit Manifest 更新**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: register QuickSettingsService in manifest"
```

---

### Task 4: 更新 MainActivity 支持 all_hidden 状态

**Covers:** 数据同步

**Files:**
- Modify: `app/src/main/java/com/example/hiderecents/MainActivity.kt`

- [ ] **Step 1: 在 restoreAllApps 方法中重置 all_hidden 状态**

在 `restoreAllApps()` 方法的 `hiddenApps.clear()` 后添加：

```kotlin
prefs.edit().putBoolean("all_hidden", false).apply()
```

- [ ] **Step 2: 在 toggleAppVisibility 方法中同步状态**

在 `toggleAppVisibility()` 方法中，当所有应用都被恢复时（`hiddenApps.isEmpty()`），添加：

```kotlin
if (hiddenApps.isEmpty()) {
    prefs.edit().putBoolean("all_hidden", false).apply()
}
```

- [ ] **Step 3: Commit MainActivity 更新**

```bash
git add app/src/main/java/com/example/hiderecents/MainActivity.kt
git commit -m "feat: sync all_hidden state with QuickSettingsService"
```

---

### Task 5: 构建和测试

**Covers:** 验证功能

**Files:**
- 无新文件

- [ ] **Step 1: 构建 APK**

```bash
cd C:\Users\lenovo\HideRecents
.\gradlew assembleRelease
```

- [ ] **Step 2: 安装到设备测试**

```bash
adb install -r app\build\outputs\apk\release\app-release.apk
```

- [ ] **Step 3: 测试快速设置磁贴**

1. 下拉通知栏，进入编辑模式
2. 找到"隐藏最近"磁贴，添加到快速设置面板
3. 在主应用中标记几个应用为隐藏
4. 点击磁贴，验证应用是否被隐藏
5. 再次点击磁贴，验证应用是否被恢复

- [ ] **Step 4: Commit 最终版本**

```bash
git add -A
git commit -m "feat: complete quick settings tile feature"
```
