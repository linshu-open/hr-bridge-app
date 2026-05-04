package cn.jarvis.hrbridge.sensors.impl

import android.content.Context
import cn.jarvis.hrbridge.sensors.Emit
import cn.jarvis.hrbridge.sensors.SensorCollector
import cn.jarvis.hrbridge.sensors.SensorType
import cn.jarvis.hrbridge.sensors.UploadMode
import kotlinx.coroutines.CoroutineScope

/**
 * 睡眠数据。
 *
 * TODO(handoff):
 *   优先方案 A：BLE 手环私有睡眠特征（华为/小米 需逆向协议）→ 本版可跳过
 *   优先方案 B：Google Sleep API（需要依赖 play-services-awareness / health-services-client）
 *   首次交付：每天早上 8:00 触发一次上报（WorkManager 一次性任务），无数据则上报占位
 *   emit(SensorType.SLEEP, json) 参见 §F2.7
 */
class SleepCollector(private val ctx: Context) : SensorCollector {

    override val type: String = SensorType.SLEEP

    /** 当前骨架默认不可用，避免 Hub 误启动 */
    override fun isAvailable(): Boolean = false

    override fun start(scope: CoroutineScope, mode: UploadMode, emit: Emit) {
        // TODO(handoff)
    }

    override fun stop() {
        // TODO(handoff)
    }
}
