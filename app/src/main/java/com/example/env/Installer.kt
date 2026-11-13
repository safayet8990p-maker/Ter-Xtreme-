package com.example.env

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.system.Os
import com.example.terminal.DiagnosticLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

sealed class InstallProgress {
  data object CheckingEnvironment : InstallProgress()

  data class FetchingManifest(val url: String) : InstallProgress()

  data class Downloading(
    val version: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
  ) : InstallProgress()

  data class VerifyingSha256(val filename: String) : InstallProgress()

  data class Extracting(
    val entriesExtracted: Int,
    val currentEntry: String,
  ) : InstallProgress()

  data class ConfiguringNetwork(val logLine: String) : InstallProgress()

  data object Complete : InstallProgress()
}

data class InstallFailure(
  val title: String,
  val message: String,
  val detail: String,
  val kind: FailureKind,
) {
  enum class FailureKind {
    NO_NETWORK,
    INSUFFICIENT_SPACE,
    CHECKSUM_MISMATCH,
    PROOT_FAILED,
  }
}

data class AlpineReleaseInfo(
  val version: String,
  val filename: String,
  val sha256: String,
  val sizeBytes: Long,
  val tarballUrl: String,
)

// Optional future second choice (VERIFY URL & checksum manifest before enabling in UI):
// Ubuntu Base aarch64: https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS
object Installer {
  private const val ALPINE_RELEASES_DIR =
    "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/"
  private const val ALPINE_YAML_URL = "${ALPINE_RELEASES_DIR}latest-releases.yaml"
  private const val MIN_REQUIRED_BYTES = 120L * 1024L * 1024L

  private val client = OkHttpClient.Builder().followRedirects(true).build()

  fun isRootfsReady(context: Context): Boolean {
    val rootfs = Launcher.rootfsDir(context)
    return File(rootfs, "bin/sh").exists() && File(rootfs, "sbin/apk").exists()
  }

  fun hasActiveNetwork(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  fun availableBytes(context: Context): Long {
    val stat = StatFs(context.filesDir.absolutePath)
    return stat.availableBytes
  }

  fun parseLatestMinirootfsYaml(yamlText: String): AlpineReleaseInfo {
    val blocks = yamlText.split(Regex("(?m)^-\\s+"))
    for (block in blocks) {
      if (!block.contains("flavor: alpine-minirootfs")) continue
      var version = ""
      var file = ""
      var sha256 = ""
      var size = 0L

      block.lines().forEach { rawLine ->
        val line = rawLine.trim()
        when {
          line.startsWith("version:") ->
            version = line.removePrefix("version:").trim().trim('"', '\'')
          line.startsWith("file:") ->
            file = line.removePrefix("file:").trim().trim('"', '\'')
          line.startsWith("sha256:") ->
            sha256 = line.removePrefix("sha256:").trim().trim('"', '\'')
          line.startsWith("size:") ->
            size = line.removePrefix("size:").trim().toLongOrNull() ?: 0L
        }
      }

      if (file.isNotBlank() && sha256.length == 64) {
        return AlpineReleaseInfo(
          version = version.ifBlank { "latest" },
          filename = file,
          sha256 = sha256.lowercase(),
          sizeBytes = size,
          tarballUrl = "$ALPINE_RELEASES_DIR$file",
        )
      }
    }
    error("Could not find alpine-minirootfs aarch64 entry in latest-releases.yaml")
  }

  fun installAlpine(
    context: Context,
    onProgress: (InstallProgress) -> Unit,
  ): InstallFailure? {
    onProgress(InstallProgress.CheckingEnvironment)

    val freeBytes = availableBytes(context)
    if (freeBytes < MIN_REQUIRED_BYTES) {
      val freeMb = freeBytes / (1024 * 1024)
      return InstallFailure(
        title = "Insufficient storage space",
        message = "Ter Xtreme needs at least 120 MB free to download and unpack Alpine Linux.",
        detail = "Available space in ${context.filesDir.absolutePath}: ${freeMb} MB",
        kind = InstallFailure.FailureKind.INSUFFICIENT_SPACE,
      )
    }

    if (!hasActiveNetwork(context)) {
      return InstallFailure(
        title = "No internet connection",
        message = "Connect to Wi-Fi or mobile data to download the Alpine Linux aarch64 rootfs.",
        detail = "Active network missing CAPABILITY_INTERNET for $ALPINE_YAML_URL",
        kind = InstallFailure.FailureKind.NO_NETWORK,
      )
    }

    val release =
      try {
        onProgress(InstallProgress.FetchingManifest(ALPINE_YAML_URL))
        val req = Request.Builder().url(ALPINE_YAML_URL).build()
        client.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) {
            error("HTTP ${resp.code} fetching $ALPINE_YAML_URL")
          }
          val body = resp.body?.string().orEmpty()
          parseLatestMinirootfsYaml(body)
        }
      } catch (t: Throwable) {
        DiagnosticLog.log("Installer", "Manifest fetch failed", t)
        return InstallFailure(
          title = "Failed to reach Alpine CDN",
          message = "Could not download release metadata from dl-cdn.alpinelinux.org.",
          detail = "${t.javaClass.simpleName}: ${t.message}",
          kind = InstallFailure.FailureKind.NO_NETWORK,
        )
      }

    DiagnosticLog.log(
      "Installer",
      "Resolved ${release.filename} (version=${release.version}, sha256=${release.sha256})",
    )

    val cacheDir = File(context.cacheDir, "rootfs-dl").apply { mkdirs() }
    val partFile = File(cacheDir, "${release.filename}.part")
    val archiveFile = File(cacheDir, release.filename)

    if (!archiveFile.exists() || sha256Hex(archiveFile) != release.sha256) {
      try {
        downloadResumable(release, partFile, onProgress)
      } catch (t: Throwable) {
        DiagnosticLog.log("Installer", "Tarball download failed", t)
        return InstallFailure(
          title = "Rootfs download interrupted",
          message = "Failed downloading ${release.filename}. Re-trying will resume from existing bytes.",
          detail = "${t.javaClass.simpleName}: ${t.message}",
          kind = InstallFailure.FailureKind.NO_NETWORK,
        )
      }

      onProgress(InstallProgress.VerifyingSha256(release.filename))
      val actualSha = sha256Hex(partFile)
      if (!actualSha.equals(release.sha256, ignoreCase = true)) {
        partFile.delete()
        return InstallFailure(
          title = "SHA256 verification failed",
          message = "Downloaded rootfs checksum did not match the official Alpine manifest.",
          detail = "Expected: ${release.sha256}\nActual:   $actualSha",
          kind = InstallFailure.FailureKind.CHECKSUM_MISMATCH,
        )
      }
      if (archiveFile.exists()) archiveFile.delete()
      check(partFile.renameTo(archiveFile)) { "Failed renaming verified archive" }
    }

    val stagingDir = File(context.filesDir, "rootfs.staging")
    val finalDir = Launcher.rootfsDir(context)
    try {
      stagingDir.deleteRecursively()
      stagingDir.mkdirs()
      extractTarGz(archiveFile, stagingDir, onProgress)
      finalDir.deleteRecursively()
      check(stagingDir.renameTo(finalDir)) { "Failed promoting extracted rootfs" }
      archiveFile.delete()
    } catch (t: Throwable) {
      stagingDir.deleteRecursively()
      DiagnosticLog.log("Installer", "Tar extraction failed", t)
      return InstallFailure(
        title = "Extraction failed",
        message = "Could not extract ${release.filename} to internal storage.",
        detail = "${t.javaClass.simpleName}: ${t.message}",
        kind = InstallFailure.FailureKind.PROOT_FAILED,
      )
    }

    return null
  }

  private fun downloadResumable(
    release: AlpineReleaseInfo,
    partFile: File,
    onProgress: (InstallProgress) -> Unit,
  ) {
    val existingBytes = if (partFile.exists()) partFile.length() else 0L
    val reqBuilder = Request.Builder().url(release.tarballUrl)
    if (existingBytes > 0L) {
      reqBuilder.header("Range", "bytes=$existingBytes-")
      DiagnosticLog.log("Installer", "Resuming download at offset $existingBytes")
    }

    client.newCall(reqBuilder.build()).execute().use { resp ->
      if (resp.code == 416) {
        return
      }
      if (!resp.isSuccessful) {
        error("HTTP ${resp.code} downloading ${release.tarballUrl}")
      }

      val isResumed = resp.code == 206
      val startOffset = if (isResumed) existingBytes else 0L
      val body = resp.body ?: error("Empty response body")
      val contentLen = body.contentLength()
      val totalBytes =
        when {
          contentLen > 0 -> startOffset + contentLen
          release.sizeBytes > 0 -> release.sizeBytes
          else -> 0L
        }

      var downloaded = startOffset
      var lastEmitMs = 0L
      val buffer = ByteArray(32 * 1024)

      FileOutputStream(partFile, isResumed).use { out ->
        body.byteStream().use { input ->
          while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
            downloaded += n
            val now = System.currentTimeMillis()
            if (now - lastEmitMs >= 100L || downloaded == totalBytes) {
              lastEmitMs = now
              onProgress(
                InstallProgress.Downloading(
                  version = release.version,
                  bytesDownloaded = downloaded,
                  totalBytes = totalBytes,
                ),
              )
            }
          }
        }
      }
    }
  }

  private fun extractTarGz(
    archiveFile: File,
    destDir: File,
    onProgress: (InstallProgress) -> Unit,
  ) {
    val canonicalRoot = destDir.canonicalPath
    val deferredDirModes = mutableListOf<Pair<File, Int>>()
    var count = 0

    TarArchiveInputStream(
      GzipCompressorInputStream(BufferedInputStream(FileInputStream(archiveFile))),
    ).use { tar ->
      val buf = ByteArray(32 * 1024)
      var entry: TarArchiveEntry? = tar.nextEntry
      while (entry != null) {
        val cleanName = entry.name.removePrefix("./").trimStart('/')
        if (cleanName.isNotEmpty() && cleanName != ".") {
          val outFile = File(destDir, cleanName)
          val canonicalParent = outFile.parentFile?.canonicalPath ?: canonicalRoot
          if (!canonicalParent.startsWith(canonicalRoot)) {
            throw SecurityException("Blocked tar path traversal: ${entry.name}")
          }

          when {
            entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> {
              // Unprivileged Android cannot mknod device nodes; /dev is bind-mounted by proot.
            }
            entry.isDirectory -> {
              outFile.mkdirs()
              deferredDirModes.add(outFile to (entry.mode and 0b111_111_111))
            }
            entry.isSymbolicLink -> {
              outFile.parentFile?.mkdirs()
              if (outFile.exists()) outFile.delete()
              Os.symlink(entry.linkName, outFile.absolutePath)
            }
            entry.isLink -> {
              val linkTarget = File(destDir, entry.linkName.removePrefix("./").trimStart('/'))
              outFile.parentFile?.mkdirs()
              if (outFile.exists()) outFile.delete()
              try {
                Os.link(linkTarget.absolutePath, outFile.absolutePath)
              } catch (_: Throwable) {
                // Fallback to copy if hard link is blocked on storage
                if (linkTarget.exists()) {
                  linkTarget.copyTo(outFile, overwrite = true)
                }
              }
            }
            entry.isFile -> {
              outFile.parentFile?.mkdirs()
              if (outFile.exists()) outFile.delete()
              FileOutputStream(outFile).use { fos ->
                while (true) {
                  val n = tar.read(buf)
                  if (n <= 0) break
                  fos.write(buf, 0, n)
                }
              }
              val mode = (entry.mode and 0b111_111_111) or 0b110_000_000
              try {
                Os.chmod(outFile.absolutePath, mode)
              } catch (_: Throwable) {}
            }
          }

          count++
          if (count % 40 == 0) {
            onProgress(InstallProgress.Extracting(count, cleanName))
          }
        }
        entry = tar.nextEntry
      }
    }

    // Apply directory modes deepest-first after extracting children
    deferredDirModes.sortedByDescending { it.first.absolutePath.length }.forEach { (dir, mode) ->
      try {
        Os.chmod(dir.absolutePath, mode or 0b111_000_000)
      } catch (_: Throwable) {}
    }

    onProgress(InstallProgress.Extracting(count, "done"))
  }

  private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(32 * 1024)
    FileInputStream(file).use { fis ->
      while (true) {
        val n = fis.read(buf)
        if (n <= 0) break
        digest.update(buf, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
