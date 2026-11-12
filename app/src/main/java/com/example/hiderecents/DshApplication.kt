package com.example.hiderecents

import android.app.Application

/**
 * 启动时做两件事：
 *  1. 装会话追踪回调，让 SplashActivity 能判断「进程是否还活着」
 *  2. 清理上一进程遗留的岛通知（岛会话状态只活在进程里，进程一死它们就再也无法更新）
 */
class DshApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppSession.install(this)
        AppLogger.init(this)
        SilentApkInstaller.appContext = this
        AppLogger.i("DshApplication onCreate")
        Unit
    }
}
