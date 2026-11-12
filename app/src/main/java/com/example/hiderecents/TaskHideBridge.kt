package com.example.hiderecents

import android.os.IBinder

/**
 * 进程内共享的 Shizuku 用户服务 binder。
 *
 * 为什么需要：快捷设置磁贴（TileService）自己 bindUserService 往往来不及 ——
 * onClick 只给很短的时间窗口，首次拉起用户服务进程经常超过 2 秒，
 * 于是磁贴一直报「服务连接失败」。而应用本体 / HiddenGuardService 早就 bind 好了，
 * 磁贴直接复用同一个 binder 即可。
 */
object TaskHideBridge {
    @Volatile
    var binder: IBinder? = null
}
