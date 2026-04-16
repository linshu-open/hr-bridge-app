package cn.jarvis.hrbriage;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        listDevices = findViewById(R.id.list_devices);
        
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        listDevices.setAdapter(adapter);
        
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) scanner = adapter.getBluetoothLeScanner();
        
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
        
        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                runOnUiThread(() -> {
                    BluetoothDevice device = result.getDevice();
                    String name = device.getName();
                    if (name != null && !name.isEmpty()) {
                        String info = name + "\n" + device.getAddress() + " (RSSI: " + result.getRssi() + ")";
                        // Check if already in list
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
                runOnUiThread(() -> Toast.makeText(ScanActivity.this, R.string.no_ble_devices, Toast.LENGTH_LONG).show());
            }
        };
        
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, settings, callback);
        
        // Stop after 10 seconds
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (scanning) {
                scanning = false;
                if (ContextCompat.checkSelfPermission(ScanActivity.this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    scanner.stopScan(callback);
                }
                if (deviceList.isEmpty()) {
                    Toast.makeText(ScanActivity.this, R.string.no_ble_devices, Toast.LENGTH_LONG).show();
                }
            }
        }, 10000);
    }
}
