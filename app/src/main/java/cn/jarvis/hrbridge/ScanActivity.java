package cn.jarvis.hrbriage;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ScanActivity extends AppCompatActivity {
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private ArrayList<String> deviceList = new ArrayList<>();
    private Map<String, BluetoothDevice> deviceMap = new HashMap<>();
    private BluetoothLeScanner scanner;
    private SharedPreferences prefs;
    private Handler handler = new Handler();
    private static final int SCAN_TIMEOUT = 10000;

    private ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            String address = device.getAddress();
            if (name != null && !deviceMap.containsKey(address)) {
                deviceMap.put(address, device);
                deviceList.add(name + "\n" + address + " (" + result.getRssi() + "dBm)");
                adapter.notifyDataSetChanged();
            }
        }
        @Override
        public void onScanFailed(int errorCode) {
            Toast.makeText(ScanActivity.this, "鎵弿澶辫触: " + errorCode, Toast.LENGTH_LONG).show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        setTitle("鎵弿璁惧");
        prefs = getSharedPreferences("hrbridge", MODE_PRIVATE);
        listView = findViewById(R.id.list_devices);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceList);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String item = deviceList.get(position);
            String address = item.split("\n")[1].split(" ")[0];
            BluetoothDevice device = deviceMap.get(address);
            prefs.edit().putString("device_name", device.getName()).putString("device_address", address).apply();
            Toast.makeText(this, "宸查€夋嫨: " + device.getName(), Toast.LENGTH_SHORT).show();
            finish();
        });
        if (checkPermissions()) startScan();
    }

    private boolean checkPermissions() {
        String[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
            new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION} :
            new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : permissions) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, permissions, 1);
                return false;
            }
        }
        return true;
    }

    private void startScan() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "璇峰厛寮€鍚摑鐗?, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            scanner.startScan(null, settings, scanCallback);
            handler.postDelayed(this::stopScan, SCAN_TIMEOUT);
        }
    }

    private void stopScan() {
        if (scanner != null && scanCallback != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                scanner.stopScan(scanCallback);
            }
        }
        if (deviceList.isEmpty()) Toast.makeText(this, "鏈壘鍒拌澶?, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() { super.onDestroy(); stopScan(); }
}