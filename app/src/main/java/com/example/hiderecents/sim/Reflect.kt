package com.example.hiderecents.sim

import android.app.ActivityManager
import android.os.IBinder
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 隐藏 API 与系统服务的反射入口。
 *
 * 存在的唯一理由：ServiceManager / IActivityManager / ICarrierConfigLoader 都是 Android 的
 * 隐藏内部类，公共 SDK 的 android.jar 里没有它们。项目不替换 android.jar（那会让每台构建机
 * 都得先换 jar），所以统一走反射 —— 与 TaskHideService 里调 IActivityManager 的做法一致。
 *
 * targetSdk 34 起反射隐藏 API 会被拦，先调 HiddenApiBypass 打豁免，否则 Class.forName 直接失败。
 */
internal object Reflect {

    private const val TAG = "SimReflect"

    private val hiddenApiReady: Boolean by lazy {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("L")
            HiddenApiBypass.addHiddenApiExemptions("I")
            true
        }.onFailure { Log.w(TAG, "HiddenApiBypass 不可用：" + it.message) }.getOrDefault(false)
    }

    fun ensureHiddenApi() {
        hiddenApiReady
    }

    fun service(name: String): IBinder? = runCatching {
        ensureHiddenApi()
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, name) as? IBinder
    }.onFailure { Log.w(TAG, "取服务 " + name + " 失败：" + it.message) }.getOrNull()

    /** 反射取 INSTR_FLAG_*；字段缺失时用 AOSP 里的位值兜底 */
    fun instrFlag(name: String, fallback: Int): Int = runCatching {
        ensureHiddenApi()
        ActivityManager::class.java.getField(name).getInt(null)
    }.getOrDefault(fallback)
}