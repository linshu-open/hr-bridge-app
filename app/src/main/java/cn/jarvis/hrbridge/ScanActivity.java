package cn.jarvis.hrbridge;

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
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 创建布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        
        tvStatus = new TextView(this);
        tvStatus.setText("正在初始化...");
        tvStatus.setTextSize(16);
        tvStatus.setPadding(10, 10, 10, 10);
        layout.addView(tvStatus);
        
        listDevices = new ListView(this);
        layout.addView(listDevices, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ));
        
        setContentView(layout);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        listDevices.setAdapter(adapter);

        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null) {
            scanner = btAdapter.getBluetoothLeScanner();
            tvStatus.setText("BLE已就绪，开始扫描...");
            Log.i(TAG, "BLE scanner initialized");
        } else {
            tvStatus.setText("错误: 没有蓝牙适配器");
            Log.e(TAG, "No Bluetooth adapter found!");
            return;
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
            tvStatus.setText("错误: BLE不可用");
            return;
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            tvStatus.setText("错误: 缺少蓝牙扫描权限");
            return;
        }

        scanning = true;
        adapter.clear();
        deviceList.clear();
        adapter.add("正在扫描...");
        Log.i(TAG, "Starting BLE scan...");

        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();

        ScanCallback callback = new ScanCallback() {
            int deviceCount = 0;
            
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                runOnUiThread(() -> {
                    BluetoothDevice device = result.getDevice();
                    String name = device.getName();
                    
                    Log.d(TAG, "Found: " + (name != null ? name : "unnamed") + " @ " + device.getAddress());
                    
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
                            deviceCount++;
                            tvStatus.setText("已发现 " + deviceCount + " 个设备");
                            Log.i(TAG, "Added: " + name);
                        }
                    }
                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "Scan failed: " + errorCode);
                runOnUiThread(() -> {
                    tvStatus.setText("扫描失败: " + errorCode);
                    Toast.makeText(ScanActivity.this, 
                        "扫描失败: " + errorCode, Toast.LENGTH_LONG).show();
                });
            }
        };

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
                    if (first != null && first.contains("正在扫描")) {
                        adapter.remove(first);
                    }
                }
                
                if (deviceList.isEmpty()) {
                    tvStatus.setText("未找到任何BLE设备\n请确认:\n1. 手环已开启心率广播\n2. 手机蓝牙已开启\n3. 已授予位置权限");
                } else {
                    tvStatus.setText("扫描完成，发现 " + deviceList.size() + " 个设备");
                }
            }
        }, 15000);
    }
}
