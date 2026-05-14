package cn.jarvis.hrbridge.service

import android.content.Context
import android.os.PowerManager
import cn.jarvis.hrbridge.util.Logger

object WakeLockScope {

    /**
     * Executes the given [block] while holding a partial wake lock with the given [timeoutMs].
     * Avoids holding permanent wake locks. The lock is released automatically when the block
     * completes or throws, or when the OS timeout expires.
     */
    suspend fun <T> withPartialWakeLock(context: Context, tag: String, timeoutMs: Long, block: suspend () -> T): T {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:$tag")

        try {
            Logger.i("WakeLock", "Acquiring partial wake lock [$tag] for max ${timeoutMs}ms")
            wakeLock?.acquire(timeoutMs)
            return block()
        } finally {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                    Logger.i("WakeLock", "Released partial wake lock [$tag]")
                }
            } catch (e: Exception) {
                Logger.w("WakeLock", "Error releasing wake lock [$tag]: ${e.message}")
            }
        }
    }
}
