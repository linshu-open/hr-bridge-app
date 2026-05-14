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
 * Auto-start receiver.
 *
 * The sensor bridge must run even when no heart-rate band is selected, because
 * location / steps / light / motion are still useful JARVIS inputs.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_USER_PRESENT -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        BridgeRuntime.ensureScheduledAndMaybeStart(context, intent.action ?: "unknown")
                        UploadWorker.schedule(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
