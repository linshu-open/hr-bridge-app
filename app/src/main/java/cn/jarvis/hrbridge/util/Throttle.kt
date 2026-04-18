package cn.jarvis.hrbridge.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 指数退避调度器：5s / 15s / 45s / 135s / 405s 共 5 次（需求文档 §7 A6）
 */
class ExponentialBackoff(
    private val baseMs: Long = 5_000L,
    private val maxAttempts: Int = 5,
    private val factor: Double = 3.0
) {
    fun nextDelayMs(attempt: Int): Long? =
        if (attempt >= maxAttempts) null
        else (baseMs * Math.pow(factor, attempt.toDouble())).toLong()
}

/**
 * 简单 debounce：在协程作用域内推迟执行，新事件会取消旧任务
 */
class Debouncer(private val scope: CoroutineScope, private val delayMs: Long) {
    private var job: Job? = null
    fun submit(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            block()
        }
    }
    fun cancel() { job?.cancel() }
}
