package com.sharp.qnn.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 。
 * Log recorder: captures **this process** logcat output in real time and
 * writes it into the Download/sharp_log/ directory.
 *
 * - 实时写入: 每行即写即 flush (应用异常退出也不遗漏尾部日志)
 * - Real-time writes: each line is flushed immediately, so even an abrupt
 *   process exit does not lose the trailing log lines.
 * sharp_log), 无需权限
 * - Storage: MediaStore Downloads collection (RELATIVE_PATH = Download/sharp_log),
 *   no runtime permission needed.
 * - 每次会话一个文件: sharp_log_<yyyyMMdd_HHmmss>.log
 * - One file per session: sharp_log_<yyyyMMdd_HHmmss>.log
 *
 * [stop] 控制 (通常由 [com.sharp.qnn.service.LogRecorderService]
 * 驱动): 开关打开时立即开始, 开关关闭时停止。应用进程存活期间不依赖 Activity 生命周期。
 * Lifecycle is driven by [start] / [stop] (usually through
 * [com.sharp.qnn.service.LogRecorderService]): recording begins as soon as the
 * toggle is enabled and stops when it is disabled. It does not depend on the
 * Activity lifecycle while the process is alive.
 *
 * Collection mechanism:
 * - 不能用 `logcat -T <时间戳>` 流式: 非 root 应用 (per-UID 读者) 的流式连接会被
 *   logd 主动断开 (logcat 打印 "Unexpected EOF!" 退出), 而 dump 模式 (-d) 正常返回。
 * - Streaming with `logcat -T <timestamp>` does not work: for non-root apps
 *   (per-UID readers) logd actively drops the streaming connection (logcat
 *   prints "Unexpected EOF!" and exits), while dump mode (-d) returns normally.
 * - 因此采用轮询快照: 每 [POLL_INTERVAL_MS] 执行一次 `logcat -d -T <上次快照时间戳>`,
 *   只取本 UID 的日志, 按时间戳续读不丢行, 效果近似实时且不会 EOF。
 * - Instead, polled snapshots are used: every [POLL_INTERVAL_MS] a
 *   `logcat -d -T <last snapshot timestamp>` is run, filtered to this UID,
 *   resumed by timestamp so no line is dropped, approximating real-time
 *   delivery without hitting EOF.
 * - 会话开始时刻之前的历史日志由 reader 按时间戳丢弃。
 * - Log lines older than the session start are dropped by the reader via timestamps.
 *
 * Implementation notes:
 * - 会话用单调递增的 [sessionId] 标识, [stop] 只终止"当前最新会话";
 *   旧 reader 线程自然结束时 (finally) 不会误杀之后开启的新会话。
 * - Sessions use a monotonically increasing [sessionId]; [stop] only ends the
 *   current (latest) session. A stale reader thread finishing in (finally)
 *   never kills a newer session started afterwards.
 * - [writer] 为 volatile, 写锁用局部引用, 与 [stop] 的置 null 互不干扰。
 * - [writer] is volatile and writes lock a local reference, so it cannot
 *   interfere with [stop] nulling the field.
 * - 失败路径完整清理已创建的 MediaStore 文件, 不泄漏句柄。
 * - Failure paths fully clean up created MediaStore entries, no handles leak.
 */
object LogRecorder {

    private const val TAG = "SharpLogRec"

    /** Poll interval in milliseconds */
    private const val POLL_INTERVAL_MS = 500L

    private val running = AtomicBoolean(false)
    private val sessionId = AtomicLong(0)
    private var reader: Thread? = null

    /** Current session writer (visible across threads; nulled only at session end) */
    @Volatile
    private var writer: BufferedWriter? = null

    /** logcat child process of the current session (managed in the reader thread) */
    @Volatile
    private var process: Process? = null

    /** Session start time (ms); the reader drops older lines accordingly */
    @Volatile
    private var sessionStartMillis = 0L

    /** Whether recording is active */
    fun isRunning(): Boolean = running.get()

    /** Start recording: create the log file and launch the logcat reader thread (idempotent) */
    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val id = sessionId.incrementAndGet()
        val ownPid = android.os.Process.myPid().toString()
        val resolver = context.applicationContext.contentResolver

        var uri: Uri? = null
        var out: OutputStream? = null
        try {
            val fileName = "sharp_log_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".log"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/sharp_log")
            }
            uri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            )
            if (uri == null) {
                abandonSession()
                return
            }
            out = resolver.openOutputStream(uri, "w")
            if (out == null) {
                resolver.delete(uri, null, null)
                abandonSession()
                return
            }
            writer = BufferedWriter(OutputStreamWriter(out, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            runCatching { out?.close() }
            if (uri != null) runCatching { resolver.delete(uri, null, null) }
            abandonSession()
            return
        }

        // Session start time (threadtime stamp format), used to filter historical lines
        sessionStartMillis = stampMillis(nowStamp()) ?: System.currentTimeMillis()

        // Header line
        writeLine("===== SharpQNN log session started " +
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                " PID=$ownPid =====")

        reader = launchReader(id)
    }

    /** Stop the latest session: kill the logcat process and close the file (idempotent) */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        finishSession()
    }

    /** Called when a reader thread exits naturally; ends only its own session */
    private fun stopSession(id: Long) {
        if (sessionId.get() != id) return
        if (!running.compareAndSet(true, false)) return
        finishSession()
    }

    private fun finishSession() {
        writeLine("===== SharpQNN log session ended " +
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + " =====")
        runCatching { process?.destroy() }
        // Close under the same lock as writeLine so a closed stream is never written
        val w = writer
        if (w != null) synchronized(w) { runCatching { w.close() } }
        writer = null
        process = null
        reader = null
    }

    /** start 失败但不涉及已创建文件时仅复位状态 */
    /** Reset state only, for start failures that left no file behind */
    private fun abandonSession() {
        process = null
        running.set(false)
    }

    // ======  ======
    // ====== Internals: collection thread ======

    /**
     * 启动 reader 线程, 轮询式采集:
     * 周期执行 `logcat -d -T <上次快照时间>` 抓取本 UID 的新日志。
     * Launch the reader thread, which polls:
     * every cycle it runs `logcat -d -T <last snapshot time>` to fetch new
     * log lines belonging to this UID.
     *
     * 为什么不用流式: App 进程内 spawn 的 logcat 长连接会被 logd 主动断开
     * (Unexpected EOF), 而 dump 模式 (-d) 每次正常返回数据后退出, 无此问题。
     * 轮询间隔 500ms, 快照间不丢行 (按时间戳续读), 效果近似实时。
     * Why not streaming: a long-lived logcat connection spawned inside the app
     * process is actively dropped by logd (Unexpected EOF), whereas dump mode
     * (-d) returns data and exits normally every time. The 500ms polling window
     * resumes by timestamp so no lines are lost, approximating real time.
     */
    private fun launchReader(id: Long): Thread {
        val t = Thread {
            val myId = id
            var last = sessionStartMillis
            var failures = 0
            try {
                while (running.get() && sessionId.get() == myId) {
                    try {
                        // last+1ms: skip the final line of the previous snapshot to
                        // avoid replaying the same timestamp during idle periods
                        val since = stampString(last + 1)
                        val p = ProcessBuilder(
                            "logcat", "-d", "-v", "threadtime", "-T", since
                        ).redirectErrorStream(true).start()
                        this@LogRecorder.process = p
                        p.inputStream.bufferedReader(StandardCharsets.UTF_8).use { br ->
                            while (true) {
                                val line = br.readLine() ?: break
                                if (!running.get() || sessionId.get() != myId) return@use
                                val t = stampMillis(line) ?: continue
                                if (t < last) continue
                                last = t
                                writeLine(line)
                            }
                        }
                        runCatching { p.destroy() }
                        if (this@LogRecorder.process === p) this@LogRecorder.process = null
                        failures = 0
                    } catch (_: Exception) {
                        failures++
                    }
                    val delay = POLL_INTERVAL_MS shl minOf(failures, 4)
                    try { Thread.sleep(delay) } catch (_: InterruptedException) {}
                }
            } finally {
                stopSession(myId)
            }
        }.apply {
            name = "sharp-logcat-reader"
            isDaemon = true
        }
        t.start()
        return t
    }

    // ======  ======
    // ====== Internals: timestamps ======

    private fun nowStamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /** Milliseconds → "MM-dd HH:mm:ss.SSS" (logcat -T argument format) */
    private fun stampString(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))

    /**
     * 解析 threadtime 行首时间戳 "MM-dd HH:mm:ss.SSS" (18 字符) 为毫秒。
     * logcat 自身输出) 返回 null。
     * Parse a "MM-dd HH:mm:ss.SSS" (18 chars) threadtime line prefix into
     * milliseconds. Lines not matching the format (separators, logcat's own
     * output) return null.
     *
     * "MM-dd" 无年份: 以当前年份解析, 再按 12 小时未来阈值判断跨年
     * (如 1 月 1 日凌晨的 "12-31" 行属于上一年)。
     * "MM-dd" carries no year: parse with the current year, then apply a 12-hour
     * future threshold to detect year boundaries (e.g. a "12-31" line seen on
     * Jan 1st belongs to the previous year).
     */
    private fun stampMillis(lineOrStamp: String): Long? {
        if (lineOrStamp.length < 18) return null
        val stamp = lineOrStamp.substring(0, 18)
        if (stamp[2] != '-' || stamp[8] != ':' || stamp[14] != '.') return null
        val now = System.currentTimeMillis()
        val withYear = SimpleDateFormat("yyyy", Locale.US).format(Date(now)) + "-" + stamp
        val d = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse(withYear)
        } catch (_: Exception) {
            return null
        }
        var ms = d.time
        if (ms > now + 12L * 3600_000) ms -= 366L * 24 * 3600_000
        return ms
    }

        // ====== Internals ======

    /** Write one line and flush synchronously (local lock reference, mutually
     * exclusive with close; failures are silent and only drop that line) */
    private fun writeLine(line: String) {
        val w = writer ?: return
        try {
            synchronized(w) {
                w.write(line)
                w.newLine()
                w.flush()
            }
        } catch (_: Exception) {
        }
    }
}
