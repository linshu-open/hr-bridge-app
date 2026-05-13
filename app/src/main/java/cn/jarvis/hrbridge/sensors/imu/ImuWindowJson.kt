package cn.jarvis.hrbridge.sensors.imu

/**
 * 将 [ImuWindowFeature] 序列化为稳定 JSON（不引入大型序列化库）。
 *
 * 输出格式与后端 upload contract `imu-window-v1` 一致：
 *   schema_version / device_id / window_id / window_start_ms / window_end_ms
 *   duration_sec / mode / sample_rate_hz / accelerometer / gyroscope
 *   derived / quality / ts
 */
object ImuWindowJson {

    fun toJson(feature: ImuWindowFeature, deviceId: String): String {
        val ts = System.currentTimeMillis() / 1000L
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"schema_version\":\"imu-window-v1\",")
        sb.append("\"device_id\":\"${escape(deviceId)}\",")
        sb.append("\"window_id\":\"${escape(deviceId)}:${feature.windowStartMs}:${feature.durationSec}\",")
        sb.append("\"window_start_ms\":${feature.windowStartMs},")
        sb.append("\"window_end_ms\":${feature.windowEndMs},")
        sb.append("\"duration_sec\":${feature.durationSec},")
        sb.append("\"mode\":\"${escape(feature.mode)}\",")
        sb.append("\"sample_rate_hz\":{")
        sb.append("\"accelerometer\":${feature.accelHz},")
        sb.append("\"gyroscope\":${feature.gyroHz}")
        sb.append("},")

        // accelerometer stats
        val a = feature.accel
        sb.append("\"accelerometer\":{")
        sb.append("\"n\":${a.n},")
        sb.append("\"mean_magnitude\":${fmt(a.meanMagnitude)},")
        sb.append("\"std_magnitude\":${fmt(a.stdMagnitude)},")
        sb.append("\"min_magnitude\":${fmt(a.minMagnitude)},")
        sb.append("\"max_magnitude\":${fmt(a.maxMagnitude)},")
        sb.append("\"jerk_mean\":${fmt(a.jerkMean)},")
        sb.append("\"jerk_max\":${fmt(a.jerkMax)},")
        sb.append("\"periodicity_score\":${fmt(a.periodicityScore)},")
        sb.append("\"movement_burst_count\":${a.movementBurstCount},")
        sb.append("\"orientation_stability\":${fmt(a.orientationStability)}")
        sb.append("},")

        // gyroscope stats
        val g = feature.gyro
        sb.append("\"gyroscope\":{")
        sb.append("\"n\":${g.n},")
        sb.append("\"mean_angular_speed\":${fmt(g.meanAngularSpeed)},")
        sb.append("\"std_angular_speed\":${fmt(g.stdAngularSpeed)},")
        sb.append("\"max_angular_speed\":${fmt(g.maxAngularSpeed)},")
        sb.append("\"micro_rotation_count\":${g.microRotationCount},")
        sb.append("\"phone_handling_score\":${fmt(g.phoneHandlingScore)},")
        sb.append("\"stable_on_table_score\":${fmt(g.stableOnTableScore)}")
        sb.append("},")

        // derived
        val d = feature.derived
        sb.append("\"derived\":{")
        sb.append("\"phone_on_table_score\":${fmt(d.phoneOnTableScore)},")
        sb.append("\"phone_in_hand_score\":${fmt(d.phoneInHandScore)},")
        sb.append("\"walking_score\":${fmt(d.walkingScore)},")
        sb.append("\"vehicle_vibration_score\":${fmt(d.vehicleVibrationScore)},")
        sb.append("\"lying_still_score\":${fmt(d.lyingStillScore)},")
        sb.append("\"dominant_pattern\":\"${escape(d.dominantPattern)}\"")
        sb.append("},")

        // quality
        val q = feature.quality
        sb.append("\"quality\":{")
        sb.append("\"missing_accel\":${q.missingAccel},")
        sb.append("\"missing_gyro\":${q.missingGyro},")
        sb.append("\"sample_drop_ratio\":${fmt(q.sampleDropRatio)},")
        sb.append("\"battery_saver\":${q.batterySaver}")
        sb.append("},")

        sb.append("\"ts\":$ts")
        sb.append("}")
        return sb.toString()
    }

    private fun escape(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun fmt(f: Float): String {
        return "%.3f".format(f)
    }
}
