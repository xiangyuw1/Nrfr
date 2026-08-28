package com.github.nrfr.manager

import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyFrameworkInitializer
import android.telephony.TelephonyManager
import com.android.internal.telephony.ICarrierConfigLoader
import com.github.nrfr.model.SimCardInfo
import rikka.shizuku.ShizukuBinderWrapper

object CarrierConfigManager {
    /**
     * Android 17 (API 37) 起框架为这些隐藏接口标注了 @Nullable，需要显式做空安全处理
     */
    private fun getCarrierConfigLoader(): ICarrierConfigLoader? {
        val binder = TelephonyFrameworkInitializer
            .getTelephonyServiceManager()
            ?.carrierConfigServiceRegisterer
            ?.get()
            ?: return null

        return ICarrierConfigLoader.Stub.asInterface(ShizukuBinderWrapper(binder))
    }

    fun getSimCards(context: Context): List<SimCardInfo> {
        val simCards = mutableListOf<SimCardInfo>()

        for (slotIndex in 0..1) {
            val subId = getSubIdForSlot(slotIndex) ?: continue
            simCards.add(
                SimCardInfo(
                    slot = slotIndex + 1,
                    subId = subId,
                    carrierName = getCarrierNameBySubId(context, subId),
                    currentConfig = getCurrentConfig(subId)
                )
            )
        }

        return simCards
    }

    /**
     * 获取指定卡槽的 subscription id，无卡或卡槽无效时返回 null。
     * Android 14 (API 34) 起使用公开的 getSubscriptionId，旧版本回退到隐藏 API getSubId。
     */
    private fun getSubIdForSlot(slotIndex: Int): Int? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            SubscriptionManager.getSubscriptionId(slotIndex)
                .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        } else {
            @Suppress("DEPRECATION")
            SubscriptionManager.getSubId(slotIndex)
                ?.firstOrNull()
                ?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        }
    }

    private fun getCurrentConfig(subId: Int): Map<String, String> {
        try {
            val carrierConfigLoader = getCarrierConfigLoader() ?: return emptyMap()
            @Suppress("DEPRECATION")
            val config = carrierConfigLoader.getConfigForSubId(subId, "com.github.nrfr") ?: return emptyMap()

            val result = mutableMapOf<String, String>()

            // 获取国家码配置
            config.getString(CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING)?.let {
                result["国家码"] = it
            }

            // 获取运营商名称配置
            if (config.getBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, false)) {
                config.getString(CarrierConfigManager.KEY_CARRIER_NAME_STRING)?.let {
                    result["运营商名称"] = it
                }
            }

            return result
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    private fun getCarrierNameBySubId(context: Context, subId: Int): String {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return ""

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 及以上使用新 API
                telephonyManager.getNetworkOperatorName(subId)
            } else {
                // Android 8-9 使用反射获取运营商名称
                val createForSubscriptionId = TelephonyManager::class.java.getMethod(
                    "createForSubscriptionId",
                    Int::class.javaPrimitiveType
                )
                val subTelephonyManager = createForSubscriptionId.invoke(telephonyManager, subId) as TelephonyManager
                subTelephonyManager.networkOperatorName
            }
        } catch (e: Exception) {
            // 如果获取失败，回退到默认的 TelephonyManager
            telephonyManager.networkOperatorName
        }
    }

    fun setCarrierConfig(
        context: Context,
        subId: Int,
        countryCode: String?,
        carrierName: String? = null
    ): Boolean {
        val bundle = PersistableBundle()

        // 设置国家码
        if (!countryCode.isNullOrEmpty() && countryCode.length == 2) {
            bundle.putString(
                CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING,
                countryCode.lowercase()
            )
        }

        // 设置运营商名称
        if (!carrierName.isNullOrEmpty()) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true)
            bundle.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, carrierName)
        }

        return overrideCarrierConfig(context, subId, bundle)
    }

    fun resetCarrierConfig(context: Context, subId: Int): Boolean {
        return overrideCarrierConfig(context, subId, null)
    }

    /**
     * 覆盖运营商配置。
     * @return true 表示走了 Instrumentation fallback 路径（需要延迟刷新 UI），false 表示直接成功
     */
    private fun overrideCarrierConfig(context: Context, subId: Int, bundle: PersistableBundle?): Boolean {
        val carrierConfigLoader = getCarrierConfigLoader()
            ?: throw IllegalStateException("无法获取 CarrierConfigService")
        try {
            carrierConfigLoader.overrideConfig(subId, bundle, true)
            return false
        } catch (e: SecurityException) {
            // 直接调用被框架拒绝时统一走 Instrumentation fallback：
            // - Android 16+ 禁止 shell（uid 2000）调用 overrideConfig：
            //   "overrideConfig cannot be invoked by shell"
            // - Android 17 user 版上 root（uid 0）虽然能通过 MODIFY_PHONE_STATE 检查，
            //   但 isSystemApp() 按 uid 反查不到包，persistent=true 也会被拒绝：
            //   "overrideConfig with persistent=true only can be invoked by system app"
            // Instrumentation + startDelegateShellPermissionIdentity 对 shell 和 root
            // 身份的 Shizuku server 都可用，因此两种模式下 fallback 均可生效。
            PrivilegedCarrierConfigRunner.overrideConfig(context, subId, bundle)
            return true
        }
    }
}
