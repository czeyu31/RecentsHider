package com.example.hiderecents.sim

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

/** 一张实体卡在当前时刻的实况：卡本身读到的 MCC/MNC，以及有没有被覆盖过 */
data class SimSlot(
    val slotIndex: Int,
    val subId: Int,
    val carrierName: String,
    val mcc: String,
    val mnc: String,
    /** 卡自身声明的国家码（大写）；读不到时为空串 */
    val iso: String,
    /** 5G / 4G / 3G / 2G / 待机 */
    val networkLabel: String,
    val countryOverride: String?,
    val carrierNameOverride: String?,
) {
    val hasOverride: Boolean get() = countryOverride != null || carrierNameOverride != null

    /** 生效的国家：覆盖值优先，否则用卡自身的 */
    val effectiveIso: String get() = countryOverride?.uppercase() ?: iso

    /** 卡标识：MCC·MNC，读不到时只显示能读到的部分 */
    val operatorCode: String
        get() = listOf(mcc, mnc).filter { it.isNotEmpty() }.joinToString(" ")
}

object SimInfo {

    private const val TAG = "SimInfo"

    fun slots(context: Context): List<SimSlot> {
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val infos = runCatching { manager.activeSubscriptionInfoList }
            .onFailure { Log.w(TAG, "读订阅列表失败：" + it.message) }
            .getOrNull()
            .orEmpty()

        return infos.map { info ->
            val subId = info.subscriptionId
            val telephony = telephonyFor(context, subId)
            val operator = runCatching { telephony?.simOperator.orEmpty() }
                .getOrElse { "" }
                .ifEmpty { runCatching { telephony?.networkOperator.orEmpty() }.getOrElse { "" } }

            val overrides = CarrierConfig.overrides(subId)
            SimSlot(
                slotIndex = info.simSlotIndex,
                subId = subId,
                carrierName = runCatching { telephony?.networkOperatorName?.toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: info.carrierName?.toString().orEmpty(),
                mcc = operator.take(3),
                mnc = operator.drop(3),
                iso = runCatching { info.countryIso?.uppercase().orEmpty() }.getOrDefault(""),
                networkLabel = networkLabel(telephony),
                countryOverride = overrides.countryCode,
                carrierNameOverride = overrides.carrierName,
            )
        }.sortedBy { it.slotIndex }
    }

    private fun telephonyFor(context: Context, subId: Int): TelephonyManager? {
        val base = context.getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching { base?.createForSubscriptionId(subId) }.getOrNull()
        }
        // Android 10 及以下只能反射拿按 subId 区分的实例
        return runCatching {
            TelephonyManager::class.java
                .getMethod("createForSubscriptionId", Int::class.javaPrimitiveType)
                .invoke(base, subId) as? TelephonyManager
        }.getOrElse { base }
    }

    private fun networkLabel(telephony: TelephonyManager?): String {
        if (telephony == null) return "待机"
        val type = runCatching { telephony.dataNetworkType }.getOrElse { TelephonyManager.NETWORK_TYPE_UNKNOWN }
        val resolved = if (type != TelephonyManager.NETWORK_TYPE_UNKNOWN) {
            type
        } else {
            runCatching { telephony.networkType }.getOrElse { TelephonyManager.NETWORK_TYPE_UNKNOWN }
        }
        return when (resolved) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            -> "3G"

            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_GSM,
            -> "2G"

            TelephonyManager.NETWORK_TYPE_IWLAN -> "WiFi 通话"

            else -> "待机"
        }
    }
}