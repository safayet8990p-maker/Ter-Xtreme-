package com.example.terminal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.env.NativeBinaryInspector
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeBinaryInspectorTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `inspectFile handles missing binary gracefully`() {
    val missing = File(tempFolder.root, "libproot.so")
    val report = NativeBinaryInspector.inspectFile(missing)
    assertFalse(report.exists)
    assertEquals(0L, report.sizeBytes)
    assertTrue(report.fileStyleSummary.contains("missing"))
  }

  @Test
  fun `parseElf extracts aarch64 type interpreter and 16KB PT_LOAD alignment`() {
    val elfFile = tempFolder.newFile("libproot.so")
    val bytes = buildSyntheticElf64Aarch64(alignBytes = 16384L, interpPath = "/system/bin/linker64")
    elfFile.writeBytes(bytes)

    val report = NativeBinaryInspector.inspectFile(elfFile)
    assertTrue(report.exists)
    assertEquals(bytes.size.toLong(), report.sizeBytes)
    assertTrue(report.fileStyleSummary.contains("ELF 64-bit LSB"))
    assertTrue(report.fileStyleSummary.contains("ARM aarch64"))
    assertTrue(report.fileStyleSummary.contains("/system/bin/linker64"))
    assertTrue(report.alignmentLabel.contains("16KB-aligned"))
  }

  @Test
  fun `inspectEnvironment returns expected target library names`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val envReport = NativeBinaryInspector.inspectEnvironment(context)
    assertEquals(
      listOf("libproot.so", "libproot-loader.so", "libterpty.so"),
      envReport.libraries.map { it.name },
    )
  }

  private fun buildSyntheticElf64Aarch64(alignBytes: Long, interpPath: String): ByteArray {
    val interpBytes = (interpPath + "\u0000").toByteArray(Charsets.UTF_8)
    val phOff = 64
    val phSize = 56
    val phCount = 2
    val interpOffset = phOff + phSize * phCount
    val totalSize = interpOffset + interpBytes.size

    val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
    // e_ident (16 bytes)
    buf.put(0x7F.toByte())
    buf.put('E'.code.toByte())
    buf.put('L'.code.toByte())
    buf.put('F'.code.toByte())
    buf.put(2.toByte()) // ELFCLASS64
    buf.put(1.toByte()) // ELFDATA2LSB
    buf.put(1.toByte()) // EV_CURRENT
    for (i in 7 until 16) buf.put(0.toByte())

    // Elf64_Ehdr remaining 48 bytes
    buf.putShort(3) // e_type = ET_DYN
    buf.putShort(183) // e_machine = EM_AARCH64
    buf.putInt(1) // e_version
    buf.putLong(0L) // e_entry
    buf.putLong(phOff.toLong()) // e_phoff
    buf.putLong(0L) // e_shoff
    buf.putInt(0) // e_flags
    buf.putShort(64) // e_ehsize
    buf.putShort(phSize.toShort()) // e_phentsize
    buf.putShort(phCount.toShort()) // e_phnum
    buf.putShort(0) // e_shentsize
    buf.putShort(0) // e_shnum
    buf.putShort(0) // e_shstrndx

    // Program header 0: PT_LOAD (56 bytes)
    buf.putInt(1) // PT_LOAD
    buf.putInt(5) // PF_R | PF_X
    buf.putLong(0L) // p_offset
    buf.putLong(0L) // p_vaddr
    buf.putLong(0L) // p_paddr
    buf.putLong(totalSize.toLong()) // p_filesz
    buf.putLong(totalSize.toLong()) // p_memsz
    buf.putLong(alignBytes) // p_align

    // Program header 1: PT_INTERP (56 bytes)
    buf.putInt(3) // PT_INTERP
    buf.putInt(4) // PF_R
    buf.putLong(interpOffset.toLong()) // p_offset
    buf.putLong(interpOffset.toLong()) // p_vaddr
    buf.putLong(interpOffset.toLong()) // p_paddr
    buf.putLong(interpBytes.size.toLong()) // p_filesz
    buf.putLong(interpBytes.size.toLong()) // p_memsz
    buf.putLong(1L) // p_align

    buf.put(interpBytes)
    return buf.array()
  }
}
