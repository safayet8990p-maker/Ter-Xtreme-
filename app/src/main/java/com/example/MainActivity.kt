package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.bridge.TermBridge
import com.example.data.SettingsStore
import com.example.env.InstallFailure
import com.example.env.InstallProgress
import com.example.env.Installer
import com.example.env.Launcher
import com.example.env.NetSetup
import com.example.env.ProotCheckResult
import com.example.term.ProotBackend
import com.example.ui.AppErrorKind
import com.example.ui.AppErrorState
import com.example.ui.BinaryDebugScreen
import com.example.ui.BootstrapScreen
import com.example.ui.ErrorScreen
import com.example.ui.SelfTestScreen
import com.example.ui.TerminalWebView
import com.example.ui.theme.TerXtremeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed class AppScreenState {
  data class Bootstrapping(val progress: InstallProgress) : AppScreenState()

  data object Terminal : AppScreenState()

  data object SelfTest : AppScreenState()

  data class BinaryDebug(val returnToError: AppErrorState? = null) : AppScreenState()

  data class FatalError(val error: AppErrorState) : AppScreenState()
}

class MainActivity : ComponentActivity() {
  private lateinit var settingsStore: SettingsStore
  private lateinit var backend: ProotBackend
  private lateinit var bridge: TermBridge

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    settingsStore = SettingsStore(applicationContext)

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )

    setContent {
      TerXtremeTheme(darkTheme = true) {
        var screenState by remember {
          mutableStateOf<AppScreenState>(
            AppScreenState.Bootstrapping(InstallProgress.CheckingEnvironment),
          )
        }
        var retryToken by remember { mutableIntStateOf(0) }
        val bootstrapLogs = remember { mutableStateListOf<String>() }

        val backendInstance = remember {
          ProotBackend(
            context = applicationContext,
            settingsStore = settingsStore,
            onFatalProotError = { title, message, detail ->
              screenState =
                AppScreenState.FatalError(
                  AppErrorState(
                    kind = AppErrorKind.PROOT_FAILED,
                    title = title,
                    message = message,
                    detail = detail,
                  ),
                )
            },
          ).also { backend = it }
        }

        val bridgeInstance = remember(backendInstance) {
          TermBridge(
            context = applicationContext,
            backend = backendInstance,
            settingsStore = settingsStore,
            onOpenSelfTest = { screenState = AppScreenState.SelfTest },
            onOpenBinaryDebug = { screenState = AppScreenState.BinaryDebug(returnToError = null) },
          ).also { bridge = it }
        }

        val notifLauncher =
          rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

        LaunchedEffect(Unit) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
              ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.POST_NOTIFICATIONS,
              ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
              notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
          }
        }

        LaunchedEffect(retryToken) {
          bootstrapLogs.clear()
          screenState = AppScreenState.Bootstrapping(InstallProgress.CheckingEnvironment)

          val binResult = withContext(Dispatchers.IO) { Launcher.checkBinaries(applicationContext) }
          if (binResult is ProotCheckResult.MissingBinaries) {
            screenState =
              AppScreenState.FatalError(
                AppErrorState(
                  kind = AppErrorKind.MISSING_BINARIES,
                  title = "Missing native proot binaries",
                  message =
                    "Ter Xtreme requires arm64-v8a libproot.so and libproot-loader.so in jniLibs. Run scripts/build_proot_arm64.sh or build via GitHub Actions.",
                  detail =
                    "nativeLibraryDir: ${binResult.nativeDir}\n" +
                      "Missing: ${binResult.missingFiles.joinToString(", ")}\n" +
                      "See README.md for GPLv2+ proot build steps.",
                ),
              )
            return@LaunchedEffect
          }

          val readyBins = binResult as ProotCheckResult.Ready
          val rootfsAlreadyInstalled =
            withContext(Dispatchers.IO) { Installer.isRootfsReady(applicationContext) }
          if (!rootfsAlreadyInstalled) {
            val installFailure =
              withContext(Dispatchers.IO) {
                Installer.installAlpine(applicationContext) { progress ->
                  screenState = AppScreenState.Bootstrapping(progress)
                }
              }
            if (installFailure != null) {
              val kind =
                when (installFailure.kind) {
                  InstallFailure.FailureKind.NO_NETWORK -> AppErrorKind.NO_NETWORK
                  InstallFailure.FailureKind.INSUFFICIENT_SPACE -> AppErrorKind.INSUFFICIENT_SPACE
                  InstallFailure.FailureKind.CHECKSUM_MISMATCH -> AppErrorKind.CHECKSUM_MISMATCH
                  InstallFailure.FailureKind.PROOT_FAILED -> AppErrorKind.PROOT_FAILED
                }
              screenState =
                AppScreenState.FatalError(
                  AppErrorState(
                    kind = kind,
                    title = installFailure.title,
                    message = installFailure.message,
                    detail = installFailure.detail,
                  ),
                )
              return@LaunchedEffect
            }
          }

          val prefs = withContext(Dispatchers.IO) { settingsStore.getCurrent() }
          if (!prefs.firstBootDone) {
            screenState =
              AppScreenState.Bootstrapping(
                InstallProgress.ConfiguringNetwork("Running initial apk update..."),
              )
            val setupResult =
              withContext(Dispatchers.IO) {
                NetSetup.runFirstBootSetup(
                  context = applicationContext,
                  binaries = readyBins,
                  settingsStore = settingsStore,
                  preferNoSeccomp = prefs.prootNoSeccomp,
                ) { line ->
                  bootstrapLogs.add(line)
                  screenState = AppScreenState.Bootstrapping(InstallProgress.ConfiguringNetwork(line))
                }
              }
            if (setupResult.isFailure) {
              val err = setupResult.exceptionOrNull()
              screenState =
                AppScreenState.FatalError(
                  AppErrorState(
                    kind = AppErrorKind.PROOT_FAILED,
                    title = "First-boot Alpine setup failed",
                    message =
                      "Alpine rootfs was extracted, but running 'apk update' inside proot failed.",
                    detail = err?.message ?: "Unknown proot failure",
                  ),
                )
              return@LaunchedEffect
            }
            val usedNoSeccomp = setupResult.getOrDefault(false)
            withContext(Dispatchers.IO) {
              settingsStore.setProotNoSeccomp(usedNoSeccomp)
              settingsStore.setFirstBootDone(true)
            }
          } else {
            withContext(Dispatchers.IO) { NetSetup.ensureGuestConfigs(applicationContext) }
          }

          screenState = AppScreenState.Terminal
        }

        BackHandler(enabled = screenState is AppScreenState.Terminal) {
          bridgeInstance.handleBackPress { finish() }
        }
        BackHandler(enabled = screenState is AppScreenState.SelfTest) {
          screenState = AppScreenState.Terminal
        }
        BackHandler(enabled = screenState is AppScreenState.BinaryDebug) {
          val returnErr = (screenState as? AppScreenState.BinaryDebug)?.returnToError
          screenState = if (returnErr != null) AppScreenState.FatalError(returnErr) else AppScreenState.Terminal
        }

        Scaffold(
          modifier = Modifier.fillMaxSize(),
          containerColor = MaterialTheme.colorScheme.background,
          contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
        ) { innerPadding ->
          Box(
            modifier =
              Modifier.fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
          ) {
            when (val state = screenState) {
              is AppScreenState.Bootstrapping ->
                BootstrapScreen(progress = state.progress, logs = bootstrapLogs)
              is AppScreenState.FatalError ->
                ErrorScreen(
                  error = state.error,
                  onRetry = { retryToken++ },
                  onOpenBinaryDebug = {
                    screenState = AppScreenState.BinaryDebug(returnToError = state.error)
                  },
                )
              AppScreenState.SelfTest ->
                SelfTestScreen(
                  settingsStore = settingsStore,
                  onBack = { screenState = AppScreenState.Terminal },
                )
              is AppScreenState.BinaryDebug ->
                BinaryDebugScreen(
                  onBack = {
                    screenState =
                      if (state.returnToError != null) {
                        AppScreenState.FatalError(state.returnToError)
                      } else {
                        AppScreenState.Terminal
                      }
                  },
                )
              AppScreenState.Terminal ->
                key(retryToken) {
                  TerminalWebView(
                    bridge = bridgeInstance,
                    onFatalError = { err -> screenState = AppScreenState.FatalError(err) },
                  )
                }
            }
          }
        }
      }
    }
  }

  override fun onDestroy() {
    if (::bridge.isInitialized) bridge.destroy()
    if (::backend.isInitialized) backend.destroy()
    super.onDestroy()
  }
}
