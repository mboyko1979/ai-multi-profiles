package dev.boyko.aiagentswitcher

import com.intellij.execution.process.OSProcessUtil
import java.lang.ProcessHandle
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Only processes obtained from our own launch; never enumerate/kill by executable or account home. */
internal object ProfileProcessCleanup {
    fun stop(process: Process, graceSeconds: Long = 0) {
        var interrupted = Thread.interrupted()
        val descendants = linkedMapOf<ProcessHandle, Instant>()
        fun remember() {
            runCatching {
                process.toHandle().descendants().use { children -> children.forEach { child ->
                    child.info().startInstant().ifPresent { descendants.putIfAbsent(child, it) }
                } }
            }
        }
        try {
            remember()
            runCatching { process.outputStream.close() } // Let Router close provider sessions/trees first.
            if (graceSeconds > 0) try { process.waitFor(graceSeconds, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { interrupted = true }
            if (process.isAlive) {
                remember()
                runCatching { OSProcessUtil.killProcessTree(process) }
                if (process.isAlive) process.destroyForcibly()
            }
            // Handles captured before graceful EOF also cover descendants whose parent has exited.
            descendants.entries.toList().asReversed().forEach { (child, started) ->
                runCatching {
                    if (child.isAlive && child.info().startInstant().orElse(null) == started) child.destroyForcibly()
                }
            }
            try { process.waitFor(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { interrupted = true }
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
