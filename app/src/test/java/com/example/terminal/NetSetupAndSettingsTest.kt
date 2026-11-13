package com.example.terminal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppSettings
import com.example.env.Launcher
import com.example.env.NetSetup
import com.example.env.ProotCheckResult
import com.example.term.SelfTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetSetupAndSettingsTest {

  @Test
  fun `parseAlpineBranch extracts major minor version`() {
    assertEquals("v3.21", NetSetup.parseAlpineBranch("3.21.3"))
    assertEquals("v3.20", NetSetup.parseAlpineBranch("3.20.0"))
    assertEquals("latest-stable", NetSetup.parseAlpineBranch(""))
    assertEquals("latest-stable", NetSetup.parseAlpineBranch("edge"))
  }

  @Test
  fun `AppSettings round-trips valid JSON and clamps font size`() {
    val base = AppSettings()
    assertEquals("auto", base.theme)
    val updated =
      AppSettings.fromJson(
        """{"theme":"amber","font":48,"keys":false,"themePacks":["amber","nord"],"updateSeen":true}""",
        base,
      )
    assertEquals("amber", updated.theme)
    assertEquals(24, updated.fontSize)
    assertEquals(false, updated.showKeyBar)
    assertEquals(setOf("amber", "nord"), updated.installedThemePacks)
    assertEquals(true, updated.linuxUpdateBannerSeen)

    val reParsed = AppSettings.fromJson(updated.toJsonString(storageGranted = true), base)
    assertEquals(updated, reParsed)
    assertTrue(updated.toJsonString(storageGranted = true).contains("\"storageGranted\":true"))
  }

  @Test
  fun `Launcher checkBinaries detects missing proot binaries`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val result = Launcher.checkBinaries(context)
    assertTrue(result is ProotCheckResult.MissingBinaries)
    val missing = result as ProotCheckResult.MissingBinaries
    assertTrue(missing.missingFiles.contains("libproot.so"))
    assertTrue(missing.missingFiles.contains("libproot-loader.so"))
  }

  @Test
  fun `SelfTestRunner defines all 8 Linux Update diagnostic steps`() {
    val items = SelfTestRunner.initialItems()
    assertEquals(8, items.size)
    assertEquals(
      listOf(
        "uname -m",
        "apk update",
        "apk upgrade --available",
        "nslookup example.com",
        "apk add --no-cache curl ca-certificates",
        "curl -I https://example.com",
        "ls -ld /storage/emulated/0",
        "terxtreme_nonexistent_cmd_404",
      ),
      items.map { it.command },
    )
  }

  @Test
  fun `Installer isRootfsReady respects installed marker and NetSetup configures root prompt`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val rootfs = Launcher.rootfsDir(context)
    rootfs.deleteRecursively()
    assertEquals(false, com.example.env.Installer.isRootfsReady(context))

    java.io.File(rootfs, "bin").mkdirs()
    java.io.File(rootfs, "bin/sh").writeText("#!/bin/sh\n")
    java.io.File(rootfs, ".terxtreme_installed").writeText("version=3.21.3\n")
    assertEquals(true, com.example.env.Installer.isRootfsReady(context))

    NetSetup.ensureGuestConfigs(context)
    val profile = java.io.File(rootfs, "etc/profile.d/terxtreme.sh").readText()
    assertTrue(profile.contains("HISTFILE=/root/.ash_history"))
    assertTrue(profile.contains("root"))
    assertTrue(!profile.contains("localhost"))
    rootfs.deleteRecursively()
  }
}
