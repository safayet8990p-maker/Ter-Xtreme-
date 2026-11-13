package com.example.env

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ElfBinaryReport(
  val name: String,
  val path: String,
  val exists: Boolean,
  val canExecute: Boolean,
  val sizeBytes: Long,
  val sha256Prefix: String,
  val hexHeader: String,
  val fileStyleSummary: String,
  val alignmentLabel: String,
  val runtimeExecProbe: String,
)

data class NativeEnvironmentReport(
  val nativeLibraryDir: String,
  val extractNativeLibsFlag: Boolean,
  val supportedAbis: String,
  val terPtyLoadStatus: String,
  val libraries: List<ElfBinaryReport>,
) {
  fun toClipboardText(appVersion: String): String =
    buildString {
      appendLine("Ter Xtreme Native Binary Debug Report")
      appendLine("App Version: $appVersion")
      appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
      appendLine("Supported ABIs: $supportedAbis")
      appendLine("nativeLibraryDir: $nativeLibraryDir")
      appendLine("FLAG_EXTRACT_NATIVE_LIBS: $extractNativeLibsFlag")
      appendLine("System.loadLibrary(\"terpty\"): $terPtyLoadStatus")
      appendLine()
      libraries.forEach { lib ->
        appendLine("=== ${lib.name} ===")
        appendLine("Path: ${lib.path}")
        appendLine("Exists: ${lib.exists} | Executable: ${lib.canExecute} | Size: ${lib.sizeBytes} bytes")
        if (lib.sha256Prefix.isNotBlank()) {
          appendLine("SHA-256 (prefix): ${lib.sha256Prefix}")
        }
        if (lib.hexHeader.isNotBlank()) {
          appendLine("ELF Hex[0..15]: ${lib.hexHeader}")
        }
        appendLine("ELF / file info: ${lib.fileStyleSummary}")
        appendLine("Page alignment: ${lib.alignmentLabel}")
        appendLine("Runtime.exec probe: ${lib.runtimeExecProbe}")
        appendLine()
      }
      appendLine("Licenses: libproot.so & libproot-loader.so = GPLv2+; libtalloc = LGPLv3+; libterpty.so = original project C JNI")
    }
}

object NativeBinaryInspector {
  private const val PT_LOAD = 1
  private const val PT_INTERP = 3
  private const val ET_REL = 1
  private const val ET_EXEC = 2
  private const val ET_DYN = 3
  private const val EM_ARM = 40
  private const val EM_X86_64 = 62
  private const val EM_AARCH64 = 183

  fun inspectEnvironment(context: Context): NativeEnvironmentReport {
    val appInfo = context.applicationInfo
    val nativeDirPath = appInfo.nativeLibraryDir.orEmpty().ifBlank { "(unavailable)" }
    val extractFlag = (appInfo.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0
    val nativeDir = if (nativeDirPath == "(unavailable)") null else File(nativeDirPath)

    val terPtyStatus =
      try {
        System.loadLibrary("terpty")
        "OK (libterpty.so loaded via System.loadLibrary)"
      } catch (t: Throwable) {
        "FAIL (${t.javaClass.simpleName}: ${t.message})"
      }

    val targets = listOf("libproot.so", "libproot-loader.so", "libterpty.so")
    val reports =
      targets.map { fileName ->
        val file = if (nativeDir != null) File(nativeDir, fileName) else File(fileName)
        inspectFile(file)
      }

    return NativeEnvironmentReport(
      nativeLibraryDir = nativeDirPath,
      extractNativeLibsFlag = extractFlag,
      supportedAbis = Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "unknown" },
      terPtyLoadStatus = terPtyStatus,
      libraries = reports,
    )
  }

  fun inspectFile(file: File): ElfBinaryReport {
    if (!file.exists()) {
      return ElfBinaryReport(
        name = file.name,
        path = file.absolutePath,
        exists = false,
        canExecute = false,
        sizeBytes = 0L,
        sha256Prefix = "",
        hexHeader = "",
        fileStyleSummary = "missing (not extracted to nativeLibraryDir)",
        alignmentLabel = "n/a",
        runtimeExecProbe = "skipped (file does not exist)",
      )
    }

    val size = file.length()
    val exec = file.canExecute()
    val shaPrefix = computeSha256Prefix(file)
    val hexHdr = readHexHeader(file)
    val elf = parseElf(file)
    val execProbe = runExecProbe(file)

    return ElfBinaryReport(
      name = file.name,
      path = file.absolutePath,
      exists = true,
      canExecute = exec,
      sizeBytes = size,
      sha256Prefix = shaPrefix,
      hexHeader = hexHdr,
      fileStyleSummary = elf.summary,
      alignmentLabel = elf.alignmentLabel,
      runtimeExecProbe = execProbe,
    )
  }

  private fun runExecProbe(file: File): String {
    val candidates =
      listOf(
        arrayOf("readelf", "-h", file.absolutePath),
        arrayOf("file", file.absolutePath),
        arrayOf("/system/bin/ls", "-l", file.absolutePath),
      )
    for (cmd in candidates) {
      try {
        val proc = Runtime.getRuntime().exec(cmd)
        val finished = proc.waitFor(2, TimeUnit.SECONDS)
        if (!finished) {
          proc.destroyForcibly()
          continue
        }
        val stdout = proc.inputStream.bufferedReader().readText().trim()
        val stderr = proc.errorStream.bufferedReader().readText().trim()
        val combined = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString(" | ")
        if (proc.exitValue() == 0 && combined.isNotBlank()) {
          return "${cmd[0]}: ${combined.lines().take(3).joinToString(" / ")}"
        }
      } catch (_: Throwable) {
        // Tool unavailable in Android PATH; try next probe
      }
    }
    return "readelf/file not installed on Android PATH (verified via Kotlin ELF hex parser)"
  }

  private fun readHexHeader(file: File): String =
    try {
      RandomAccessFile(file, "r").use { raf ->
        val len = minOf(16L, file.length()).toInt()
        val bytes = ByteArray(len)
        raf.readFully(bytes)
        bytes.joinToString(" ") { "%02X".format(it) }
      }
    } catch (_: Throwable) {
      ""
    }

  internal data class ElfParseResult(
    val summary: String,
    val alignmentLabel: String,
  )

  internal fun parseElf(file: File): ElfParseResult {
    if (!file.exists() || file.length() < 52L) {
      return ElfParseResult("data (${file.length()} bytes, too small for ELF header)", "unknown")
    }

    return try {
      RandomAccessFile(file, "r").use { raf ->
        val ident = ByteArray(16)
        raf.readFully(ident)
        if (ident[0] != 0x7F.toByte() ||
          ident[1] != 'E'.code.toByte() ||
          ident[2] != 'L'.code.toByte() ||
          ident[3] != 'F'.code.toByte()
        ) {
          return ElfParseResult("non-ELF file (${file.length()} bytes)", "unknown")
        }

        val eiClass = ident[4].toInt() and 0xFF
        val eiData = ident[5].toInt() and 0xFF
        val bitsLabel =
          when (eiClass) {
            1 -> "ELF 32-bit"
            2 -> "ELF 64-bit"
            else -> "ELF class($eiClass)"
          }
        val order = if (eiData == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val endianLabel = if (eiData == 2) "MSB" else "LSB"

        if (eiClass == 2 && file.length() >= 64L) {
          val hdr = ByteArray(48)
          raf.readFully(hdr)
          val bb = ByteBuffer.wrap(hdr).order(order)
          val eType = bb.getShort(0).toInt() and 0xFFFF
          val eMachine = bb.getShort(2).toInt() and 0xFFFF
          val ePhOff = bb.getLong(16)
          val ePhEntSize = bb.getShort(38).toInt() and 0xFFFF
          val ePhNum = bb.getShort(40).toInt() and 0xFFFF

          val typeStr = elfTypeName(eType)
          val archStr = elfMachineName(eMachine)
          var interp: String? = null
          var maxAlign = 0L

          if (ePhOff > 0 && ePhEntSize >= 56 && ePhNum in 1..256) {
            for (i in 0 until ePhNum) {
              val off = ePhOff + i.toLong() * ePhEntSize.toLong()
              if (off + 56 > file.length()) break
              raf.seek(off)
              val ph = ByteArray(56)
              raf.readFully(ph)
              val pbb = ByteBuffer.wrap(ph).order(order)
              val pType = pbb.getInt(0)
              val pOffset = pbb.getLong(8)
              val pFileSz = pbb.getLong(32)
              val pAlign = pbb.getLong(48)

              if (pType == PT_LOAD && pAlign > maxAlign) {
                maxAlign = pAlign
              } else if (pType == PT_INTERP && pOffset >= 0 && pFileSz in 1..512 && pOffset + pFileSz <= file.length()) {
                raf.seek(pOffset)
                val pathBytes = ByteArray(pFileSz.toInt())
                raf.readFully(pathBytes)
                interp = String(pathBytes, Charsets.UTF_8).trimEnd('\u0000')
              }
            }
          }

          val alignDesc =
            when {
              maxAlign >= 16384L -> "${maxAlign}B (${maxAlign / 1024}KB-aligned, Android 15+ safe)"
              maxAlign > 0L -> "${maxAlign}B (${maxAlign / 1024}KB-aligned)"
              else -> "no PT_LOAD"
            }
          val summary =
            buildString {
              append("$bitsLabel $endianLabel $typeStr, $archStr")
              if (!interp.isNullOrBlank()) {
                append(", interpreter $interp")
              } else {
                append(", statically linked or shared library")
              }
            }
          ElfParseResult(summary = summary, alignmentLabel = alignDesc)
        } else {
          val hdr = ByteArray(36)
          raf.readFully(hdr)
          val bb = ByteBuffer.wrap(hdr).order(order)
          val eType = bb.getShort(0).toInt() and 0xFFFF
          val eMachine = bb.getShort(2).toInt() and 0xFFFF
          ElfParseResult(
            summary = "$bitsLabel $endianLabel ${elfTypeName(eType)}, ${elfMachineName(eMachine)}",
            alignmentLabel = "32-bit ELF",
          )
        }
      }
    } catch (t: Throwable) {
      ElfParseResult("ELF parse error: ${t.javaClass.simpleName}: ${t.message}", "unknown")
    }
  }

  private fun elfTypeName(type: Int): String =
    when (type) {
      ET_REL -> "relocatable"
      ET_EXEC -> "executable"
      ET_DYN -> "shared object / PIE executable"
      else -> "type($type)"
    }

  private fun elfMachineName(machine: Int): String =
    when (machine) {
      EM_AARCH64 -> "ARM aarch64"
      EM_ARM -> "ARM 32-bit"
      EM_X86_64 -> "x86-64"
      else -> "machine($machine)"
    }

  private fun computeSha256Prefix(file: File): String =
    try {
      val digest = MessageDigest.getInstance("SHA-256")
      val buf = ByteArray(16 * 1024)
      FileInputStream(file).use { fis ->
        while (true) {
          val n = fis.read(buf)
          if (n <= 0) break
          digest.update(buf, 0, n)
        }
      }
      digest.digest().take(8).joinToString("") { "%02x".format(it) } + "..."
    } catch (_: Throwable) {
      ""
    }
}
