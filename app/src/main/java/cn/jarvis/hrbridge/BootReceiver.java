package cn.jarvis.hrbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 开机自启动接收器
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE);
            boolean autoStart = prefs.getBoolean("auto_start", false);

            if (autoStart) {
                String serverUrl = prefs.getString("server_url", "");
                String token = prefs.getString("token", "");

                Intent serviceIntent = new Intent(context, HeartRateService.class);
                serviceIntent.putExtra("server_url", serverUrl);
                serviceIntent.putExtra("token", token);

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
