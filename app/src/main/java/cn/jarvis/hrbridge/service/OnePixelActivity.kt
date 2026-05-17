package cn.jarvis.hrbridge.service

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Window

class OnePixelActivity : Activity() {
    
    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_FINISH_ONE_PIXEL) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val window: Window = window
        window.setGravity(Gravity.START or Gravity.TOP)
        val params = window.attributes
        params.x = 0
        params.y = 0
        params.height = 1
        params.width = 1
        window.attributes = params
        
        // Register local receiver to finish this activity
        val filter = IntentFilter(ACTION_FINISH_ONE_PIXEL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(finishReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(finishReceiver)
    }

    companion object {
        const val ACTION_FINISH_ONE_PIXEL = "cn.jarvis.hrbridge.FINISH_ONE_PIXEL"
    }
}
