package com.example

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.example.facerig.CharacterMeshData
import com.example.facerig.CharacterRigEngine
import com.example.facerig.CustomModelLoader
import com.example.facerig.CustomModelStorage
import com.example.facerig.FaceDebugSnapshot
import com.example.facerig.FaceRigAdjustment
import com.example.storage.ModelFileSaver
import com.example.storage.SaveResult
import com.example.ui.Mesh2MotionScreen
import com.example.ui.StudioMode
import com.example.ui.theme.Mesh2MotionTheme
import com.example.webview.BlobDownloadBridge
import com.example.webview.Mesh2MotionWebViewFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

  companion object {
    private const val TAG = "MainActivity"
  }

  private var webView: WebView? = null
  private var filePathCallback: ValueCallback<Array<Uri>>? = null
  private var pendingLegacySave: SaveResult.RequiresDocumentPicker? = null
  private var adjustJob: Job? = null

  private var activeMode by mutableStateOf(StudioMode.FACE_RIG_DEBUG)
  private var loadProgress by mutableIntStateOf(0)
  private var isLoading by mutableStateOf(true)
  private var loadError by mutableStateOf<String?>(null)
  private var showExitDialog by mutableStateOf(false)
  private var isImportingModel by mutableStateOf(false)
  private var modelLoadError by mutableStateOf<String?>(null)
  private var rigAdjustment by mutableStateOf(FaceRigAdjustment())
  private val snackbarHostState = SnackbarHostState()

  private lateinit var rigEngine: CharacterRigEngine
  private lateinit var faceDebugSnapshotState: MutableState<FaceDebugSnapshot>

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

  private val customModelPickerLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        importCustomModel(uri)
      }
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

    val (initialMesh, isCustomInitial) = loadInitialCharacterMesh()
    rigEngine = CharacterRigEngine(initialMesh, isCustomInitial)
    faceDebugSnapshotState = mutableStateOf(rigEngine.step(0f))

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
          faceDebugSnapshot = faceDebugSnapshotState.value,
          rigAdjustment = rigAdjustment,
          isImportingModel = isImportingModel,
          modelLoadError = modelLoadError,
          onFaceSnapshotUpdate = { updated -> faceDebugSnapshotState.value = updated },
          onRigAdjustmentChange = { nextAdj ->
            rigAdjustment = nextAdj
            adjustJob?.cancel()
            adjustJob =
              lifecycleScope.launch {
                val updated =
                  withContext(Dispatchers.Default) {
                    rigEngine.updateRigAdjustment(nextAdj)
                  }
                faceDebugSnapshotState.value = updated
              }
          },
          onMarkersChange = { nextMarkers ->
            adjustJob?.cancel()
            adjustJob =
              lifecycleScope.launch {
                val updated =
                  withContext(Dispatchers.Default) {
                    rigEngine.setMarkers(nextMarkers)
                  }
                faceDebugSnapshotState.value = updated
              }
          },
          onMarkerDragDelta = { markerIdx, deltaBlender ->
            adjustJob?.cancel()
            adjustJob =
              lifecycleScope.launch {
                val updated =
                  withContext(Dispatchers.Default) {
                    rigEngine.moveMarker(markerIdx, deltaBlender)
                  }
                faceDebugSnapshotState.value = updated
              }
          },
          onResetMarkersToAuto = {
            adjustJob?.cancel()
            adjustJob =
              lifecycleScope.launch {
                val updated =
                  withContext(Dispatchers.Default) {
                    rigEngine.resetMarkersToAuto()
                  }
                faceDebugSnapshotState.value = updated
              }
          },
          onLoadCustomModel = {
            modelLoadError = null
            customModelPickerLauncher.launch(
              arrayOf(
                "model/gltf-binary",
                "model/gltf+json",
                "application/octet-stream",
                "*/*",
              )
            )
          },
          onResetToTestHead = { resetToDefaultTestHead() },
          onDismissModelError = { modelLoadError = null },
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

  private fun loadInitialCharacterMesh(): Pair<CharacterMeshData, Boolean> {
    val saved = CustomModelStorage.getSavedModelFile(this)
    if (saved != null) {
      val (file, ext) = saved
      runCatching {
        CustomModelLoader.loadFromFile(file, ext)
      }.onSuccess { customMesh ->
        Log.i(TAG, "Loaded persisted custom .$ext model from ${file.absolutePath}")
        return customMesh to true
      }.onFailure { err ->
        Log.e(TAG, "Failed loading saved custom model, falling back to test head", err)
        CustomModelStorage.clearSavedModel(this)
        modelLoadError = getString(R.string.facerig_custom_model_failed, err.localizedMessage ?: ext)
      }
    }
    return CustomModelLoader.loadDefaultAssetModel(this) to false
  }

  private fun importCustomModel(uri: Uri) {
    lifecycleScope.launch {
      isImportingModel = true
      modelLoadError = null
      try {
        val (mesh, updatedSnapshot) =
          withContext(Dispatchers.IO) {
            val (savedFile, ext) = CustomModelStorage.saveFromUri(this@MainActivity, uri)
            val parsedMesh = CustomModelLoader.loadFromFile(savedFile, ext)
            val snapshot = rigEngine.replaceMesh(parsedMesh, custom = true)
            parsedMesh to snapshot
          }
        rigAdjustment = FaceRigAdjustment()
        faceDebugSnapshotState.value = updatedSnapshot
        val msg = getString(R.string.facerig_custom_model_loaded, mesh.restPositions.size / 3)
        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        snackbarHostState.showSnackbar(msg)
      } catch (e: Exception) {
        Log.e(TAG, "Error importing custom 3D model", e)
        CustomModelStorage.clearSavedModel(this@MainActivity)
        val detail = e.localizedMessage ?: "Неизвестная ошибка формата"
        modelLoadError = getString(R.string.facerig_custom_model_failed, detail)
      } finally {
        isImportingModel = false
      }
    }
  }

  private fun resetToDefaultTestHead() {
    lifecycleScope.launch {
      isImportingModel = true
      modelLoadError = null
      try {
        val snapshot =
          withContext(Dispatchers.IO) {
            CustomModelStorage.clearSavedModel(this@MainActivity)
            val defaultMesh = CustomModelLoader.loadDefaultAssetModel(this@MainActivity)
            rigEngine.replaceMesh(defaultMesh, custom = false)
          }
        rigAdjustment = FaceRigAdjustment()
        faceDebugSnapshotState.value = snapshot
        val msg = getString(R.string.facerig_reset_done)
        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        snackbarHostState.showSnackbar(msg)
      } finally {
        isImportingModel = false
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
    adjustJob?.cancel()
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
