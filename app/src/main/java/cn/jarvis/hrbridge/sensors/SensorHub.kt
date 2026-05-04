package cn.jarvis.hrbridge.sensors

import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.CoroutineScope

/**
 * 管理所有 [SensorCollector] 的总线。
 * 由 [cn.jarvis.hrbridge.service.HeartRateService]（实际上是 SensorHub 宿主）调用。
 *
 * - [start] 按"启用集合"启动对应 Collector
 * - [applyMode] 广播模式切换
 * - [stop] 全部停止
 *
 * 并不直接持有 SettingsStore，由外层把启用集合和模式喂进来，便于测试。
 */
class SensorHub(
    private val collectors: List<SensorCollector>,
    private val repo: SensorRepository
) {
    @Volatile private var currentMode: UploadMode = UploadMode.NORMAL
    private val running: MutableSet<String> = mutableSetOf()

    fun start(scope: CoroutineScope, enabled: Set<String>, mode: UploadMode) {
        currentMode = mode
        val emit: Emit = { type, json -> repo.ingest(type, json) }

        for (c in collectors) {
            val want = c.type in enabled && c.isAvailable()
            val isRunning = c.type in running
            when {
                want && !isRunning -> {
                    runCatching { c.start(scope, mode, emit) }
                        .onSuccess { running.add(c.type); Logger.i("SensorHub", "start ${c.type}") }
                        .onFailure { Logger.w("SensorHub", "start ${c.type} failed: ${it.message}") }
                }
                !want && isRunning -> {
                    runCatching { c.stop() }
                    running.remove(c.type)
                    Logger.i("SensorHub", "stop ${c.type}")
                }
                /* want == isRunning：不动 */
            }
        }
    }

    fun applyMode(mode: UploadMode) {
        if (mode == currentMode) return
        currentMode = mode
        for (c in collectors) {
            if (c.type in running) runCatching { c.onModeChanged(mode) }
        }
        Logger.i("SensorHub", "mode → ${mode.wire}")
    }

    fun stop() {
        for (c in collectors) {
            if (c.type in running) runCatching { c.stop() }
        }
        running.clear()
    }

    fun runningTypes(): Set<String> = running.toSet()
}
