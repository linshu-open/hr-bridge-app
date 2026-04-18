package cn.jarvis.hrbridge.util

import android.util.Log
import cn.jarvis.hrbridge.BuildConfig

/**
 * 轻量 Logger。BuildConfig.DEBUG 时输出到 Logcat；release 仅保留 warn/error。
 * Phase 2 将扩展为同时写 Room 的滚动窗口日志（替代 v1 塞 prefs 的反模式）。
 */
object Logger {
    private const val ROOT_TAG = "HRBridge"

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d("$ROOT_TAG/$tag", msg)
    }

    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i("$ROOT_TAG/$tag", msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w("$ROOT_TAG/$tag", msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e("$ROOT_TAG/$tag", msg, tr)
    }
}
