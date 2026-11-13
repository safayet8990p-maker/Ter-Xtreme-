package com.example.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import com.example.data.AppSettings
import com.example.data.SettingsStore
import com.example.env.StorageHelper
import com.example.term.ProotBackend
import com.example.terminal.DiagnosticLog
import com.example.ui.TerminalAndroidWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class TermBridge(
  private val context: Context,
  private val backend: ProotBackend,
  private val settingsStore: SettingsStore,
  private val onOpenSelfTest: () -> Unit,
  private val onOpenBinaryDebug: () -> Unit = {},
  private val onRequestStorage: () -> Unit = {},
) {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var webView: TerminalAndroidWebView? = null

  init {
    backend.attachCallbacks(
      outputSender = { tabId, bytes -> sendOutputToWeb(tabId, bytes) },
      statusSender = { tabId, status, message -> sendStatusToWeb(tabId, status, message) },
    )
  }

  fun attachWebView(view: TerminalAndroidWebView) {
    webView = view
  }

  fun detachWebView() {
    webView = null
  }

  fun destroy() {
    scope.cancel()
  }

  @JavascriptInterface
  fun onReady() {
    DiagnosticLog.log("TermBridge", "WebView xterm.js v2.0.1 UI ready")
  }

  @JavascriptInterface
  fun onTabCreated(tabId: String, cols: Int, rows: Int) {
    backend.onTabCreated(tabId, cols, rows)
  }

  @JavascriptInterface
  fun onTabSelected(tabId: String) {
    DiagnosticLog.log("TermBridge", "Active tab: $tabId")
  }

  @JavascriptInterface
  fun onTabClosed(tabId: String) {
    backend.onTabClosed(tabId)
  }

  @JavascriptInterface
  fun write(tabId: String, base64Bytes: String) {
    val bytes =
      try {
        Base64.decode(base64Bytes, Base64.DEFAULT)
      } catch (t: IllegalArgumentException) {
        DiagnosticLog.log("TermBridge", "Invalid Base64 write from JS", t)
        return
      }
    backend.write(tabId, bytes)
  }

  @JavascriptInterface
  fun onInput(tabId: String, base64Bytes: String) {
    write(tabId, base64Bytes)
  }

  @JavascriptInterface
  fun onResize(tabId: String, cols: Int, rows: Int) {
    backend.onResize(tabId, cols, rows)
  }

  @JavascriptInterface
  fun loadSettings(): String =
    runBlocking {
      settingsStore.getCurrent().toJsonString(
        storageGranted = StorageHelper.hasStorageAccess(context),
      )
    }

  @JavascriptInterface
  fun saveSettings(json: String) {
    scope.launch {
      val current = settingsStore.getCurrent()
      val updated = AppSettings.fromJson(json, current)
      settingsStore.updateUiSettings(updated)
    }
  }

  @JavascriptInterface
  fun installThemePack(packId: String) {
    scope.launch {
      settingsStore.installThemePack(packId, activate = true)
    }
  }

  @JavascriptInterface
  fun markLinuxUpdateSeen() {
    scope.launch {
      settingsStore.markLinuxUpdateBannerSeen()
    }
  }

  @JavascriptInterface
  fun resetSettings() {
    scope.launch {
      settingsStore.resetUiDefaults()
    }
  }

  @JavascriptInterface
  fun openSelfTest() {
    scope.launch { settingsStore.markLinuxUpdateBannerSeen() }
    mainHandler.post { onOpenSelfTest() }
  }

  @JavascriptInterface
  fun openBinaryDebug() {
    mainHandler.post { onOpenBinaryDebug() }
  }

  @JavascriptInterface
  fun requestStoragePermission() {
    mainHandler.post { onRequestStorage() }
  }

  @JavascriptInterface
  fun hasStoragePermission(): Boolean = StorageHelper.hasStorageAccess(context)

  @JavascriptInterface
  fun setTerminalInputMode(enabled: Boolean) {
    mainHandler.post {
      webView?.isTerminalInputMode = enabled
    }
  }

  @JavascriptInterface
  fun showKeyboard() {
    mainHandler.post {
      webView?.showSoftKeyboard()
    }
  }

  @JavascriptInterface
  fun hideKeyboard() {
    mainHandler.post {
      webView?.hideSoftKeyboard()
    }
  }

  @JavascriptInterface
  fun copyText(text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Ter Xtreme", text))
  }

  @JavascriptInterface
  fun getClipboardText(): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
  }

  @JavascriptInterface
  fun logJsError(message: String) {
    DiagnosticLog.log("WebViewJS", message)
  }

  fun sendOutputToWeb(tabId: String, bytes: ByteArray) {
    val view = webView ?: return
    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val safeTabId = JSONObject.quote(tabId)
    val safeEncoded = JSONObject.quote(encoded)
    val js = "window.terBridge && window.terBridge.onNativeOutput($safeTabId, $safeEncoded);"
    mainHandler.post { view.evaluateJavascript(js, null) }
  }

  fun sendStatusToWeb(tabId: String, status: String, message: String) {
    val view = webView ?: return
    val js =
      "window.terBridge && window.terBridge.onNativeStatus(${JSONObject.quote(tabId)}, ${JSONObject.quote(status)}, ${JSONObject.quote(message)});"
    mainHandler.post { view.evaluateJavascript(js, null) }
  }

  fun handleBackPress(onUnhandled: () -> Unit) {
    val view = webView
    if (view == null) {
      onUnhandled()
      return
    }
    mainHandler.post {
      view.evaluateJavascript(
        "window.terBridge ? window.terBridge.handleBackPress() : false",
      ) { result ->
        if (result != "true") {
          onUnhandled()
        }
      }
    }
  }
}
