package com.ronan.qmusicwatch.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

private val logUrlPattern = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
private val logSecretPattern = Regex("(authorization|cookie|set-cookie|qm_keyst|qqmusic_key|musickey|p_lskey|p_skey|skey|qrcode_id|qrsig|ptqrtoken|access[_-]?token|refresh[_-]?(?:token|key))\\s*[:=]\\s*[^;\\s,]+", RegexOption.IGNORE_CASE)
private val diagnosticSensitiveFieldPattern = Regex(
    "[\\\"']?(query|keyword|search(?:[_-]?query)?|q|track(?:[_-]?id)?|song[_-]?id|account(?:[_-]?id)?|uin|qq|user[_-]?(?:id|name)|nickname|media[_-]?id)[\\\"']?\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^;,\\r\\n]+)",
    RegexOption.IGNORE_CASE,
)

object AppLog {
    @Volatile private var file: File? = null
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "qmusic-applog") }
    private val draining = java.util.concurrent.atomic.AtomicBoolean(false)
    // Only touched on the worker thread, so a single shared formatter is safe.
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private const val CLEAR_MARKER = "\u0000"

    fun init(context: Context) {
        val target = File(context.filesDir, "logs/qmusic-watch.log")
        target.parentFile?.mkdirs()
        file = target
        write("APP", "start version=${com.ronan.qmusicwatch.BuildConfig.VERSION_NAME} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}")
    }

    /**
     * Enqueues the entry and returns immediately; the single worker thread owns
     * all disk IO, so UI/frame/player callbacks never block on file writes.
     */
    fun write(tag: String, message: String) {
        if (file == null) return
        queue.offer(tag to message)
        scheduleDrain()
    }

    fun clear() {
        if (file == null) return
        queue.offer(CLEAR_MARKER to "")
        scheduleDrain()
        flush()
    }

    /** Waits until all log entries queued before this call reach disk. */
    fun flush(timeoutMs: Long = 3_000L): Boolean {
        if (file == null || Thread.currentThread().name == "qmusic-applog") return true
        return runCatching {
            executor.submit(Callable { Unit }).get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)
    }

    fun diagnosticExcerpt(maxBytes: Int = 48 * 1024): String {
        val target = file ?: return ""
        // Run on the worker so the read never races an in-flight append.
        if (Thread.currentThread().name == "qmusic-applog") return readExcerpt(target, maxBytes)
        return runCatching {
            executor.submit(Callable { readExcerpt(target, maxBytes) }).get(3, TimeUnit.SECONDS)
        }.getOrDefault("")
    }

    fun shareIntent(context: Context): Intent {
        val target = file ?: return Intent(Intent.ACTION_SEND).setType("text/plain")
        flush()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        return Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun copyTo(context: Context, uri: Uri) {
        val target = file ?: return
        flush()
        context.contentResolver.openOutputStream(uri)?.use { output -> target.inputStream().use { it.copyTo(output) } }
    }

    private fun scheduleDrain() {
        if (draining.compareAndSet(false, true)) executor.execute(::drain)
    }

    private fun drain() {
        try {
            while (true) {
                val item = queue.poll() ?: break
                val target = file ?: return
                if (item.first == CLEAR_MARKER) {
                    target.writeText("")
                    continue
                }
                if (target.length() > 256 * 1024) target.writeText("")
                val time = timeFormat.format(Date())
                target.appendText("$time ${item.first} ${redactLogMessage(item.second).take(1200)}\n")
            }
        } finally {
            draining.set(false)
            if (queue.isNotEmpty() && draining.compareAndSet(false, true)) executor.execute(::drain)
        }
    }

    private fun readExcerpt(target: File, maxBytes: Int): String {
        if (!target.isFile || maxBytes <= 0) return ""
        val lines = target.readLines().asReversed()
        val selected = ArrayDeque<String>()
        var bytes = 0
        for (line in lines) {
            val clean = redactDiagnosticMessage(line)
            val size = clean.encodeToByteArray().size + 1
            if (bytes + size > maxBytes) break
            selected.addFirst(clean)
            bytes += size
        }
        return selected.joinToString("\n")
    }
}

internal fun redactLogMessage(message: String): String {
    var value = message.replace('\n', ' ').replace('\r', ' ')
    value = logUrlPattern.replace(value, "<url>")
    value = logSecretPattern.replace(value) { match -> "${match.groupValues[1]}=<redacted>" }
    return value
}

internal fun redactDiagnosticMessage(message: String): String =
    diagnosticSensitiveFieldPattern.replace(redactLogMessage(message)) { match -> "${match.groupValues[1]}=<redacted>" }
