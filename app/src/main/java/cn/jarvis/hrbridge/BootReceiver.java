package cn.jarvis.hrbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Start service on boot if configured
            android.content.SharedPreferences prefs = context.getSharedPreferences("hrbridge", Context.MODE_PRIVATE);
            String deviceName = prefs.getString("device_name", "");
            if (!deviceName.isEmpty()) {
                Intent serviceIntent = new Intent(context, HeartRateService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
