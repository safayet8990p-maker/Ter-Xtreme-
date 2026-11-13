package com.example.env

import android.content.Context
import com.example.terminal.DiagnosticLog
import java.io.File

sealed class ProotCheckResult {
  data class Ready(
    val prootBinary: File,
    val loaderBinary: File,
  ) : ProotCheckResult()

  data class MissingBinaries(
    val nativeDir: String,
    val missingFiles: List<String>,
  ) : ProotCheckResult()
}

data class ProotLaunchSpec(
  val cmd: String,
  val cwd: String,
  val argv: Array<String>,
  val envp: Array<String>,
  val noSeccomp: Boolean,
)

object Launcher {
  // VERIFY: Some Android kernels block ptrace+seccomp syscall filtering; PROOT_NO_SECCOMP=1 is the standard fallback.
  // VERIFY: --link2symlink maps hard links (used by apk/tar) to symlinks on Android filesystems where hard links fail.
  fun checkBinaries(context: Context): ProotCheckResult {
    val nativePath = context.applicationInfo.nativeLibraryDir.orEmpty()
    if (nativePath.isBlank()) {
      DiagnosticLog.log("Launcher", "applicationInfo.nativeLibraryDir is null or blank")
      return ProotCheckResult.MissingBinaries(
        "(nativeLibraryDir unavailable)",
        listOf("libproot.so", "libproot-loader.so"),
      )
    }
    val nativeDir = File(nativePath)
    val proot = File(nativeDir, "libproot.so")
    val loader = File(nativeDir, "libproot-loader.so")
    val missing = buildList {
      if (!proot.exists() || !proot.canExecute()) add("libproot.so")
      if (!loader.exists()) add("libproot-loader.so")
    }
    return if (missing.isEmpty()) {
      ProotCheckResult.Ready(proot, loader)
    } else {
      DiagnosticLog.log(
        "Launcher",
        "Missing proot binaries in ${nativeDir.absolutePath}: ${missing.joinToString()}",
      )
      ProotCheckResult.MissingBinaries(nativeDir.absolutePath, missing)
    }
  }

  fun rootfsDir(context: Context): File = File(context.filesDir, "rootfs")

  fun tmpDir(context: Context): File = File(context.filesDir, "tmp").apply { mkdirs() }

  fun buildInteractiveShellSpec(
    context: Context,
    binaries: ProotCheckResult.Ready,
    noSeccomp: Boolean,
    username: String,
    hostname: String,
  ): ProotLaunchSpec =
    buildCommandSpec(
      context = context,
      binaries = binaries,
      noSeccomp = noSeccomp,
      username = username,
      hostname = hostname,
      guestCommand = listOf("/bin/sh", "-l"),
    )

  fun buildExecSpec(
    context: Context,
    binaries: ProotCheckResult.Ready,
    noSeccomp: Boolean,
    commandLine: String,
  ): ProotLaunchSpec =
    buildCommandSpec(
      context = context,
      binaries = binaries,
      noSeccomp = noSeccomp,
      username = "root",
      hostname = "alpine",
      guestCommand = listOf("/bin/sh", "-lc", commandLine),
    )

  private fun buildCommandSpec(
    context: Context,
    binaries: ProotCheckResult.Ready,
    noSeccomp: Boolean,
    username: String,
    hostname: String,
    guestCommand: List<String>,
  ): ProotLaunchSpec {
    val rootfs = rootfsDir(context)
    val prootTmp = tmpDir(context)
    File(rootfs, "tmp").apply {
      mkdirs()
      try {
        android.system.Os.chmod(absolutePath, 0b111_111_111)
      } catch (_: Throwable) {}
    }
    File(rootfs, "root").mkdirs()

    val argv =
      buildList {
        add(binaries.prootBinary.absolutePath)
        add("--kill-on-exit")
        add("--link2symlink")
        add("-0")
        add("-r")
        add(rootfs.absolutePath)
        add("-b")
        add("/dev")
        add("-b")
        add("/proc")
        add("-b")
        add("/sys")
        add("-w")
        add("/root")
        addAll(guestCommand)
      }.toTypedArray()

    val envp =
      buildList {
        add("PROOT_TMP_DIR=${prootTmp.absolutePath}")
        add("PROOT_LOADER=${binaries.loaderBinary.absolutePath}")
        if (noSeccomp) {
          add("PROOT_NO_SECCOMP=1")
        }
        add("HOME=/root")
        add("USER=${username.ifBlank { "root" }}")
        add("HOSTNAME=${hostname.ifBlank { "alpine" }}")
        add("TERM=xterm-256color")
        add("COLORTERM=truecolor")
        add("LANG=C.UTF-8")
        add("TMPDIR=/tmp")
        add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
      }.toTypedArray()

    DiagnosticLog.log(
      "Launcher",
      "Prepared proot spec (noSeccomp=$noSeccomp): ${argv.joinToString(" ")}",
    )

    return ProotLaunchSpec(
      cmd = binaries.prootBinary.absolutePath,
      cwd = rootfs.absolutePath,
      argv = argv,
      envp = envp,
      noSeccomp = noSeccomp,
    )
  }
}
