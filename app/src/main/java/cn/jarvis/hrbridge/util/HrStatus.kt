package cn.jarvis.hrbridge.util

/**
 * 心率状态模型（v2.0 统一阈值，对齐需求文档 §2.1 v2.0 统一阈值模型）
 *
 *     critical-low  : hr < 50   → "critical"
 *     low           : 50..<60   → "low"
 *     normal        : 60..100   → "normal"
 *     elevated      : 100..<=120→ "elevated"
 *     high          : 121..<140 → "high"
 *     critical-high : >=140     → "critical"
 *
 * [status] 字段直接上送服务端，保持与 v1 兼容（枚举值同 "low/normal/elevated/high/critical/test"）。
 */
enum class HrStatus(val wire: String) {
    CRITICAL_LOW("critical"),
    LOW("low"),
    NORMAL("normal"),
    ELEVATED("elevated"),
    HIGH("high"),
    CRITICAL_HIGH("critical"),
    TEST("test");

    /** 是否为紧急状态（需要绕过批量上传，走单条） */
    val isCritical: Boolean get() = this == CRITICAL_LOW || this == CRITICAL_HIGH
}

/** 阈值配置，来自 DataStore；默认值见需求文档 */
data class HrThresholds(
    val criticalLow: Int = 50,
    val low: Int = 60,
    val normalMax: Int = 100,
    val elevated: Int = 120,
    val criticalHigh: Int = 140
) {
    fun classify(hr: Int): HrStatus = when {
        hr < criticalLow   -> HrStatus.CRITICAL_LOW
        hr < low           -> HrStatus.LOW
        hr <= normalMax    -> HrStatus.NORMAL
        hr <= elevated     -> HrStatus.ELEVATED
        hr < criticalHigh  -> HrStatus.HIGH
        else               -> HrStatus.CRITICAL_HIGH
    }

    companion object { val DEFAULT = HrThresholds() }
}

/** 心率趋势（基于最近 N 个样本） */
enum class HrTrend(val wire: String) {
    STABLE("stable"),
    RISING("rising"),
    FALLING("falling");

    companion object {
        /** 近 N 个心率样本的线性趋势判断；阈值 3 bpm */
        fun infer(samples: List<Int>): HrTrend {
            if (samples.size < 3) return STABLE
            val first = samples.take(samples.size / 2).average()
            val last = samples.drop(samples.size / 2).average()
            val delta = last - first
            return when {
                delta > 3.0  -> RISING
                delta < -3.0 -> FALLING
                else         -> STABLE
            }
        }
    }
}
