package cn.jarvis.hrbridge.sensors

import kotlinx.coroutines.CoroutineScope

/**
 * 采集器统一接口。每个具体 Collector 负责：
 *   1. 申请并持有 Android 系统传感器（SensorManager / FusedLocation / BluetoothAdapter …）
 *   2. 按 [UploadMode] 决定采样频率与汇总窗口
 *   3. 将一条可上传的 JSON 样本通过 [Emit] 回调交给上层（SensorHub → SensorRepository）
 *
 * 具体实现细节交给后续模型补齐，当前先做空骨架（见 sensors/impl/）。
 */
typealias Emit = suspend (type: String, json: String) -> Unit

interface SensorCollector {
    /** 固定为 [SensorType] 里的某个常量 */
    val type: String

    /** 启动采集；多次调用需幂等，由 SensorHub 管理生命周期 */
    fun start(scope: CoroutineScope, mode: UploadMode, emit: Emit)

    /** 切换上传模式（可能触发采样率变更） */
    fun onModeChanged(mode: UploadMode) { /* 默认不处理，具体 Collector 覆盖 */ }

    /** 停止并释放硬件资源 */
    fun stop()

    /** 当前是否可用（硬件存在且权限具备） */
    fun isAvailable(): Boolean = true
}
