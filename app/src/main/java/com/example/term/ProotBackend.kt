package com.example.term

import android.content.Context
import com.example.data.SettingsStore
import com.example.env.Launcher
import com.example.env.NetSetup
import com.example.env.ProotCheckResult
import com.example.service.TermService
import com.example.terminal.DiagnosticLog
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProotBackend(
  private val context: Context,
  private val settingsStore: SettingsStore,
  private val onFatalProotError: (title: String, message: String, detail: String) -> Unit,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val sessions = ConcurrentHashMap<String, PtySession>()
  private val fallbackRetriedTabs = ConcurrentHashMap.newKeySet<String>()

  @Volatile private var sendOutput: ((tabId: String, bytes: ByteArray) -> Unit)? = null
  @Volatile private var sendStatus: ((tabId: String, status: String, message: String) -> Unit)? = null

  init {
    TermService.onStopRequested = {
      closeAllSessions()
    }
  }

  fun attachCallbacks(
    outputSender: (tabId: String, bytes: ByteArray) -> Unit,
    statusSender: (tabId: String, status: String, message: String) -> Unit,
  ) {
    sendOutput = outputSender
    sendStatus = statusSender
  }

  fun onTabCreated(tabId: String, cols: Int, rows: Int) {
    scope.launch {
      startSession(tabId, cols, rows, forceNoSeccomp = null)
    }
  }

  private suspend fun startSession(
    tabId: String,
    cols: Int,
    rows: Int,
    forceNoSeccomp: Boolean?,
  ) {
    val binaries = Launcher.checkBinaries(context)
    if (binaries !is ProotCheckResult.Ready) {
      val missing = binaries as ProotCheckResult.MissingBinaries
      onFatalProotError(
        "Missing native proot binaries",
        "libproot.so and libproot-loader.so must be built for arm64-v8a and packaged in jniLibs.",
        "Searched: ${missing.nativeDir}\nMissing: ${missing.missingFiles.joinToString()}",
      )
      return
    }

    NetSetup.ensureGuestConfigs(context)
    val prefs = settingsStore.getCurrent()
    val useNoSeccomp = forceNoSeccomp ?: prefs.prootNoSeccomp
    val spec =
      Launcher.buildInteractiveShellSpec(
        context = context,
        binaries = binaries,
        noSeccomp = useNoSeccomp,
        username = prefs.username,
        hostname = prefs.hostname,
      )

    sessions.remove(tabId)?.close()

    try {
      val session =
        PtySession(
          tabId = tabId,
          cmd = spec.cmd,
          cwd = spec.cwd,
          argv = spec.argv,
          envp = spec.envp,
          cols = cols,
          rows = rows,
          onOutput = { id, bytes -> sendOutput?.invoke(id, bytes) },
          onExit = { id, exitCode, runtimeMs ->
            handleSessionExit(id, cols, rows, exitCode, runtimeMs, useNoSeccomp)
          },
        )
      sessions[tabId] = session
      TermService.sync(context, sessions.size)
      val modeLabel = if (useNoSeccomp) "alpine · no-seccomp" else "alpine · pid ${session.pid}"
      sendStatus?.invoke(tabId, "running", modeLabel)
    } catch (t: Throwable) {
      DiagnosticLog.log("ProotBackend", "Failed spawning PTY for $tabId", t)
      onFatalProotError(
        "Failed to start PTY shell",
        "Could not fork/exec proot inside an Android pseudo-terminal.",
        "${t.javaClass.simpleName}: ${t.message}",
      )
    }
  }

  private fun handleSessionExit(
    tabId: String,
    cols: Int,
    rows: Int,
    exitCode: Int,
    runtimeMs: Long,
    usedNoSeccomp: Boolean,
  ) {
    sessions.remove(tabId)
    TermService.sync(context, sessions.size)

    // Automatic PROOT_NO_SECCOMP=1 fallback if proot crashed immediately during startup
    if (!usedNoSeccomp && exitCode != 0 && runtimeMs < 2000L && fallbackRetriedTabs.add(tabId)) {
      DiagnosticLog.log(
        "ProotBackend",
        "Early exit ($exitCode in ${runtimeMs}ms) on $tabId; retrying with PROOT_NO_SECCOMP=1",
      )
      sendOutput?.invoke(
        tabId,
        "\r\n\u001b[33m[proot exited early ($exitCode); retrying with PROOT_NO_SECCOMP=1...]\u001b[0m\r\n"
          .toByteArray(StandardCharsets.UTF_8),
      )
      scope.launch {
        settingsStore.setProotNoSeccomp(true)
        startSession(tabId, cols, rows, forceNoSeccomp = true)
      }
      return
    }

    sendStatus?.invoke(tabId, "exited", "exited ($exitCode)")
    sendOutput?.invoke(
      tabId,
      "\r\n\u001b[90m[process exited with code $exitCode]\u001b[0m\r\n"
        .toByteArray(StandardCharsets.UTF_8),
    )
  }

  fun write(tabId: String, bytes: ByteArray) {
    sessions[tabId]?.write(bytes)
  }

  fun onResize(tabId: String, cols: Int, rows: Int) {
    sessions[tabId]?.resize(cols, rows)
  }

  fun onTabClosed(tabId: String) {
    fallbackRetriedTabs.remove(tabId)
    sessions.remove(tabId)?.close()
    TermService.sync(context, sessions.size)
  }

  fun closeAllSessions() {
    val all = sessions.keys.toList()
    all.forEach { key ->
      sessions.remove(key)?.close()
      sendStatus?.invoke(key, "stopped", "stopped")
    }
    TermService.sync(context, 0)
  }

  fun destroy() {
    TermService.onStopRequested = null
    closeAllSessions()
    scope.cancel()
  }
}
