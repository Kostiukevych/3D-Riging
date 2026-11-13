package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.facerig.CharacterObjLoader
import com.example.facerig.CharacterRigEngine
import com.example.facerig.CustomModelLoader
import com.example.facerig.DebugAnimationType
import com.example.facerig.FaceFit
import com.example.facerig.FaceMarkers
import com.example.facerig.FaceRigAdjustment
import com.example.facerig.FbxMeshLoader
import com.example.facerig.GlbMeshLoader
import com.example.facerig.blenderToGltf
import com.example.facerig.gltfToBlender
import com.example.storage.ModelFileSaver
import com.example.webview.Mesh2MotionTranslator
import com.example.webview.Mesh2MotionWebViewFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    assertEquals("3D Ot Dimona", appName)
    val dictJson = Mesh2MotionTranslator.loadDictionaryJson(context)
    val dict = JSONObject(dictJson)
    assertEquals("Обзор", dict.getString("Explore"))
    assertEquals("Редактировать скелет", dict.getString("Edit Skeleton"))
    assertEquals("Экспорт GLB", dict.getString("Export GLB"))
    assertTrue(dict.length() >= 200)
    val script = Mesh2MotionTranslator.buildInjectScript(dictJson)
    assertTrue(script.contains("MutationObserver"))
    assertTrue(script.contains("Шаг "))
  }

  @Test
  fun `sanitize filename preserves glb extension and cleans special chars`() {
    val cleaned = ModelFileSaver.sanitizeFileName("hero rig (final)!.glb", "model/gltf-binary")
    assertEquals("hero_rig__final__.glb", cleaned)

    val inferred = ModelFileSaver.sanitizeFileName("rigged_avatar", "model/gltf-binary")
    assertEquals("rigged_avatar.glb", inferred)
  }

  @Test
  fun `character rig engine validates face rig, loads vertex-colored bust, and runs FaceFit auto and manual`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val defaultMesh = CustomModelLoader.loadDefaultAssetModel(context)
    assertTrue(defaultMesh.restPositions.isNotEmpty())
    assertTrue(defaultMesh.triangleIndices.isNotEmpty())

    val mesh = CharacterObjLoader.loadFromAssets(context)
    assertNotNull(mesh.vertexColors)
    assertEquals(mesh.restPositions.size, mesh.vertexColors!!.size)

    val engine = CharacterRigEngine(mesh)
    assertTrue(engine.validationErrors.isEmpty())
    val faceBone = engine.skeleton.first { it.name == "face" }
    assertEquals("head", faceBone.parentName)

    val snapshot1 = engine.step(0.016f)
    assertTrue(snapshot1.isHeadChildVerified)
    assertFalse(snapshot1.activeBoneName.isBlank())
    assertFalse(snapshot1.isCustomModel)
    assertFalse(snapshot1.hasManualMarkers)

    val shifted = snapshot1.markers.copy(noseTip = snapshot1.markers.noseTip + Vec3(0f, -0.02f, 0.01f))
    val manualSnap = engine.setMarkers(shifted)
    assertTrue(manualSnap.hasManualMarkers)
    assertEquals(shifted.noseTip.z, engine.fittedFaceBones.first { it.name == "nose.003" }.head.z, 1e-3f)

    // Symmetric left-eye placement mirrors right eye around headCenter.x
    val symTarget = Vec3(engine.headCenter.x + 0.14f, snapshot1.markers.eyeL.y, snapshot1.markers.eyeL.z + 0.02f)
    val symSnap = engine.placeMarkerAt(FaceMarkers.IDX_EYE_L, symTarget, symmetric = true)
    assertEquals(symTarget.x, symSnap.markers.eyeL.x, 1e-4f)
    assertEquals(2f * engine.headCenter.x - symTarget.x, symSnap.markers.eyeR.x, 1e-4f)
    assertEquals(symTarget.z, symSnap.markers.eyeR.z, 1e-4f)

    // Raycast from front of nose hits the head mesh surface
    val hit = engine.raycastHeadSurface(
      rayOrigin = Vec3(engine.headCenter.x, engine.headCenter.y - 2.0f, engine.headCenter.z),
      rayDir = Vec3(0f, 1f, 0f),
    )
    assertNotNull(hit)

    val resetSnap = engine.resetMarkersToAuto()
    assertFalse(resetSnap.hasManualMarkers)

    val p = Vec3(0.12f, -0.45f, 0.85f)
    val rt = gltfToBlender(blenderToGltf(p))
    assertEquals(p.x, rt.x, 1e-5f)
    assertEquals(p.y, rt.y, 1e-5f)
    assertEquals(p.z, rt.z, 1e-5f)

    val auto = FaceFit.autoFit(mesh.headBboxMin, mesh.headBboxMax)
    val manual = FaceFit.manualFit(auto, FaceMarkers.fromBones(auto))
    assertEquals(auto.size, manual.size)

    engine.selectAnimation(DebugAnimationType.TALK)
    val snapshot2 = engine.step(0.033f)
    assertEquals(DebugAnimationType.TALK, snapshot2.selectedAnimation)
  }

  @Test
  fun `glb and gltf loaders parse geometry and rig adjustment updates fitTo`() {
    val glbBytes = buildSyntheticGlbTriangle()
    val customMesh = GlbMeshLoader.parseGlb(glbBytes)
    assertEquals(3, customMesh.restPositions.size / 3)
    assertEquals("mixamorig:Head", customMesh.detectedHeadBoneName)

    val gltfMesh = GlbMeshLoader.parseGltf(buildSyntheticEmbeddedGltf())
    assertEquals(3, gltfMesh.restPositions.size / 3)
    assertEquals("Head", gltfMesh.detectedHeadBoneName)

    val context = ApplicationProvider.getApplicationContext<Context>()
    val engine = CharacterRigEngine(CharacterObjLoader.loadFromAssets(context))
    val customSnap = engine.replaceMesh(customMesh, custom = true)
    assertTrue(customSnap.isCustomModel)
    assertTrue(customSnap.isHeadChildVerified)

    val adjSnap = engine.updateRigAdjustment(FaceRigAdjustment(offsetX = 0.05f, offsetZ = 0.1f, scale = 1.2f))
    assertEquals(3, adjSnap.vertexCount)
  }

  @Test
  fun `fbx ascii loader parses geometry and detects mixamorig Head bone`() {
    val asciiFbx =
      """
      ; FBX 7.4.0 project file
      Objects:  {
        Model: 1001, "Model::mixamorig:Head", "LimbNode" {
        }
        Geometry: 2001, "Geometry::HeadMesh", "Mesh" {
          Vertices: *9 {
            a: -0.3,0.0,0.3,0.3,0.0,0.3,0.0,1.0,0.0
          }
          PolygonVertexIndex: *3 {
            a: 0,1,-3
          }
        }
      }
      """.trimIndent()
    val mesh = FbxMeshLoader.parseFbx(asciiFbx.toByteArray(Charsets.UTF_8))
    assertEquals(3, mesh.restPositions.size / 3)
    assertEquals(3, mesh.triangleIndices.size)
    assertEquals("mixamorig:Head", mesh.detectedHeadBoneName)
  }

  @Test
  fun `webview asset resolver handles www assets and 3d mime types`() {
    assertEquals(
      listOf("www/rigs/human.glb"),
      Mesh2MotionWebViewFactory.resolveLocalAssetCandidates("/rigs/human.glb"),
    )
    assertEquals(
      listOf("www/assets/rigs/human.glb", "rigs/human.glb"),
      Mesh2MotionWebViewFactory.resolveLocalAssetCandidates("/assets/rigs/human.glb"),
    )
    assertEquals(
      listOf("www/index.html"),
      Mesh2MotionWebViewFactory.resolveLocalAssetCandidates("/assets/www/index.html"),
    )
    assertEquals("model/gltf-binary", Mesh2MotionWebViewFactory.guessMimeFromPath("/rigs/human.glb"))
    assertEquals("model/gltf+json", Mesh2MotionWebViewFactory.guessMimeFromPath("/rigs/human.gltf"))
    assertEquals("application/octet-stream", Mesh2MotionWebViewFactory.guessMimeFromPath("/rigs/human.fbx"))
    assertEquals("application/wasm", Mesh2MotionWebViewFactory.guessMimeFromPath("/wasm/draco.wasm"))
  }

  private fun buildSyntheticEmbeddedGltf(): ByteArray {
    val binBuf = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
    binBuf.putFloat(-0.3f); binBuf.putFloat(0.0f); binBuf.putFloat(0.3f)
    binBuf.putFloat(0.3f); binBuf.putFloat(0.0f); binBuf.putFloat(0.3f)
    binBuf.putFloat(0.0f); binBuf.putFloat(1.0f); binBuf.putFloat(0.0f)
    binBuf.putShort(0); binBuf.putShort(1); binBuf.putShort(2)
    val b64 = Base64.encodeToString(binBuf.array(), Base64.NO_WRAP)

    val json =
      """
      {
        "asset": {"version": "2.0"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [
          {"name": "Head", "children": [1]},
          {"mesh": 0}
        ],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
        "buffers": [{"byteLength": 42, "uri": "data:application/octet-stream;base64,$b64"}],
        "bufferViews": [
          {"buffer": 0, "byteOffset": 0, "byteLength": 36},
          {"buffer": 0, "byteOffset": 36, "byteLength": 6}
        ],
        "accessors": [
          {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
          {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}
        ]
      }
      """.trimIndent()
    return json.toByteArray(Charsets.UTF_8)
  }

  private fun buildSyntheticGlbTriangle(): ByteArray {
    val binBuf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    binBuf.putFloat(-0.3f); binBuf.putFloat(0.0f); binBuf.putFloat(0.3f)
    binBuf.putFloat(0.3f); binBuf.putFloat(0.0f); binBuf.putFloat(0.3f)
    binBuf.putFloat(0.0f); binBuf.putFloat(1.0f); binBuf.putFloat(0.0f)
    binBuf.putShort(0); binBuf.putShort(1); binBuf.putShort(2)
    val binBytes = binBuf.array()

    val jsonStr =
      """
      {
        "asset": {"version": "2.0"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [
          {"name": "mixamorig:Head", "children": [1]},
          {"mesh": 0}
        ],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
        "buffers": [{"byteLength": ${binBytes.size}}],
        "bufferViews": [
          {"buffer": 0, "byteOffset": 0, "byteLength": 36},
          {"buffer": 0, "byteOffset": 36, "byteLength": 6}
        ],
        "accessors": [
          {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
          {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}
        ]
      }
      """.trimIndent()
    val rawJson = jsonStr.toByteArray(Charsets.UTF_8)
    val jsonPad = (4 - (rawJson.size % 4)) % 4
    val paddedJson = ByteArray(rawJson.size + jsonPad) { idx ->
      if (idx < rawJson.size) rawJson[idx] else 0x20.toByte()
    }

    val totalLen = 12 + 8 + paddedJson.size + 8 + binBytes.size
    val out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(0x46546C67)
    out.putInt(2)
    out.putInt(totalLen)
    out.putInt(paddedJson.size)
    out.putInt(0x4E4F534A)
    out.put(paddedJson)
    out.putInt(binBytes.size)
    out.putInt(0x004E4942)
    out.put(binBytes)
    return out.array()
  }
}
