package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.env.ElfBinaryReport
import com.example.env.NativeBinaryInspector
import com.example.env.NativeEnvironmentReport

@Composable
fun BinaryDebugScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var report by remember { mutableStateOf(NativeBinaryInspector.inspectEnvironment(context)) }

  Surface(
    modifier = modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        IconButton(
          onClick = onBack,
          modifier = Modifier.testTag("binary_debug_back_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.binary_debug_title),
            style = MaterialTheme.typography.headlineSmall,
          )
          Text(
            text = stringResource(R.string.binary_debug_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Button(
          onClick = { report = NativeBinaryInspector.inspectEnvironment(context) },
          modifier = Modifier.weight(1f).testTag("binary_debug_refresh_button"),
        ) {
          Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(6.dp))
          Text(stringResource(R.string.action_refresh))
        }
        OutlinedButton(
          onClick = { copyBinaryReport(context, report) },
          modifier = Modifier.weight(1f).testTag("binary_debug_copy_button"),
        ) {
          Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(6.dp))
          Text(stringResource(R.string.action_copy_report))
        }
      }

      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item {
          RuntimeFlagsCard(report = report)
        }
        items(report.libraries, key = { it.name }) { lib ->
          BinaryReportCard(lib = lib)
        }
        item {
          LicenseObligationsCard()
        }
      }
    }
  }
}

@Composable
private fun RuntimeFlagsCard(report: NativeEnvironmentReport) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = "Runtime Native Packaging & Loader",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = "nativeLibraryDir: ${report.nativeLibraryDir}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      )
      Text(
        text = "FLAG_EXTRACT_NATIVE_LIBS: ${report.extractNativeLibsFlag}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = if (report.extractNativeLibsFlag) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error,
      )
      Text(
        text = "System.loadLibrary(\"terpty\"): ${report.terPtyLoadStatus}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color =
          if (report.terPtyLoadStatus.startsWith("OK")) {
            Color(0xFF4ADE80)
          } else {
            MaterialTheme.colorScheme.error
          },
      )
      Text(
        text = "SUPPORTED_ABIS: ${report.supportedAbis}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      )
    }
  }
}

@Composable
private fun BinaryReportCard(lib: ElfBinaryReport) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = if (lib.exists) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
          contentDescription = if (lib.exists) "Present" else "Missing",
          tint = if (lib.exists) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error,
          modifier = Modifier.size(20.dp),
        )
        Text(
          text = lib.name,
          style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
          color = MaterialTheme.colorScheme.secondary,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = if (lib.exists) "${lib.sizeBytes} B" else "MISSING",
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          color = if (lib.exists) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error,
        )
      }

      Text(
        text = "exists=${lib.exists}  canExecute=${lib.canExecute}  size=${formatSize(lib.sizeBytes)}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      )
      Text(
        text = "file: ${lib.fileStyleSummary}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (lib.hexHeader.isNotBlank()) {
        Text(
          text = "hex[0..15]: ${lib.hexHeader}",
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          color = MaterialTheme.colorScheme.secondary,
        )
      }
      Text(
        text = "align: ${lib.alignmentLabel}" +
          if (lib.sha256Prefix.isNotBlank()) "  sha256: ${lib.sha256Prefix}" else "",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = "exec: ${lib.runtimeExecProbe}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun LicenseObligationsCard() {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = "License Obligations",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text =
          "libproot.so & libproot-loader.so are GPLv2+. Distributing an APK containing them requires providing the GPLv2 notice and corresponding PRoot + talloc (LGPLv3+) source and build script (scripts/build_proot_arm64.sh). libterpty.so is original C JNI.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun formatSize(bytes: Long): String =
  when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024f)
    else -> String.format("%.2f MB", bytes / (1024f * 1024f))
  }

private fun copyBinaryReport(context: Context, report: NativeEnvironmentReport) {
  val text = report.toClipboardText("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
  cm.setPrimaryClip(ClipData.newPlainText("Ter Xtreme Binary Debug", text))
  Toast.makeText(context, "Binary debug report copied", Toast.LENGTH_SHORT).show()
}
