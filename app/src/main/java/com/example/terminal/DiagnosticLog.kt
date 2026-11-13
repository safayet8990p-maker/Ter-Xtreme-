package com.example.terminal

import android.os.Build
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object DiagnosticLog {
  private const val MAX_LINES = 250
  private val lock = Any()
  private val lines = ArrayDeque<String>(MAX_LINES)
  private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

  fun log(tag: String, message: String, throwable: Throwable? = null) {
    val timestamp = formatter.format(Date())
    val line =
      if (throwable != null) {
        "$timestamp [$tag] $message: ${throwable.javaClass.simpleName}: ${throwable.message}"
      } else {
        "$timestamp [$tag] $message"
      }
    synchronized(lock) {
      if (lines.size >= MAX_LINES) {
        lines.removeFirst()
      }
      lines.addLast(line)
    }
  }

  fun dumpReport(appVersion: String, summary: String): String {
    val snapshot =
      synchronized(lock) { lines.joinToString(separator = "\n") }
    return buildString {
      appendLine("Ter Xtreme Diagnostic Report")
      appendLine("App Version: $appVersion")
      appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
      appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
      appendLine("Summary: $summary")
      appendLine("--- Logs ---")
      append(snapshot.ifBlank { "(no log entries)" })
    }
  }
}
