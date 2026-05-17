package cn.jarvis.hrbridge.sensors

/**
 * Highly efficient thread-safe ring buffer for sensor samples to avoid high CPU overhead.
 * Pre-allocated to avoid GC memory pressure.
 */
class SensorRingBuffer(val capacity: Int, val fieldsPerSample: Int) {
    private val lock = Any()
    private val data = FloatArray(capacity * fieldsPerSample)
    private var writeIndex = 0
    private var count = 0

    fun put(vararg values: Float) {
        synchronized(lock) {
            val idx = writeIndex % capacity
            val base = idx * fieldsPerSample
            val n = Math.min(fieldsPerSample, values.size)
            for (i in 0 until n) {
                data[base + i] = values[i]
            }
            writeIndex++
            if (count < capacity) {
                count++
            }
        }
    }

    /**
     * Drains/snapshots all currently collected elements and clears the buffer.
     * Returns a flat array of elements and the number of samples copied.
     */
    fun snapshotAndClear(): Pair<FloatArray, Int> {
        synchronized(lock) {
            if (count == 0) {
                return Pair(FloatArray(0), 0)
            }
            val n = count
            val result = FloatArray(n * fieldsPerSample)
            
            // Read from oldest to newest.
            // If count < capacity, oldest is at index 0.
            // If count == capacity, oldest is at writeIndex % capacity.
            val oldestIdx = if (writeIndex < capacity) 0 else writeIndex % capacity
            
            for (i in 0 until n) {
                val readIdx = (oldestIdx + i) % capacity
                val srcBase = readIdx * fieldsPerSample
                val dstBase = i * fieldsPerSample
                System.arraycopy(data, srcBase, result, dstBase, fieldsPerSample)
            }
            
            writeIndex = 0
            count = 0
            return Pair(result, n)
        }
    }

    fun size(): Int = synchronized(lock) { count }
}
