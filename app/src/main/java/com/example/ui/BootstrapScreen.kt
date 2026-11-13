package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.env.InstallProgress

@Composable
fun BootstrapScreen(
  progress: InstallProgress,
  awaitingInstallConsent: Boolean,
  storageGranted: Boolean,
  logs: List<String>,
  onStartInstall: () -> Unit,
  onRequestStorage: () -> Unit,
  onOpenBinaryDebug: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.Terminal,
          contentDescription = "Alpine Linux Bootstrap",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(48.dp),
        )
        Text(
          text = if (awaitingInstallConsent) "Ter Xtreme v2.0.1 · Linux Setup" else "Installing Alpine Linux (aarch64)",
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onBackground,
        )

        if (awaitingInstallConsent) {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text =
                "root:~# ready to install Alpine Linux (aarch64) minirootfs (~3.6 MB).\n" +
                  "• Installs once to internal storage and persists all files.\n" +
                  "• Storage access (/storage/emulated/0): " +
                  if (storageGranted) "GRANTED" else "NOT GRANTED (optional)",
              style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(14.dp),
            )
          }

          Button(
            onClick = onStartInstall,
            modifier = Modifier.fillMaxWidth().testTag("install_linux_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Download,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.action_install_linux))
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            if (!storageGranted) {
              OutlinedButton(
                onClick = onRequestStorage,
                modifier = Modifier.weight(1f).testTag("bootstrap_storage_button"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.FolderOpen,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(stringResource(R.string.action_grant_storage))
              }
            }
            OutlinedButton(
              onClick = onOpenBinaryDebug,
              modifier = Modifier.weight(1f).testTag("bootstrap_binary_debug_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.Build,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.size(6.dp))
              Text("Binary Debug")
            }
          }
        } else {
          val statusText =
            when (progress) {
              InstallProgress.CheckingEnvironment -> "Checking storage and network..."
              is InstallProgress.FetchingManifest -> "Fetching official Alpine release manifest..."
              is InstallProgress.Downloading -> {
                val dlMb = progress.bytesDownloaded / (1024f * 1024f)
                val totMb = progress.totalBytes / (1024f * 1024f)
                if (progress.totalBytes > 0) {
                  String.format(
                    "Downloading Alpine %s (%.2f / %.2f MB)...",
                    progress.version,
                    dlMb,
                    totMb,
                  )
                } else {
                  String.format("Downloading Alpine %s (%.2f MB)...", progress.version, dlMb)
                }
              }
              is InstallProgress.VerifyingSha256 -> "Verifying SHA256 for ${progress.filename}..."
              is InstallProgress.Extracting ->
                "Extracting rootfs (${progress.entriesExtracted} entries): ${progress.currentEntry}"
              is InstallProgress.ConfiguringNetwork ->
                "First-boot apk setup: ${progress.logLine}"
              InstallProgress.Complete -> "Alpine Linux ready."
            }

          Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary,
          )

          if (progress is InstallProgress.Downloading && progress.totalBytes > 0) {
            val fraction =
              (progress.bytesDownloaded.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
              progress = { fraction },
              modifier = Modifier.fillMaxWidth(),
            )
          } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }

          if (logs.isNotEmpty()) {
            Card(
              colors =
                CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = logs.takeLast(8).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
              )
            }
          }
        }
      }
    }
  }
}
