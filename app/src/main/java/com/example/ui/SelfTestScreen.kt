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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.data.SettingsStore
import com.example.term.SelfTestItem
import com.example.term.SelfTestRunner
import com.example.term.SelfTestStatus
import com.example.terminal.DiagnosticLog
import kotlinx.coroutines.launch

@Composable
fun SelfTestScreen(
  settingsStore: SettingsStore,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var items by remember { mutableStateOf(SelfTestRunner.initialItems()) }
  var running by remember { mutableStateOf(false) }

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
          modifier = Modifier.testTag("selftest_back_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = stringResource(R.string.action_back_terminal),
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.linux_update_title),
            style = MaterialTheme.typography.headlineSmall,
          )
          Text(
            text = stringResource(R.string.linux_update_subtitle),
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
          enabled = !running,
          onClick = {
            running = true
            scope.launch {
              try {
                SelfTestRunner.runAll(context, settingsStore) { updated ->
                  items = updated
                }
              } finally {
                running = false
              }
            }
          },
          modifier = Modifier.weight(1f).testTag("run_selftest_button"),
        ) {
          Icon(
            imageVector = Icons.Outlined.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(6.dp))
          Text(if (running) "Updating..." else stringResource(R.string.action_run_update))
        }
        OutlinedButton(
          onClick = { copySelfTestLogs(context, items) },
          modifier = Modifier.testTag("selftest_copy_logs_button"),
        ) {
          Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(6.dp))
          Text(stringResource(R.string.action_copy_logs))
        }
      }

      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(items, key = { it.id }) { item ->
          SelfTestRow(item = item)
        }
      }
    }
  }
}

@Composable
private fun SelfTestRow(item: SelfTestItem) {
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        when (item.status) {
          SelfTestStatus.PENDING ->
            Icon(
              imageVector = Icons.Outlined.HourglassTop,
              contentDescription = "Pending",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
          SelfTestStatus.RUNNING ->
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
            )
          SelfTestStatus.PASS ->
            Icon(
              imageVector = Icons.Outlined.CheckCircle,
              contentDescription = "Pass",
              tint = Color(0xFF4ADE80),
              modifier = Modifier.size(20.dp),
            )
          SelfTestStatus.FAIL ->
            Icon(
              imageVector = Icons.Outlined.ErrorOutline,
              contentDescription = "Fail",
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = item.command,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.secondary,
          )
          Text(
            text = item.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        val badge =
          when (item.status) {
            SelfTestStatus.PENDING -> "WAIT"
            SelfTestStatus.RUNNING -> "RUN"
            SelfTestStatus.PASS -> "PASS (${item.exitCode ?: 0})"
            SelfTestStatus.FAIL -> "FAIL (${item.exitCode ?: -1})"
          }
        Text(
          text = badge,
          style = MaterialTheme.typography.bodySmall,
          color =
            when (item.status) {
              SelfTestStatus.PASS -> Color(0xFF4ADE80)
              SelfTestStatus.FAIL -> MaterialTheme.colorScheme.error
              else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }

      if (item.output.isNotBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = item.output.take(700),
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

private fun copySelfTestLogs(context: Context, items: List<SelfTestItem>) {
  val summary =
    items.joinToString("\n") {
      "[${it.status}] ${it.command} (exit=${it.exitCode})\n${it.output}"
    }
  val report =
    DiagnosticLog.dumpReport(
      appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
      summary = "Self-Test Results:\n$summary",
    )
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
  cm.setPrimaryClip(ClipData.newPlainText("Ter Xtreme Self-Test", report))
  Toast.makeText(context, "Self-Test logs copied", Toast.LENGTH_SHORT).show()
}
