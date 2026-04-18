package cn.jarvis.hrbridge.ble

/**
 * 解析 Heart Rate Measurement (0x2A37) 特征值字节流。
 *
 * 格式（Bluetooth SIG）：
 *   byte 0 = flags
 *     bit 0: 心率值格式  0=UINT8, 1=UINT16（LE）
 *     bit 1-2: 传感器接触状态
 *     bit 3: Energy Expended Present
 *     bit 4: RR-Interval Present
 *   byte 1..: 心率值（1 或 2 字节）
 *   剩余字节：Energy Expended (2B) / RR Intervals (每 2B)
 */
object HrParser {

    data class Parsed(
        val hr: Int,
        val rrIntervalsMs: List<Int> = emptyList(),
        val sensorContactSupported: Boolean = false,
        val sensorContactDetected: Boolean = false
    )

    fun parse(bytes: ByteArray?): Parsed? {
        if (bytes == null || bytes.isEmpty()) return null
        val flags = bytes[0].toInt() and 0xFF
        val is16 = (flags and 0x01) != 0
        val contactSupported = (flags and 0x04) != 0
        val contactDetected  = (flags and 0x02) != 0
        val rrPresent        = (flags and 0x10) != 0
        val energyPresent    = (flags and 0x08) != 0

        var idx = 1
        val hr = if (is16) {
            if (bytes.size < idx + 2) return null
            val lo = bytes[idx].toInt() and 0xFF
            val hi = bytes[idx + 1].toInt() and 0xFF
            idx += 2
            (hi shl 8) or lo
        } else {
            if (bytes.size < idx + 1) return null
            val v = bytes[idx].toInt() and 0xFF
            idx += 1
            v
        }

        if (energyPresent) idx += 2    // 跳过 Energy Expended

        val rr = mutableListOf<Int>()
        if (rrPresent) {
            while (idx + 2 <= bytes.size) {
                val lo = bytes[idx].toInt() and 0xFF
                val hi = bytes[idx + 1].toInt() and 0xFF
                idx += 2
                val raw = (hi shl 8) or lo       // 单位 1/1024 秒
                rr += (raw * 1000 / 1024)
            }
        }

        return Parsed(hr, rr, contactSupported, contactDetected)
    }
}
