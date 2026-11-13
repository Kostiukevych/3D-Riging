package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.Vec3
import com.example.facerig.CameraPreset
import com.example.facerig.CharacterRigEngine
import com.example.facerig.DebugAnimationType
import com.example.facerig.FaceDebugSnapshot
import com.example.facerig.FaceMarkers
import com.example.facerig.FaceRigAdjustment
import com.example.facerig.FaceRigGlSurfaceView
import com.example.facerig.ProjectedMarker
import kotlin.math.roundToInt

private enum class StudioSubTab {
  RIG_SETUP,
  ANIMATION,
}

private enum class FineTuneSubMode {
  MARKERS,
  BBOX,
}

@Composable
fun FaceRigDebugScreen(
  engine: CharacterRigEngine,
  snapshot: FaceDebugSnapshot,
  rigAdjustment: FaceRigAdjustment,
  isImportingModel: Boolean,
  modelLoadError: String?,
  onSnapshotUpdate: (FaceDebugSnapshot) -> Unit,
  onRigAdjustmentChange: (FaceRigAdjustment) -> Unit,
  onMarkersChange: (FaceMarkers) -> Unit,
  onMarkerDragDelta: (Int, Vec3, Boolean) -> Unit,
  onMarkerPlaceAt: (Int, Vec3, Boolean) -> Unit,
  onResetMarkersToAuto: () -> Unit,
  onLoadCustomModel: () -> Unit,
  onResetToTestHead: () -> Unit,
  onDismissModelError: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  var activeSubTab by remember { mutableStateOf(StudioSubTab.RIG_SETUP) }
  var showSkeleton by remember { mutableStateOf(true) }
  var showWireframe by remember { mutableStateOf(false) }
  var symmetryEnabled by remember { mutableStateOf(true) }
  var surfaceSnapEnabled by remember { mutableStateOf(true) }
  var showFineTune by remember { mutableStateOf(false) }
  var fineTuneSubMode by remember { mutableStateOf(FineTuneSubMode.MARKERS) }
  var selectedMarkerIndex by remember { mutableIntStateOf(FaceMarkers.IDX_NOSE) }
  var headSway by remember { mutableStateOf(engine.headMotionEnabled) }
  var projectedMarkers by remember { mutableStateOf<List<ProjectedMarker>>(emptyList()) }

  val currentSymmetry by rememberUpdatedState(symmetryEnabled)
  val currentDragCallback by rememberUpdatedState(onMarkerDragDelta)
  val currentPlaceCallback by rememberUpdatedState(onMarkerPlaceAt)

  val glSurfaceView =
    remember(engine) {
      FaceRigGlSurfaceView(
        context = context,
        engine = engine,
        onSnapshot = onSnapshotUpdate,
        onMarkerSelected = { idx -> selectedMarkerIndex = idx },
        onMarkerDragged = { idx, delta -> currentDragCallback(idx, delta, currentSymmetry) },
        onMarkerPlaced = { idx, target -> currentPlaceCallback(idx, target, currentSymmetry) },
        onMarkersProjected = { list -> projectedMarkers = list },
      )
    }

  LaunchedEffect(activeSubTab, surfaceSnapEnabled, selectedMarkerIndex) {
    val isSetup = activeSubTab == StudioSubTab.RIG_SETUP
    glSurfaceView.showMarkers = isSetup
    glSurfaceView.editMarkersMode = isSetup
    glSurfaceView.surfaceSnapEnabled = surfaceSnapEnabled
    glSurfaceView.selectedMarkerIndex = selectedMarkerIndex
  }

  DisposableEffect(lifecycleOwner, glSurfaceView) {
    val observer =
      LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_RESUME -> glSurfaceView.onResume()
          Lifecycle.Event.ON_PAUSE -> glSurfaceView.onPause()
          else -> Unit
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    AndroidView(
      factory = { glSurfaceView },
      modifier = Modifier.fillMaxSize().testTag("facerig_gl_viewport"),
    )

    // 2D projected labels next to 3D landmark handles in Rig Setup mode
    if (activeSubTab == StudioSubTab.RIG_SETUP && projectedMarkers.isNotEmpty()) {
      val shortLabels =
        listOf(
          stringResource(R.string.facerig_marker_eye_l),
          stringResource(R.string.facerig_marker_eye_r),
          stringResource(R.string.facerig_marker_nose),
          stringResource(R.string.facerig_marker_mouth_l),
          stringResource(R.string.facerig_marker_mouth_r),
          stringResource(R.string.facerig_marker_chin),
          stringResource(R.string.facerig_marker_brow_l),
          stringResource(R.string.facerig_marker_brow_r),
          stringResource(R.string.facerig_marker_ear_l),
          stringResource(R.string.facerig_marker_ear_r),
        )
      Box(modifier = Modifier.fillMaxSize()) {
        projectedMarkers.forEach { pm ->
          if (!pm.visible || pm.index !in shortLabels.indices) return@forEach
          // When symmetry is ON, show labels for left + center points plus selected point to keep view clean
          val isMirrorSecondary = symmetryEnabled && pm.index in listOf(
            FaceMarkers.IDX_EYE_R,
            FaceMarkers.IDX_MOUTH_R,
            FaceMarkers.IDX_BROW_R,
            FaceMarkers.IDX_EAR_R,
          )
          val isSelected = pm.index == selectedMarkerIndex
          if (isMirrorSecondary && !isSelected) return@forEach

          val displayLabel =
            if (symmetryEnabled) {
              shortLabels[pm.index].removeSuffix(" L").removeSuffix(" R")
            } else {
              shortLabels[pm.index]
            }

          Surface(
            modifier =
              Modifier.offset {
                IntOffset(
                  x = (pm.screenX + 14f).roundToInt(),
                  y = (pm.screenY - 26f).roundToInt(),
                )
              },
            shape = RoundedCornerShape(8.dp),
            color =
              if (isSelected) {
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.94f)
              } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.80f)
              },
            border =
              BorderStroke(
                width = 1.dp,
                color =
                  if (isSelected) {
                    MaterialTheme.colorScheme.onTertiary
                  } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                  },
              ),
          ) {
            Text(
              text = displayLabel,
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              color =
                if (isSelected) {
                  MaterialTheme.colorScheme.onTertiary
                } else {
                  MaterialTheme.colorScheme.onSurface
                },
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }
      }
    }

    // Top overlay: compact HUD + Camera Presets on left, Action toolbar on right
    Row(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .fillMaxWidth()
          .padding(10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Top,
    ) {
      Column(
        modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
          tonalElevation = 4.dp,
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector =
                  if (snapshot.validationErrors.isEmpty()) {
                    Icons.Rounded.CheckCircle
                  } else {
                    Icons.Rounded.WarningAmber
                  },
                contentDescription = stringResource(R.string.cd_validator_status),
                tint =
                  if (snapshot.validationErrors.isEmpty()) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.error
                  },
                modifier = Modifier.size(16.dp),
              )
              Text(
                text =
                  if (snapshot.validationErrors.isEmpty()) {
                    stringResource(
                      R.string.facerig_validator_ok,
                      snapshot.headBoneCount,
                      snapshot.vertexCount,
                    )
                  } else {
                    stringResource(R.string.facerig_validator_errors, snapshot.validationErrors.size)
                  },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }

            if (activeSubTab == StudioSubTab.ANIMATION) {
              val boneLabel =
                when {
                  snapshot.selectedAnimation == DebugAnimationType.BONE_TEST -> {
                    stringResource(R.string.facerig_active_bone_format, snapshot.activeBoneName)
                  }
                  snapshot.activeExpressionName != null -> {
                    stringResource(
                      R.string.facerig_active_expr_format,
                      snapshot.activeExpressionName,
                      snapshot.activeBoneName,
                    )
                  }
                  else -> {
                    stringResource(
                      R.string.facerig_anim_bone_hint,
                      localizedAnimationLabel(snapshot.selectedAnimation),
                      snapshot.activeBoneName,
                    )
                  }
                }
              Text(
                text = boneLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag("active_bone_label"),
              )
            } else {
              Text(
                text =
                  if (snapshot.hasManualMarkers) {
                    stringResource(R.string.facerig_fit_mode_manual)
                  } else {
                    stringResource(R.string.facerig_fit_mode_auto)
                  },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("active_bone_label"),
              )
            }
          }
        }

        // Camera quick-preset pills (Front / 3/4 / Side)
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
          tonalElevation = 4.dp,
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            TextButton(
              onClick = { glSurfaceView.setCameraPreset(CameraPreset.FRONT) },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              modifier = Modifier.heightIn(min = 48.dp).testTag("cam_front_btn"),
            ) {
              Text(stringResource(R.string.facerig_cam_front), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(
              onClick = { glSurfaceView.setCameraPreset(CameraPreset.THREE_QUARTER) },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              modifier = Modifier.heightIn(min = 48.dp).testTag("cam_three_quarter_btn"),
            ) {
              Text(stringResource(R.string.facerig_cam_three_quarter), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(
              onClick = { glSurfaceView.setCameraPreset(CameraPreset.SIDE) },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              modifier = Modifier.heightIn(min = 48.dp).testTag("cam_side_btn"),
            ) {
              Text(stringResource(R.string.facerig_cam_side), style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }

      // Top-right toolbar: Upload Model + Reset + Skeleton + Wireframe + Head Motion + Reset Camera
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 4.dp,
      ) {
        Column(
          modifier = Modifier.padding(4.dp),
          horizontalAlignment = Alignment.End,
        ) {
          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(
              onClick = onLoadCustomModel,
              enabled = !isImportingModel,
              modifier = Modifier.testTag("load_custom_model_icon_btn"),
            ) {
              Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = stringResource(R.string.cd_load_custom_model),
                tint = MaterialTheme.colorScheme.primary,
              )
            }
            if (snapshot.isCustomModel) {
              IconButton(
                onClick = onResetToTestHead,
                enabled = !isImportingModel,
                modifier = Modifier.testTag("reset_test_head_icon_btn"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.RestartAlt,
                  contentDescription = stringResource(R.string.cd_reset_test_head),
                  tint = MaterialTheme.colorScheme.tertiary,
                )
              }
            }
            IconButton(
              onClick = {
                showSkeleton = !showSkeleton
                glSurfaceView.showSkeleton = showSkeleton
              },
              modifier = Modifier.testTag("toggle_skeleton_btn"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Hub,
                contentDescription = stringResource(R.string.cd_toggle_skeleton),
                tint =
                  if (showSkeleton) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  },
              )
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(
              onClick = {
                showWireframe = !showWireframe
                glSurfaceView.showWireframe = showWireframe
              },
              modifier = Modifier.testTag("toggle_wireframe_btn"),
            ) {
              Icon(
                imageVector = Icons.Rounded.GridOn,
                contentDescription = stringResource(R.string.cd_toggle_wireframe),
                tint =
                  if (showWireframe) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  },
              )
            }
            IconButton(
              onClick = {
                headSway = !headSway
                engine.headMotionEnabled = headSway
              },
              modifier = Modifier.testTag("toggle_head_motion_btn"),
            ) {
              Icon(
                imageVector = Icons.Rounded.AccessibilityNew,
                contentDescription = stringResource(R.string.cd_toggle_head_motion),
                tint =
                  if (headSway) {
                    MaterialTheme.colorScheme.secondary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  },
              )
            }
            IconButton(
              onClick = { glSurfaceView.resetCamera() },
              modifier = Modifier.testTag("reset_camera_btn"),
            ) {
              Icon(
                imageVector = Icons.Rounded.CenterFocusStrong,
                contentDescription = stringResource(R.string.cd_reset_camera),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }

    // Center error card if custom model loading fails
    if (modelLoadError != null) {
      Surface(
        modifier =
          Modifier
            .align(Alignment.Center)
            .padding(20.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .testTag("model_error_card"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 8.dp,
      ) {
        Column(
          modifier = Modifier.padding(18.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.WarningAmber,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(24.dp),
            )
            Text(
              text = stringResource(R.string.facerig_error_title),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.error,
            )
          }
          Text(
            text = modelLoadError,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
          ) {
            TextButton(
              onClick = onDismissModelError,
              modifier = Modifier.testTag("dismiss_model_error_btn"),
            ) {
              Text(text = stringResource(R.string.facerig_error_dismiss))
            }
          }
        }
      }
    }

    if (isImportingModel) {
      Surface(
        modifier = Modifier.align(Alignment.Center),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 8.dp,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = stringResource(R.string.facerig_loading_glb),
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }

    // Bottom Dock: Guided Rig Setup vs Animation Preview
    Column(
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      AnimatedVisibility(visible = activeSubTab == StudioSubTab.RIG_SETUP && showFineTune) {
        Surface(
          modifier = Modifier.fillMaxWidth().testTag("rig_adjust_panel"),
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
          tonalElevation = 6.dp,
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                  selected = fineTuneSubMode == FineTuneSubMode.MARKERS,
                  onClick = { fineTuneSubMode = FineTuneSubMode.MARKERS },
                  label = { Text(stringResource(R.string.facerig_mode_markers)) },
                  modifier = Modifier.testTag("mode_markers_chip"),
                )
                FilterChip(
                  selected = fineTuneSubMode == FineTuneSubMode.BBOX,
                  onClick = { fineTuneSubMode = FineTuneSubMode.BBOX },
                  label = { Text(stringResource(R.string.facerig_mode_bbox)) },
                  modifier = Modifier.testTag("mode_bbox_chip"),
                )
              }
              TextButton(
                onClick = {
                  if (fineTuneSubMode == FineTuneSubMode.MARKERS) {
                    onResetMarkersToAuto()
                  } else {
                    onRigAdjustmentChange(FaceRigAdjustment())
                  }
                },
                modifier = Modifier.testTag("reset_rig_adjust_btn"),
              ) {
                Text(
                  text =
                    if (fineTuneSubMode == FineTuneSubMode.MARKERS) {
                      stringResource(R.string.facerig_marker_auto_reset)
                    } else {
                      stringResource(R.string.facerig_adjust_reset)
                    },
                )
              }
            }

            if (fineTuneSubMode == FineTuneSubMode.MARKERS) {
              val currentPts = snapshot.markers.toFullList()
              val activePt = currentPts[selectedMarkerIndex.coerceIn(0, currentPts.lastIndex)]
              val headC = engine.headCenter
              val minX = headC.x - 0.55f
              val maxX = headC.x + 0.55f
              val minY = headC.y - 0.70f
              val maxY = headC.y + 0.45f
              val minZ = headC.z - 0.65f
              val maxZ = headC.z + 0.65f

              AdjustmentSliderRow(
                label = stringResource(R.string.facerig_marker_x, activePt.x),
                value = activePt.x.coerceIn(minX, maxX),
                valueRange = minX..maxX,
                tag = "slider_marker_x",
                onValueChange = { nx ->
                  onMarkersChange(
                    snapshot.markers.withPoint(
                      index = selectedMarkerIndex,
                      point = activePt.copy(x = nx),
                      symmetric = symmetryEnabled,
                      centerX = headC.x,
                    ),
                  )
                },
              )
              AdjustmentSliderRow(
                label = stringResource(R.string.facerig_marker_y, activePt.y),
                value = activePt.y.coerceIn(minY, maxY),
                valueRange = minY..maxY,
                tag = "slider_marker_y",
                onValueChange = { ny ->
                  onMarkersChange(
                    snapshot.markers.withPoint(
                      index = selectedMarkerIndex,
                      point = activePt.copy(y = ny),
                      symmetric = symmetryEnabled,
                      centerX = headC.x,
                    ),
                  )
                },
              )
              AdjustmentSliderRow(
                label = stringResource(R.string.facerig_marker_z, activePt.z),
                value = activePt.z.coerceIn(minZ, maxZ),
                valueRange = minZ..maxZ,
                tag = "slider_marker_z",
                onValueChange = { nz ->
                  onMarkersChange(
                    snapshot.markers.withPoint(
                      index = selectedMarkerIndex,
                      point = activePt.copy(z = nz),
                      symmetric = symmetryEnabled,
                      centerX = headC.x,
                    ),
                  )
                },
              )
            } else {
              AdjustmentSliderRow(
                label = stringResource(R.string.facerig_adjust_scale, rigAdjustment.scale),
                value = rigAdjustment.scale,
                valueRange = 0.5f..1.6f,
                tag = "slider_rig_scale",
                onValueChange = { onRigAdjustmentChange(rigAdjustment.copy(scale = it)) },
              )
              AdjustmentSliderRow(
                label = stringResource(R.string.facerig_adjust_offset_x, rigAdjustment.offsetX),
                value = rigAdjustment.offsetX,
                valueRange = -0.35f..0.35f,
                tag = "slider_rig_offset_x",
                onValueChange = { onRigAdjustmentChange(rigAdjustment.copy(offsetX = it)) },
              )
              AdjustmentSliderRow(
                label = stringResource(R.string.facerig_adjust_offset_y, rigAdjustment.offsetY),
                value = rigAdjustment.offsetY,
                valueRange = -0.35f..0.35f,
                tag = "slider_rig_offset_y",
                onValueChange = { onRigAdjustmentChange(rigAdjustment.copy(offsetY = it)) },
              )
              AdjustmentSliderRow(
                label = stringResource(R.string.facerig_adjust_offset_z, rigAdjustment.offsetZ),
                value = rigAdjustment.offsetZ,
                valueRange = -0.35f..0.35f,
                tag = "slider_rig_offset_z",
                onValueChange = { onRigAdjustmentChange(rigAdjustment.copy(offsetZ = it)) },
              )
            }
          }
        }
      }

      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 6.dp,
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          // Segmented Setup / Animate switch
          SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
              selected = activeSubTab == StudioSubTab.RIG_SETUP,
              onClick = { activeSubTab = StudioSubTab.RIG_SETUP },
              shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
              icon = { Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) },
              modifier = Modifier.testTag("subtab_rig_setup"),
            ) {
              Text(stringResource(R.string.facerig_tab_setup), style = MaterialTheme.typography.labelLarge)
            }
            SegmentedButton(
              selected = activeSubTab == StudioSubTab.ANIMATION,
              onClick = { activeSubTab = StudioSubTab.ANIMATION },
              shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
              icon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)) },
              modifier = Modifier.testTag("subtab_animation"),
            ) {
              Text(stringResource(R.string.facerig_tab_animate), style = MaterialTheme.typography.labelLarge)
            }
          }

          if (activeSubTab == StudioSubTab.RIG_SETUP) {
            // Contextual guide banner explaining what & where to place
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            ) {
              Text(
                text = guideTextForMarker(selectedMarkerIndex),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
              )
            }

            // Landmark zone chips (6 clean symmetric zones or all 10 when symmetry is off)
            val zoneEntries =
              if (symmetryEnabled) {
                listOf(
                  FaceMarkers.IDX_EYE_L to stringResource(R.string.facerig_marker_eye_l).removeSuffix(" L"),
                  FaceMarkers.IDX_BROW_L to stringResource(R.string.facerig_marker_brow_l).removeSuffix(" L"),
                  FaceMarkers.IDX_NOSE to stringResource(R.string.facerig_marker_nose),
                  FaceMarkers.IDX_MOUTH_L to stringResource(R.string.facerig_marker_mouth_l).removeSuffix(" L"),
                  FaceMarkers.IDX_CHIN to stringResource(R.string.facerig_marker_chin),
                  FaceMarkers.IDX_EAR_L to stringResource(R.string.facerig_marker_ear_l).removeSuffix(" L"),
                )
              } else {
                listOf(
                  FaceMarkers.IDX_EYE_L to stringResource(R.string.facerig_marker_eye_l),
                  FaceMarkers.IDX_EYE_R to stringResource(R.string.facerig_marker_eye_r),
                  FaceMarkers.IDX_BROW_L to stringResource(R.string.facerig_marker_brow_l),
                  FaceMarkers.IDX_BROW_R to stringResource(R.string.facerig_marker_brow_r),
                  FaceMarkers.IDX_NOSE to stringResource(R.string.facerig_marker_nose),
                  FaceMarkers.IDX_MOUTH_L to stringResource(R.string.facerig_marker_mouth_l),
                  FaceMarkers.IDX_MOUTH_R to stringResource(R.string.facerig_marker_mouth_r),
                  FaceMarkers.IDX_CHIN to stringResource(R.string.facerig_marker_chin),
                  FaceMarkers.IDX_EAR_L to stringResource(R.string.facerig_marker_ear_l),
                  FaceMarkers.IDX_EAR_R to stringResource(R.string.facerig_marker_ear_r),
                )
              }

            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              zoneEntries.forEach { (idx, label) ->
                val isSelected =
                  selectedMarkerIndex == idx ||
                    (symmetryEnabled && isMirrorPair(selectedMarkerIndex, idx))
                FilterChip(
                  selected = isSelected,
                  onClick = { selectedMarkerIndex = idx },
                  label = { Text(label) },
                  modifier = Modifier.testTag("marker_chip_$idx"),
                )
              }
            }

            // Helpful toggles: Symmetry L↔R, Snap to Mesh, Auto-fit, Fine-tune sliders
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              FilterChip(
                selected = symmetryEnabled,
                onClick = { symmetryEnabled = !symmetryEnabled },
                leadingIcon = {
                  Icon(Icons.Rounded.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = { Text(stringResource(R.string.facerig_symmetry_lr)) },
                modifier = Modifier.testTag("symmetry_toggle_chip"),
              )
              FilterChip(
                selected = surfaceSnapEnabled,
                onClick = { surfaceSnapEnabled = !surfaceSnapEnabled },
                leadingIcon = {
                  Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = { Text(stringResource(R.string.facerig_surface_snap)) },
                modifier = Modifier.testTag("snap_toggle_chip"),
              )
              OutlinedButton(
                onClick = {
                  onRigAdjustmentChange(FaceRigAdjustment())
                  onResetMarkersToAuto()
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("auto_fit_btn"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
              ) {
                Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                Text(stringResource(R.string.facerig_marker_auto_reset), style = MaterialTheme.typography.labelMedium)
              }
              FilterChip(
                selected = showFineTune,
                onClick = { showFineTune = !showFineTune },
                leadingIcon = {
                  Icon(Icons.Rounded.Straighten, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = { Text(stringResource(R.string.facerig_fine_tune)) },
                modifier = Modifier.testTag("toggle_rig_adjust_btn"),
              )
            }
          } else {
            if (snapshot.isCustomModel) {
              OutlinedButton(
                onClick = onResetToTestHead,
                enabled = !isImportingModel,
                modifier = Modifier.heightIn(min = 48.dp).testTag("reset_test_head_btn"),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.RestartAlt,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp).padding(end = 4.dp),
                )
                Text(text = stringResource(R.string.facerig_btn_reset_test_head))
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              DebugAnimationType.entries.forEach { animType ->
                val selected = snapshot.selectedAnimation == animType
                val label = localizedAnimationLabel(animType)
                if (selected) {
                  Button(
                    onClick = { engine.selectAnimation(animType) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("anim_btn_${animType.key}"),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    colors =
                      ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                      ),
                  ) {
                    Text(text = label, fontWeight = FontWeight.SemiBold)
                  }
                } else {
                  OutlinedButton(
                    onClick = { engine.selectAnimation(animType) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("anim_btn_${animType.key}"),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                  ) {
                    Text(text = label)
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

private fun isMirrorPair(a: Int, b: Int): Boolean =
  (a == FaceMarkers.IDX_EYE_L && b == FaceMarkers.IDX_EYE_R) ||
    (a == FaceMarkers.IDX_EYE_R && b == FaceMarkers.IDX_EYE_L) ||
    (a == FaceMarkers.IDX_MOUTH_L && b == FaceMarkers.IDX_MOUTH_R) ||
    (a == FaceMarkers.IDX_MOUTH_R && b == FaceMarkers.IDX_MOUTH_L) ||
    (a == FaceMarkers.IDX_BROW_L && b == FaceMarkers.IDX_BROW_R) ||
    (a == FaceMarkers.IDX_BROW_R && b == FaceMarkers.IDX_BROW_L) ||
    (a == FaceMarkers.IDX_EAR_L && b == FaceMarkers.IDX_EAR_R) ||
    (a == FaceMarkers.IDX_EAR_R && b == FaceMarkers.IDX_EAR_L)

@Composable
private fun guideTextForMarker(markerIndex: Int): String =
  when (markerIndex) {
    FaceMarkers.IDX_EYE_L, FaceMarkers.IDX_EYE_R -> stringResource(R.string.facerig_guide_eye)
    FaceMarkers.IDX_NOSE -> stringResource(R.string.facerig_guide_nose)
    FaceMarkers.IDX_MOUTH_L, FaceMarkers.IDX_MOUTH_R -> stringResource(R.string.facerig_guide_mouth)
    FaceMarkers.IDX_CHIN -> stringResource(R.string.facerig_guide_chin)
    FaceMarkers.IDX_BROW_L, FaceMarkers.IDX_BROW_R -> stringResource(R.string.facerig_guide_brow)
    FaceMarkers.IDX_EAR_L, FaceMarkers.IDX_EAR_R -> stringResource(R.string.facerig_guide_ear)
    else -> stringResource(R.string.facerig_guide_nose)
  }

@Composable
private fun AdjustmentSliderRow(
  label: String,
  value: Float,
  valueRange: ClosedFloatingPointRange<Float>,
  tag: String,
  onValueChange: (Float) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.widthIn(min = 120.dp),
    )
    Slider(
      value = value,
      onValueChange = onValueChange,
      valueRange = valueRange,
      modifier = Modifier.weight(1f).testTag(tag),
    )
  }
}

@Composable
private fun localizedAnimationLabel(type: DebugAnimationType): String =
  when (type) {
    DebugAnimationType.BONE_TEST -> stringResource(R.string.facerig_anim_bone_test)
    DebugAnimationType.EXPRESSION_TEST -> stringResource(R.string.facerig_anim_expression_test)
    DebugAnimationType.IDLE -> stringResource(R.string.facerig_anim_idle)
    DebugAnimationType.TALK -> stringResource(R.string.facerig_anim_talk)
    DebugAnimationType.EMOTION_SHOW -> stringResource(R.string.facerig_anim_emotion_show)
  }
