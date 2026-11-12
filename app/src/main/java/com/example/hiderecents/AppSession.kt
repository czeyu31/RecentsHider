package com.example.hiderecents

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 进程内的会话状态。
 *
 * 关键点：这些字段都是静态的，进程被系统杀掉时会随之清空。
 * 所以「lastActivityName 不为空」就等价于「进程没被杀」——
 * 不需要额外存磁盘，也不会误判。
 */
object AppSession {

    @Volatile
    private var lastActivityName: String? = null

    @Volatile
    private var foreground: Boolean = false

    /** 进程活着时返回上次停留的页面类名，进程被杀后返回 null */
    fun lastActivityName(): String? = lastActivityName

    fun isWarm(): Boolean = lastActivityName != null

    fun isForeground(): Boolean = foreground

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    lastActivityName = activity.javaClass.name
                    foreground = true
                }

                override fun onActivityPaused(activity: Activity) {
                    foreground = false
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
    }
}
