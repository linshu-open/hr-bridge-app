package cn.jarvis.hrbridge.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.ble.BleConnection
import cn.jarvis.hrbridge.data.prefs.AppSettings
import cn.jarvis.hrbridge.util.ExponentialBackoff
import cn.jarvis.hrbridge.util.HrStatus
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 心率前台服务：
 * - 启动时读取选中设备 MAC，建立 GATT 连接
 * - 订阅心率通知 → 入库（Repository）→ 紧急心率立即上传 + 本地告警
 * - 断线指数退避自动重连（3/9/27/81s）
 * - 可被 BootReceiver 启动
 */
class HeartRateService : LifecycleService() {

    private lateinit var connection: BleConnection
    private val backoff = ExponentialBackoff(baseMs = 3_000L, maxAttempts = Int.MAX_VALUE, factor = 3.0)
    private var reconnectJob: Job? = null
    private var attempt = 0

    private val recentHrBuffer = ArrayDeque<Int>()
    private val maxBufferSize = 12       // 最近 ~1 分钟窗口用于趋势判断

    private var currentDeviceMac: String = ""
    private var currentDeviceName: String = ""
    private var criticalSinceMs: Long = 0L   // 连续 critical 开始时间，用于 30s 响铃规则

    override fun onCreate() {
        super.onCreate()
        connection = ServiceLocator.newBleConnection()
        subscribeBleEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // 立刻进入前台，避免 Android 12+ 超时崩溃
        promoteToForeground(getString(cn.jarvis.hrbridge.R.string.notif_service_scanning))

        lifecycleScope.launch {
            val s = ServiceLocator.settingsStore.settings.first()
            currentDeviceMac = s.selectedDeviceMac
            currentDeviceName = s.selectedDeviceName
            if (currentDeviceMac.isEmpty()) {
                Logger.w("HRService", "未配置设备，停止服务")
                stopSelf()
                return@launch
            }
            // 注册周期上传任务（幂等）
            UploadWorker.schedule(this@HeartRateService)
            connection.connect(currentDeviceMac)
        }

        return START_STICKY
    }

    private fun promoteToForeground(text: String, hr: Int? = null) {
        val notif = NotifyHelper.serviceNotification(this, text, hr)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotifyHelper.NOTIF_ID_SERVICE, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } else {
            startForeground(NotifyHelper.NOTIF_ID_SERVICE, notif)
        }
    }

    private fun updateForegroundNotification(text: String, hr: Int?) {
        val nm = getSystemService<NotificationManager>() ?: return
        nm.notify(NotifyHelper.NOTIF_ID_SERVICE, NotifyHelper.serviceNotification(this, text, hr))
    }

    private fun subscribeBleEvents() {
        connection.events.onEach { evt ->
            when (evt) {
                is BleConnection.Event.Connecting -> {
                    updateForegroundNotification("正在连接设备…", null)
                }
                is BleConnection.Event.Connected -> {
                    attempt = 0
                    reconnectJob?.cancel()
                    updateForegroundNotification("已连接 $currentDeviceName", null)
                }
                is BleConnection.Event.ServicesDiscovered -> {
                    Logger.i("HRService", "服务发现完成")
                }
                is BleConnection.Event.HeartRate -> handleHeartRate(evt.hr)
                is BleConnection.Event.Disconnected -> scheduleReconnect()
                is BleConnection.Event.Error -> {
                    Logger.w("HRService", "BLE 错误: ${evt.message}")
                    updateForegroundNotification("BLE 错误: ${evt.message}", null)
                    scheduleReconnect()
                }
            }
        }.launchIn(lifecycleScope)
    }

    private fun handleHeartRate(hr: Int) {
        recentHrBuffer.addLast(hr)
        while (recentHrBuffer.size > maxBufferSize) recentHrBuffer.removeFirst()

        updateForegroundNotification(
            getString(cn.jarvis.hrbridge.R.string.notif_service_connected, currentDeviceName, hr),
            hr
        )

        lifecycleScope.launch {
            val settings = ServiceLocator.settingsStore.settings.first()
            val (id, status) = ServiceLocator.hrRepository.ingest(
                hr = hr,
                device = currentDeviceName.ifEmpty { "unknown" },
                recentHrForTrend = recentHrBuffer.toList()
            )

            // 紧急心率：立即单条上传 + 考虑本地告警
            if (status.isCritical) {
                ServiceLocator.hrRepository.uploadImmediate(id)
                maybeTriggerLocalAlert(hr, status, settings)
            } else {
                criticalSinceMs = 0L
            }
        }
    }

    private fun maybeTriggerLocalAlert(hr: Int, status: HrStatus, settings: AppSettings) {
        if (!settings.alertEnabled) return
        if (isInQuietHours(settings)) return

        // 规则：需求文档 §7 A14 — 持续 30s 才响铃
        val now = System.currentTimeMillis()
        if (criticalSinceMs == 0L) {
            criticalSinceMs = now
            return
        }
        if (now - criticalSinceMs < 30_000L) return

        val title = if (status == HrStatus.CRITICAL_HIGH) "心率异常（偏高）" else "心率异常（偏低）"
        val nm = getSystemService<NotificationManager>() ?: return
        nm.notify(
            NotifyHelper.NOTIF_ID_ALERT,
            NotifyHelper.alertNotification(this, title, "当前 $hr bpm，请关注")
        )
    }

    private fun isInQuietHours(s: AppSettings): Boolean {
        val hour = java.time.LocalTime.now().hour
        val start = s.alertQuietStart
        val end = s.alertQuietEnd
        // 跨零点情况（例如 23-7）
        return if (start <= end) hour in start until end
               else hour >= start || hour < end
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val delay = backoff.nextDelayMs(attempt) ?: 405_000L
        reconnectJob = lifecycleScope.launch {
            Logger.i("HRService", "${delay}ms 后重连（第 ${attempt + 1} 次）")
            updateForegroundNotification("连接断开，${delay / 1000}s 后重试", null)
            delay(delay)
            attempt++
            if (currentDeviceMac.isNotEmpty()) connection.connect(currentDeviceMac)
        }
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        connection.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, HeartRateService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HeartRateService::class.java))
        }
    }
}
