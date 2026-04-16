package cn.jarvis.hrbriage;

import android.Manifest;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
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
            if (checkPerms()) startScan();
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
            tvLog.setText(R.string.no_log);
        });
        
        // Check for updates
        checkUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateLog();
    }

    private void updateLog() {
        if (logUpdating.get()) return;
        logUpdating.set(true);
        new Thread(() -> {
            String log = prefs.getString("log", "");
            runOnUiThread(() -> {
                if (!log.isEmpty()) tvLog.setText(log);
                logUpdating.set(false);
            });
        }).start();
    }

    private boolean checkPerms() {
        String[] perms = {
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        };
        boolean granted = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        if (!granted) {
            ActivityCompat.requestPermissions(this, perms, REQ_PERMS);
        }
        return granted;
    }

    private void startScan() {
        startActivityForResult(new Intent(this, ScanActivity.class), 100);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 100 && res == RESULT_OK && data != null) {
            String name = data.getStringExtra("device_name");
            String addr = data.getStringExtra("device_address");
            prefs.edit().putString("device_name", name).putString("device_address", addr).apply();
            tvDeviceName.setText(name + " (" + addr + ")");
            Toast.makeText(this, R.string.device_selected, Toast.LENGTH_SHORT).show();
        }
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.btn_settings);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);
        
        final EditText etUrl = new EditText(this);
        etUrl.setHint("http://ip:port/jarvis/sensor/heart-rate");
        etUrl.setText(prefs.getString("server_url", ""));
        layout.addView(etUrl);
        
        final EditText etToken = new EditText(this);
        etToken.setHint("Token");
        etToken.setText(prefs.getString("token", ""));
        layout.addView(etToken);
        
        builder.setView(layout);
        builder.setPositiveButton(android.R.string.ok, (d, w) -> {
            prefs.edit()
                .putString("server_url", etUrl.getText().toString())
                .putString("token", etToken.getText().toString())
                .apply();
            Toast.makeText(this, R.string.btn_settings, Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton(android.R.string.cancel, null);
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
                String currentVer = "v1.2.0";
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
