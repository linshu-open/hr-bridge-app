package cn.jarvis.hrbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.jarvis.hrbridge.util.Logger

class ScreenStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                Logger.d("ScreenStatus", "Screen off -> Launching OnePixelActivity")
                val pixelIntent = Intent(context, OnePixelActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }
                context.startActivity(pixelIntent)
            }
            Intent.ACTION_SCREEN_ON -> {
                Logger.d("ScreenStatus", "Screen on -> Finishing OnePixelActivity")
                context.sendBroadcast(Intent(OnePixelActivity.ACTION_FINISH_ONE_PIXEL))
            }
        }
    }
}
