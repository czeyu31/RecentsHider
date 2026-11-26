package com.example.hiderecents.sim

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.Process
import android.telephony.CarrierConfigManager
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper

internal const val ARG_CALLER_PID = "sim_caller_pid"
internal const val ARG_SUB_ID = "sim_sub_id"
internal const val ARG_CONFIG = "sim_config"
internal const val ARG_RESET = "sim_reset"

/**
 * 兜底通道：借一次 Instrumentation 启动，把本进程的 uid 临时挂上 shell 的权限身份，
 * 再用 CarrierConfigManager.overrideConfig 写入。
 *
 * 为什么需要：部分 ROM 直接用 shell 身份调 ICarrierConfigLoader.overrideConfig 会抛
 * SecurityException("cannot be invoked by shell")。而 startDelegateShellPermissionIdentity
 * 只是让本进程「拥有一份 shell 的权限集合」，发起调用的仍然是本应用的 uid —— 于是绕开了
 * 那条专门拦 shell 身份的限制，权限检查本身依然满足。
 *
 * 这条路径改动的是本应用自己进程的 delegation 状态，所以 Instrumentation 必须跑在本进程里，
 * 用 caller_pid 校验来保证这一点（见 [SimConfigInstrumentation.onCreate]）。
 */
internal class FallbackResult(
    val ok: Boolean,
    val persistent: Boolean,
    val error: String? = null,
)

internal object PrivilegedRunner {

    private const val TAG = "SimPrivilegedRunner"

    /** Instrumentation 是异步拉起的，最长等这么久拿结果 */
    private const val WAIT_MS = 4000L

    fun override(context: Context, subId: Int, bundle: PersistableBundle?): FallbackResult {
        Reflect.ensureHiddenApi()
        SimWriteState.reset()

        val args = Bundle().apply {
            putInt(ARG_CALLER_PID, Process.myPid())
            putInt(ARG_SUB_ID, subId)
            putBoolean(ARG_RESET, bundle == null)
            if (bundle != null) putParcelable(ARG_CONFIG, bundle)
        }

        val activityBinder = Reflect.service(Context.ACTIVITY_SERVICE)
            ?: error("拿不到 activity 服务")
        val am = Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(activityBinder))
            ?: error("IActivityManager 转接失败")

        val component = ComponentName(context, SimConfigInstrumentation::class.java)
        val flags = Reflect.instrFlag("INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS", 1) or
            Reflect.instrFlag("INSTR_FLAG_NO_RESTART", 16)

        // IUiAutomationConnection 也是隐藏接口，只能反射造一个实现传进去
        val automation = Class.forName("android.app.UiAutomationConnection")
            .getDeclaredConstructor()
            .newInstance()

        // Android 10 起 startInstrumentation 多了一个 abiOverride 参数；按实际签名补参
        val method = am.javaClass.methods
            .filter { it.name == "startInstrumentation" && it.parameterTypes.size in 7..8 }
            .maxByOrNull { it.parameterTypes.size }
            ?: error("IActivityManager 没有可用的 startInstrumentation 签名")

        val params = ArrayList<Any?>(method.parameterTypes.size).apply {
            add(component)
            add(null)          // profileFile
            add(flags)
            add(args)
            add(null)          // IInstrumentationWatcher
            add(automation)
            add(0)             // userId
            if (method.parameterTypes.size >= 8) add(null)  // abiOverride
        }

        // 返回值必须看：系统找不到 instrumentation（例如类名解析错）时这里直接 false，
        // 此时 onCreate 根本不会执行，不能当成写入成功。
        val started = method.invoke(am, *params.toTypedArray()) as? Boolean ?: true
        if (!started) {
            return FallbackResult(ok = false, persistent = false, error = "系统拒绝启动 Instrumentation")
        }
        Log.d(TAG, "已通过 Instrumentation 下发 subId=" + subId)

        // 等 onCreate 把结果写回（同进程、但异步执行）
        val deadline = android.os.SystemClock.elapsedRealtime() + WAIT_MS
        while (!SimWriteState.finished && android.os.SystemClock.elapsedRealtime() < deadline) {
            runCatching { Thread.sleep(25) }
        }
        return if (!SimWriteState.finished) {
            FallbackResult(ok = false, persistent = false, error = "Instrumentation 未在 " + WAIT_MS + "ms 内返回结果")
        } else {
            FallbackResult(SimWriteState.ok, SimWriteState.persistent, SimWriteState.error)
        }
    }
}

/**
 * 真正在 shell 权限身份下写配置的地方。由 [PrivilegedRunner] 通过 startInstrumentation 拉起，
 * 全程跑在本应用进程内，写完立刻 finish。
 */
class SimConfigInstrumentation : Instrumentation() {

    private companion object {
        const val TAG = "SimConfigInstr"
    }

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)

        // 同包自启动时唯一合法的调用者就是本进程；别的进程拉起来就立刻收场
        if (arguments.getInt(ARG_CALLER_PID, 0) != Process.myPid()) {
            Log.w(TAG, "调用方不是本进程，放弃")
            fail("调用方不是本进程")
            return
        }

        val subId = arguments.getInt(ARG_SUB_ID, -1)
        val config = if (arguments.getBoolean(ARG_RESET, false)) null else readConfig(arguments)
        if (subId < 0) {
            fail("subId 非法")
            return
        }

        val am = runCatching { activityManager() }.getOrElse {
            Log.w(TAG, "取 IActivityManager 失败：" + it.message)
            fail(it.message ?: "取 IActivityManager 失败")
            return
        }

        try {
            startDelegate(am)
            val persistent = try {
                write(subId, config, persistent = true)
            } catch (e: SecurityException) {
                // 这个 ROM 要求 persistent=true 必须是系统应用，退化成非持久
                Log.w(TAG, "持久写入被拒，改为非持久：" + e.message)
                write(subId, config, persistent = false)
            }
            SimWriteState.persistent = persistent
            SimWriteState.ok = true
            Log.d(TAG, "写入成功，persistent=" + persistent)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            Log.w(TAG, "写配置失败：", cause)
            SimWriteState.ok = false
            SimWriteState.error = cause.message ?: cause.javaClass.simpleName
        } finally {
            runCatching { stopDelegate(am) }
            // 结果要先写好，主流程靠这个标记收尾
            SimWriteState.finished = true
            finish(0, Bundle())
        }
    }

    /** 早退分支统一走这里：写清失败原因再置完成标记 */
    private fun fail(message: String) {
        SimWriteState.ok = false
        SimWriteState.error = message
        SimWriteState.finished = true
        finish(0, Bundle())
    }

    private fun activityManager(): Any {
        val binder = Reflect.service(android.content.Context.ACTIVITY_SERVICE)
            ?: error("拿不到 activity 服务")
        return Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(binder))
            ?: error("IActivityManager 转接失败")
    }

    private fun startDelegate(am: Any) {
        val method = am.javaClass.methods
            .firstOrNull { it.name == "startDelegateShellPermissionIdentity" }
        if (method == null) {
            Log.w(TAG, "该 ROM 的 IActivityManager 没有 startDelegateShellPermissionIdentity")
            return
        }
        method.invoke(am, Process.myUid(), null)
        Log.d(TAG, "已挂上 shell 权限身份，uid=" + Process.myUid())
    }

    private fun stopDelegate(am: Any) {
        am.javaClass.methods
            .firstOrNull { it.name == "stopDelegateShellPermissionIdentity" }
            ?.invoke(am)
    }

    /**
     * 写入并返回是否以持久方式生效。
     *
     * 反射调用的异常会被 InvocationTargetException 包一层，必须拆开再抛：
     * 上层靠 catch (SecurityException) 判断要不要退化成非持久写入，包着就永远匹配不到。
     */
    private fun write(subId: Int, config: PersistableBundle?, persistent: Boolean): Boolean {
        val manager = targetContext.getSystemService(CarrierConfigManager::class.java)
            ?: error("没有 CarrierConfigManager")
        val methods = CarrierConfigManager::class.java.methods

        val threeArgs = methods.firstOrNull {
            it.name == "overrideConfig" && it.parameterTypes.size == 3
        }
        if (threeArgs != null) {
            invokeUnwrapped(threeArgs, manager, subId, config, persistent)
            return persistent
        }

        val twoArgs = methods.firstOrNull {
            it.name == "overrideConfig" && it.parameterTypes.size == 2
        } ?: error("CarrierConfigManager 没有 overrideConfig")
        invokeUnwrapped(twoArgs, manager, subId, config)
        // 两参重载没有 persistent 参数，系统按非持久处理
        return false
    }

    private fun invokeUnwrapped(method: java.lang.reflect.Method, receiver: Any, vararg args: Any?) {
        try {
            method.invoke(receiver, *args)
        } catch (t: Throwable) {
            throw (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        }
    }

    private fun readConfig(arguments: Bundle): PersistableBundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments.getParcelable(ARG_CONFIG, PersistableBundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments.getParcelable(ARG_CONFIG)
        }
}