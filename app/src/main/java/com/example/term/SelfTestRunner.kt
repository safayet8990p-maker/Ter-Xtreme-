package com.example.term

import android.content.Context
import com.example.data.SettingsStore
import com.example.env.Launcher
import com.example.env.NetSetup
import com.example.env.ProotCheckResult
import com.example.terminal.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SelfTestStatus {
  PENDING,
  RUNNING,
  PASS,
  FAIL,
}

data class SelfTestItem(
  val id: String,
  val command: String,
  val description: String,
  val status: SelfTestStatus = SelfTestStatus.PENDING,
  val exitCode: Int? = null,
  val output: String = "",
)

object SelfTestRunner {
  fun initialItems(): List<SelfTestItem> =
    listOf(
      SelfTestItem("arch", "uname -m", "Verify aarch64 architecture inside proot"),
      SelfTestItem("ping", "ping -c1 1.1.1.1", "Verify raw ICMP / socket connectivity"),
      SelfTestItem("dns", "nslookup example.com", "Verify DNS resolution via /etc/resolv.conf"),
      SelfTestItem("apk_update", "apk update", "Verify Alpine HTTPS mirror index fetch"),
      SelfTestItem("apk_add", "apk add --no-cache curl", "Verify installing curl package"),
      SelfTestItem("curl", "curl -I https://example.com", "Verify HTTPS TLS + ca-certificates"),
      SelfTestItem("wget", "wget -O /tmp/t https://example.com && ls -l /tmp/t", "Verify wget file download"),
      SelfTestItem(
        "bad_cmd",
        "terxtreme_nonexistent_cmd_404",
        "Verify real /bin/sh reports 'not found' (no fake shell)",
      ),
    )

  suspend fun runAll(
    context: Context,
    settingsStore: SettingsStore,
    onUpdate: (List<SelfTestItem>) -> Unit,
  ) =
    withContext(Dispatchers.IO) {
      val items = initialItems().toMutableList()
      onUpdate(items.toList())

      val binaries = Launcher.checkBinaries(context)
      if (binaries !is ProotCheckResult.Ready) {
        val missing = binaries as ProotCheckResult.MissingBinaries
        for (i in items.indices) {
          items[i] =
            items[i].copy(
              status = SelfTestStatus.FAIL,
              exitCode = -1,
              output = "Missing binaries in ${missing.nativeDir}: ${missing.missingFiles.joinToString()}",
            )
        }
        onUpdate(items.toList())
        return@withContext
      }

      NetSetup.ensureGuestConfigs(context)
      val noSeccomp = settingsStore.getCurrent().prootNoSeccomp

      for (i in items.indices) {
        val current = items[i]
        items[i] = current.copy(status = SelfTestStatus.RUNNING, output = "")
        onUpdate(items.toList())

        val result =
          NetSetup.runProotCommand(
            context = context,
            binaries = binaries,
            noSeccomp = noSeccomp,
            command = current.command,
            timeoutSec = 90,
          )

        val cleanOut = result.output.replace(Regex("\u001b\\[[0-9;?]*[a-zA-Z]"), "").trim()
        val passed =
          when (current.id) {
            "arch" -> result.exitCode == 0 && cleanOut.contains("aarch64")
            "bad_cmd" ->
              result.exitCode != 0 &&
                cleanOut.contains("not found", ignoreCase = true)
            else -> result.exitCode == 0
          }

        DiagnosticLog.log(
          "SelfTest",
          "[${if (passed) "PASS" else "FAIL"}] ${current.command} (exit=${result.exitCode})",
        )

        items[i] =
          current.copy(
            status = if (passed) SelfTestStatus.PASS else SelfTestStatus.FAIL,
            exitCode = result.exitCode,
            output = cleanOut.ifBlank { "(no output)" },
          )
        onUpdate(items.toList())
      }
    }
}
