package cn.jarvis.hrbridge.sensors.impl

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.sensors.Emit
import cn.jarvis.hrbridge.sensors.SensorCollector
import cn.jarvis.hrbridge.sensors.SensorFreqConfig
import cn.jarvis.hrbridge.sensors.SensorType
import cn.jarvis.hrbridge.sensors.UploadMode
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * GPS / 位置采集（原生 LocationManager，不依赖 play-services）。
 *
 * - 按 UploadMode 配置请求间隔：POWER_SAVER 15min / NORMAL 5min / REALTIME 30s
 * - Geocoder 反向地理编码填充 "location" 字段；失败不阻断
 * - 地理围栏：读取 settings.home/office 坐标，命中时给 activity 打标
 * - emit(SensorType.LOCATION, json) 格式见 §F2.4
 */
class LocationCollector(private val ctx: Context) : SensorCollector {

    override val type: String = SensorType.LOCATION

    private val lm: LocationManager? =
        ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var mode: UploadMode = UploadMode.NORMAL
    private var freqConfig: SensorFreqConfig? = null
    private var emitRef: Emit? = null
    private var scopeRef: CoroutineScope? = null
    private var lastLat: Double = 0.0
    private var lastLng: Double = 0.0
    private var lastEmittedTimeMs: Long = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val now = System.currentTimeMillis()
            val interval = intervalMs()
            val minDist = minDistanceM()

            // 计算距离上次发射的距离
            val dist = if (lastLat != 0.0 && lastLng != 0.0) {
                distance(loc.latitude, loc.longitude, lastLat, lastLng)
            } else {
                Double.MAX_VALUE
            }

            // 严格节流：时间间隔未达到且位移未达到设定阈值时丢弃，避免静止时高频上报位置
            if (lastEmittedTimeMs != 0L && (now - lastEmittedTimeMs < interval - 5000L)) {
                if (minDist <= 0f || dist < minDist) {
                    Logger.d("Location", "Dropped duplicate/frequent update: elapsed=${now - lastEmittedTimeMs}ms dist=${"%.1f".format(dist)}m")
                    return
                }
            }

            lastLat = loc.latitude
            lastLng = loc.longitude
            lastEmittedTimeMs = now
            val scope = scopeRef ?: return
            scope.launch { emitLocation(loc) }
        }
        @Deprecated("旧 API 兼容")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun isAvailable(): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        return lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
               lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    }

    override fun start(scope: CoroutineScope, mode: UploadMode, emit: Emit) {
        this.mode = mode
        this.emitRef = emit
        this.scopeRef = scope
        registerLocationUpdates()
        Logger.i("Location", "started, mode=$mode")
    }

    override fun onModeChanged(mode: UploadMode) {
        if (this.mode == mode) return
        this.mode = mode
        unregisterLocationUpdates()
        registerLocationUpdates()
    }

    override fun applyFrequency(config: SensorFreqConfig) {
        if (config == freqConfig) return
        freqConfig = config
        // Re-register GPS with new interval & min distance
        unregisterLocationUpdates()
        registerLocationUpdates()
        Logger.d("Location", "freq applied: interval=${config.locationIntervalMs}ms minDist=${config.locationMinDistance}m")
    }

    override fun stop() {
        unregisterLocationUpdates()
        emitRef = null
        scopeRef = null
        Logger.i("Location", "stopped")
    }

    // ---- internal ----

    private fun intervalMs(): Long = freqConfig?.locationIntervalMs ?: when (mode) {
        UploadMode.POWER_SAVER -> 15 * 60_000L
        UploadMode.NORMAL      ->  5 * 60_000L
        UploadMode.REALTIME    ->     30_000L
    }

    private fun minDistanceM(): Float = freqConfig?.locationMinDistance ?: 0f

    @Suppress("MissingPermission")
    private fun registerLocationUpdates() {
        val lm = this.lm ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val interval = intervalMs()
        val minDist = minDistanceM()
        // 动态选择定位源：若处于 REALTIME（实时追踪）或正在高速移动，启用高精度硬件 GPS；否则使用网络定位，省电且不闪烁 GPS 状态栏图标
        val provider = if (mode == UploadMode.REALTIME || (freqConfig != null && freqConfig!!.locationIntervalMs < 60_000L)) {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER 
            else LocationManager.NETWORK_PROVIDER
        } else {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER
            else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER
            else LocationManager.PASSIVE_PROVIDER
        }

        try {
            lm.requestLocationUpdates(provider, interval, minDist, listener)
        } catch (e: Exception) {
            Logger.w("Location", "requestLocationUpdates failed: ${e.message}")
        }
    }

    private fun unregisterLocationUpdates() {
        lm?.removeUpdates(listener)
    }

    private suspend fun emitLocation(loc: Location) {
        val emit = emitRef ?: return
        val ts = System.currentTimeMillis() / 1000
        val lat = loc.latitude
        val lng = loc.longitude
        val accuracy = loc.accuracy

        // 反向地理编码（不阻断）
        val locationName = tryReverseGeocode(lat, lng)

        // 地理围栏推断 activity
        val activity = inferActivity(lat, lng)

        val json = buildString {
            append("{")
            append("\"location\":${jsonEscape(locationName)},")
            append("\"latitude\":$lat,")
            append("\"longitude\":$lng,")
            append("\"accuracy\":$accuracy,")
            append("\"activity\":\"$activity\",")
            append("\"ts\":$ts")
            append("}")
        }
        runCatching { emit(SensorType.LOCATION, json) }
            .onFailure { Logger.w("Location", "emit failed: ${it.message}") }
    }

    private fun tryReverseGeocode(lat: Double, lng: Double): String {
        return try {
            val geocoder = Geocoder(ctx, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            addresses?.firstOrNull()?.getAddressLine(0) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun inferActivity(lat: Double, lng: Double): String {
        val s = ServiceLocator.settingsStore.settings.first()
        val homeLat = s.homeLat.toDouble()
        val homeLng = s.homeLng.toDouble()
        val offLat = s.officeLat.toDouble()
        val offLng = s.officeLng.toDouble()

        val fenceRadius = 100.0 // 100m 围栏半径
        if (homeLat != 0.0 && homeLng != 0.0 && distance(lat, lng, homeLat, homeLng) < fenceRadius)
            return "home"
        if (offLat != 0.0 && offLng != 0.0 && distance(lat, lng, offLat, offLng) < fenceRadius)
            return "office"
        return "other"
    }

    /** Haversine 距离（米） */
    private fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun jsonEscape(s: String): String {
        if (s.isEmpty()) return "\"\""
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"', '\\' -> sb.append('\\').append(c)
                '\n' -> sb.append("\\n")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
