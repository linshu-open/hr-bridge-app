package cn.jarvis.hrbridge;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * JARVIS 蹇冪巼妗ユ帴 APP 涓荤晫闈? */
public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String[] REQUIRED_PERMISSIONS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
        new String[]{
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.POST_NOTIFICATIONS
        } :
        new String[]{
            Manifest.permission.ACCESS_FINE_LOCATION
        };

    private TextView tvStatus;
    private TextView tvHeartRate;
    private TextView tvDeviceName;
    private Button btnStart;
    private Button btnStop;
    private Button btnSettings;

    private SharedPreferences prefs;
    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("config", MODE_PRIVATE);

        initViews();
        checkPermissions();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvHeartRate = findViewById(R.id.tv_heart_rate);
        tvDeviceName = findViewById(R.id.tv_device_name);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnSettings = findViewById(R.id.btn_settings);

        btnStart.setOnClickListener(v -> startService());
        btnStop.setOnClickListener(v -> stopService());
        btnSettings.setOnClickListener(v -> showSettings());

        btnStop.setEnabled(false);
    }

    private void startService() {
        if (!checkPermissions()) {
            Toast.makeText(this, "Please grant permissions first", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, HeartRateService.class);
        intent.putExtra("server_url", prefs.getString("server_url", "http://100.126.107.40:18890/jarvis/sensor/heart-rate"));
        intent.putExtra("token", prefs.getString("token", ""));
        intent.putExtra("device_name", prefs.getString("device_name", "HUAWEI Band 10-2A8"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        isRunning = true;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        tvStatus.setText("Scanning...");
    }

    private void stopService() {
        Intent intent = new Intent(this, HeartRateService.class);
        stopService(intent);

        isRunning = false;
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        tvStatus.setText("Stopped");
    }

    private void showSettings() {
        Toast.makeText(this, "Settings: Configure in app/src/main/res/xml/prefs.xml", Toast.LENGTH_LONG).show();
    }

    private boolean checkPermissions() {
        boolean allGranted = true;
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
        }

        return allGranted;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "Permissions required for BLE scanning", Toast.LENGTH_LONG).show();
            }
        }
    }

    public void updateHeartRate(int hr, String deviceName) {
        runOnUiThread(() -> {
            tvHeartRate.setText(String.valueOf(hr));
            tvDeviceName.setText("Device: " + deviceName);
            tvStatus.setText("Connected");
        });
    }
}