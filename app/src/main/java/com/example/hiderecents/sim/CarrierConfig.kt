package com.example.hiderecents.sim

import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import com.example.hiderecents.BuildConfig
import rikka.shizuku.ShizukuBinderWrapper

/** 一张卡当前的覆盖状态；两个字段都为 null 表示没改过 */
data class Overrides(
    val countryCode: String?,
    val carrierName: String?,
)

/**
 * 一次写入的结果。
 *
 * delayed = true 表示走的是 Instrumentation 兜底：写入由另一个上下文下发，本进程读回来的配置
 * 可能要过一会儿才变，界面需要延迟刷新，不能立刻判定成败。
 *
 * persistent = false 表示这次只写进了内存：部分 ROM 要求「持久写入必须是系统应用」，
 * 壳身份只能写非持久版本，重启或重新插卡后会失效。
 */
class OverrideOutcome(
    val ok: Boolean,
    val delayed: Boolean = false,
    val persistent: Boolean = true,
    val error: String? = null,
)

/**
 * 兜底通道的结果回传。
 *
 * Instrumentation 的 onCreate 相对 startInstrumentation 是异步的，所以这里不是「写完读个变量」
 * 那么简单：主流程必须等 [finished] 被置位再读结果，否则会读到上一轮的残留、或者把失败当成功。
 */
internal object SimWriteState {
    @Volatile
    var finished: Boolean = false

    @Volatile
    var ok: Boolean = false

    @Volatile
    var persistent: Boolean = false

    @Volatile
    var error: String? = null

    fun reset() {
        finished = false
        ok = false
        persistent = false
        error = null
    }
}

/**
 * SIM 卡国家码覆盖：读写 CarrierConfigLoader。
 *
 * 改的不是 SIM 卡本身，是系统里「这张卡的运营商配置」中两个 key：
 *  - sim_country_iso_override_string  让系统把卡当作某个国家的卡
 *  - carrier_name_override_bool / carrier_name_string  覆盖运营商显示名
 * 全部可逆，还原就是把 override 置空。
 */
object CarrierConfig {

    private const val TAG = "SimCarrierConfig"

    private const val SERVICE = "carrier_config"

    // 这几个 key 在 CarrierConfigManager 里是 @SystemApi，公共 SDK 取不到常量，只能写字面量。
    private const val KEY_SIM_COUNTRY_ISO = "sim_country_iso_override_string"
    private const val KEY_CARRIER_NAME_OVERRIDE = "carrier_name_override_bool"
    private const val KEY_CARRIER_NAME = "carrier_name_string"

    /** 读这张卡当前的覆盖值（没改过则两项都是 null） */
    fun overrides(subId: Int): Overrides {
        val config = configFor(subId) ?: return Overrides(null, null)
        return Overrides(
            countryCode = config.getString(KEY_SIM_COUNTRY_ISO)?.takeIf { it.isNotBlank() },
            carrierName = config
                .takeIf { it.getBoolean(KEY_CARRIER_NAME_OVERRIDE, false) }
                ?.getString(KEY_CARRIER_NAME)
                ?.takeIf { it.isNotBlank() },
        )
    }

    fun setOverride(
        context: Context,
        subId: Int,
        countryCode: String?,
        carrierName: String?,
    ): OverrideOutcome {
        val bundle = PersistableBundle()
        var hasValue = false

        val code = countryCode?.trim()?.lowercase().orEmpty()
        if (code.isNotEmpty()) {
            if (code.length != 2 || !code.all { it in 'a'..'z' }) {
                return OverrideOutcome(ok = false, error = "国家码必须是两位字母，例如 us / jp")
            }
            bundle.putString(KEY_SIM_COUNTRY_ISO, code)
            hasValue = true
        }

        val name = carrierName?.trim().orEmpty()
        if (name.isNotEmpty()) {
            bundle.putBoolean(KEY_CARRIER_NAME_OVERRIDE, true)
            bundle.putString(KEY_CARRIER_NAME, name)
            hasValue = true
        }

        if (!hasValue) return OverrideOutcome(ok = false, error = "国家码和运营商名至少要填一项")
        return apply(context, subId, bundle)
    }

    fun reset(context: Context, subId: Int): OverrideOutcome = apply(context, subId, null)

    /** 只为诊断页用：确认 carrier_config 服务与 AIDL 转接这条路走得通 */
    fun channelReachable(): Boolean = loader() != null

    private fun apply(context: Context, subId: Int, bundle: PersistableBundle?): OverrideOutcome {
        val direct = runCatching { callOverride(subId, bundle) }
        if (direct.isSuccess) return OverrideOutcome(ok = true)

        val failure = direct.exceptionOrNull()
        val security = (failure as? SecurityException) ?: (failure?.cause as? SecurityException)
        if (security == null) {
            // 方法不存在、binder 断、Shizuku 没授权 —— 这些兜底通道救不回来
            val reason = failure?.message ?: failure?.javaClass?.simpleName ?: "未知错误"
            return OverrideOutcome(ok = false, error = reason)
        }

        Log.w(TAG, "直接写入被拒（" + security.message + "），改走 Instrumentation 兜底")
        return runCatching { PrivilegedRunner.override(context, subId, bundle) }.fold(
            onSuccess = { result ->
                if (result.ok) {
                    OverrideOutcome(ok = true, delayed = true, persistent = result.persistent)
                } else {
                    OverrideOutcome(ok = false, error = result.error ?: "兜底写入失败")
                }
            },
            onFailure = { OverrideOutcome(ok = false, error = it.message ?: it.javaClass.simpleName) },
        )
    }

    /**
     * 直连 carrier_config 的 overrideConfig。借 Shizuku 的 shell 身份调用，
     * 所以不需要本应用持有 MODIFY_PHONE_STATE。
     */
    private fun callOverride(subId: Int, bundle: PersistableBundle?) {
        val loader = loader() ?: error("拿不到 carrier_config 服务（Shizuku 未授权？）")
        val methods = loader.javaClass.methods

        val threeArgs = methods.firstOrNull {
            it.name == "overrideConfig" && it.parameterTypes.size == 3
        }
        if (threeArgs != null) {
            threeArgs.invoke(loader, subId, bundle, true)
            return
        }

        // Android 12 之前的签名没有 persistent 参数
        val twoArgs = methods.firstOrNull {
            it.name == "overrideConfig" && it.parameterTypes.size == 2
        } ?: error("该 ROM 的 ICarrierConfigLoader 没有 overrideConfig")
        twoArgs.invoke(loader, subId, bundle)
    }

    private fun configFor(subId: Int): PersistableBundle? = runCatching {
        val loader = loader() ?: return@runCatching null
        val methods = loader.javaClass.methods

        // Android 13 起多了一个 callingPackage 参数，按参数个数自适应
        val twoArgs = methods.firstOrNull {
            it.name == "getConfigForSubId" && it.parameterTypes.size == 2
        }
        if (twoArgs != null) {
            return@runCatching twoArgs.invoke(loader, subId, BuildConfig.APPLICATION_ID) as? PersistableBundle
        }
        val oneArg = methods.firstOrNull {
            it.name == "getConfigForSubId" && it.parameterTypes.size == 1
        } ?: return@runCatching null
        oneArg.invoke(loader, subId) as? PersistableBundle
    }.onFailure { Log.w(TAG, "读配置失败：" + it.message) }.getOrNull()

    private fun loader(): Any? = runCatching {
        Reflect.ensureHiddenApi()
        val binder = Reflect.service(SERVICE) ?: return@runCatching null
        Class.forName("com.android.internal.telephony.ICarrierConfigLoader\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(binder))
    }.onFailure { Log.w(TAG, "取 carrier_config 失败：" + it.message) }.getOrNull()
}