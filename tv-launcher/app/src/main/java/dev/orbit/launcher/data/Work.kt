package dev.orbit.launcher.data

import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Two background threads and the main handler: all the concurrency this app needs. */
object Work {
    val main = Handler(Looper.getMainLooper())

    private val count = AtomicInteger()
    private val factory = ThreadFactory { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
            r.run()
        }, "orbit-io-${count.incrementAndGet()}")
    }

    /** Disk and PackageManager work. Two threads: one A53 core stays free for UI and RenderThread. */
    val io = Executors.newFixedThreadPool(2, factory)

    fun ui(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post(block)
    }
}
