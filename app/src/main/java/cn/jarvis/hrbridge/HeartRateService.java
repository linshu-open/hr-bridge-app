package cn.jarvis.hrbriage;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class HeartRateService extends Service {
    private static final String TAG = "HeartRateService";
    private static final String CHANNEL_ID = "hrbridge_channel";
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
                log("鎵惧埌璁惧: " + name);
                stopScan();
                connectDevice(device);
            }
        }
        @Override
        public void onScanFailed(int errorCode) { log("鎵弿澶辫触: " + errorCode); }
    };

    private BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { log("宸茶繛鎺ヨ澶?); gatt.discoverServices(); }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) { log("璁惧鏂紑锛岄噸鏂版壂鎻?.."); handler.postDelayed(() -> startScan(), 3000); }
        }
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = gatt.getService(HR_SERVICE_UUID);
            if (service == null) { log("鏈壘鍒板績鐜囨湇鍔?); return; }
            BluetoothGattCharacteristic chr = service.getCharacteristic(HR_MEASUREMENT_UUID);
            if (chr == null) { log("鏈壘鍒板績鐜囩壒寰?); return; }
            gatt.setCharacteristicNotification(chr, true);
            BluetoothGattDescriptor desc = chr.getDescriptor(CCCD_UUID);
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(desc);
            log("寮€濮嬬洃鍚績鐜?..");
        }
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic chr) {
            int hr = parseHeartRate(chr);
            log("蹇冪巼: " + hr + " bpm");
            hrBuffer.add(hr);
            updateNotification("蹇冪巼: " + hr + " bpm");
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
        startForeground(1, createNotification("姝ｅ湪鎵弿..."));
        log("鏈嶅姟鍚姩锛岀洰鏍囪澶? " + targetDeviceName);
        startScan();
        return START_STICKY;
    }

    private void startScan() {
        if (scanner == null) { log("BLE 涓嶅彲鐢?); return; }
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            scanner.startScan(null, settings, scanCallback);
            log("寮€濮嬫壂鎻?..");
        }
    }

    private void stopScan() {
        if (scanner != null && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) scanner.stopScan(scanCallback);
    }

    private void connectDevice(BluetoothDevice device) {
        log("姝ｅ湪杩炴帴: " + device.getName());
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
                log("涓婃姤鎴愬姛: " + hr + " bpm (HTTP " + code + ")");
                conn.disconnect();
            } catch (Exception e) { log("涓婃姤澶辫触: " + e.getMessage()); }
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
        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("JARVIS 蹇冪巼妗ユ帴").setContentText(text).setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL_ID, "JARVIS 蹇冪巼妗ユ帴", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public void onDestroy() { super.onDestroy(); stopScan(); if (gatt != null && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { gatt.disconnect(); gatt.close(); } }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}