package com.example

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.example.facerig.CharacterObjLoader
import com.example.facerig.CharacterRigEngine
import com.example.facerig.FaceDebugSnapshot
import com.example.storage.ModelFileSaver
import com.example.storage.SaveResult
import com.example.ui.Mesh2MotionScreen
import com.example.ui.StudioMode
import com.example.ui.theme.Mesh2MotionTheme
import com.example.webview.BlobDownloadBridge
import com.example.webview.Mesh2MotionWebViewFactory
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private var webView: WebView? = null
  private var filePathCallback: ValueCallback<Array<Uri>>? = null
  private var pendingLegacySave: SaveResult.RequiresDocumentPicker? = null

  private var activeMode by mutableStateOf(StudioMode.FACE_RIG_DEBUG)
  private var loadProgress by mutableIntStateOf(0)
  private var isLoading by mutableStateOf(true)
  private var loadError by mutableStateOf<String?>(null)
  private var showExitDialog by mutableStateOf(false)
  private val snackbarHostState = SnackbarHostState()

  private lateinit var rigEngine: CharacterRigEngine
  private lateinit var faceDebugSnapshot: FaceDebugSnapshot

  private val fileChooserLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val callback = filePathCallback
      filePathCallback = null
      if (callback == null) return@registerForActivityResult

      if (result.resultCode != Activity.RESULT_OK || result.data == null) {
        callback.onReceiveValue(null)
        return@registerForActivityResult
      }

      val uris = extractSelectedUris(result.data!!)
      callback.onReceiveValue(if (uris.isNotEmpty()) uris else null)
    }

  private val legacyDocumentSaverLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
      val pending = pendingLegacySave
      pendingLegacySave = null
      if (uri == null || pending == null) return@registerForActivityResult

      lifecycleScope.launch {
        val result =
          ModelFileSaver.writeBytesToUri(
            context = this@MainActivity,
            targetUri = uri,
            bytes = pending.bytes,
            fileName = pending.fileName,
          )
        handleSaveResult(result)
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )

    // Load character model, run FaceRigValidator.validate(), fitTo(), and skinWeights()
    val characterMesh = CharacterObjLoader.loadFromAssets(this)
    rigEngine = CharacterRigEngine(characterMesh)
    var snapshotState by mutableStateOf(rigEngine.step(0f))
    faceDebugSnapshot = snapshotState

    val blobBridge =
      BlobDownloadBridge(
        onBase64Received = { base64, fileName, mimeType ->
          lifecycleScope.launch {
            val result =
              ModelFileSaver.saveBase64Export(
                context = this@MainActivity,
                base64Payload = base64,
                rawFileName = fileName,
                rawMimeType = mimeType,
              )
            handleSaveResult(result)
          }
        },
        onError = { message ->
          lifecycleScope.launch {
            val formatted = getString(R.string.download_failed_toast, message)
            Toast.makeText(this@MainActivity, formatted, Toast.LENGTH_LONG).show()
            snackbarHostState.showSnackbar(formatted)
          }
        },
      )

    val createdWebView =
      Mesh2MotionWebViewFactory.create(
        context = this,
        bridge = blobBridge,
        onProgressChanged = { progress ->
          loadProgress = progress
          if (progress >= 100) {
            isLoading = false
          }
        },
        onPageLoaded = {
          isLoading = false
        },
        onLoadError = { reason ->
          isLoading = false
          loadError = reason
        },
        onShowFileChooser = { callback, intent ->
          filePathCallback?.onReceiveValue(null)
          filePathCallback = callback
          return@create try {
            fileChooserLauncher.launch(
              Intent.createChooser(intent, getString(R.string.file_chooser_title))
            )
            true
          } catch (e: Exception) {
            filePathCallback = null
            callback.onReceiveValue(null)
            false
          }
        },
      )
    webView = createdWebView

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          val view = webView
          when {
            showExitDialog -> showExitDialog = false
            activeMode == StudioMode.WEB_STUDIO && view != null && view.canGoBack() -> view.goBack()
            else -> showExitDialog = true
          }
        }
      },
    )

    setContent {
      Mesh2MotionTheme {
        Mesh2MotionScreen(
          activeMode = activeMode,
          onModeSelected = { activeMode = it },
          rigEngine = rigEngine,
          faceDebugSnapshot = snapshotState,
          onFaceSnapshotUpdate = { updated ->
            snapshotState = updated
            faceDebugSnapshot = updated
          },
          webView = createdWebView,
          loadProgress = loadProgress,
          isLoading = isLoading,
          loadError = loadError,
          showExitDialog = showExitDialog,
          snackbarHostState = snackbarHostState,
          onReload = {
            loadError = null
            isLoading = true
            loadProgress = 0
            createdWebView.loadUrl(Mesh2MotionWebViewFactory.ENTRY_URL)
          },
          onConfirmExit = {
            showExitDialog = false
            finish()
          },
          onDismissExit = { showExitDialog = false },
        )
      }
    }
  }

  private suspend fun handleSaveResult(result: SaveResult) {
    when (result) {
      is SaveResult.Saved -> {
        val message = getString(R.string.download_saved_toast, result.fileName)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        snackbarHostState.showSnackbar(message)
      }
      is SaveResult.RequiresDocumentPicker -> {
        pendingLegacySave = result
        legacyDocumentSaverLauncher.launch(result.fileName)
      }
      is SaveResult.Failed -> {
        val message = getString(R.string.download_failed_toast, result.reason)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        snackbarHostState.showSnackbar(message)
      }
    }
  }

  private fun extractSelectedUris(data: Intent): Array<Uri> {
    val uris = mutableListOf<Uri>()
    data.data?.let { uris.add(it) }
    val clipData = data.clipData
    if (clipData != null) {
      for (i in 0 until clipData.itemCount) {
        clipData.getItemAt(i)?.uri?.let { uri ->
          if (!uris.contains(uri)) {
            uris.add(uri)
          }
        }
      }
    }
    return uris.toTypedArray()
  }

  override fun onPause() {
    super.onPause()
    webView?.onPause()
  }

  override fun onResume() {
    super.onResume()
    webView?.onResume()
  }

  override fun onDestroy() {
    filePathCallback?.onReceiveValue(null)
    filePathCallback = null
    webView?.apply {
      stopLoading()
      removeJavascriptInterface(BlobDownloadBridge.INTERFACE_NAME)
      destroy()
    }
    webView = null
    super.onDestroy()
  }
}
