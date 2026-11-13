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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.terminal.DiagnosticLog

enum class AppErrorKind {
  MISSING_BINARIES,
  WEBVIEW_UNAVAILABLE,
  NO_NETWORK,
  INSUFFICIENT_SPACE,
  CHECKSUM_MISMATCH,
  PROOT_FAILED,
}

data class AppErrorState(
  val kind: AppErrorKind,
  val title: String,
  val message: String,
  val detail: String,
)

@Composable
fun ErrorScreen(
  error: AppErrorState,
  onRetry: () -> Unit,
  onOpenBinaryDebug: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val icon =
    when (error.kind) {
      AppErrorKind.MISSING_BINARIES -> Icons.Outlined.Build
      AppErrorKind.NO_NETWORK -> Icons.Outlined.CloudOff
      AppErrorKind.INSUFFICIENT_SPACE -> Icons.Outlined.Storage
      AppErrorKind.CHECKSUM_MISMATCH -> Icons.Outlined.Security
      AppErrorKind.PROOT_FAILED -> Icons.Outlined.Terminal
      AppErrorKind.WEBVIEW_UNAVAILABLE -> Icons.Outlined.ErrorOutline
    }

  Surface(
    modifier = modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(24.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = error.title,
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(48.dp),
        )
        Text(
          text = error.title,
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
          text = error.message,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = error.detail,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          OutlinedButton(
            onClick = { copyDiagnosticLogs(context, error) },
            modifier = Modifier.weight(1f).testTag("copy_logs_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.ContentCopy,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.action_copy_logs))
          }
          Button(
            onClick = onRetry,
            modifier = Modifier.weight(1f).testTag("retry_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Refresh,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.action_retry))
          }
        }
        if (onOpenBinaryDebug != null) {
          OutlinedButton(
            onClick = onOpenBinaryDebug,
            modifier = Modifier.fillMaxWidth().testTag("error_binary_debug_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Build,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.action_binary_debug))
          }
        }
      }
    }
  }
}

private fun copyDiagnosticLogs(context: Context, error: AppErrorState) {
  val report =
    DiagnosticLog.dumpReport(
      appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
      summary = "${error.kind}: ${error.title} — ${error.detail}",
    )
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
  cm.setPrimaryClip(ClipData.newPlainText("Ter Xtreme Logs", report))
  Toast.makeText(context, "Diagnostic logs copied", Toast.LENGTH_SHORT).show()
}
