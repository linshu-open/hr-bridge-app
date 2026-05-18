package cn.jarvis.hrbridge

import android.content.Context
import cn.jarvis.hrbridge.ble.BleConnection
import cn.jarvis.hrbridge.ble.BleScanner
import cn.jarvis.hrbridge.data.local.HrDatabase
import cn.jarvis.hrbridge.data.prefs.LegacyMigration
import cn.jarvis.hrbridge.data.prefs.SettingsStore
import cn.jarvis.hrbridge.data.remote.GithubApi
import cn.jarvis.hrbridge.data.remote.JarvisApi
import cn.jarvis.hrbridge.data.repo.HrRepository
import cn.jarvis.hrbridge.sensors.AlertManager
import cn.jarvis.hrbridge.sensors.SensorHub
import cn.jarvis.hrbridge.sensors.SensorRepository
import cn.jarvis.hrbridge.sensors.SensorRingBuffer
import cn.jarvis.hrbridge.sensors.impl.AccelerometerCollector
import cn.jarvis.hrbridge.sensors.impl.BluetoothStateCollector
import cn.jarvis.hrbridge.sensors.impl.GyroscopeCollector
import cn.jarvis.hrbridge.sensors.impl.LightCollector
import cn.jarvis.hrbridge.sensors.impl.LocationCollector
import cn.jarvis.hrbridge.sensors.impl.SleepCollector
import cn.jarvis.hrbridge.sensors.impl.StepCounterCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 手写依赖容器（替代 Hilt，保持 APK 体积）。
 *
 * 使用：
 *     val repo = ServiceLocator.hrRepository
 *
 * 初始化：
 *     App.onCreate 中调用 ServiceLocator.init(this)
 */
object ServiceLocator {
    private lateinit var appCtx: Context

    // ---- 单例缓存 ----
    lateinit var settingsStore: SettingsStore    ; private set
    lateinit var jarvisApi: JarvisApi            ; private set
    lateinit var githubApi: GithubApi            ; private set
    lateinit var hrRepository: HrRepository      ; private set
    lateinit var sensorRepository: SensorRepository ; private set
    lateinit var sensorHub: SensorHub            ; private set
    lateinit var alertManager: AlertManager       ; private set
    lateinit var bleScanner: BleScanner          ; private set
    private lateinit var appScope: CoroutineScope

    /** 专用给前台服务用；每个 Service 实例独占一个 BleConnection（因为 GATT 是一对一） */
    fun newBleConnection(): BleConnection = BleConnection(appCtx)

    val applicationContext: Context get() = appCtx
    val scope: CoroutineScope get() = appScope

    private fun getProcessName(context: Context): String? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return android.app.Application.getProcessName()
        }
        val pid = android.os.Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        return am?.runningAppProcesses?.find { it.pid == pid }?.processName
    }

    fun init(ctx: Context) {
        if (::appCtx.isInitialized) return
        appCtx = ctx.applicationContext

        // 避免在 :keepalive 辅助进程中初始化整个 Sensor 数据库和 DataStore，防止多进程写锁和资源浪费
        val processName = getProcessName(appCtx)
        if (processName != null && processName.endsWith(":keepalive")) {
            cn.jarvis.hrbridge.util.Logger.i("ServiceLocator", "Skipping initialization in keepalive process: $processName")
            return
        }

        settingsStore = SettingsStore(appCtx)
        jarvisApi     = JarvisApi()
        githubApi     = GithubApi()
        bleScanner    = BleScanner(appCtx)

        val db = HrDatabase.get(appCtx)
        hrRepository = HrRepository(db.hrDao(), jarvisApi, settingsStore)
        alertManager = AlertManager(appCtx)
        sensorRepository = SensorRepository(db.sensorDao(), jarvisApi, settingsStore, alertManager)

        // SensorHub 在 Service 中 start；此处只构建依赖关系
        val accelerometerCollector = AccelerometerCollector(appCtx)
        val gyroscopeCollector = GyroscopeCollector(appCtx)
        sensorHub = SensorHub(
            collectors = listOf(
                StepCounterCollector(appCtx),
                LocationCollector(appCtx),
                accelerometerCollector,
                gyroscopeCollector,
                LightCollector(appCtx),
                BluetoothStateCollector(appCtx),
                SleepCollector(appCtx)
            ),
            repo = sensorRepository
        )

        // M1B: wire shared IMU aggregator to accel + gyro collectors
        accelerometerCollector.aggregator = sensorHub.imuAggregator
        gyroscopeCollector.aggregator = sensorHub.imuAggregator

        // M1B: wire motion state detector to accelerometer (master trigger)
        accelerometerCollector.motionDetector = sensorHub.motionDetector

        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // 持续收集 Settings 变化更新内存缓存，提供极速同步读取
        appScope.launch {
            runCatching {
                settingsStore.settings.collect { settings ->
                    settingsStore.cache = settings
                }
            }
        }

        // 后台跑一次 v1→v2 迁移 + 维护
        appScope.launch {
            runCatching { LegacyMigration.runIfNeeded(appCtx, settingsStore) }
            runCatching { ensureCurrentMcpEndpoints() }
            runCatching { hrRepository.runMaintenance() }
            runCatching { sensorRepository.runMaintenance() }
        }
    }

    private suspend fun ensureCurrentMcpEndpoints() {
        val s = settingsStore.settings.first()
        fun isOldOrUnsafe(url: String): Boolean {
            val normalized = url.lowercase()
            return normalized.isBlank() ||
                normalized.startsWith("http://") ||
                "100.126.107.40" in normalized ||
                "114.132.201.207" in normalized ||
                ":18890" in normalized ||
                "/jarvis/sensor" in normalized
        }

        if (isOldOrUnsafe(s.serverUrl)) {
            settingsStore.setServerUrl(BuildConfig.DEFAULT_SERVER_URL)
        }
        if (isOldOrUnsafe(s.batchUrl)) {
            settingsStore.setBatchUrl(BuildConfig.DEFAULT_BATCH_URL)
        }
        if (isOldOrUnsafe(s.sensorBaseUrl) || "/mcp/api/sensor" !in s.sensorBaseUrl) {
            settingsStore.setSensorBaseUrl(BuildConfig.DEFAULT_SENSOR_BASE_URL)
        }
    }
}
