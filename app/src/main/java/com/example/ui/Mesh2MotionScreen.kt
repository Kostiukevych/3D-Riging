package com.example.ui

import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.Vec3
import com.example.facerig.CharacterRigEngine
import com.example.facerig.FaceDebugSnapshot
import com.example.facerig.FaceMarkers
import com.example.facerig.FaceRigAdjustment

enum class StudioMode {
  FACE_RIG_DEBUG,
  WEB_STUDIO,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Mesh2MotionScreen(
  activeMode: StudioMode,
  onModeSelected: (StudioMode) -> Unit,
  rigEngine: CharacterRigEngine,
  faceDebugSnapshot: FaceDebugSnapshot,
  rigAdjustment: FaceRigAdjustment,
  isImportingModel: Boolean,
  modelLoadError: String?,
  onFaceSnapshotUpdate: (FaceDebugSnapshot) -> Unit,
  onRigAdjustmentChange: (FaceRigAdjustment) -> Unit,
  onMarkersChange: (FaceMarkers) -> Unit,
  onMarkerDragDelta: (Int, Vec3, Boolean) -> Unit,
  onMarkerPlaceAt: (Int, Vec3, Boolean) -> Unit,
  onResetMarkersToAuto: () -> Unit,
  onLoadCustomModel: () -> Unit,
  onResetToTestHead: () -> Unit,
  onDismissModelError: () -> Unit,
  webView: WebView,
  loadProgress: Int,
  isLoading: Boolean,
  loadError: String?,
  showExitDialog: Boolean,
  snackbarHostState: SnackbarHostState,
  onReload: () -> Unit,
  onConfirmExit: () -> Unit,
  onDismissExit: () -> Unit,
) {
  Scaffold(
    modifier =
      Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing),
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      PrimaryTabRow(
        selectedTabIndex = activeMode.ordinal,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
      ) {
        Tab(
          selected = activeMode == StudioMode.FACE_RIG_DEBUG,
          onClick = { onModeSelected(StudioMode.FACE_RIG_DEBUG) },
          modifier = Modifier.testTag("tab_face_rig_debug"),
          text = { Text(stringResource(R.string.tab_face_rig_debug)) },
          icon = {
            Icon(
              imageVector = Icons.Rounded.Face,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
          },
        )
        Tab(
          selected = activeMode == StudioMode.WEB_STUDIO,
          onClick = { onModeSelected(StudioMode.WEB_STUDIO) },
          modifier = Modifier.testTag("tab_web_studio"),
          text = { Text(stringResource(R.string.tab_web_studio)) },
          icon = {
            Icon(
              imageVector = Icons.Rounded.Public,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
          },
        )
      }
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding),
    ) {
      if (activeMode == StudioMode.FACE_RIG_DEBUG) {
        FaceRigDebugScreen(
          engine = rigEngine,
          snapshot = faceDebugSnapshot,
          rigAdjustment = rigAdjustment,
          isImportingModel = isImportingModel,
          modelLoadError = modelLoadError,
          onSnapshotUpdate = onFaceSnapshotUpdate,
          onRigAdjustmentChange = onRigAdjustmentChange,
          onMarkersChange = onMarkersChange,
          onMarkerDragDelta = onMarkerDragDelta,
          onMarkerPlaceAt = onMarkerPlaceAt,
          onResetMarkersToAuto = onResetMarkersToAuto,
          onLoadCustomModel = onLoadCustomModel,
          onResetToTestHead = onResetToTestHead,
          onDismissModelError = onDismissModelError,
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        WebStudioContent(
          webView = webView,
          loadProgress = loadProgress,
          isLoading = isLoading,
          loadError = loadError,
          onReload = onReload,
        )
      }
    }
  }

  if (showExitDialog) {
    AlertDialog(
      onDismissRequest = onDismissExit,
      title = { Text(text = stringResource(R.string.exit_dialog_title)) },
      text = { Text(text = stringResource(R.string.exit_dialog_message)) },
      confirmButton = {
        Button(
          onClick = onConfirmExit,
          modifier = Modifier.testTag("exit_confirm_button"),
        ) {
          Text(text = stringResource(R.string.exit_dialog_confirm))
        }
      },
      dismissButton = {
        TextButton(
          onClick = onDismissExit,
          modifier = Modifier.testTag("exit_cancel_button"),
        ) {
          Text(text = stringResource(R.string.exit_dialog_cancel))
        }
      },
    )
  }
}

@Composable
private fun WebStudioContent(
  webView: WebView,
  loadProgress: Int,
  isLoading: Boolean,
  loadError: String?,
  onReload: () -> Unit,
) {
  Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
      factory = { webView },
      modifier = Modifier.fillMaxSize().testTag("webview_container"),
    )

    AnimatedVisibility(
      visible = isLoading && loadError == null,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.align(Alignment.TopCenter),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
          progress = { (loadProgress.coerceIn(0, 100)) / 100f },
          modifier = Modifier.fillMaxWidth().height(3.dp).testTag("studio_loading_bar"),
          color = MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
      }
    }

    if (loadError != null) {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Surface(
          modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 4.dp,
        ) {
          Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.WarningAmber,
              contentDescription = stringResource(R.string.cd_studio_badge),
              tint = MaterialTheme.colorScheme.tertiary,
              modifier = Modifier.size(44.dp),
            )
            Text(
              text = stringResource(R.string.error_loading_title),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.SemiBold,
              textAlign = TextAlign.Center,
            )
            Text(
              text = stringResource(R.string.error_loading_subtitle),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
            Text(
              text = loadError,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.error,
              textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
              onClick = onReload,
              modifier = Modifier.testTag("reload_button"),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primary,
                  contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Refresh,
                  contentDescription = stringResource(R.string.cd_reload_studio),
                  modifier = Modifier.size(18.dp),
                )
                Text(text = stringResource(R.string.action_reload))
              }
            }
          }
        }
      }
    }

    if (isLoading && loadProgress < 25 && loadError == null) {
      Surface(
        modifier =
          Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 24.dp),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        tonalElevation = 6.dp,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.ViewInAr,
            contentDescription = stringResource(R.string.cd_studio_badge),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = stringResource(R.string.loading_studio),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }
  }
}
