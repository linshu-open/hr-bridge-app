package cn.jarvis.hrbridge;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_PERMS = 1;
    private static final int REQ_ENABLE_BT = 2;
    private TextView tvDeviceName, tvStatus, tvHeartRate, tvLog;
    private Button btnScan, btnStart, btnStop, btnSettings, btnClearLog;
    private SharedPreferences prefs;
    private AtomicBoolean logUpdating = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("hrbridge", MODE_PRIVATE);
        tvDeviceName = findViewById(R.id.tv_device_name);
        tvStatus = findViewById(R.id.tv_status);
        tvHeartRate = findViewById(R.id.tv_heart_rate);
        tvLog = findViewById(R.id.tv_log);
        btnScan = findViewById(R.id.btn_scan);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnSettings = findViewById(R.id.btn_settings);
        btnClearLog = findViewById(R.id.btn_clear_log);

        // Load saved settings
        String deviceName = prefs.getString("device_name", "");
        if (!deviceName.isEmpty()) {
            tvDeviceName.setText(deviceName);
        }

        btnScan.setOnClickListener(v -> {
            addLog("点击扫描按钮");
            if (checkBluetoothAndPermissions()) {
                addLog("开始扫描...");
                startActivityForResult(new Intent(this, ScanActivity.class), 100);
            }
        });

        btnStart.setOnClickListener(v -> {
            String device = prefs.getString("device_name", "");
            String url = prefs.getString("server_url", "");
            if (device.isEmpty()) {
                Toast.makeText(this, R.string.no_device, Toast.LENGTH_SHORT).show();
                return;
            }
            if (url.isEmpty()) {
                Toast.makeText(this, R.string.server_url_label, Toast.LENGTH_SHORT).show();
                return;
            }
            startService(new Intent(this, HeartRateService.class));
            tvStatus.setText(R.string.service_running);
            Toast.makeText(this, R.string.service_running, Toast.LENGTH_SHORT).show();
        });

        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, HeartRateService.class));
            tvStatus.setText(R.string.service_stopped);
            Toast.makeText(this, R.string.service_stopped, Toast.LENGTH_SHORT).show();
        });

        btnSettings.setOnClickListener(v -> showSettingsDialog());

        btnClearLog.setOnClickListener(v -> {
            prefs.edit().putString("log", "").apply();
            tvLog.setText("");
        });

        // Check for updates
        checkUpdate();
    }

    private boolean checkBluetoothAndPermissions() {
        // 检查蓝牙是否开启
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ba = bm.getAdapter();
        
        if (ba == null) {
            addLog("设备不支持蓝牙");
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_LONG).show();
            return false;
        }
        
        if (!ba.isEnabled()) {
            addLog("蓝牙未开启，请求开启");
            Intent enableBt = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBt, REQ_ENABLE_BT);
            return false;
        }
        
        addLog("蓝牙已开启");

        // 根据Android版本请求不同权限
        // Android 12+ (API 31+) 只需要 BLUETOOTH_SCAN 和 BLUETOOTH_CONNECT
        // Android 11及以下需要 ACCESS_FINE_LOCATION
        java.util.List<String> permList = new java.util.ArrayList<>();
        permList.add(Manifest.permission.BLUETOOTH_SCAN);
        permList.add(Manifest.permission.BLUETOOTH_CONNECT);
        
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            permList.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        
        String[] perms = permList.toArray(new String[0]);
        addLog("Android " + android.os.Build.VERSION.SDK_INT + ", 需要权限: " + java.util.Arrays.toString(perms));
        
        java.util.List<String> notGranted = new java.util.ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                notGranted.add(p);
                addLog("缺少权限: " + p);
            }
        }
        
        if (!notGranted.isEmpty()) {
            addLog("请求权限: " + notGranted.size() + " 个");
            ActivityCompat.requestPermissions(this, notGranted.toArray(new String[0]), REQ_PERMS);
            return false;
        }
        
        addLog("所有权限已授予");
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQ_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                addLog("蓝牙已开启");
                Toast.makeText(this, "蓝牙已开启，请再次点击扫描", Toast.LENGTH_SHORT).show();
            } else {
                addLog("用户拒绝开启蓝牙");
                Toast.makeText(this, "需要开启蓝牙才能扫描设备", Toast.LENGTH_LONG).show();
            }
        }
        
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            String name = data.getStringExtra("device_name");
            String address = data.getStringExtra("device_address");
            prefs.edit().putString("device_name", name).putString("device_address", address).apply();
            tvDeviceName.setText(name);
            addLog("已选择设备: " + name);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                addLog("权限已授予，请再次点击扫描");
                Toast.makeText(this, "权限已授予，请点击扫描", Toast.LENGTH_SHORT).show();
            } else {
                addLog("部分权限被拒绝");
                new AlertDialog.Builder(this)
                    .setTitle("需要权限")
                    .setMessage("需要蓝牙和位置权限才能扫描心率设备，请到设置中授予权限")
                    .setPositiveButton("去设置", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("取消", null)
                    .show();
            }
        }
    }

    private void addLog(String msg) {
        String log = prefs.getString("log", "");
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        String newLog = log + "\n[" + time + "] " + msg;
        prefs.edit().putString("log", newLog).apply();
        tvLog.setText(newLog);
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.btn_settings);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText etUrl = new EditText(this);
        etUrl.setHint(getString(R.string.server_url_label));
        etUrl.setText(prefs.getString("server_url", "http://100.126.107.40:18890/jarvis/sensor/heart-rate"));
        layout.addView(etUrl);

        final EditText etToken = new EditText(this);
        etToken.setHint("Token (可选)");
        etToken.setText(prefs.getString("token", ""));
        layout.addView(etToken);

        builder.setView(layout);
        builder.setPositiveButton(R.string.save, (dialog, which) -> {
            prefs.edit()
                .putString("server_url", etUrl.getText().toString())
                .putString("token", etToken.getText().toString())
                .apply();
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private void checkUpdate() {
        new Thread(() -> {
            try {
                URL url = new URL("https://api.github.com/repos/linshu-open/hr-bridge-app/releases/latest");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.connect();
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                JSONObject json = new JSONObject(sb.toString());
                String latestVer = json.getString("tag_name");
                String currentVer = "v1.2.3";
                if (!latestVer.equals(currentVer)) {
                    String apkUrl = json.getJSONArray("assets").getJSONObject(0).getString("browser_download_url");
                    runOnUiThread(() -> showUpdateDialog(apkUrl));
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private void showUpdateDialog(String apkUrl) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.update_available)
            .setMessage(R.string.update_now)
            .setPositiveButton(R.string.update_now, (d, w) -> downloadAndInstall(apkUrl))
            .setNegativeButton(R.string.later, null)
            .show();
    }

    private void downloadAndInstall(String apkUrl) {
        new Thread(() -> {
            try {
                URL url = new URL(apkUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                InputStream is = conn.getInputStream();
                File file = new File(getExternalFilesDir(null), "update.apk");
                FileOutputStream fos = new FileOutputStream(file);
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                is.close();

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setDataAndType(
                    androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file),
                    "application/vnd.android.package-archive"
                );
                startActivity(intent);
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }
}
