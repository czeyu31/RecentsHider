package com.example.hiderecents

import com.example.hiderecents.ui.screens.ManagedApp

/**
 * 应用列表的进程级缓存。
 *
 * 为什么需要：应用管理页每次 onCreate 都会重新 `pm list packages` 并给 550 个应用
 * 逐个解码图标，从工具箱反复进来时每次都要等十几秒（用户看到的就是「一直加载不出来」）。
 * 缓存住整份 List<ManagedApp> 后，第二次进入可以立刻渲染，隐藏状态再按偏好重算。
 */
object AppListCache {
    @Volatile
    var apps: List<ManagedApp>? = null

    @Volatile
    var cachedAt: Long = 0L

    fun put(list: List<ManagedApp>) {
        apps = list
        cachedAt = System.currentTimeMillis()
    }

    fun clear() {
        apps = null
        cachedAt = 0L
    }
}
