package cn.jarvis.hrbriage;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.util.*;

public class ScanActivity extends AppCompatActivity {
    private ListView listDevices;
    private BluetoothLeScanner scanner;
    private List<ScanResult> deviceList = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private boolean scanning = false;
    
    // Heart Rate Service UUID - 只扫描支持心率广播的设备
    private static final UUID HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        listDevices = findViewById(R.id.list_devices);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        listDevices.setAdapter(adapter);

        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null) scanner = btAdapter.getBluetoothLeScanner();

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
            Toast.makeText(this, R.string.no_ble_devices, Toast.LENGTH_LONG).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.no_ble_devices, Toast.LENGTH_LONG).show();
            return;
        }

        scanning = true;
        adapter.clear();
        deviceList.clear();
        adapter.add(getString(R.string.scanning));

        // 过滤 Heart Rate Service UUID - 只显示支持心率广播的设备
        List<ScanFilter> filters = new ArrayList<>();
        ScanFilter filter = new ScanFilter.Builder()
            .setServiceUuid(new ParcelUuid(HR_SERVICE_UUID))
            .build();
        filters.add(filter);

        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();

        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                runOnUiThread(() -> {
                    BluetoothDevice device = result.getDevice();
                    String name = device.getName();
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
                        }
                    }
                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                runOnUiThread(() -> Toast.makeText(ScanActivity.this, 
                    "扫描失败: " + errorCode, Toast.LENGTH_LONG).show());
            }
        };

        // 使用过滤扫描 - 只扫描心率广播设备
        scanner.startScan(filters, settings, callback);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (scanning) {
                scanning = false;
                if (ContextCompat.checkSelfPermission(ScanActivity.this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    scanner.stopScan(callback);
                }
                if (deviceList.isEmpty()) {
                    Toast.makeText(ScanActivity.this, 
                        "未找到心率广播设备，请确认手环已开启心率广播", 
                        Toast.LENGTH_LONG).show();
                }
            }
        }, 10000);
    }
}
