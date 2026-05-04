package cn.jarvis.hrbridge.sensors

/**
 * 上传节流模式。详见需求文档 §F5 智能上传策略表。
 *
 * Collector 内部根据当前模式调整采样/汇总频率。
 */
enum class UploadMode(val wire: String) {
    /** 省电：加速度/陀螺仪 5 分钟汇总，GPS 15 分钟 */
    POWER_SAVER("power_saver"),

    /** 常规：默认 */
    NORMAL("normal"),

    /** 实时：HR 异常时自动切入 5 分钟，然后回落 */
    REALTIME("realtime");

    companion object {
        fun of(wire: String?): UploadMode = values().firstOrNull { it.wire == wire } ?: NORMAL
    }
}
