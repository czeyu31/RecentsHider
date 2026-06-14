# UI Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [`) syntax for tracking.

**Goal:** 将 RecentsHider 重构为工具类软件 "SystemTool"，首页显示手机信息，工具箱覆盖在首页上，点击工具图标跳转到独立页面。

**Architecture:** 完全重写 MainActivity 为 Dashboard（系统信息首页），新建 ToolboxActivity（工具箱覆盖层）、AdbActivity（ADB命令）、NotificationHistoryActivity（历史通知）。使用 NotificationListenerService 监听通知。移除底部导航栏和卸载功能。

**Tech Stack:** Kotlin, Android Material Design 3, Shizuku API, NotificationListenerService, SharedPreferences

---

## UI 设计参考

参考文件位于 `C:\Users\lenovo\Desktop\新建文件夹 (2)\stitch_\stitch_\`：

| 文件 | 对应页面 |
|------|----------|
| `_1/code.html` | Dashboard 首页（系统信息） |
| `_2/code.html` | 工具箱覆盖层 |
| `_3/code.html` | 历史通知页面 |
| `_4/code.html` | 主题调色板 |
| `_5/code.html` | Dashboard 变体 |
| `adb/code.html` | ADB 命令执行页面 |
| `obsidian_system/DESIGN.md` | 设计系统规范 |

---

## File Structure

| 文件 | 操作 | 说明 |
|------|------|------|
| `app/src/main/java/com/example/hiderecents/MainActivity.kt` | 重写 | Dashboard 首页 |
| `app/src/main/java/com/example/hiderecents/ToolboxActivity.kt` | 新建 | 工具箱覆盖层 |
| `app/src/main/java/com/example/hiderecents/AdbActivity.kt` | 新建 | ADB 命令执行 |
| `app/src/main/java/com/example/hiderecents/NotificationHistoryActivity.kt` | 新建 | 历史通知 |
| `app/src/main/java/com/example/hiderecents/NotificationListenerServiceImpl.kt` | 新建 | 通知监听服务 |
| `app/src/main/java/com/example/hiderecents/AppDetailActivity.kt` | 新建 | 应用详情（从工具箱进入） |
| `app/src/main/AndroidManifest.xml` | 修改 | 注册新 Activity 和 Service |
| `app/src/main/res/layout/activity_main.xml` | 重写 | Dashboard 布局 |
| `app/src/main/res/layout/activity_toolbox.xml` | 新建 | 工具箱布局 |
| `app/src/main/res/layout/activity_adb.xml` | 新建 | ADB 布局 |
| `app/src/main/res/layout/activity_notification_history.xml` | 新建 | 历史通知布局 |
| `app/src/main/res/layout/activity_app_detail.xml` | 新建 | 应用详情布局 |
| `app/src/main/res/layout/dialog_color_picker.xml` | 修改 | 更新调色板样式 |
| `app/src/main/res/values/colors.xml` | 修改 | 更新颜色系统 |
| `app/src/main/res/values/strings.xml` | 修改 | 更新字符串 |
| `app/src/main/res/menu/main_menu.xml` | 删除 | 不再需要 |

---

### Task 1: 更新设计系统（colors.xml, strings.xml）

**Covers:** 主题颜色系统

**Files:**
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 更新 colors.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="black">#FF000000</color>
    <color name="white">#FFFFFFFF</color>

    <!-- Obsidian System Colors -->
    <color name="background">#FF131313</color>
    <color name="surface">#FF131313</color>
    <color name="surface_dim">#FF131313</color>
    <color name="surface_bright">#FF393939</color>
    <color name="surface_container_lowest">#FF0E0E0E</color>
    <color name="surface_container_low">#FF1C1B1B</color>
    <color name="surface_container">#FF20201F</color>
    <color name="surface_container_high">#FF2A2A2A</color>
    <color name="surface_container_highest">#FF353535</color>
    <color name="surface_variant">#FF353535</color>

    <color name="on_surface">#FFE5E2E1</color>
    <color name="on_surface_variant">#FFC2C6D7</color>
    <color name="outline">#FF8C90A0</color>
    <color name="outline_variant">#FF424655</color>

    <color name="primary">#FFB0C6FF</color>
    <color name="on_primary">#FF002D6E</color>
    <color name="primary_container">#FF558DFF</color>
    <color name="on_primary_container">#FF002661</color>

    <color name="secondary">#FFBDF4FF</color>
    <color name="on_secondary">#FF00363D</color>
    <color name="secondary_container">#FF00E3FD</color>
    <color name="on_secondary_container">#FF00616D</color>

    <color name="tertiary">#FFFFB692</color>
    <color name="on_tertiary">#FF562000</color>
    <color name="tertiary_container">#FFEB6A19</color>
    <color name="on_tertiary_container">#FF4B1B00</color>

    <color name="error">#FFFFB4AB</color>
    <color name="on_error">#FF690005</color>
    <color name="error_container">#FF93000A</color>
    <color name="on_error_container">#FFFFDAD6</color>
</resources>
```

- [ ] **Step 2: 更新 strings.xml**

```xml
<resources>
    <string name="app_name">系统工具</string>
    <string name="hidden_count">已隐藏 %d 个应用</string>
    <string name="toolbox">工具箱</string>
    <string name="adb_commands">ADB 命令</string>
    <string name="notification_history">历史通知</string>
    <string name="app_management">应用管理</string>
    <string name="app_detail">应用详情</string>
    <string name="cpu_usage">处理器 CPU</string>
    <string name="memory_usage">内存 RAM</string>
    <string name="storage">存储</string>
    <string name="battery">电池</string>
    <string name="network">网络</string>
    <string name="performance_mode">性能模式</string>
    <string name="system_health">系统健康度</string>
    <string name="one_key_boost">一键加速</string>
</resources>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/colors.xml app/src/main/res/values/strings.xml
git commit -m "refactor: update design system to Obsidian theme"
```

---

### Task 2: 重写 Dashboard 首页布局

**Covers:** 首页系统信息展示

**Files:**
- Rewrite: `app/src/main/res/layout/activity_main.xml`

- [ ] **Step 1: 创建新的 activity_main.xml**

参考 `_1/code.html` 的 Bento Grid 布局，使用 Android XML 实现：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@color/background">

    <!-- TopAppBar -->
    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="@color/background"
        android:padding="16dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:weightSum="3">

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="horizontal"
                android:gravity="center_vertical">

                <ImageView
                    android:layout_width="24dp"
                    android:layout_height="24dp"
                    android:src="@drawable/ic_terminal"
                    android:contentDescription="terminal"
                    app:tint="@color/primary" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="系统工具"
                    android:textSize="18sp"
                    android:textColor="@color/primary"
                    android:textStyle="bold"
                    android:layout_marginStart="8dp" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="2"
                android:orientation="horizontal"
                android:gravity="end"
                android:layout_marginEnd="16dp">

                <TextView
                    android:id="@+id/tvHiddenStatus"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="已隐藏 0"
                    android:textSize="12sp"
                    android:textColor="@color/on_surface_variant"
                    android:background="@drawable/bg_status_chip"
                    android:paddingHorizontal="12dp"
                    android:paddingVertical="4dp"
                    android:layout_marginEnd="12dp" />

                <ImageView
                    android:id="@+id/btnPalette"
                    android:layout_width="32dp"
                    android:layout_height="32dp"
                    android:src="@drawable/ic_color_palette"
                    android:contentDescription="palette"
                    android:padding="4dp"
                    android:background="@drawable/bg_icon_button"
                    app:tint="@color/primary"
                    android:layout_marginEnd="8dp" />

                <ImageView
                    android:id="@+id/btnToolbox"
                    android:layout_width="32dp"
                    android:layout_height="32dp"
                    android:src="@drawable/ic_toolbox"
                    android:contentDescription="toolbox"
                    android:padding="4dp"
                    android:background="@drawable/bg_icon_button"
                    app:tint="@color/primary" />
            </LinearLayout>
        </LinearLayout>
    </com.google.android.material.appbar.MaterialToolbar>

    <!-- ScrollView for Dashboard Content -->
    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:fillViewport="true"
        android:clipToPadding="false"
        android:paddingBottom="16dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="16dp">

            <!-- CPU Card -->
            <androidx.cardview.widget.CardView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginBottom="12dp"
                app:cardBackgroundColor="@color/surface_container_high"
                app:cardCornerRadius="12dp"
                app:cardElevation="0dp">

                <RelativeLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:padding="16dp">

                    <LinearLayout
                        android:id="@+id/cpuHeader"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:orientation="horizontal"
                        android:gravity="center_vertical">

                        <View
                            android:layout_width="8dp"
                            android:layout_height="8dp"
                            android:background="@drawable/bg_pulse_dot" />

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="处理器 CPU"
                            android:textSize="12sp"
                            android:textColor="@color/on_surface_variant"
                            android:layout_marginStart="8dp" />
                    </LinearLayout>

                    <!-- CPU Progress Ring -->
                    <FrameLayout
                        android:id="@+id/cpuRingContainer"
                        android:layout_width="120dp"
                        android:layout_height="120dp"
                        android:layout_centerHorizontal="true"
                        android:layout_below="@id/cpuHeader"
                        android:layout_marginTop="16dp">

                        <ProgressBar
                            android:id="@+id/cpuProgress"
                            style="@style/CircularProgress"
                            android:layout_width="120dp"
                            android:layout_height="120dp" />

                        <LinearLayout
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:orientation="vertical"
                            android:gravity="center"
                            android:layout_gravity="center">

                            <TextView
                                android:id="@+id/tvCpuPercent"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="0%"
                                android:textSize="22sp"
                                android:textColor="@color/primary"
                                android:textStyle="bold" />

                            <TextView
                                android:id="@+id/tvCpuFreq"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="0 GHz"
                                android:textSize="11sp"
                                android:textColor="@color/on_surface_variant" />
                        </LinearLayout>
                    </FrameLayout>

                    <!-- CPU Cores Bar Chart -->
                    <LinearLayout
                        android:id="@+id/cpuCoresChart"
                        android:layout_width="match_parent"
                        android:layout_height="40dp"
                        android:layout_below="@id/cpuRingContainer"
                        android:layout_marginTop="16dp"
                        android:orientation="horizontal"
                        android:gravity="bottom">

                        <!-- Core bars will be added dynamically -->
                    </LinearLayout>
                </RelativeLayout>
            </androidx.cardview.widget.CardView>

            <!-- Memory Card -->
            <androidx.cardview.widget.CardView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginBottom="12dp"
                app:cardBackgroundColor="@color/surface_container_high"
                app:cardCornerRadius="12dp"
                app:cardElevation="0dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="16dp">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="horizontal"
                        android:gravity="center_vertical">

                        <LinearLayout
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:orientation="vertical">

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="内存 RAM"
                                android:textSize="12sp"
                                android:textColor="@color/on_surface_variant" />

                            <LinearLayout
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:orientation="horizontal"
                                android:layout_marginTop="4dp">

                                <TextView
                                    android:id="@+id/tvMemUsed"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text="0 GB"
                                    android:textSize="18sp"
                                    android:textColor="@color/on_surface"
                                    android:textStyle="bold" />

                                <TextView
                                    android:id="@+id/tvMemTotal"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text=" / 0 GB"
                                    android:textSize="12sp"
                                    android:textColor="@color/on_surface_variant"
                                    android:layout_gravity="bottom"
                                    android:layout_marginBottom="2dp" />
                            </LinearLayout>
                        </LinearLayout>

                        <ImageView
                            android:layout_width="24dp"
                            android:layout_height="24dp"
                            android:src="@drawable/ic_memory"
                            app:tint="@color/primary_container" />
                    </LinearLayout>

                    <!-- Memory Bars -->
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:layout_marginTop="16dp">

                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:orientation="horizontal"
                            android:gravity="center_vertical"
                            android:layout_marginBottom="4dp">

                            <TextView
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:text="系统核心"
                                android:textSize="11sp"
                                android:textColor="@color/on_surface_variant" />

                            <TextView
                                android:id="@+id/tvMemSystem"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="0 GB"
                                android:textSize="11sp"
                                android:textColor="@color/on_surface_variant" />
                        </LinearLayout>

                        <ProgressBar
                            android:id="@+id/pbMemSystem"
                            style="@style/LinearProgress"
                            android:layout_width="match_parent"
                            android:layout_height="6dp"
                            android:progress="0" />

                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:orientation="horizontal"
                            android:gravity="center_vertical"
                            android:layout_marginTop="12dp"
                            android:layout_marginBottom="4dp">

                            <TextView
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:text="应用程序"
                                android:textSize="11sp"
                                android:textColor="@color/on_surface_variant" />

                            <TextView
                                android:id="@+id/tvMemApps"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="0 GB"
                                android:textSize="11sp"
                                android:textColor="@color/on_surface_variant" />
                        </LinearLayout>

                        <ProgressBar
                            android:id="@+id/pbMemApps"
                            style="@style/LinearProgress"
                            android:layout_width="match_parent"
                            android:layout_height="6dp"
                            android:progress="0" />
                    </LinearLayout>

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/btnBoost"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="16dp"
                        android:text="一键加速"
                        android:textSize="12sp"
                        app:icon="@drawable/ic_clean"
                        app:iconSize="18dp"
                        app:cornerRadius="8dp" />
                </LinearLayout>
            </androidx.cardview.widget.CardView>

            <!-- Battery Card -->
            <androidx.cardview.widget.CardView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginBottom="12dp"
                app:cardBackgroundColor="@color/surface_container_high"
                app:cardCornerRadius="12dp"
                app:cardElevation="0dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="16dp">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="电池"
                        android:textSize="12sp"
                        android:textColor="@color/on_surface_variant" />

                    <LinearLayout
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:orientation="horizontal"
                        android:layout_marginTop="8dp">

                        <TextView
                            android:id="@+id/tvBatteryPercent"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="0"
                            android:textSize="22sp"
                            android:textColor="@color/tertiary"
                            android:textStyle="bold" />

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="%"
                            android:textSize="12sp"
                            android:textColor="@color/on_surface_variant"
                            android:layout_gravity="bottom"
                            android:layout_marginBottom="2dp" />
                    </LinearLayout>

                    <LinearLayout
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:orientation="horizontal"
                        android:layout_marginTop="8dp">

                        <ImageView
                            android:layout_width="14dp"
                            android:layout_height="14dp"
                            android:src="@drawable/ic_thermostat"
                            app:tint="@color/tertiary" />

                        <TextView
                            android:id="@+id/tvBatteryTemp"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="0°C"
                            android:textSize="11sp"
                            android:textColor="@color/on_surface_variant"
                            android:layout_marginStart="4dp" />
                    </LinearLayout>

                    <LinearLayout
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:orientation="horizontal"
                        android:layout_marginTop="4dp">

                        <ImageView
                            android:layout_width="14dp"
                            android:layout_height="14dp"
                            android:src="@drawable/ic_bolt"
                            app:tint="@color/tertiary" />

                        <TextView
                            android:id="@+id/tvBatteryStatus"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="未知"
                            android:textSize="11sp"
                            android:textColor="@color/on_surface_variant"
                            android:layout_marginStart="4dp" />
                    </LinearLayout>
                </LinearLayout>
            </androidx.cardview.widget.CardView>

            <!-- System Health Section -->
            <androidx.cardview.widget.CardView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginBottom="12dp"
                app:cardBackgroundColor="@color/surface_container"
                app:cardCornerRadius="12dp"
                app:cardElevation="0dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="16dp">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="horizontal"
                        android:gravity="center_vertical">

                        <TextView
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:text="系统健康度"
                            android:textSize="18sp"
                            android:textColor="@color/on_surface"
                            android:textStyle="bold" />

                        <TextView
                            android:id="@+id/tvHealthStatus"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="良好"
                            android:textSize="11sp"
                            android:textColor="@color/primary"
                            android:background="@drawable/bg_health_chip"
                            android:paddingHorizontal="12dp"
                            android:paddingVertical="4dp" />
                    </LinearLayout>

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="horizontal"
                        android:layout_marginTop="16dp">

                        <LinearLayout
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:orientation="vertical">

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="运行时间"
                                android:textSize="11sp"
                                android:textColor="@color/on_surface_variant" />

                            <TextView
                                android:id="@+id/tvUptime"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="0h 0m"
                                android:textSize="14sp"
                                android:textColor="@color/on_surface"
                                android:textStyle="bold"
                                android:layout_marginTop="4dp" />
                        </LinearLayout>

                        <LinearLayout
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:orientation="vertical">

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="进程数"
                                android:textSize="11sp"
                                android:textColor="@color/on_surface_variant" />

                            <TextView
                                android:id="@+id/tvProcesses"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="0"
                                android:textSize="14sp"
                                android:textColor="@color/on_surface"
                                android:textStyle="bold"
                                android:layout_marginTop="4dp" />
                        </LinearLayout>

                        <LinearLayout
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:orientation="vertical">

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="Root 状态"
                                android:textSize="11sp"
                                android:textColor="@color/on_surface_variant" />

                            <TextView
                                android:id="@+id/tvRootStatus"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="未获取"
                                android:textSize="14sp"
                                android:textColor="@color/error"
                                android:textStyle="bold"
                                android:layout_marginTop="4dp" />
                        </LinearLayout>
                    </LinearLayout>
                </LinearLayout>
            </androidx.cardview.widget.CardView>

        </LinearLayout>
    </ScrollView>
</LinearLayout>
```

- [ ] **Step 2: 创建辅助 drawable 资源**

创建 `bg_status_chip.xml`, `bg_icon_button.xml`, `bg_pulse_dot.xml`, `bg_health_chip.xml`, `bg_circular_progress.xml`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/res/drawable/
git commit -m "refactor: new dashboard layout with system info cards"
```

---

### Task 3: 重写 MainActivity.kt（Dashboard 逻辑）

**Covers:** 首页系统信息展示、调色板、工具箱入口

**Files:**
- Rewrite: `app/src/main/java/com/example/hiderecents/MainActivity.kt`

- [ ] **Step 1: 重写 MainActivity**

新 MainActivity 功能：
- 显示 CPU 使用率（通过 `/proc/stat`）
- 显示内存使用（通过 `/proc/meminfo`）
- 显示电池状态（通过 `BatteryManager`）
- 显示系统运行时间（通过 `/proc/uptime`）
- 调色板按钮 → 弹出颜色选择器
- 工具箱按钮 → 打开 ToolboxActivity
- 定时刷新数据（每 2 秒）

核心代码结构：

```kotlin
package com.example.hiderecents

import android.content.*
import android.os.*
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.io.RandomAccessFile

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvCpuPercent: TextView
    private lateinit var tvCpuFreq: TextView
    private lateinit var tvMemUsed: TextView
    private lateinit var tvMemTotal: TextView
    private lateinit var tvMemSystem: TextView
    private lateinit var tvMemApps: TextView
    private lateinit var tvBatteryPercent: TextView
    private lateinit var tvBatteryTemp: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvProcesses: TextView
    private lateinit var tvRootStatus: TextView
    private lateinit var tvHiddenStatus: TextView
    private lateinit var tvHealthStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 2000L

    private val themeColors = intArrayOf(
        0xFF90CAF9.toInt(), 0xFFA5D6A7.toInt(), 0xFFCE93D8.toInt(), 0xFFFFCC80.toInt(),
        0xFFEF9A9A.toInt(), 0xFF80CBC4.toInt(), 0xFFF48FB1.toInt(), 0xFF80DEEA.toInt(),
        0xFFFFFFFF.toInt(), 0xFFBDBDBD.toInt(), 0xFF000000.toInt(), 0xFFA1887F.toInt()
    )
    private var currentThemeColor = 0

    private var prevCpuIdle: Long = 0
    private var prevCpuTotal: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        currentThemeColor = prefs.getInt("theme_color", 0)

        initViews()
        setupButtons()
        startDataRefresh()
    }

    private fun initViews() {
        tvCpuPercent = findViewById(R.id.tvCpuPercent)
        tvCpuFreq = findViewById(R.id.tvCpuFreq)
        tvMemUsed = findViewById(R.id.tvMemUsed)
        tvMemTotal = findViewById(R.id.tvMemTotal)
        tvMemSystem = findViewById(R.id.tvMemSystem)
        tvMemApps = findViewById(R.id.tvMemApps)
        tvBatteryPercent = findViewById(R.id.tvBatteryPercent)
        tvBatteryTemp = findViewById(R.id.tvBatteryTemp)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        tvUptime = findViewById(R.id.tvUptime)
        tvProcesses = findViewById(R.id.tvProcesses)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        tvHiddenStatus = findViewById(R.id.tvHiddenStatus)
        tvHealthStatus = findViewById(R.id.tvHealthStatus)

        val hiddenApps = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        tvHiddenStatus.text = "已隐藏 ${hiddenApps.size}"
    }

    private fun setupButtons() {
        findViewById<ImageView>(R.id.btnPalette).setOnClickListener { showThemeColorPicker() }
        findViewById<ImageView>(R.id.btnToolbox).setOnClickListener {
            startActivity(Intent(this, ToolboxActivity::class.java))
        }
    }

    private fun startDataRefresh() {
        handler.post(object : Runnable {
            override fun run() {
                updateCpuInfo()
                updateMemoryInfo()
                updateBatteryInfo()
                updateSystemInfo()
                handler.postDelayed(this, refreshInterval)
            }
        })
    }

    private fun updateCpuInfo() {
        try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val line = reader.readLine()
            reader.close()
            val parts = line.split("\\s+".toRegex())
            val idle = parts[4].toLong()
            val total = parts.drop(1).take(7).sumOf { it.toLong() }
            val diffIdle = idle - prevCpuIdle
            val diffTotal = total - prevCpuTotal
            val usage = if (diffTotal > 0) ((diffTotal - diffIdle) * 100 / diffTotal).toInt() else 0
            prevCpuIdle = idle
            prevCpuTotal = total
            tvCpuPercent.text = "$usage%"
            // Read CPU frequency
            val freq = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").readText().trim().toLongOrNull() ?: 0
            tvCpuFreq.text = "${freq / 1000} MHz"
        } catch (_: Exception) {}
    }

    private fun updateMemoryInfo() {
        try {
            val reader = RandomAccessFile("/proc/meminfo", "r")
            val lines = reader.readLines()
            reader.close()
            val memTotal = lines.first { it.startsWith("MemTotal") }.split("\\s+".toRegex())[1].toLong()
            val memAvail = lines.first { it.startsWith("MemAvailable") }.split("\\s+".toRegex())[1].toLong()
            val memUsed = memTotal - memAvail
            val totalGB = String.format("%.1f", memTotal / 1048576.0)
            val usedGB = String.format("%.1f", memUsed / 1048576.0)
            tvMemUsed.text = "$usedGB GB"
            tvMemTotal.text = " / $totalGB GB"
            // System/App split (approximate)
            val systemGB = String.format("%.1f", memTotal * 0.15 / 1048576.0)
            val appsGB = String.format("%.1f", memUsed * 0.85 / 1048576.0)
            tvMemSystem.text = "$systemGB GB"
            tvMemApps.text = "$appsGB GB"
        } catch (_: Exception) {}
    }

    private fun updateBatteryInfo() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val temp = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val status = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0) ?: 0

        tvBatteryPercent.text = "$level"
        tvBatteryTemp.text = "${temp / 10}°C"
        tvBatteryStatus.text = when (status) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "已满"
            else -> "未知"
        }
    }

    private fun updateSystemInfo() {
        try {
            val uptime = File("/proc/uptime").readText().split(" ")[0].toDouble().toLong()
            val hours = uptime / 3600
            val minutes = (uptime % 3600) / 60
            tvUptime.text = "${hours}h ${minutes}m"

            val procDir = File("/proc")
            val procCount = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }?.size ?: 0
            tvProcesses.text = "$procCount"

            val rootCheck = arrayOf("/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su")
            val isRooted = rootCheck.any { File(it).exists() }
            tvRootStatus.text = if (isRooted) "已获取" else "未获取"
            tvRootStatus.setTextColor(getColor(if (isRooted) R.color.primary else R.color.error))

            val health = when {
                procCount > 500 -> "警告"
                procCount > 300 -> "一般"
                else -> "良好"
            }
            tvHealthStatus.text = health
        } catch (_: Exception) {}
    }

    private fun showThemeColorPicker() {
        // Reuse existing color picker dialog
        val dialogView = layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("选择主题颜色").setView(dialogView).setCancelable(true).create()
        val ids = listOf(R.id.color_0, R.id.color_1, R.id.color_2, R.id.color_3, R.id.color_4, R.id.color_5,
            R.id.color_6, R.id.color_7, R.id.color_8, R.id.color_9, R.id.color_10, R.id.color_11)
        ids.forEachIndexed { index, id ->
            dialogView.findViewById<android.view.View>(id).setOnClickListener {
                currentThemeColor = index
                prefs.edit().putInt("theme_color", index).apply()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/hiderecents/MainActivity.kt
git commit -m "refactor: rewrite MainActivity as system dashboard"
```

---

### Task 4: 创建 ToolboxActivity（工具箱覆盖层）

**Covers:** 工具箱覆盖层

**Files:**
- Create: `app/src/main/java/com/example/hiderecents/ToolboxActivity.kt`
- Create: `app/src/main/res/layout/activity_toolbox.xml`

- [ ] **Step 1: 创建 activity_toolbox.xml**

参考 `_2/code.html` 的 3x3 网格布局：

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#99000000">

    <!-- Toolbox Panel (doesn't fully cover screen) -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:orientation="vertical"
        android:background="@drawable/bg_toolbox_panel"
        android:padding="24dp"
        android:layout_marginHorizontal="24dp">

        <!-- Header -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:layout_marginBottom="24dp">

            <ImageView
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:src="@drawable/ic_toolbox"
                android:contentDescription="toolbox"
                android:layout_marginEnd="8dp" />

            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="工具箱"
                android:textSize="18sp"
                android:textColor="@color/on_surface"
                android:textStyle="bold" />

            <ImageView
                android:id="@+id/btnClose"
                android:layout_width="32dp"
                android:layout_height="32dp"
                android:src="@drawable/ic_close"
                android:contentDescription="close"
                android:padding="4dp" />
        </LinearLayout>

        <!-- 3x3 Grid -->
        <GridLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:columnCount="3"
            android:rowCount="3"
            android:useDefaultMargins="true">

            <!-- ADB 命令 -->
            <LinearLayout
                android:id="@+id/toolAdb"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_columnWeight="1"
                android:orientation="vertical"
                android:gravity="center"
                android:padding="12dp">

                <ImageView
                    android:layout_width="48dp"
                    android:layout_height="48dp"
                    android:src="@drawable/ic_terminal"
                    android:background="@drawable/bg_tool_icon_primary"
                    android:padding="12dp"
                    android:contentDescription="adb" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="ADB 命令"
                    android:textSize="12sp"
                    android:textColor="@color/on_surface_variant"
                    android:layout_marginTop="8dp" />
            </LinearLayout>

            <!-- 应用管理 -->
            <LinearLayout
                android:id="@+id/toolAppManage"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_columnWeight="1"
                android:orientation="vertical"
                android:gravity="center"
                android:padding="12dp">

                <ImageView
                    android:layout_width="48dp"
                    android:layout_height="48dp"
                    android:src="@drawable/ic_apps"
                    android:background="@drawable/bg_tool_icon_secondary"
                    android:padding="12dp"
                    android:contentDescription="apps" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="应用管理"
                    android:textSize="12sp"
                    android:textColor="@color/on_surface_variant"
                    android:layout_marginTop="8dp" />
            </LinearLayout>

            <!-- 历史通知 -->
            <LinearLayout
                android:id="@+id/toolNotifications"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_columnWeight="1"
                android:orientation="vertical"
                android:gravity="center"
                android:padding="12dp">

                <ImageView
                    android:layout_width="48dp"
                    android:layout_height="48dp"
                    android:src="@drawable/ic_notifications"
                    android:background="@drawable/bg_tool_icon_tertiary"
                    android:padding="12dp"
                    android:contentDescription="notifications" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="历史通知"
                    android:textSize="12sp"
                    android:textColor="@color/on_surface_variant"
                    android:layout_marginTop="8dp" />
            </LinearLayout>
        </GridLayout>

        <!-- Scroll hint -->
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="点击图标进入功能页面"
            android:textSize="11sp"
            android:textColor="@color/on_surface_variant"
            android:layout_gravity="center"
            android:layout_marginTop="16dp"
            android:alpha="0.5" />
    </LinearLayout>
</FrameLayout>
```

- [ ] **Step 2: 创建 ToolboxActivity.kt**

```kotlin
package com.example.hiderecents

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class ToolboxActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toolbox)

        findViewById<ImageView>(R.id.btnClose).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.toolAdb).setOnClickListener {
            startActivity(Intent(this, AdbActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.toolAppManage).setOnClickListener {
            startActivity(Intent(this, AppManageActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.toolNotifications).setOnClickListener {
            startActivity(Intent(this, NotificationHistoryActivity::class.java))
        }
    }
}
```

- [ ] **Step 3: 创建辅助 drawable**

创建 `bg_toolbox_panel.xml`, `bg_tool_icon_primary.xml`, `bg_tool_icon_secondary.xml`, `bg_tool_icon_tertiary.xml`, `ic_close.xml`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/hiderecents/ToolboxActivity.kt app/src/main/res/layout/activity_toolbox.xml app/src/main/res/drawable/
git commit -m "feat: add ToolboxActivity with overlay panel"
```

---

### Task 5: 创建 AdbActivity（ADB 命令执行）

**Covers:** ADB 命令功能

**Files:**
- Create: `app/src/main/java/com/example/hiderecents/AdbActivity.kt`
- Create: `app/src/main/res/layout/activity_adb.xml`

- [ ] **Step 1: 创建 activity_adb.xml**

参考 `adb/code.html` 的终端风格布局，包含返回按钮、设备信息、命令输入、终端输出。

- [ ] **Step 2: 创建 AdbActivity.kt**

复用现有的 `executeCommandSync` 和 Shizuku 绑定逻辑，但作为独立 Activity。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/hiderecents/AdbActivity.kt app/src/main/res/layout/activity_adb.xml
git commit -m "feat: add AdbActivity for ADB command execution"
```

---

### Task 6: 创建 AppManageActivity（应用管理）

**Covers:** 应用管理功能（保留原有隐藏/恢复功能，移除卸载）

**Files:**
- Create: `app/src/main/java/com/example/hiderecents/AppManageActivity.kt`
- Create: `app/src/main/res/layout/activity_app_manage.xml`
- Create: `app/src/main/res/layout/item_app_manage.xml`

- [ ] **Step 1: 创建应用管理布局**

保留原有的应用列表和搜索功能，移除卸载按钮，保留隐藏/恢复开关和应用详情入口。

- [ ] **Step 2: 创建 AppManageActivity.kt**

从原 MainActivity 迁移应用列表、搜索、隐藏/恢复功能。移除卸载功能。长按图标打开 AppDetailActivity。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/hiderecents/AppManageActivity.kt app/src/main/res/layout/
git commit -m "feat: add AppManageActivity with hide/restore (no uninstall)"
```

---

### Task 7: 创建 AppDetailActivity（应用详情）

**Covers:** 应用详情功能

**Files:**
- Create: `app/src/main/java/com/example/hiderecents/AppDetailActivity.kt`
- Create: `app/src/main/res/layout/activity_app_detail.xml`

- [ ] **Step 1: 迁移应用详情 UI**

从原 `showAppDetail` 对话框迁移为独立 Activity，保留所有信息展示和 APK 提取功能。

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/hiderecents/AppDetailActivity.kt app/src/main/res/layout/activity_app_detail.xml
git commit -m "feat: add AppDetailActivity (migrated from dialog)"
```

---

### Task 8: 创建 NotificationListenerService（通知监听）

**Covers:** 历史通知数据收集

**Files:**
- Create: `app/src/main/java/com/example/hiderecents/NotificationListenerServiceImpl.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 创建 NotificationListenerServiceImpl**

```kotlin
package com.example.hiderecents

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class NotificationListenerServiceImpl : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val packageName = sbn.packageName

        if (title.isEmpty() && text.isEmpty()) return

        saveNotification(packageName, title, text, sbn.postTime)
    }

    private fun saveNotification(packageName: String, title: String, text: String, time: Long) {
        val prefs = getSharedPreferences("notification_history", Context.MODE_PRIVATE)
        val existing = prefs.getString("notifications", "[]") ?: "[]"
        val array = JSONArray(existing)

        val obj = JSONObject().apply {
            put("packageName", packageName)
            put("title", title)
            put("text", text)
            put("time", time)
        }

        array.put(obj)

        // Keep only last 1000 notifications
        if (array.length() > 1000) {
            val trimmed = JSONArray()
            for (i in array.length() - 1000 until array.length()) {
                trimmed.put(array[i])
            }
            prefs.edit().putString("notifications", trimmed.toString()).apply()
        } else {
            prefs.edit().putString("notifications", array.toString()).apply()
        }
    }
}
```

- [ ] **Step 2: 更新 AndroidManifest.xml**

添加权限和服务声明：

```xml
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />
```

在 `<application>` 内添加：

```xml
<service
    android:name=".NotificationListenerServiceImpl"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/hiderecents/NotificationListenerServiceImpl.kt app/src/main/AndroidManifest.xml
git commit -m "feat: add NotificationListenerService for history"
```

---

### Task 9: 创建 NotificationHistoryActivity（历史通知页面）

**Covers:** 历史通知展示

**Files:**
- Create: `app/src/main/java/com/example/hiderecents/NotificationHistoryActivity.kt`
- Create: `app/src/main/res/layout/activity_notification_history.xml`
- Create: `app/src/main/res/layout/item_notification.xml`

- [ ] **Step 1: 创建布局**

参考 `_3/code.html` 的分栏布局（左侧应用列表 + 右侧通知列表）。

- [ ] **Step 2: 创建 NotificationHistoryActivity.kt**

从 SharedPreferences 读取通知数据，按应用分组显示。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/hiderecents/NotificationHistoryActivity.kt app/src/main/res/layout/
git commit -m "feat: add NotificationHistoryActivity"
```

---

### Task 10: 清理旧代码和资源

**Covers:** 移除不需要的功能

**Files:**
- Delete: `app/src/main/res/menu/bottom_nav.xml`
- Delete: `app/src/main/res/layout/fragment_adb.xml`
- Modify: `app/src/main/res/menu/main_menu.xml` (删除或简化)
- Modify: `app/src/main/AndroidManifest.xml` (移除 BottomNavigation 相关)

- [ ] **Step 1: 删除不需要的资源文件**

- [ ] **Step 2: 更新移除 BottomNavigation 引用**

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: remove bottom nav and unused resources"
```

---

### Task 11: 构建和测试

**Covers:** 验证功能

- [ ] **Step 1: 构建 APK**

```bash
cd C:\Users\lenovo\HideRecents
.\gradlew assembleRelease
```

- [ ] **Step 2: 修复编译错误**

- [ ] **Step 3: 安装测试**

```bash
adb install -r app\build\outputs\apk\release\app-release.apk
```

- [ ] **Step 4: 验证功能**

1. 首页显示系统信息（CPU、内存、电池）
2. 右上角调色板可切换主题
3. 右上角工具箱打开覆盖层
4. 点击工具箱图标跳转到对应页面
5. ADB 命令可执行
6. 应用管理可隐藏/恢复应用
7. 历史通知可查看

- [ ] **Step 5: Commit 最终版本**

```bash
git add -A
git commit -m "refactor: complete UI refactoring to SystemTool"
```
