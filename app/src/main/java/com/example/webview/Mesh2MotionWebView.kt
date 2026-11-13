package com.example.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

object Mesh2MotionWebViewFactory {

  const val ENTRY_URL = "https://appassets.androidplatform.net/assets/www/index.html"
  private const val ASSET_DOMAIN = "appassets.androidplatform.net"
  private const val TAG = "Mesh2MotionWebView"

  @SuppressLint("SetJavaScriptEnabled")
  fun create(
    context: Context,
    bridge: BlobDownloadBridge,
    onProgressChanged: (Int) -> Unit,
    onPageLoaded: () -> Unit,
    onLoadError: (String) -> Unit,
    onShowFileChooser: (
      filePathCallback: ValueCallback<Array<Uri>>,
      chooserIntent: Intent,
    ) -> Boolean,
  ): WebView {
    val assetLoader =
      WebViewAssetLoader.Builder()
        .setDomain(ASSET_DOMAIN)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    return WebView(context).apply {
      layoutParams =
        ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        )
      setBackgroundColor(Color.parseColor("#0D1017"))
      setLayerType(View.LAYER_TYPE_HARDWARE, null)

      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        @Suppress("DEPRECATION")
        databaseEnabled = true
        allowFileAccess = true
        allowContentAccess = true
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
      }

      addJavascriptInterface(bridge, BlobDownloadBridge.INTERFACE_NAME)

      webViewClient =
        object : WebViewClient() {
          override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
          ): WebResourceResponse? {
            val response = assetLoader.shouldInterceptRequest(request.url) ?: return null
            normalizeMimeTypeIfNeeded(request.url.path, response)
            return response
          }

          override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            view.evaluateJavascript(BlobDownloadBridge.INJECT_SCRIPT, null)
            onPageLoaded()
          }

          override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
          ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
              onLoadError(error.description?.toString() ?: "WebView failed to load asset bundle")
            }
          }
        }

      webChromeClient =
        object : WebChromeClient() {
          override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgressChanged(newProgress)
          }

          override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            consoleMessage?.let {
              Log.d(TAG, "[JS:${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
            }
            return super.onConsoleMessage(consoleMessage)
          }

          override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams?,
          ): Boolean {
            val allowMultiple =
              fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

            val intent =
              Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                  Intent.EXTRA_MIME_TYPES,
                  arrayOf(
                    "model/gltf-binary",
                    "model/gltf+json",
                    "application/octet-stream",
                    "application/zip",
                    "application/x-zip-compressed",
                    "model/*",
                    "*/*",
                  ),
                )
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
              }

            return onShowFileChooser(filePathCallback, intent)
          }
        }

      setDownloadListener(
        DownloadListener { url, _, contentDisposition, mimeType, _ ->
          if (url.isNullOrBlank()) return@DownloadListener

          val guessedName =
            URLUtil.guessFileName(url, contentDisposition, mimeType)
              .ifBlank { "mesh2motion_export.glb" }

          if (url.startsWith("blob:") || url.startsWith("data:")) {
            val quotedUrl = JSONObject.quote(url)
            val quotedName = JSONObject.quote(guessedName)
            evaluateJavascript(
              "window.__m2mFetchBlobToAndroid && window.__m2mFetchBlobToAndroid($quotedUrl, $quotedName);",
              null,
            )
          } else {
            runCatching {
              val externalIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
              context.startActivity(externalIntent)
            }
          }
        }
      )

      loadUrl(ENTRY_URL)
    }
  }

  private fun normalizeMimeTypeIfNeeded(path: String?, response: WebResourceResponse) {
    val lowerPath = path?.lowercase() ?: return
    val currentMime = response.mimeType?.lowercase()
    val overrideMime =
      when {
        lowerPath.endsWith(".js") || lowerPath.endsWith(".mjs") -> "application/javascript"
        lowerPath.endsWith(".wasm") -> "application/wasm"
        lowerPath.endsWith(".css") -> "text/css"
        lowerPath.endsWith(".html") -> "text/html"
        lowerPath.endsWith(".json") -> "application/json"
        lowerPath.endsWith(".svg") -> "image/svg+xml"
        else -> null
      }

    if (overrideMime != null && (currentMime.isNullOrBlank() || currentMime == "application/octet-stream")) {
      response.mimeType = overrideMime
    }
  }
}
