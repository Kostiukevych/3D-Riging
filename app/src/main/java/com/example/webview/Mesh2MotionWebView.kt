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
import java.io.ByteArrayInputStream
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

    val translatorJson = Mesh2MotionTranslator.loadDictionaryJson(context)
    val translatorScript = Mesh2MotionTranslator.buildInjectScript(translatorJson)

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
            val uri = request.url
            val host = uri.host?.lowercase()

            // Strictly serve local assets for the virtual asset domain (including root-relative remapping)
            if (host == ASSET_DOMAIN) {
              val rawPath = uri.path ?: "/"
              if (rawPath.startsWith("/assets/")) {
                val relAsset = rawPath.removePrefix("/assets/")
                if (assetExists(context, relAsset)) {
                  val direct = assetLoader.shouldInterceptRequest(uri)
                  if (direct != null) {
                    normalizeMimeTypeIfNeeded(uri.path, direct)
                    return direct
                  }
                }
              } else {
                val remappedPath = "/assets/www${if (rawPath.startsWith("/")) rawPath else "/$rawPath"}"
                val relAsset = remappedPath.removePrefix("/assets/")
                if (assetExists(context, relAsset)) {
                  val remapped = uri.buildUpon().path(remappedPath).build()
                  val mappedResponse = assetLoader.shouldInterceptRequest(remapped)
                  if (mappedResponse != null) {
                    normalizeMimeTypeIfNeeded(remapped.path, mappedResponse)
                    return mappedResponse
                  }
                }
              }

              // Prevent WebView from logging FileNotFoundException or doing DNS resolution on missing assets
              val status = if (rawPath.endsWith("favicon.ico", ignoreCase = true)) 204 else 404
              return emptyResponse(status, if (status == 204) "No Content" else "Not Found", guessMimeFromPath(rawPath))
            }

            // Offline isolation: try local offline_vendor mirror before returning an empty offline stub
            val offlineMirrored = tryLoadMirroredVendorAsset(context, assetLoader, uri)
            if (offlineMirrored != null) {
              normalizeMimeTypeIfNeeded(uri.path, offlineMirrored)
              return offlineMirrored
            }

            // Never allow background subresource requests (analytics/fonts/CDN) to trigger ERR_NAME_NOT_RESOLVED
            Log.d(TAG, "Blocked external offline request: $uri")
            return emptyResponse(200, "OK", guessMimeFromPath(uri.path))
          }

          override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
          ): Boolean {
            val url = request.url
            if (url.host.equals(ASSET_DOMAIN, ignoreCase = true)) {
              return false
            }
            // Open explicit user-tapped external links in system browser if desired
            if (request.hasGesture() && (url.scheme == "http" || url.scheme == "https")) {
              runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, url))
              }
              return true
            }
            return true
          }

          override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            view.evaluateJavascript(BlobDownloadBridge.INJECT_SCRIPT, null)
            view.evaluateJavascript(translatorScript, null)
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
              Log.d(
                TAG,
                "[JS:${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})",
              )
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
                    "model/obj",
                    "application/octet-stream",
                    "application/zip",
                    "application/x-zip-compressed",
                    "text/plain",
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
          }
        }
      )

      loadUrl(ENTRY_URL)
    }
  }

  private fun assetExists(context: Context, assetPath: String): Boolean {
    val clean = assetPath.trimStart('/')
    if (clean.isEmpty()) return false
    return try {
      context.assets.open(clean).close()
      true
    } catch (_: Exception) {
      false
    }
  }

  private fun tryLoadMirroredVendorAsset(
    context: Context,
    assetLoader: WebViewAssetLoader,
    externalUri: Uri,
  ): WebResourceResponse? {
    val host = externalUri.host ?: return null
    val path = externalUri.path?.trimStart('/') ?: ""
    if (path.isEmpty()) return null
    val relAsset = "www/offline_vendor/$host/$path"
    if (!assetExists(context, relAsset)) return null
    val mirroredUri =
      Uri.Builder()
        .scheme("https")
        .authority(ASSET_DOMAIN)
        .path("/assets/$relAsset")
        .build()
    return assetLoader.shouldInterceptRequest(mirroredUri)
  }

  private fun emptyResponse(statusCode: Int, reason: String, mimeType: String): WebResourceResponse =
    WebResourceResponse(
      mimeType,
      "UTF-8",
      statusCode,
      reason,
      mapOf("Cache-Control" to "no-store"),
      ByteArrayInputStream(ByteArray(0)),
    )

  private fun guessMimeFromPath(path: String?): String {
    val lowerPath = path?.lowercase() ?: return "application/octet-stream"
    return when {
      lowerPath.endsWith(".js") || lowerPath.endsWith(".mjs") -> "application/javascript"
      lowerPath.endsWith(".wasm") -> "application/wasm"
      lowerPath.endsWith(".css") -> "text/css"
      lowerPath.endsWith(".html") -> "text/html"
      lowerPath.endsWith(".json") -> "application/json"
      lowerPath.endsWith(".svg") -> "image/svg+xml"
      lowerPath.endsWith(".png") -> "image/png"
      lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg") -> "image/jpeg"
      lowerPath.endsWith(".webp") -> "image/webp"
      lowerPath.endsWith(".woff2") -> "font/woff2"
      lowerPath.endsWith(".woff") -> "font/woff"
      lowerPath.endsWith(".ttf") -> "font/ttf"
      lowerPath.endsWith(".glb") -> "model/gltf-binary"
      lowerPath.endsWith(".gltf") -> "model/gltf+json"
      else -> "application/octet-stream"
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
        lowerPath.endsWith(".woff2") -> "font/woff2"
        else -> null
      }

    if (overrideMime != null && (currentMime.isNullOrBlank() || currentMime == "application/octet-stream")) {
      response.mimeType = overrideMime
    }
  }
}
