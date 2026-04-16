package cn.jarvis.hrbriage;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.util.*;

public class ScanActivity extends AppCompatActivity {
    private static final String TAG = "ScanActivity";
    private ListView listDevices;
    private BluetoothLeScanner scanner;
    private List<ScanResult> deviceList = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private boolean scanning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        listDevices = findViewById(R.id.list_devices);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        listDevices.setAdapter(adapter);

        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null) {
            scanner = btAdapter.getBluetoothLeScanner();
            Log.i(TAG, "BLE scanner initialized");
        } else {
            Log.e(TAG, "No Bluetooth adapter found!");
        }

        listDevices.setOnItemClickListener((parent, view, position, id) -> {
            if (position < deviceList.size()) {
                ScanResult result = deviceList.get(position);
                Intent data = new Intent();
                data.putExtra("device_name", result.getDevice().getName());
                data.putExtra("device_address", result.getDevice().getAddress());
                setResult(RESULT_OK, data);
                finish();
            }
        });

        startScan();
    }

    private void startScan() {
        if (scanner == null) {
            Toast.makeText(this, "BLE不可用", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "缺少蓝牙扫描权限", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        scanning = true;
        adapter.clear();
        deviceList.clear();
        adapter.add(getString(R.string.scanning));
        Log.i(TAG, "Starting BLE scan...");

        // 不使用UUID过滤，扫描所有设备
        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();

        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                runOnUiThread(() -> {
                    BluetoothDevice device = result.getDevice();
                    String name = device.getName();
                    
                    // 记录所有扫描到的设备
                    Log.d(TAG, "Found device: " + (name != null ? name : "unnamed") + " @ " + device.getAddress());
                    
                    if (name != null && !name.isEmpty()) {
                        String info = name + "\n" + device.getAddress() + " (RSSI: " + result.getRssi() + ")";
                        boolean found = false;
                        for (ScanResult r : deviceList) {
                            if (r.getDevice().getAddress().equals(device.getAddress())) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            deviceList.add(result);
                            adapter.add(info);
                            Log.i(TAG, "Added device: " + name);
                        }
                    }
                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "Scan failed: " + errorCode);
                runOnUiThread(() -> Toast.makeText(ScanActivity.this, 
                    "扫描失败: " + errorCode, Toast.LENGTH_LONG).show());
            }
        };

        // 不使用过滤器
        scanner.startScan(null, settings, callback);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (scanning) {
                scanning = false;
                if (ContextCompat.checkSelfPermission(ScanActivity.this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    scanner.stopScan(callback);
                }
                
                // 移除"正在扫描"提示
                if (adapter.getCount() > 0) {
                    String first = adapter.getItem(0);
                    if (first != null && first.contains(getString(R.string.scanning))) {
                        adapter.remove(first);
                    }
                }
                
                if (deviceList.isEmpty()) {
                    Toast.makeText(ScanActivity.this, 
                        "未找到任何BLE设备", 
                        Toast.LENGTH_LONG).show();
                }
            }
        }, 15000);  // 延长到15秒
    }
}
