package cn.jarvis.hrbridge.sensors.imu

import cn.jarvis.hrbridge.sensors.UploadMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for ImuWindowAggregator deterministic stat computation.
 *
 * These tests use synthetic samples and do not require Android APIs.
 */
class ImuWindowAggregatorTest {

    @Test
    fun `desk-still samples produce high phoneOnTableScore`() {
        val agg = ImuWindowAggregator()
        agg.applyMode(UploadMode.NORMAL) // 30s window
        val now = System.currentTimeMillis()
        val startMs = now - 30_000L

        // Simulate 30s of phone-on-table: gravity-only, very low std
        for (i in 0 until 300) {
            val t = startMs + (i * 100L)
            agg.addAccel(AccelSample(
                tMs = t,
                x = 0.02f,
                y = -0.01f,
                z = 9.80f,
                magnitude = 9.81f
            ))
            // Very little gyro: stable
            if (i % 2 == 0) {
                agg.addGyro(GyroSample(
                    tMs = t,
                    x = 0.001f,
                    y = 0.002f,
                    z = -0.001f,
                    angularSpeed = 0.003f
                ))
            }
        }

        val feature = agg.pollCompleted(now)
        assertNotNull(feature, "should produce a completed window")

        // Accel stats
        assertEquals(300, feature.accel.n)
        assertTrue(feature.accel.stdMagnitude < 0.2f, "desk-still accel std should be tiny, got ${feature.accel.stdMagnitude}")
        assertTrue(feature.accel.stdMagnitude < 0.5f,
            "accel std ${feature.accel.stdMagnitude} should be <0.5 for still phone")
        assertEquals(0, feature.accel.movementBurstCount,
            "no movement bursts on desk")

        // Derived scores
        assertTrue(feature.derived.phoneOnTableScore >= 0.75f,
            "phone_on_table should be high on desk: ${feature.derived.phoneOnTableScore}")
        assertTrue(feature.derived.phoneInHandScore < 0.3f,
            "phone_in_hand should be low on desk")
        assertTrue(feature.derived.walkingScore < 0.15f,
            "walking should be near zero on desk")
        assertEquals("phone_on_table", feature.derived.dominantPattern,
            "dominant pattern should be phone_on_table")
    }

    @Test
    fun `walking samples produce high walkingScore`() {
        val agg = ImuWindowAggregator()
        agg.applyMode(UploadMode.NORMAL)
        val now = System.currentTimeMillis()
        val startMs = now - 30_000L

        // Simulate walking: rhythmic magnitude oscillation ~11.0-13.0
        for (i in 0 until 300) {
            val t = startMs + (i * 100L)
            // Period ~60 cycles per window (1 step per 0.5s ≈ 15 per window at 10Hz = 30 samples per cycle)
            val phase = (i % 15).toDouble() / 15.0
            val mag = 11.5f + (1.2f * kotlin.math.sin(phase * 2 * Math.PI)).toFloat()
            val gravRatio = (9.8f / mag).coerceIn(0f, 1f)
            agg.addAccel(AccelSample(
                tMs = t,
                x = 0.1f,
                y = 0.1f,
                z = mag * gravRatio,
                magnitude = mag
            ))
            agg.addGyro(GyroSample(
                tMs = t,
                x = 0.15f,
                y = 0.10f,
                z = 0.12f,
                angularSpeed = 0.22f
            ))
        }

        val feature = agg.pollCompleted(now)
        assertNotNull(feature, "should produce a completed window")

        // Accel should show more variance than desk
        assertTrue(feature.accel.stdMagnitude > 0.5f,
            "walking std should be >0.5, got ${feature.accel.stdMagnitude}")

        // Derived scores
        assertTrue(feature.derived.walkingScore >= 0.3f,
            "walkingScore should be at least moderate: ${feature.derived.walkingScore}")
        assertEquals("walking_with_phone", feature.derived.dominantPattern,
            "walking should dominate")
    }

    @Test
    fun `phone-in-hand scrolling produces handling score over table`() {
        val agg = ImuWindowAggregator()
        agg.applyMode(UploadMode.NORMAL)
        val now = System.currentTimeMillis()
        val startMs = now - 30_000L

        for (i in 0 until 300) {
            val t = startMs + (i * 100L)
            // Slight orientation changes + small gyro rotation
            val angle = (i % 40).toDouble() / 40.0 * Math.PI * 0.1
            agg.addAccel(AccelSample(
                tMs = t,
                x = (0.15f * kotlin.math.sin(angle)).toFloat(),
                y = 0.05f,
                z = (9.75f + 0.15f * kotlin.math.cos(angle)).toFloat(),
                magnitude = 9.82f
            ))
            // Regular micro-rotations
            agg.addGyro(GyroSample(
                tMs = t,
                x = 0.08f,
                y = 0.12f,
                z = 0.05f,
                angularSpeed = 0.15f
            ))
        }

        val feature = agg.pollCompleted(now)
        assertNotNull(feature, "should produce a completed window")

        assertEquals("phone_in_hand_scrolling", feature.derived.dominantPattern,
            "scrolling should be detected via gyro handling score")
    }

    @Test
    fun `gyro-only data returns empty accel but still completes`() {
        val agg = ImuWindowAggregator()
        agg.applyMode(UploadMode.NORMAL)
        val now = System.currentTimeMillis()
        val startMs = now - 30_000L

        for (i in 0 until 150) {
            val t = startMs + (i * 200L)
            agg.addGyro(GyroSample(
                tMs = t,
                x = 0.01f,
                y = 0.01f,
                z = 0.01f,
                angularSpeed = 0.02f
            ))
        }

        val feature = agg.pollCompleted(now)
        assertNotNull(feature, "gyro-only window should complete")
        assertEquals(0, feature.accel.n, "accel should be empty")
        assertTrue(feature.quality.missingAccel, "should flag missing accel")
    }

    @Test
    fun `POWER_SAVER mode uses 60s window`() {
        val agg = ImuWindowAggregator()
        agg.applyMode(UploadMode.POWER_SAVER)
        assertEquals(60_000L, agg.windowDurationMs)
    }

    @Test
    fun `REALTIME mode uses 10s window`() {
        val agg = ImuWindowAggregator()
        agg.applyMode(UploadMode.REALTIME)
        assertEquals(10_000L, agg.windowDurationMs)
    }

    @Test
    fun `JSON output contains all required fields`() {
        val agg = ImuWindowAggregator()
        agg.applyMode(UploadMode.NORMAL)
        val now = System.currentTimeMillis()
        val startMs = now - 30_000L

        for (i in 0 until 100) {
            val t = startMs + (i * 300L)
            agg.addAccel(AccelSample(t, 0f, 0f, 9.8f, 9.81f))
            agg.addGyro(GyroSample(t, 0f, 0f, 0f, 0.01f))
        }

        val feature = agg.pollCompleted(now)
        assertNotNull(feature)

        val json = ImuWindowJson.toJson(feature, "test-device-001")
        val required = listOf(
            "schema_version", "imu-window-v1",
            "window_start_ms", "window_end_ms",
            "accelerometer", "gyroscope",
            "derived", "quality",
            "phone_on_table_score", "phone_in_hand_score",
            "walking_score", "lying_still_score",
            "dominant_pattern"
        )
        for (key in required) {
            assertTrue(json.contains(key), "JSON must contain '$key'")
        }
    }

    @Test
    fun `empty aggregator returns null`() {
        val agg = ImuWindowAggregator()
        val result = agg.pollCompleted(System.currentTimeMillis())
        assertEquals(null, result)
    }
}
