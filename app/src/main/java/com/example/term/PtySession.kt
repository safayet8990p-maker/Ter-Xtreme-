package com.example.term

import com.example.terminal.DiagnosticLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PtySession(
  val tabId: String,
  cmd: String,
  cwd: String,
  argv: Array<String>,
  envp: Array<String>,
  cols: Int,
  rows: Int,
  private val onOutput: (tabId: String, bytes: ByteArray) -> Unit,
  private val onExit: (tabId: String, exitCode: Int, runtimeMs: Long) -> Unit,
) {
  val pid: Int
  private val masterFd: Int
  private val closed = AtomicBoolean(false)
  private val fdClosed = AtomicBoolean(false)
  private val writerExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "pty-write-$tabId").apply { isDaemon = true }
  }
  private val startTimeMs = System.currentTimeMillis()

  init {
    val pair = PtyNative.createSubprocess(cmd, cwd, argv, envp, cols, rows)
    require(pair.size == 2 && pair[0] > 0 && pair[1] >= 0) {
      "PtyNative.createSubprocess returned invalid handles"
    }
    pid = pair[0]
    masterFd = pair[1]
    DiagnosticLog.log("PtySession", "Started $tabId pid=$pid fd=$masterFd (${cols}x$rows)")
    startReaderThread()
  }

  private fun startReaderThread() {
    Thread(
      {
        val readBuf = ByteArray(8192)
        val batch = ByteArrayOutputStream(16384)
        var lastFlushMs = System.currentTimeMillis()

        try {
          while (!closed.get()) {
            val n = PtyNative.readPty(masterFd, readBuf, 0, readBuf.size)
            val now = System.currentTimeMillis()
            if (n > 0) {
              batch.write(readBuf, 0, n)
              if (batch.size() >= 8192 || now - lastFlushMs >= 16L) {
                flushBatch(batch)
                lastFlushMs = now
              }
            } else if (n == 0) {
              if (batch.size() > 0 && now - lastFlushMs >= 16L) {
                flushBatch(batch)
                lastFlushMs = now
              }
            } else {
              // EOF or EIO when slave PTY closes
              break
            }
          }
        } finally {
          if (batch.size() > 0) {
            flushBatch(batch)
          }
          val exitCode = PtyNative.waitForProcess(pid, noHang = false)
          val duration = System.currentTimeMillis() - startTimeMs
          cleanupFd()
          DiagnosticLog.log("PtySession", "Exited $tabId pid=$pid code=$exitCode after ${duration}ms")
          onExit(tabId, exitCode, duration)
        }
      },
      "pty-read-$tabId",
    ).apply {
      isDaemon = true
      start()
    }
  }

  private fun flushBatch(batch: ByteArrayOutputStream) {
    val bytes = batch.toByteArray()
    batch.reset()
    if (bytes.isNotEmpty()) {
      onOutput(tabId, bytes)
    }
  }

  fun write(bytes: ByteArray) {
    if (closed.get() || bytes.isEmpty()) return
    writerExecutor.execute {
      if (!closed.get()) {
        PtyNative.writePty(masterFd, bytes, 0, bytes.size)
      }
    }
  }

  fun resize(cols: Int, rows: Int) {
    if (closed.get() || cols <= 0 || rows <= 0) return
    PtyNative.setPtyWindowSize(masterFd, cols, rows)
  }

  fun close() {
    if (closed.compareAndSet(false, true)) {
      PtyNative.killProcess(pid, 15) // SIGTERM
      cleanupFd()
      writerExecutor.shutdownNow()
    }
  }

  private fun cleanupFd() {
    if (fdClosed.compareAndSet(false, true)) {
      PtyNative.closeFd(masterFd)
    }
  }
}
