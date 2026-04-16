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
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * JARVIS 心率桥接 APP 主界面
 */
public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.POST_NOTIFICATIONS
    };

    private TextView tvStatus;
    private TextView tvHeartRate;
    private TextView tvDeviceName;
    private Button btnStart;
    private Button btnStop;
    private Button btnSettings;
    private EditText etServerUrl;
    private EditText etToken;
    private Switch swAutoStart;

    private SharedPreferences prefs;
    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("config", MODE_PRIVATE);

        initViews();
        loadConfig();
        checkPermissions();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvHeartRate = findViewById(R.id.tv_heart_rate);
        tvDeviceName = findViewById(R.id.tv_device_name);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnSettings = findViewById(R.id.btn_settings);
        etServerUrl = findViewById(R.id.et_server_url);
        etToken = findViewById(R.id.et_token);
        swAutoStart = findViewById(R.id.sw_auto_start);

        btnStart.setOnClickListener(v -> startService());
        btnStop.setOnClickListener(v -> stopService());
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        updateUI();
    }

    private void loadConfig() {
        String serverUrl = prefs.getString("server_url", "http://100.126.107.40:18890/jarvis/sensor/heart-rate");
        String token = prefs.getString("token", "");
        boolean autoStart = prefs.getBoolean("auto_start", false);

        etServerUrl.setText(serverUrl);
        etToken.setText(token);
        swAutoStart.setChecked(autoStart);
    }

    private void saveConfig() {
        String serverUrl = etServerUrl.getText().toString().trim();
        String token = etToken.getText().toString().trim();
        boolean autoStart = swAutoStart.isChecked();

        prefs.edit()
            .putString("server_url", serverUrl)
            .putString("token", token)
            .putBoolean("auto_start", autoStart)
            .apply();
    }

    private void checkPermissions() {
        boolean allGranted = true;
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "需要所有权限才能正常运行", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startService() {
        saveConfig();

        String serverUrl = etServerUrl.getText().toString().trim();
        if (TextUtils.isEmpty(serverUrl)) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, HeartRateService.class);
        intent.putExtra("server_url", serverUrl);
        intent.putExtra("token", etToken.getText().toString().trim());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        isRunning = true;
        updateUI();
        Toast.makeText(this, "心率监测已启动", Toast.LENGTH_SHORT).show();
    }

    private void stopService() {
        Intent intent = new Intent(this, HeartRateService.class);
        stopService(intent);

        isRunning = false;
        updateUI();
        Toast.makeText(this, "心率监测已停止", Toast.LENGTH_SHORT).show();
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("设置");

        View view = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        EditText etDeviceName = view.findViewById(R.id.et_device_name);
        EditText etInterval = view.findViewById(R.id.et_interval);

        String deviceName = prefs.getString("device_name", "HUAWEI Band 10-2A8");
        int interval = prefs.getInt("scan_interval", 30);

        etDeviceName.setText(deviceName);
        etInterval.setText(String.valueOf(interval));

        builder.setView(view);
        builder.setPositiveButton("保存", (dialog, which) -> {
            prefs.edit()
                .putString("device_name", etDeviceName.getText().toString().trim())
                .putInt("scan_interval", Integer.parseInt(etInterval.getText().toString().trim()))
                .apply();
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void updateUI() {
        if (isRunning) {
            tvStatus.setText("状态: 运行中");
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark));
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
        } else {
            tvStatus.setText("状态: 已停止");
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark));
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
        }
    }

    public void updateHeartRate(int hr, String deviceName) {
        runOnUiThread(() -> {
            tvHeartRate.setText("心率: " + hr + " bpm");
            tvDeviceName.setText("设备: " + deviceName);
        });
    }
}
