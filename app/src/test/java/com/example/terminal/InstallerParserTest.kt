package com.example.terminal

import com.example.env.Installer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InstallerParserTest {

  @Test
  fun `parseLatestMinirootfsYaml picks aarch64 alpine-minirootfs entry`() {
    val sampleYaml =
      """
      - arch: aarch64
        branch: v3.21
        flavor: alpine-standard
        file: alpine-standard-3.21.3-aarch64.iso
        sha256: 1111111111111111111111111111111111111111111111111111111111111111
        size: 123456789
        version: 3.21.3
      - arch: aarch64
        branch: v3.21
        date: 2025-02-14
        file: alpine-minirootfs-3.21.3-aarch64.tar.gz
        flavor: alpine-minirootfs
        sha256: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
        size: 3654321
        version: 3.21.3
      """.trimIndent()

    val info = Installer.parseLatestMinirootfsYaml(sampleYaml)
    assertEquals("3.21.3", info.version)
    assertEquals("alpine-minirootfs-3.21.3-aarch64.tar.gz", info.filename)
    assertEquals("a".repeat(64), info.sha256)
    assertEquals(3654321L, info.sizeBytes)
    assertEquals(
      "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz",
      info.tarballUrl,
    )
  }

  @Test
  fun `parseLatestMinirootfsYaml rejects missing minirootfs`() {
    val yaml =
      """
      - arch: aarch64
        flavor: alpine-virt
        file: alpine-virt-3.21.3-aarch64.iso
        sha256: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
      """.trimIndent()

    assertThrows(IllegalStateException::class.java) {
      Installer.parseLatestMinirootfsYaml(yaml)
    }
  }
}
