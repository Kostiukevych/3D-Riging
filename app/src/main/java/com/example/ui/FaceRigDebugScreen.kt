package com.example.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.TouchApp
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.Vec3
import com.example.facerig.CharacterRigEngine
import com.example.facerig.DebugAnimationType
import com.example.facerig.FaceDebugSnapshot
import com.example.facerig.FaceMarkers
import com.example.facerig.FaceRigAdjustment
import com.example.facerig.FaceRigGlSurfaceView

private enum class AdjustSubMode {
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
  onMarkerDragDelta: (Int, Vec3) -> Unit,
  onResetMarkersToAuto: () -> Unit,
  onLoadCustomModel: () -> Unit,
  onResetToTestHead: () -> Unit,
  onDismissModelError: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  var showSkeleton by remember { mutableStateOf(true) }
  var showWireframe by remember { mutableStateOf(false) }
  var showAdjustPanel by remember { mutableStateOf(false) }
  var adjustSubMode by remember { mutableStateOf(AdjustSubMode.MARKERS) }
  var selectedMarkerIndex by remember { mutableIntStateOf(2) } // default: noseTip
  var editMarkersOn3D by remember { mutableStateOf(false) }
  var headSway by remember { mutableStateOf(engine.headMotionEnabled) }

  val glSurfaceView =
    remember(engine) {
      FaceRigGlSurfaceView(
        context = context,
        engine = engine,
        onSnapshot = onSnapshotUpdate,
        onMarkerSelected = { idx -> selectedMarkerIndex = idx },
        onMarkerDragged = { idx, delta -> onMarkerDragDelta(idx, delta) },
      )
    }

  LaunchedEffect(showAdjustPanel, adjustSubMode, editMarkersOn3D, selectedMarkerIndex) {
    val markersVisible = showAdjustPanel && adjustSubMode == AdjustSubMode.MARKERS
    glSurfaceView.showMarkers = markersVisible
    glSurfaceView.editMarkersMode = markersVisible && editMarkersOn3D
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

    // Top overlay row: Left status HUD + Right action icons
    Row(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .fillMaxWidth()
          .padding(10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Top,
    ) {
      Surface(
        modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 4.dp,
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(3.dp),
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

          Text(
            text =
              if (snapshot.isHeadChildVerified) {
                stringResource(R.string.facerig_parent_verified)
              } else {
                stringResource(R.string.facerig_parent_missing)
              },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
          )

          Text(
            text =
              if (snapshot.hasManualMarkers) {
                stringResource(R.string.facerig_fit_mode_manual)
              } else {
                stringResource(R.string.facerig_fit_mode_auto)
              },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          if (snapshot.detectedHeadBoneName != null) {
            Text(
              text = stringResource(R.string.facerig_head_bone_detected, snapshot.detectedHeadBoneName),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

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
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.testTag("active_bone_label"),
          )
        }
      }

      // Top-right toolbar: Upload Model + Reset + Fit Sliders/Markers + Viewport icons
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
              onClick = { showAdjustPanel = !showAdjustPanel },
              modifier = Modifier.testTag("toggle_rig_adjust_btn"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = stringResource(R.string.cd_toggle_rig_adjust),
                tint =
                  if (showAdjustPanel) {
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

    // Bottom dock: FaceFit 6-Marker / BBox panel + Model Reset + Animation buttons
    Column(
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      AnimatedVisibility(visible = showAdjustPanel) {
        Surface(
          modifier = Modifier.fillMaxWidth().testTag("rig_adjust_panel"),
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
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
                  selected = adjustSubMode == AdjustSubMode.MARKERS,
                  onClick = { adjustSubMode = AdjustSubMode.MARKERS },
                  label = { Text(stringResource(R.string.facerig_mode_markers)) },
                  modifier = Modifier.testTag("mode_markers_chip"),
                )
                FilterChip(
                  selected = adjustSubMode == AdjustSubMode.BBOX,
                  onClick = { adjustSubMode = AdjustSubMode.BBOX },
                  label = { Text(stringResource(R.string.facerig_mode_bbox)) },
                  modifier = Modifier.testTag("mode_bbox_chip"),
                )
              }
              TextButton(
                onClick = {
                  if (adjustSubMode == AdjustSubMode.MARKERS) {
                    onResetMarkersToAuto()
                  } else {
                    onRigAdjustmentChange(FaceRigAdjustment())
                  }
                },
                modifier = Modifier.testTag("reset_rig_adjust_btn"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.AutoFixHigh,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp).padding(end = 4.dp),
                )
                Text(
                  text =
                    if (adjustSubMode == AdjustSubMode.MARKERS) {
                      stringResource(R.string.facerig_marker_auto_reset)
                    } else {
                      stringResource(R.string.facerig_adjust_reset)
                    },
                )
              }
            }

            if (adjustSubMode == AdjustSubMode.MARKERS) {
              val markerLabels =
                listOf(
                  stringResource(R.string.facerig_marker_eye_l),
                  stringResource(R.string.facerig_marker_eye_r),
                  stringResource(R.string.facerig_marker_nose),
                  stringResource(R.string.facerig_marker_mouth_l),
                  stringResource(R.string.facerig_marker_mouth_r),
                  stringResource(R.string.facerig_marker_chin),
                )

              Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                FilterChip(
                  selected = editMarkersOn3D,
                  onClick = { editMarkersOn3D = !editMarkersOn3D },
                  leadingIcon = {
                    Icon(
                      imageVector = Icons.Rounded.TouchApp,
                      contentDescription = null,
                      modifier = Modifier.size(16.dp),
                    )
                  },
                  label = { Text(stringResource(R.string.facerig_marker_drag_toggle)) },
                  modifier = Modifier.testTag("marker_drag_3d_chip"),
                )
                markerLabels.forEachIndexed { idx, label ->
                  FilterChip(
                    selected = selectedMarkerIndex == idx,
                    onClick = { selectedMarkerIndex = idx },
                    label = { Text(label) },
                    modifier = Modifier.testTag("marker_chip_$idx"),
                  )
                }
              }

              val currentPts = snapshot.markers.toList()
              val activePt = currentPts[selectedMarkerIndex.coerceIn(0, 5)]
              val headC = engine.headCenter
              val minX = headC.x - 0.50f
              val maxX = headC.x + 0.50f
              val minY = headC.y - 0.65f
              val maxY = headC.y + 0.25f
              val minZ = headC.z - 0.60f
              val maxZ = headC.z + 0.60f

              AdjustmentSliderRow(
                label = stringResource(R.string.facerig_marker_x, activePt.x),
                value = activePt.x.coerceIn(minX, maxX),
                valueRange = minX..maxX,
                tag = "slider_marker_x",
                onValueChange = { nx ->
                  val updated = currentPts.toMutableList()
                  updated[selectedMarkerIndex] = activePt.copy(x = nx)
                  onMarkersChange(FaceMarkers.fromList(updated))
                },
              )
              AdjustmentSliderRow(
                label = stringResource(R.string.facerig_marker_y, activePt.y),
                value = activePt.y.coerceIn(minY, maxY),
                valueRange = minY..maxY,
                tag = "slider_marker_y",
                onValueChange = { ny ->
                  val updated = currentPts.toMutableList()
                  updated[selectedMarkerIndex] = activePt.copy(y = ny)
                  onMarkersChange(FaceMarkers.fromList(updated))
                },
              )
              AdjustmentSliderRow(
                label = stringResource(R.string.facerig_marker_z, activePt.z),
                value = activePt.z.coerceIn(minZ, maxZ),
                valueRange = minZ..maxZ,
                tag = "slider_marker_z",
                onValueChange = { nz ->
                  val updated = currentPts.toMutableList()
                  updated[selectedMarkerIndex] = activePt.copy(z = nz)
                  onMarkersChange(FaceMarkers.fromList(updated))
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 6.dp,
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

          Text(
            text = stringResource(R.string.facerig_animations_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

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
                  Text(
                    text = label,
                    fontWeight = FontWeight.SemiBold,
                  )
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
