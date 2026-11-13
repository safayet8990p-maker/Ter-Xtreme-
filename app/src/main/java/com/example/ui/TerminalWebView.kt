package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.example.bridge.TermBridge
import com.example.terminal.DiagnosticLog

private const val ENTRY_URL = "https://appassets.androidplatform.net/assets/index.html"

class TerminalAndroidWebView(context: Context) : WebView(context) {
  @Volatile var isTerminalInputMode: Boolean = true

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
    val base = super.onCreateInputConnection(outAttrs) ?: return null
    outAttrs.imeOptions =
      (outAttrs.imeOptions and EditorInfo.IME_MASK_ACTION) or
        EditorInfo.IME_FLAG_NO_FULLSCREEN or
        EditorInfo.IME_FLAG_NO_EXTRACT_UI

    if (!isTerminalInputMode) {
      return base
    }

    outAttrs.inputType =
      InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

    return object : InputConnectionWrapper(base, true) {
      override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0 && afterLength == 0) {
          val textBefore = getTextBeforeCursor(1, 0)
          if (textBefore.isNullOrEmpty()) {
            repeat(beforeLength) {
              sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
              sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
            return true
          }
        }
        return super.deleteSurroundingText(beforeLength, afterLength)
      }
    }
  }

  fun showSoftKeyboard() {
    requestFocus()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
  }

  fun hideSoftKeyboard() {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    imm.hideSoftInputFromWindow(windowToken, 0)
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalWebView(
  bridge: TermBridge,
  onFatalError: (AppErrorState) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val assetLoader =
    remember(context) {
      WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(context))
        .build()
    }

  val webView =
    remember(context, bridge) {
      try {
        TerminalAndroidWebView(context).apply {
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
          isFocusable = true
          isFocusableInTouchMode = true
          overScrollMode = WebView.OVER_SCROLL_NEVER

          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = false
          }

          addJavascriptInterface(bridge, "TermBridge")

          webChromeClient =
            object : WebChromeClient() {
              override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                DiagnosticLog.log(
                  "WebConsole",
                  "${consoleMessage.messageLevel()}: ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                )
                return super.onConsoleMessage(consoleMessage)
              }
            }

          webViewClient =
            object : WebViewClient() {
              override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
              ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

              override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
              ): Boolean = true

              override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
              ) {
                if (request.isForMainFrame) {
                  val detail = "Code ${error.errorCode}: ${error.description} (${request.url})"
                  DiagnosticLog.log("WebView", detail)
                  onFatalError(
                    AppErrorState(
                      kind = AppErrorKind.WEBVIEW_UNAVAILABLE,
                      title = "Terminal UI failed to load",
                      message = "The bundled WebView shell encountered a loading error.",
                      detail = detail,
                    ),
                  )
                }
              }

              override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
              ): Boolean {
                val msg = "WebView render process gone (didCrash=${detail.didCrash()})"
                DiagnosticLog.log("WebView", msg)
                onFatalError(
                  AppErrorState(
                    kind = AppErrorKind.WEBVIEW_UNAVAILABLE,
                    title = "Terminal renderer stopped",
                    message = "Android terminated the WebView renderer process.",
                    detail = msg,
                  ),
                )
                return true
              }
            }

          bridge.attachWebView(this)
          loadUrl(ENTRY_URL)
        }
      } catch (t: Throwable) {
        DiagnosticLog.log("WebView", "Failed to initialize WebView", t)
        onFatalError(
          AppErrorState(
            kind = AppErrorKind.WEBVIEW_UNAVAILABLE,
            title = "WebView unavailable",
            message = "Android System WebView could not be initialized on this device.",
            detail = "${t.javaClass.simpleName}: ${t.message}",
          ),
        )
        null
      }
    }

  DisposableEffect(webView) {
    onDispose {
      bridge.detachWebView()
      webView?.apply {
        stopLoading()
        removeJavascriptInterface("TermBridge")
        destroy()
      }
    }
  }

  if (webView != null) {
    AndroidView(
      factory = { webView },
      modifier = modifier.fillMaxSize().testTag("terminal_webview"),
    )
  }
}
