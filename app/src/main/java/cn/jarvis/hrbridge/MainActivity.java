package cn.jarvis.hrbriage;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

public class MainActivity extends AppCompatActivity {
    private TextView tvStatus, tvHeartRate, tvDeviceName, tvLog;
    private Button btnStart, btnStop, btnSettings, btnScan, btnClearLog;
    private ScrollView scrollLog;
    private SharedPreferences prefs;
    private static final String GITHUB_REPO = "linshu-open/hr-bridge-app";
    private static final String CURRENT_VERSION = "1.2.0";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("hrbridge", MODE_PRIVATE);
        tvStatus = findViewById(R.id.tv_status);
        tvHeartRate = findViewById(R.id.tv_heart_rate);
        tvDeviceName = findViewById(R.id.tv_device_name);
        tvLog = findViewById(R.id.tv_log);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnSettings = findViewById(R.id.btn_settings);
        btnScan = findViewById(R.id.btn_scan);
        btnClearLog = findViewById(R.id.btn_clear_log);
        scrollLog = findViewById(R.id.scroll_log);
        loadConfig();
        btnStart.setOnClickListener(v -> { if (checkPermissions()) startService(); });
        btnStop.setOnClickListener(v -> stopService());
        btnSettings.setOnClickListener(v -> showSettingsDialog());
        btnScan.setOnClickListener(v -> startActivity(new Intent(this, ScanActivity.class)));
        btnClearLog.setOnClickListener(v -> { prefs.edit().remove("log").apply(); tvLog.setText(""); });
        checkForUpdate();
    }

    @Override
    protected void onResume() { super.onResume(); loadConfig(); loadLog(); }

    private void loadConfig() {
        String deviceName = prefs.getString("device_name", "");
        tvDeviceName.setText(deviceName.isEmpty() ? "鏈€夋嫨璁惧" : "璁惧: " + deviceName);
        tvStatus.setText("鐘舵€? 鏈惎鍔?);
        tvHeartRate.setText("蹇冪巼: -- bpm");
    }

    private void loadLog() {
        String log = prefs.getString("log", "");
        tvLog.setText(log.isEmpty() ? "鏆傛棤鏃ュ織" : log);
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    private boolean checkPermissions() {
        String[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
            new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS} :
            new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, permissions, 1);
                return false;
            }
        }
        return true;
    }

    private void startService() {
        String deviceName = prefs.getString("device_name", "");
        if (deviceName.isEmpty()) { Toast.makeText(this, "璇峰厛鎵弿閫夋嫨璁惧", Toast.LENGTH_LONG).show(); return; }
        Intent intent = new Intent(this, HeartRateService.class);
        intent.putExtra("server_url", prefs.getString("server_url", ""));
        intent.putExtra("token", prefs.getString("token", ""));
        intent.putExtra("device_name", deviceName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent); else startService(intent);
        tvStatus.setText("鐘舵€? 鎵弿涓?..");
        Toast.makeText(this, "鏈嶅姟宸插惎鍔?, Toast.LENGTH_SHORT).show();
    }

    private void stopService() {
        stopService(new Intent(this, HeartRateService.class));
        tvStatus.setText("鐘舵€? 宸插仠姝?);
        tvHeartRate.setText("蹇冪巼: -- bpm");
    }

    private void showSettingsDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        TextView etServerUrl = view.findViewById(R.id.et_server_url);
        TextView etToken = view.findViewById(R.id.et_token);
        etServerUrl.setText(prefs.getString("server_url", "http://100.126.107.40:18890/jarvis/sensor/heart-rate"));
        etToken.setText(prefs.getString("token", "jarvis-hr-token-2026"));
        new AlertDialog.Builder(this).setTitle("璁剧疆").setView(view)
            .setPositiveButton("淇濆瓨", (d, w) -> {
                prefs.edit().putString("server_url", etServerUrl.getText().toString()).putString("token", etToken.getText().toString()).apply();
                Toast.makeText(this, "璁剧疆宸蹭繚瀛?, Toast.LENGTH_SHORT).show();
            }).setNegativeButton("鍙栨秷", null).show();
    }

    private void checkForUpdate() {
        new Thread(() -> {
            try {
                URL url = new URL("https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(5000);
                Scanner scanner = new Scanner(conn.getInputStream());
                StringBuilder json = new StringBuilder();
                while (scanner.hasNext()) json.append(scanner.nextLine());
                scanner.close();
                String response = json.toString();
                String latestVersion = response.split("\"tag_name\":\"v")[1].split("\"")[0];
                String downloadUrl = response.split("\"browser_download_url\":\"")[1].split("\"")[0];
                if (!latestVersion.equals(CURRENT_VERSION)) {
                    runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("鍙戠幇鏂扮増鏈?v" + latestVersion)
                        .setPositiveButton("绔嬪嵆鏇存柊", (d, w) -> downloadAndInstall(downloadUrl))
                        .setNegativeButton("绋嶅悗", null).show());
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private void downloadAndInstall(String downloadUrl) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
                File file = new File(getExternalFilesDir(null), "app-update.apk");
                FileOutputStream fos = new FileOutputStream(file);
                InputStream is = conn.getInputStream();
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
                fos.close(); is.close();
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.setDataAndType(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file), "application/vnd.android.package-archive");
                    startActivity(intent);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "涓嬭浇澶辫触: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}