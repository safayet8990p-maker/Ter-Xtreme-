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
    val updated =
      AppSettings.fromJson(
        """{"theme":"amber","font":48,"keys":false,"save":true,"user":"dev","host":"box"}""",
        base,
      )
    assertEquals("amber", updated.theme)
    assertEquals(24, updated.fontSize)
    assertEquals(false, updated.showKeyBar)
    assertEquals("dev", updated.username)
    assertEquals("box", updated.hostname)

    val reParsed = AppSettings.fromJson(updated.toJsonString(), base)
    assertEquals(updated, reParsed)
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
  fun `SelfTestRunner defines all 8 live diagnostic steps`() {
    val items = SelfTestRunner.initialItems()
    assertEquals(8, items.size)
    assertEquals(
      listOf(
        "uname -m",
        "ping -c1 1.1.1.1",
        "nslookup example.com",
        "apk update",
        "apk add --no-cache curl",
        "curl -I https://example.com",
        "wget -O /tmp/t https://example.com && ls -l /tmp/t",
        "terxtreme_nonexistent_cmd_404",
      ),
      items.map { it.command },
    )
  }
}
