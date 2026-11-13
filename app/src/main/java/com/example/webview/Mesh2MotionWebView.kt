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

  private val CORS_HEADERS =
    mapOf(
      "Access-Control-Allow-Origin" to "*",
      "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
      "Access-Control-Allow-Headers" to "*",
    )

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
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = true
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        cacheMode = WebSettings.LOAD_DEFAULT
      }

      addJavascriptInterface(bridge, BlobDownloadBridge.INTERFACE_NAME)

      webViewClient =
        object : WebViewClient() {
          override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
          ): WebResourceResponse? {
            if (request.method.equals("OPTIONS", ignoreCase = true)) {
              return emptyResponse(204, "No Content", "text/plain")
            }

            val uri = request.url
            val host = uri.host?.lowercase()

            // Strictly serve local assets for the virtual asset domain (including root-relative remapping)
            if (host == ASSET_DOMAIN) {
              val rawPath = Uri.decode(uri.path ?: "/")
              val candidates = resolveLocalAssetCandidates(rawPath)
              for (relAsset in candidates) {
                if (assetExists(context, relAsset)) {
                  val assetUri = uri.buildUpon().path("/assets/$relAsset").build()
                  val response = assetLoader.shouldInterceptRequest(assetUri)
                  if (response != null) {
                    normalizeMimeTypeIfNeeded(relAsset, response)
                    attachCorsHeaders(response)
                    return response
                  }
                }
              }

              // Prevent WebView from logging FileNotFoundException or doing DNS resolution on missing assets
              val status = if (rawPath.endsWith("favicon.ico", ignoreCase = true)) 204 else 404
              return emptyResponse(
                status,
                if (status == 204) "No Content" else "Not Found",
                guessMimeFromPath(rawPath),
              )
            }

            // Offline isolation: try local offline_vendor mirror or direct www/ fallback before stubbing
            val offlineMirrored = tryLoadMirroredVendorAsset(context, assetLoader, uri)
            if (offlineMirrored != null) {
              normalizeMimeTypeIfNeeded(uri.path, offlineMirrored)
              attachCorsHeaders(offlineMirrored)
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
            if (request.hasGesture() && (url.scheme == "http" || url.scheme == "https")) {
              runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, url))
              }
              return true
            }
            return true
          }

          override fun onPageCommitVisible(view: WebView, url: String?) {
            super.onPageCommitVisible(view, url)
            view.evaluateJavascript(translatorScript, null)
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

  internal fun resolveLocalAssetCandidates(rawPath: String): List<String> {
    val clean = rawPath.trimStart('/')
    if (clean.isEmpty()) return listOf("www/index.html")
    val out = ArrayList<String>(3)
    if (clean.startsWith("assets/www/")) {
      out += clean.removePrefix("assets/")
    } else if (clean.startsWith("assets/")) {
      // Root-relative Vite bundle requests like /assets/chunk.js or /assets/rigs/human.glb live under www/assets/
      out += "www/$clean"
      out += clean.removePrefix("assets/")
    } else if (clean.startsWith("www/")) {
      out += clean
    } else {
      out += "www/$clean"
    }
    return out
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
    val path = Uri.decode(externalUri.path ?: "").trimStart('/')
    if (path.isEmpty()) return null
    val candidatePaths =
      listOf(
        "www/offline_vendor/$host/$path",
        "www/$path",
      )
    for (relAsset in candidatePaths) {
      if (!assetExists(context, relAsset)) continue
      val mirroredUri =
        Uri.Builder()
          .scheme("https")
          .authority(ASSET_DOMAIN)
          .path("/assets/$relAsset")
          .build()
      val response = assetLoader.shouldInterceptRequest(mirroredUri)
      if (response != null) return response
    }
    return null
  }

  private fun attachCorsHeaders(response: WebResourceResponse) {
    val merged = HashMap<String, String>()
    response.responseHeaders?.let { merged.putAll(it) }
    merged.putAll(CORS_HEADERS)
    response.responseHeaders = merged
  }

  private fun emptyResponse(statusCode: Int, reason: String, mimeType: String): WebResourceResponse {
    val headers = HashMap<String, String>(CORS_HEADERS)
    headers["Cache-Control"] = "no-store"
    return WebResourceResponse(
      mimeType,
      "UTF-8",
      statusCode,
      reason,
      headers,
      ByteArrayInputStream(ByteArray(0)),
    )
  }

  internal fun guessMimeFromPath(path: String?): String {
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
      lowerPath.endsWith(".dae") -> "model/vnd.collada+xml"
      lowerPath.endsWith(".obj") -> "text/plain"
      lowerPath.endsWith(".fbx") || lowerPath.endsWith(".bin") -> "application/octet-stream"
      lowerPath.endsWith(".zip") -> "application/zip"
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
        lowerPath.endsWith(".glb") -> "model/gltf-binary"
        lowerPath.endsWith(".gltf") -> "model/gltf+json"
        lowerPath.endsWith(".dae") -> "model/vnd.collada+xml"
        else -> null
      }

    if (overrideMime != null && (currentMime.isNullOrBlank() || currentMime == "application/octet-stream")) {
      response.mimeType = overrideMime
    }
  }
}
