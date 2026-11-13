package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.facerig.CharacterObjLoader
import com.example.facerig.CharacterRigEngine
import com.example.facerig.DebugAnimationType
import com.example.storage.ModelFileSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Mesh2Motion", appName)
  }

  @Test
  fun `sanitize filename preserves glb extension and cleans special chars`() {
    val cleaned = ModelFileSaver.sanitizeFileName("hero rig (final)!.glb", "model/gltf-binary")
    assertEquals("hero_rig__final__.glb", cleaned)

    val inferred = ModelFileSaver.sanitizeFileName("rigged_avatar", "model/gltf-binary")
    assertEquals("rigged_avatar.glb", inferred)
  }

  @Test
  fun `character rig engine validates face rig, attaches face to head, fits and skins mesh`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val mesh = CharacterObjLoader.loadFromAssets(context)
    val engine = CharacterRigEngine(mesh)

    assertTrue(engine.validationErrors.isEmpty())
    val faceBone = engine.skeleton.first { it.name == "face" }
    assertEquals("head", faceBone.parentName)

    val snapshot1 = engine.step(0.016f)
    assertTrue(snapshot1.isHeadChildVerified)
    assertFalse(snapshot1.activeBoneName.isBlank())

    engine.selectAnimation(DebugAnimationType.TALK)
    val snapshot2 = engine.step(0.033f)
    assertEquals(DebugAnimationType.TALK, snapshot2.selectedAnimation)
  }
}
