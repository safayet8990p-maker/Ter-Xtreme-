package com.example.env

import android.content.Context
import android.system.Os
import com.example.data.SettingsStore
import com.example.term.PtySession
import com.example.terminal.DiagnosticLog
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object NetSetup {
  private const val RESOLV_CONF = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
  private const val HOSTS_FILE = "127.0.0.1 localhost localhost.localdomain\n::1 localhost\n"

  fun ensureGuestConfigs(context: Context) {
    val rootfs = Launcher.rootfsDir(context)
    val etc = File(rootfs, "etc").apply { mkdirs() }

    File(etc, "resolv.conf").writeText(RESOLV_CONF)
    File(etc, "hosts").writeText(HOSTS_FILE)

    val releaseRaw =
      File(etc, "alpine-release").takeIf { it.exists() }?.readText()?.trim().orEmpty()
    val branch = parseAlpineBranch(releaseRaw)
    val apkDir = File(etc, "apk").apply { mkdirs() }
    File(apkDir, "repositories").writeText(
      "https://dl-cdn.alpinelinux.org/alpine/$branch/main\n" +
        "https://dl-cdn.alpinelinux.org/alpine/$branch/community\n",
    )

    val profileDir = File(etc, "profile.d").apply { mkdirs() }
    File(profileDir, "terxtreme.sh").writeText(
      "export PS1='\\[\\e[1;35m\\]\\u@\\h\\[\\e[0m\\]:\\[\\e[1;36m\\]\\w\\[\\e[0m\\]\\$ '\n",
    )

    val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
    val pkgScript = File(localBin, "pkg")
    pkgScript.writeText(
      """#!/bin/sh
set -e
cmd="${'$'}1"
[ -n "${'$'}cmd" ] && shift || true
case "${'$'}cmd" in
  install|add)
    exec apk add --no-cache "${'$'}@"
    ;;
  remove|rm|del)
    exec apk del "${'$'}@"
    ;;
  search)
    exec apk search -v "${'$'}@"
    ;;
  update)
    exec apk update "${'$'}@"
    ;;
  upgrade)
    exec apk upgrade "${'$'}@"
    ;;
  list)
    exec apk info "${'$'}@"
    ;;
  *)
    echo "Usage: pkg {install|remove|search|update|upgrade|list} [args]"
    exit 1
    ;;
esac
""",
    )
    try {
      Os.chmod(pkgScript.absolutePath, 0b111_101_101) // 0755
    } catch (t: Throwable) {
      DiagnosticLog.log("NetSetup", "chmod 0755 failed on /usr/local/bin/pkg", t)
    }
    DiagnosticLog.log("NetSetup", "Configured DNS, hosts, HTTPS apk repositories ($branch), and pkg wrapper")
  }

  internal fun parseAlpineBranch(release: String): String {
    val parts = release.split(".")
    return if (parts.size >= 2 && parts[0].all { it.isDigit() } && parts[1].all { it.isDigit() }) {
      "v${parts[0]}.${parts[1]}"
    } else {
      "latest-stable"
    }
  }

  fun runFirstBootSetup(
    context: Context,
    binaries: ProotCheckResult.Ready,
    settingsStore: SettingsStore,
    preferNoSeccomp: Boolean,
    onLogLine: (String) -> Unit,
  ): Result<Boolean> {
    ensureGuestConfigs(context)

    val firstAttempt =
      runProotCommand(
        context = context,
        binaries = binaries,
        noSeccomp = preferNoSeccomp,
        command = "apk update && apk add --no-cache ca-certificates",
        timeoutSec = 120,
        onLogLine = onLogLine,
      )
    if (firstAttempt.exitCode == 0) {
      DiagnosticLog.log("NetSetup", "First-boot apk update succeeded (noSeccomp=$preferNoSeccomp)")
      return Result.success(preferNoSeccomp)
    }

    if (!preferNoSeccomp) {
      onLogLine("Retrying proot with PROOT_NO_SECCOMP=1...")
      DiagnosticLog.log(
        "NetSetup",
        "Initial proot attempt exited ${firstAttempt.exitCode}; retrying with PROOT_NO_SECCOMP=1",
      )
      val fallbackAttempt =
        runProotCommand(
          context = context,
          binaries = binaries,
          noSeccomp = true,
          command = "apk update && apk add --no-cache ca-certificates",
          timeoutSec = 120,
          onLogLine = onLogLine,
        )
      if (fallbackAttempt.exitCode == 0) {
        DiagnosticLog.log("NetSetup", "First-boot apk update succeeded with PROOT_NO_SECCOMP=1 fallback")
        return Result.success(true)
      }
      return Result.failure(
        IllegalStateException(
          "proot apk update failed (exit ${fallbackAttempt.exitCode}): ${fallbackAttempt.outputTail}",
        ),
      )
    }

    return Result.failure(
      IllegalStateException(
        "proot apk update failed (exit ${firstAttempt.exitCode}): ${firstAttempt.outputTail}",
      ),
    )
  }

  data class CommandOutput(
    val exitCode: Int,
    val output: String,
    val outputTail: String,
  )

  fun runProotCommand(
    context: Context,
    binaries: ProotCheckResult.Ready,
    noSeccomp: Boolean,
    command: String,
    timeoutSec: Long = 60,
    onLogLine: (String) -> Unit = {},
  ): CommandOutput {
    val spec = Launcher.buildExecSpec(context, binaries, noSeccomp, command)
    val latch = CountDownLatch(1)
    val sb = StringBuilder()
    var exitCode = -1

    val session =
      PtySession(
        tabId = "exec-${System.nanoTime()}",
        cmd = spec.cmd,
        cwd = spec.cwd,
        argv = spec.argv,
        envp = spec.envp,
        cols = 80,
        rows = 24,
        onOutput = { _, bytes ->
          val chunk = String(bytes, StandardCharsets.UTF_8)
          synchronized(sb) { sb.append(chunk) }
          chunk.lines().filter { it.isNotBlank() }.forEach(onLogLine)
        },
        onExit = { _, code, _ ->
          exitCode = code
          latch.countDown()
        },
      )

    val completed = latch.await(timeoutSec, TimeUnit.SECONDS)
    if (!completed) {
      session.close()
      exitCode = 124
    }
    val full = synchronized(sb) { sb.toString() }
    val tail = full.trim().lines().takeLast(6).joinToString(" | ")
    return CommandOutput(exitCode = exitCode, output = full, outputTail = tail)
  }
}
