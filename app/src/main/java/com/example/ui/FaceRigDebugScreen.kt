package com.example.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import com.example.facerig.CharacterRigEngine
import com.example.facerig.DebugAnimationType
import com.example.facerig.FaceDebugSnapshot
import com.example.facerig.FaceRigGlSurfaceView

@Composable
fun FaceRigDebugScreen(
  engine: CharacterRigEngine,
  snapshot: FaceDebugSnapshot,
  onSnapshotUpdate: (FaceDebugSnapshot) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  var showSkeleton by remember { mutableStateOf(true) }
  var showWireframe by remember { mutableStateOf(false) }
  var headSway by remember { mutableStateOf(engine.headMotionEnabled) }

  val glSurfaceView =
    remember(engine) {
      FaceRigGlSurfaceView(
        context = context,
        engine = engine,
        onSnapshot = onSnapshotUpdate,
      )
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

    // Top-left HUD: active bone from BoneTestAnimation.activeBone() + Validator & Hierarchy status
    Surface(
      modifier =
        Modifier
          .align(Alignment.TopStart)
          .padding(12.dp),
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
      tonalElevation = 4.dp,
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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

        val boneLabel =
          if (snapshot.selectedAnimation == DebugAnimationType.BONE_TEST) {
            stringResource(R.string.facerig_active_bone_format, snapshot.activeBoneName)
          } else if (snapshot.activeExpressionName != null) {
            stringResource(
              R.string.facerig_active_expr_format,
              snapshot.activeExpressionName,
              snapshot.activeBoneName,
            )
          } else {
            stringResource(
              R.string.facerig_anim_bone_hint,
              snapshot.selectedAnimation.key,
              snapshot.activeBoneName,
            )
          }

        Text(
          text = boneLabel,
          style = MaterialTheme.typography.bodyMedium,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.tertiary,
          modifier = Modifier.testTag("active_bone_label"),
        )
      }
    }

    // Top-right viewport controls
    Surface(
      modifier =
        Modifier
          .align(Alignment.TopEnd)
          .padding(12.dp),
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
      tonalElevation = 4.dp,
    ) {
      Row(
        modifier = Modifier.padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
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

    // Bottom animation selector dock
    Surface(
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .padding(12.dp),
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
      tonalElevation = 6.dp,
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
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
                  text = animType.key,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.SemiBold,
                )
              }
            } else {
              OutlinedButton(
                onClick = { engine.selectAnimation(animType) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("anim_btn_${animType.key}"),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
              ) {
                Text(
                  text = animType.key,
                  fontFamily = FontFamily.Monospace,
                )
              }
            }
          }
        }
      }
    }
  }
}
