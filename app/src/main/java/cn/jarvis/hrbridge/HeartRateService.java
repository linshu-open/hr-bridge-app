package cn.jarvis.hrbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class HeartRateService extends Service {
    private static final String TAG = "HeartRateService";
    private static final String CHANNEL_ID = "hrbridge_channel";
    public static final String ACTION_HEART_RATE_UPDATE = "cn.jarvis.hrbridge.HEART_RATE_UPDATE";
    public static final String EXTRA_HEART_RATE = "heart_rate";
    public static final String EXTRA_AVG = "avg";
    private static final UUID HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private String serverUrl, token, targetDeviceName;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private SharedPreferences prefs;
    private Handler handler = new Handler(Looper.getMainLooper());
    private List<Integer> hrBuffer = new ArrayList<>();

    private ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            if (name != null && name.equals(targetDeviceName)) {
                log("Found: " + name);
                stopScan();
                connectDevice(device);
            }
        }
        @Override
        public void onScanFailed(int errorCode) { log("Scan failed: " + errorCode); }
    };

    private BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { 
                log("Connected"); 
                gatt.discoverServices(); 
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) { 
                log("Disconnected, rescanning..."); 
                handler.postDelayed(() -> startScan(), 3000); 
            }
        }
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = gatt.getService(HR_SERVICE_UUID);
            if (service == null) { log("HR service not found"); return; }
            BluetoothGattCharacteristic chr = service.getCharacteristic(HR_MEASUREMENT_UUID);
            if (chr == null) { log("HR characteristic not found"); return; }
            gatt.setCharacteristicNotification(chr, true);
            BluetoothGattDescriptor desc = chr.getDescriptor(CCCD_UUID);
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(desc);
            log("Listening for HR...");
        }
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic chr) {
            int hr = parseHeartRate(chr);
            log("HR: " + hr + " bpm");
            hrBuffer.add(hr);
            updateNotification("HR: " + hr + " bpm");
            
            // 发送广播通知UI更新
            int avg = hrBuffer.size() > 1 ? (int) hrBuffer.stream().mapToInt(i -> i).average().orElse(hr) : hr;
            Intent intent = new Intent(ACTION_HEART_RATE_UPDATE);
            intent.putExtra(EXTRA_HEART_RATE, hr);
            intent.putExtra(EXTRA_AVG, avg);
            LocalBroadcastManager.getInstance(HeartRateService.this).sendBroadcast(intent);
            
            if (hrBuffer.size() >= 10) postHeartRate();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        prefs = getSharedPreferences("hrbridge", MODE_PRIVATE);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) scanner = adapter.getBluetoothLeScanner();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        serverUrl = prefs.getString("server_url", "");
        token = prefs.getString("token", "");
        targetDeviceName = prefs.getString("device_name", "");
        startForeground(1, createNotification("Scanning..."));
        log("Service started, target: " + targetDeviceName);
        startScan();
        return START_STICKY;
    }

    private void startScan() {
        if (scanner == null) { log("BLE unavailable"); return; }
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            scanner.startScan(null, settings, scanCallback);
            log("Scanning...");
        }
    }

    private void stopScan() {
        if (scanner != null && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) scanner.stopScan(scanCallback);
    }

    private void connectDevice(BluetoothDevice device) {
        log("Connecting: " + device.getName());
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) gatt = device.connectGatt(this, false, gattCallback);
    }

    private int parseHeartRate(BluetoothGattCharacteristic chr) {
        int flag = chr.getProperties();
        return (flag & 0x01) != 0 ? chr.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 1) : chr.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1);
    }

    private void postHeartRate() {
        if (hrBuffer.isEmpty()) return;
        int hr = hrBuffer.get(hrBuffer.size() - 1);
        int avg = (int) hrBuffer.stream().mapToInt(i -> i).average().orElse(hr);
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("hr", hr).put("avg", avg).put("status", hr < 60 ? "low" : hr <= 100 ? "normal" : hr <= 120 ? "elevated" : "high").put("trend", "stable").put("samples", hrBuffer.size()).put("device", targetDeviceName).put("ts", System.currentTimeMillis() / 1000);
                if (token != null && !token.isEmpty()) json.put("token", token);
                HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes());
                os.close();
                int code = conn.getResponseCode();
                log("Uploaded: " + hr + " bpm (HTTP " + code + ")");
                conn.disconnect();
            } catch (Exception e) { log("Upload failed: " + e.getMessage()); }
        }).start();
        hrBuffer.clear();
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        String log = prefs.getString("log", "");
        String time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        prefs.edit().putString("log", log + "\n[" + time + "] " + msg).apply();
    }

    private void updateNotification(String text) { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, createNotification(text)); }

    private Notification createNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("JARVIS HR Bridge").setContentText(text).setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL_ID, "JARVIS HR Bridge", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public void onDestroy() { super.onDestroy(); stopScan(); if (gatt != null && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { gatt.disconnect(); gatt.close(); } }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
