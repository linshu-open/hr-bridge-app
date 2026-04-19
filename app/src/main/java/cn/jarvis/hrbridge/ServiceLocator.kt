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

    fun init(ctx: Context) {
        if (::appCtx.isInitialized) return
        appCtx = ctx.applicationContext

        settingsStore = SettingsStore(appCtx)
        jarvisApi     = JarvisApi()
        githubApi     = GithubApi()
        bleScanner    = BleScanner(appCtx)

        val db = HrDatabase.get(appCtx)
        hrRepository = HrRepository(db.hrDao(), jarvisApi, settingsStore)
        alertManager = AlertManager(appCtx)
        sensorRepository = SensorRepository(db.sensorDao(), jarvisApi, settingsStore, alertManager)

        // SensorHub 在 Service 中 start；此处只构建依赖关系
        sensorHub = SensorHub(
            collectors = listOf(
                StepCounterCollector(appCtx),
                LocationCollector(appCtx),
                AccelerometerCollector(appCtx),
                GyroscopeCollector(appCtx),
                LightCollector(appCtx),
                BluetoothStateCollector(appCtx),
                SleepCollector(appCtx)
            ),
            repo = sensorRepository
        )

        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // 后台跑一次 v1→v2 迁移 + 维护
        appScope.launch {
            runCatching { LegacyMigration.runIfNeeded(appCtx, settingsStore) }
            runCatching { hrRepository.runMaintenance() }
            runCatching { sensorRepository.runMaintenance() }
        }
    }
}
