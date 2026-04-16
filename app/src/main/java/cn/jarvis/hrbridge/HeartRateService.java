package cn.jarvis.hrbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 心率监测后台服务
 * 扫描 BLE 心率广播，上报到 JARVIS 服务器
 */
public class HeartRateService extends Service {

    private static final String TAG = "HeartRateService";
    private static final String CHANNEL_ID = "hr_bridge_channel";
    private static final int NOTIFICATION_ID = 1001;

    // BLE Heart Rate Service UUID
    private static final UUID HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;

    private String serverUrl;
    private String token;
    private String targetDeviceName;
    private int scanInterval;

    private ExecutorService executor;
    private Handler handler;
    private boolean isScanning = false;

    private List<Integer> hrBuffer = new ArrayList<>();
    private long lastPostTime = 0;
    private static final long POST_INTERVAL = 60000; // 60秒上报一次

    @Override
    public void onCreate() {
        super.onCreate();

        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        bleScanner = bluetoothAdapter.getBluetoothLeScanner();

        executor = Executors.newSingleThreadExecutor();
        handler = new Handler(Looper.getMainLooper());

        loadConfig();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("正在扫描设备..."));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            serverUrl = intent.getStringExtra("server_url");
            token = intent.getStringExtra("token");
        }

        loadConfig();
        startScan();

        return START_STICKY;
    }

    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        targetDeviceName = prefs.getString("device_name", "HUAWEI Band 10-2A8");
        scanInterval = prefs.getInt("scan_interval", 30);
        if (serverUrl == null) {
            serverUrl = prefs.getString("server_url", "http://100.126.107.40:18890/jarvis/sensor/heart-rate");
        }
        if (token == null) {
            token = prefs.getString("token", "");
        }
    }

    private void startScan() {
        if (isScanning || bleScanner == null) return;

        Log.i(TAG, "开始扫描 BLE 设备: " + targetDeviceName);

        ScanFilter filter = new ScanFilter.Builder()
            .setDeviceName(targetDeviceName)
            .build();

        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(filter);

        try {
            bleScanner.startScan(filters, settings, scanCallback);
            isScanning = true;
        } catch (SecurityException e) {
            Log.e(TAG, "BLE 扫描权限被拒绝", e);
        }
    }

    private void stopScan() {
        if (!isScanning || bleScanner == null) return;

        try {
            bleScanner.stopScan(scanCallback);
            isScanning = false;
        } catch (SecurityException e) {
            Log.e(TAG, "停止扫描失败", e);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();

            if (name != null && name.contains(targetDeviceName)) {
                Log.i(TAG, "找到设备: " + name + " (" + device.getAddress() + ")");
                stopScan();
                connectDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "扫描失败: " + errorCode);
            updateNotification("扫描失败，正在重试...");
            handler.postDelayed(() -> startScan(), 5000);
        }
    };

    private void connectDevice(BluetoothDevice device) {
        try {
            updateNotification("正在连接 " + device.getName() + "...");
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "连接失败", e);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "已连接到设备");
                updateNotification("已连接，正在发现服务...");
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                    Log.e(TAG, "发现服务失败", e);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "设备已断开");
                updateNotification("设备已断开，正在重新扫描...");
                closeGatt();
                handler.postDelayed(() -> startScan(), 3000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "服务发现失败");
                return;
            }

            try {
                BluetoothGattCharacteristic hrChar = gatt.getService(HR_SERVICE_UUID)
                    .getCharacteristic(HR_MEASUREMENT_UUID);

                if (hrChar != null) {
                    boolean success = gatt.setCharacteristicNotification(hrChar, true);
                    if (success) {
                        BluetoothGattDescriptor descriptor = hrChar.getDescriptor(CCCD_UUID);
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(descriptor);
                        updateNotification("已订阅心率通知");
                        Log.i(TAG, "心率通知已启用");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "订阅心率通知失败", e);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (HR_MEASUREMENT_UUID.equals(characteristic.getUuid())) {
                int hr = parseHeartRate(characteristic.getValue());
                if (hr > 0) {
                    Log.d(TAG, "收到心率: " + hr + " bpm");
                    hrBuffer.add(hr);
                    if (hrBuffer.size() > 30) hrBuffer.remove(0);

                    updateNotification("心率: " + hr + " bpm");
                    tryPostHeartRate(hr);
                }
            }
        }
    };

    private int parseHeartRate(byte[] data) {
        if (data == null || data.length < 2) return -1;

        int flags = data[0] & 0xFF;
        if ((flags & 0x01) != 0) {
            // 16-bit 格式
            if (data.length >= 3) {
                return ((data[2] & 0xFF) << 8) | (data[1] & 0xFF);
            }
        } else {
            // 8-bit 格式
            return data[1] & 0xFF;
        }
        return -1;
    }

    private void tryPostHeartRate(int currentHr) {
        long now = System.currentTimeMillis();
        if (now - lastPostTime < POST_INTERVAL || hrBuffer.isEmpty()) {
            return;
        }

        int avgHr = 0;
        for (int h : hrBuffer) avgHr += h;
        avgHr /= hrBuffer.size();

        String status;
        if (currentHr < 60) status = "low";
        else if (currentHr <= 100) status = "normal";
        else if (currentHr <= 120) status = "elevated";
        else status = "high";

        String trend = "stable";
        if (hrBuffer.size() >= 3) {
            int recentAvg = 0, olderAvg = 0;
            for (int i = 0; i < 3; i++) {
                recentAvg += hrBuffer.get(hrBuffer.size() - 1 - i);
                if (i < Math.min(3, hrBuffer.size())) {
                    olderAvg += hrBuffer.get(i);
                }
            }
            recentAvg /= 3;
            olderAvg /= Math.min(3, hrBuffer.size());

            if (recentAvg > olderAvg + 5) trend = "rising";
            else if (recentAvg < olderAvg - 5) trend = "falling";
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("hr", currentHr);
            payload.put("avg", avgHr);
            payload.put("status", status);
            payload.put("trend", trend);
            payload.put("samples", hrBuffer.size());
            payload.put("device", targetDeviceName);
            payload.put("ts", now / 1000);
            if (!token.isEmpty()) {
                payload.put("token", token);
            }
        } catch (Exception e) {
            Log.e(TAG, "构建 JSON 失败", e);
            return;
        }

        executor.execute(() -> postToServer(payload.toString()));
        lastPostTime = now;
        hrBuffer.clear();
    }

    private void postToServer(String json) {
        try {
            URL url = new URL(serverUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            OutputStream os = conn.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            if (code == 200) {
                Log.i(TAG, "上报成功: " + json);
            } else {
                Log.w(TAG, "上报失败: HTTP " + code);
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "上报失败", e);
        }
    }

    private void closeGatt() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.close();
            } catch (Exception e) {
                Log.e(TAG, "关闭 GATT 失败", e);
            }
            bluetoothGatt = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "心率桥接服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("JARVIS 心率桥接后台服务");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String content) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS 心率桥接")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String content) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, createNotification(content));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopScan();
        closeGatt();
        executor.shutdown();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
