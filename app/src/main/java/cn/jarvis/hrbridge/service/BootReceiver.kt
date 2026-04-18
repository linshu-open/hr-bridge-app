package cn.jarvis.hrbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启：
 *   - BOOT_COMPLETED: 普通开机
 *   - LOCKED_BOOT_COMPLETED: 直接启动模式（锁屏前）
 *   - MY_PACKAGE_REPLACED: APP 更新后
 *
 * 仅在用户已配置过设备时才启动。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val s = ServiceLocator.settingsStore.settings.first()
                        if (s.selectedDeviceMac.isNotEmpty()) {
                            Logger.i("BootReceiver", "开机自启服务，设备=${s.selectedDeviceName}")
                            HeartRateService.start(context)
                            UploadWorker.schedule(context)
                        } else {
                            Logger.i("BootReceiver", "未配置设备，跳过自启")
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
