package com.example.facerig

import android.content.Context
import android.util.Log
import com.example.BonePose
import com.example.BoneTestAnimation
import com.example.ExpressionTestAnimation
import com.example.FaceAnimation
import com.example.FaceAnimator
import com.example.FaceBone
import com.example.FaceRig
import com.example.FaceRigValidator
import com.example.IdleAnimation
import com.example.KeyframeAnimation
import com.example.TalkAnimation
import com.example.Vec3
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class DebugAnimationType(val key: String) {
  BONE_TEST("bone_test"),
  EXPRESSION_TEST("expression_test"),
  IDLE("idle"),
  TALK("talk"),
  EMOTION_SHOW("emotion_show"),
}

data class FaceRigAdjustment(
  val offsetX: Float = 0f,
  val offsetY: Float = 0f,
  val offsetZ: Float = 0f,
  val scale: Float = 1f,
)

data class RigBone(
  val name: String,
  val parentName: String?,
  val parentIndex: Int,
  val head: Vec3,
  val tail: Vec3,
  val deform: Boolean,
  val isFaceBone: Boolean,
)

data class VertexSkin(
  val boneIndices: IntArray,
  val weights: FloatArray,
)

data class CharacterMeshData(
  val restPositions: FloatArray,
  val triangleIndices: ShortArray,
  val isHeadTriangle: BooleanArray,
  val headVertexIndices: Set<Int>,
  val headBboxMin: Vec3,
  val headBboxMax: Vec3,
  val detectedHeadBoneName: String? = null,
  val vertexColors: FloatArray? = null,
)

data class RenderBoneSegment(
  val name: String,
  val isFaceBone: Boolean,
  val isActive: Boolean,
  val isParentHead: Boolean,
  val hx: Float,
  val hy: Float,
  val hz: Float,
  val tx: Float,
  val ty: Float,
  val tz: Float,
)

data class FaceDebugSnapshot(
  val selectedAnimation: DebugAnimationType,
  val activeBoneName: String,
  val activeExpressionName: String?,
  val validationErrors: List<String>,
  val bonesWithoutExpressionsCount: Int,
  val headBoneCount: Int,
  val vertexCount: Int,
  val isHeadChildVerified: Boolean,
  val isCustomModel: Boolean,
  val detectedHeadBoneName: String?,
  val markers: FaceMarkers,
  val hasManualMarkers: Boolean,
)

object CharacterObjLoader {
  fun loadFromAssets(context: Context, assetPath: String = "models/character.obj"): CharacterMeshData {
    context.assets.open(assetPath).use { stream ->
      return parseObj(BufferedReader(InputStreamReader(stream)))
    }
  }

  fun parseObj(reader: BufferedReader): CharacterMeshData {
    val positions = ArrayList<Float>()
    val colors = ArrayList<Float>()
    var hasAllColors = true
    val indices = ArrayList<Short>()
    val triIsHead = ArrayList<Boolean>()
    val headVerts = HashSet<Int>()

    var currentGroup = "body"
    reader.forEachLine { rawLine ->
      val line = rawLine.trim()
      if (line.isEmpty() || line.startsWith("#")) return@forEachLine

      when {
        line.startsWith("g ") -> {
          currentGroup = line.substring(2).trim().lowercase()
        }
        line.startsWith("v ") -> {
          val parts = line.split(Regex("\\s+"))
          if (parts.size >= 4) {
            positions.add(parts[1].toFloat())
            positions.add(parts[2].toFloat())
            positions.add(parts[3].toFloat())
            if (parts.size >= 7) {
              colors.add(parts[4].toFloat())
              colors.add(parts[5].toFloat())
              colors.add(parts[6].toFloat())
            } else {
              hasAllColors = false
            }
          }
        }
        line.startsWith("f ") -> {
          val parts = line.split(Regex("\\s+")).drop(1)
          if (parts.size >= 3) {
            val vIdx = parts.map { token -> token.substringBefore('/').toInt() - 1 }
            val isHead = currentGroup.contains("head")
            for (k in 1 until vIdx.size - 1) {
              val a = vIdx[0]
              val b = vIdx[k]
              val c = vIdx[k + 1]
              indices.add(a.toShort())
              indices.add(b.toShort())
              indices.add(c.toShort())
              triIsHead.add(isHead)
              if (isHead) {
                headVerts.add(a)
                headVerts.add(b)
                headVerts.add(c)
              }
            }
          }
        }
      }
    }

    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY

    val targetVerts = if (headVerts.isNotEmpty()) headVerts else (0 until positions.size / 3).toSet()
    for (vi in targetVerts) {
      val x = positions[vi * 3]
      val y = positions[vi * 3 + 1]
      val z = positions[vi * 3 + 2]
      minX = min(minX, x)
      minY = min(minY, y)
      minZ = min(minZ, z)
      maxX = max(maxX, x)
      maxY = max(maxY, y)
      maxZ = max(maxZ, z)
    }

    return CharacterMeshData(
      restPositions = positions.toFloatArray(),
      triangleIndices = indices.toShortArray(),
      isHeadTriangle = triIsHead.toBooleanArray(),
      headVertexIndices = targetVerts,
      headBboxMin = Vec3(minX, minY, minZ),
      headBboxMax = Vec3(maxX, maxY, maxZ),
      detectedHeadBoneName = "head",
      vertexColors = if (hasAllColors && colors.size == positions.size) colors.toFloatArray() else null,
    )
  }
}

class CharacterRigEngine(
  initialMesh: CharacterMeshData,
  initialIsCustomModel: Boolean = false,
) {
  companion object {
    private const val TAG_VALIDATOR = "FaceRigValidator"
    private const val TAG_ENGINE = "CharacterRigEngine"
  }

  private val rawFaceBones: List<FaceBone> = FaceRig.build()
  val validationErrors: List<String> = FaceRigValidator.validate(rawFaceBones)
  val bonesWithoutExpressions: List<String> = FaceRigValidator.bonesWithoutExpression(rawFaceBones)

  private val stateLock = Any()

  @Volatile var mesh: CharacterMeshData = initialMesh
    private set

  @Volatile var isCustomModel: Boolean = initialIsCustomModel
    private set

  @Volatile var rigAdjustment: FaceRigAdjustment = FaceRigAdjustment()
    private set

  @Volatile var meshRevision: Int = 0
    private set

  private lateinit var autoFittedFaceBones: List<FaceBone>
  @Volatile var currentMarkers: FaceMarkers = FaceMarkers.fromBones(rawFaceBones)
    private set
  @Volatile var hasManualMarkers: Boolean = false
    private set

  lateinit var fittedFaceBones: List<FaceBone>
    private set
  lateinit var skeleton: List<RigBone>
    private set
  lateinit var boneIndexByName: Map<String, Int>
    private set
  lateinit var headSize: Vec3
    private set
  lateinit var headCenter: Vec3
    private set

  private lateinit var skinBindings: Array<VertexSkin>
  private lateinit var invBindMatrices: Array<FloatArray>
  private lateinit var localMatrices: Array<FloatArray>
  private lateinit var worldMatrices: Array<FloatArray>
  private lateinit var skinMatrices: Array<FloatArray>

  @Volatile var deformedPositions: FloatArray = initialMesh.restPositions.copyOf()
    private set

  @Volatile var deformedNormals: FloatArray = FloatArray(initialMesh.restPositions.size)
    private set

  val boneSegments: MutableList<RenderBoneSegment> = ArrayList()

  val animator = FaceAnimator()
  val boneTestAnimation: BoneTestAnimation = BoneTestAnimation(rawFaceBones)
  private val expressionTestAnimation = ExpressionTestAnimation()
  private val idleAnimation = IdleAnimation()
  private val talkAnimation = TalkAnimation()
  private val emotionShowAnimation = KeyframeAnimation.emotionShow()

  @Volatile var selectedAnimation: DebugAnimationType = DebugAnimationType.BONE_TEST
    private set

  @Volatile var headMotionEnabled: Boolean = false
  private var elapsedTime = 0f

  init {
    if (validationErrors.isEmpty()) {
      Log.i(
        TAG_VALIDATOR,
        "FaceRigValidator.validate() passed with 0 errors (${rawFaceBones.size} face bones). " +
          "Bones without expressions (${bonesWithoutExpressions.size}): ${bonesWithoutExpressions.joinToString()}",
      )
    } else {
      validationErrors.forEach { err ->
        Log.e(TAG_VALIDATOR, "Validation error: $err")
      }
    }

    bindMeshInternal(initialMesh, initialIsCustomModel, FaceRigAdjustment(), manualMarkers = null)
    selectAnimation(DebugAnimationType.BONE_TEST)
    step(0f)
  }

  fun replaceMesh(newMesh: CharacterMeshData, custom: Boolean): FaceDebugSnapshot {
    synchronized(stateLock) {
      val freshAdjustment = FaceRigAdjustment()
      bindMeshInternal(newMesh, custom, freshAdjustment, manualMarkers = null)
      meshRevision += 1
      return stepLocked(0f)
    }
  }

  fun updateRigAdjustment(adjustment: FaceRigAdjustment): FaceDebugSnapshot {
    synchronized(stateLock) {
      bindMeshInternal(mesh, isCustomModel, adjustment, manualMarkers = null)
      return stepLocked(0f)
    }
  }

  fun setMarkers(markers: FaceMarkers): FaceDebugSnapshot {
    synchronized(stateLock) {
      bindMeshInternal(mesh, isCustomModel, rigAdjustment, manualMarkers = markers)
      return stepLocked(0f)
    }
  }

  fun moveMarker(markerIndex: Int, deltaBlender: Vec3, symmetric: Boolean = false): FaceDebugSnapshot {
    synchronized(stateLock) {
      val list = currentMarkers.toFullList()
      if (markerIndex in list.indices) {
        val nextPt = list[markerIndex] + deltaBlender
        val updatedMarkers =
          currentMarkers.withPoint(
            index = markerIndex,
            point = nextPt,
            symmetric = symmetric,
            centerX = headCenter.x,
          )
        bindMeshInternal(mesh, isCustomModel, rigAdjustment, manualMarkers = updatedMarkers)
      }
      return stepLocked(0f)
    }
  }

  fun placeMarkerAt(markerIndex: Int, targetBlender: Vec3, symmetric: Boolean = true): FaceDebugSnapshot {
    synchronized(stateLock) {
      val updatedMarkers =
        currentMarkers.withPoint(
          index = markerIndex,
          point = targetBlender,
          symmetric = symmetric,
          centerX = headCenter.x,
        )
      bindMeshInternal(mesh, isCustomModel, rigAdjustment, manualMarkers = updatedMarkers)
      return stepLocked(0f)
    }
  }

  fun resetMarkersToAuto(): FaceDebugSnapshot {
    synchronized(stateLock) {
      bindMeshInternal(mesh, isCustomModel, rigAdjustment, manualMarkers = null)
      return stepLocked(0f)
    }
  }

  /**
   * Raycast against the current rest head triangles in Blender coordinates.
   * Returns hit point nudged slightly outward along triangle normal so markers sit on the skin surface.
   */
  fun raycastHeadSurface(rayOrigin: Vec3, rayDir: Vec3, surfaceOffset: Float = 0.006f): Vec3? {
    val currentMesh = mesh
    val pos = currentMesh.restPositions
    val idx = currentMesh.triangleIndices
    val headTris = currentMesh.isHeadTriangle
    val dirLen = rayDir.length().coerceAtLeast(1e-6f)
    val dx = rayDir.x / dirLen
    val dy = rayDir.y / dirLen
    val dz = rayDir.z / dirLen

    var bestT = Float.POSITIVE_INFINITY
    var bestNx = 0f
    var bestNy = -1f
    var bestNz = 0f

    val triCount = idx.size / 3
    for (t in 0 until triCount) {
      if (t < headTris.size && !headTris[t]) continue
      val ia = idx[t * 3].toInt() and 0xFFFF
      val ib = idx[t * 3 + 1].toInt() and 0xFFFF
      val ic = idx[t * 3 + 2].toInt() and 0xFFFF

      val ax = pos[ia * 3]; val ay = pos[ia * 3 + 1]; val az = pos[ia * 3 + 2]
      val bx = pos[ib * 3]; val by = pos[ib * 3 + 1]; val bz = pos[ib * 3 + 2]
      val cx = pos[ic * 3]; val cy = pos[ic * 3 + 1]; val cz = pos[ic * 3 + 2]

      val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
      val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az

      // pvec = cross(dir, e2)
      val px = dy * e2z - dz * e2y
      val py = dz * e2x - dx * e2z
      val pz = dx * e2y - dy * e2x

      val det = e1x * px + e1y * py + e1z * pz
      if (kotlin.math.abs(det) < 1e-6f) continue
      val invDet = 1f / det

      val tx = rayOrigin.x - ax
      val ty = rayOrigin.y - ay
      val tz = rayOrigin.z - az

      val u = (tx * px + ty * py + tz * pz) * invDet
      if (u < 0f || u > 1f) continue

      // qvec = cross(tvec, e1)
      val qx = ty * e1z - tz * e1y
      val qy = tz * e1x - tx * e1z
      val qz = tx * e1y - ty * e1x

      val v = (dx * qx + dy * qy + dz * qz) * invDet
      if (v < 0f || u + v > 1f) continue

      val dist = (e2x * qx + e2y * qy + e2z * qz) * invDet
      if (dist > 0.01f && dist < bestT) {
        bestT = dist
        // Normal = cross(e1, e2), flip toward ray origin if needed
        var nx = e1y * e2z - e1z * e2y
        var ny = e1z * e2x - e1x * e2z
        var nz = e1x * e2y - e1y * e2x
        val nLen = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
        nx /= nLen; ny /= nLen; nz /= nLen
        if (nx * dx + ny * dy + nz * dz > 0f) {
          nx = -nx; ny = -ny; nz = -nz
        }
        bestNx = nx; bestNy = ny; bestNz = nz
      }
    }

    if (!bestT.isFinite()) return null
    return Vec3(
      rayOrigin.x + dx * bestT + bestNx * surfaceOffset,
      rayOrigin.y + dy * bestT + bestNy * surfaceOffset,
      rayOrigin.z + dz * bestT + bestNz * surfaceOffset,
    )
  }

  private fun bindMeshInternal(
    targetMesh: CharacterMeshData,
    custom: Boolean,
    adjustment: FaceRigAdjustment,
    manualMarkers: FaceMarkers?,
  ) {
    mesh = targetMesh
    isCustomModel = custom
    rigAdjustment = adjustment

    val baseCenter = (targetMesh.headBboxMin + targetMesh.headBboxMax) * 0.5f
    val adjScale = adjustment.scale.coerceIn(0.4f, 2.0f)
    val baseHalf = (targetMesh.headBboxMax - targetMesh.headBboxMin) * 0.5f * adjScale
    val adjOffset = Vec3(adjustment.offsetX, adjustment.offsetY, adjustment.offsetZ)
    val adjustedCenter = baseCenter + adjOffset
    val adjustedMin = adjustedCenter - baseHalf
    val adjustedMax = adjustedCenter + baseHalf

    headSize = adjustedMax - adjustedMin
    headCenter = adjustedCenter

    val meshConformed = FaceFit.autoFitToMesh(targetMesh)
    autoFittedFaceBones =
      if (adjustment == FaceRigAdjustment()) {
        meshConformed
      } else {
        meshConformed.map { bone ->
          fun xform(p: Vec3) = (p - baseCenter) * adjScale + baseCenter + adjOffset
          bone.copy(head = xform(bone.head), tail = xform(bone.tail))
        }
      }

    if (manualMarkers != null) {
      currentMarkers = manualMarkers
      hasManualMarkers = true
      fittedFaceBones = FaceFit.manualFit(autoFittedFaceBones, manualMarkers)
    } else {
      currentMarkers = FaceMarkers.fromBones(autoFittedFaceBones)
      hasManualMarkers = false
      fittedFaceBones = autoFittedFaceBones
    }
    Log.i(
      TAG_ENGINE,
      "FaceFit(${if (hasManualMarkers) "manual" else "auto"}) head bbox min=$adjustedMin, max=$adjustedMax " +
        "(custom=$custom, headBone=${targetMesh.detectedHeadBoneName})",
    )

    val headBaseZ = targetMesh.headBboxMin.z
    val headTopZ = targetMesh.headBboxMax.z
    val bodySpecs =
      listOf(
        Triple("hips", null, Vec3(0f, 0f, -0.35f) to Vec3(0f, 0f, -0.10f)),
        Triple("spine", "hips", Vec3(0f, 0f, -0.10f) to Vec3(0f, 0f, 0.12f)),
        Triple("chest", "spine", Vec3(0f, 0f, 0.12f) to Vec3(0f, 0f, 0.28f)),
        Triple("neck", "chest", Vec3(0f, 0f, 0.28f) to Vec3(0f, 0f, headBaseZ)),
        Triple("head", "neck", Vec3(0f, 0f, headBaseZ) to Vec3(0f, 0f, headTopZ)),
      )

    val tempBones = ArrayList<RigBone>(bodySpecs.size + fittedFaceBones.size)
    val indexMap = HashMap<String, Int>()

    for ((name, parent, segment) in bodySpecs) {
      val parentIdx = if (parent != null) indexMap[parent] ?: -1 else -1
      val idx = tempBones.size
      indexMap[name] = idx
      tempBones +=
        RigBone(
          name = name,
          parentName = parent,
          parentIndex = parentIdx,
          head = segment.first,
          tail = segment.second,
          deform = true,
          isFaceBone = false,
        )
    }

    // Attach FaceRig root bone "face" as a child of body skeleton bone "head"
    val pendingFace =
      fittedFaceBones.map { fb ->
        val attachedParent = if (fb.name == "face") "head" else fb.parent
        fb to attachedParent
      }

    val remaining = pendingFace.toMutableList()
    while (remaining.isNotEmpty()) {
      var progressed = false
      val iter = remaining.iterator()
      while (iter.hasNext()) {
        val (fb, parentName) = iter.next()
        val parentIdx = if (parentName == null) -1 else indexMap[parentName]
        if (parentName == null || parentIdx != null) {
          val idx = tempBones.size
          indexMap[fb.name] = idx
          tempBones +=
            RigBone(
              name = fb.name,
              parentName = parentName,
              parentIndex = parentIdx ?: -1,
              head = fb.head,
              tail = fb.tail,
              deform = fb.deform,
              isFaceBone = true,
            )
          iter.remove()
          progressed = true
        }
      }
      if (!progressed) {
        val (fb, parentName) = remaining.removeAt(0)
        val idx = tempBones.size
        indexMap[fb.name] = idx
        tempBones +=
          RigBone(
            name = fb.name,
            parentName = parentName,
            parentIndex = -1,
            head = fb.head,
            tail = fb.tail,
            deform = fb.deform,
            isFaceBone = true,
          )
      }
    }

    skeleton = tempBones
    boneIndexByName = indexMap

    val faceOffset = bodySpecs.size
    val headBoneIdx = indexMap["head"] ?: 0
    val neckBoneIdx = indexMap["neck"] ?: headBoneIdx
    val chestBoneIdx = indexMap["chest"] ?: neckBoneIdx
    val spineBoneIdx = indexMap["spine"] ?: chestBoneIdx
    val hipsBoneIdx = indexMap["hips"] ?: spineBoneIdx

    val vertexCount = targetMesh.restPositions.size / 3
    val scaledFalloff = (0.10f * adjustment.scale).coerceIn(0.05f, 0.25f)
    skinBindings =
      Array(vertexCount) { vi ->
        val vx = targetMesh.restPositions[vi * 3]
        val vy = targetMesh.restPositions[vi * 3 + 1]
        val vz = targetMesh.restPositions[vi * 3 + 2]
        val v = Vec3(vx, vy, vz)

        if (vi in targetMesh.headVertexIndices) {
          val faceWeights = FaceRig.skinWeights(v, fittedFaceBones, maxInfl = 4, falloff = scaledFalloff)
          if (faceWeights.isEmpty()) {
            VertexSkin(intArrayOf(headBoneIdx), floatArrayOf(1f))
          } else {
            val mappedIdx = IntArray(faceWeights.size) { k -> faceOffset + faceWeights[k].first }
            val mappedW = FloatArray(faceWeights.size) { k -> faceWeights[k].second }
            VertexSkin(mappedIdx, mappedW)
          }
        } else {
          when {
            vz > 0.28f -> VertexSkin(intArrayOf(neckBoneIdx, headBoneIdx), floatArrayOf(0.8f, 0.2f))
            vz > 0.12f -> VertexSkin(intArrayOf(chestBoneIdx, neckBoneIdx), floatArrayOf(0.85f, 0.15f))
            vz > -0.10f -> VertexSkin(intArrayOf(spineBoneIdx), floatArrayOf(1f))
            else -> VertexSkin(intArrayOf(hipsBoneIdx), floatArrayOf(1f))
          }
        }
      }

    invBindMatrices =
      Array(skeleton.size) { i ->
        val h = skeleton[i].head
        translationMatrix(-h.x, -h.y, -h.z)
      }
    localMatrices = Array(skeleton.size) { FloatArray(16) }
    worldMatrices = Array(skeleton.size) { FloatArray(16) }
    skinMatrices = Array(skeleton.size) { FloatArray(16) }
    deformedPositions = targetMesh.restPositions.copyOf()
    deformedNormals = FloatArray(targetMesh.restPositions.size)
  }

  fun selectAnimation(type: DebugAnimationType) {
    synchronized(stateLock) {
      selectedAnimation = type
      val anim: FaceAnimation =
        when (type) {
          DebugAnimationType.BONE_TEST -> boneTestAnimation
          DebugAnimationType.EXPRESSION_TEST -> expressionTestAnimation
          DebugAnimationType.IDLE -> idleAnimation
          DebugAnimationType.TALK -> talkAnimation
          DebugAnimationType.EMOTION_SHOW -> emotionShowAnimation
        }
      animator.play(anim)
    }
  }

  fun step(dt: Float): FaceDebugSnapshot = synchronized(stateLock) { stepLocked(dt) }

  fun readRenderState(consumer: (FloatArray, FloatArray, List<RenderBoneSegment>, Vec3, FaceMarkers, Int) -> Unit) {
    synchronized(stateLock) {
      consumer(deformedPositions, deformedNormals, boneSegments, headCenter, currentMarkers, meshRevision)
    }
  }

  private fun stepLocked(dt: Float): FaceDebugSnapshot {
    val clampedDt = dt.coerceIn(0f, 0.05f)
    elapsedTime += clampedDt
    val poses = animator.update(clampedDt)
    val activeBone = boneTestAnimation.activeBone(animator.currentTime)
    val activeExpr =
      if (selectedAnimation == DebugAnimationType.EXPRESSION_TEST) {
        expressionTestAnimation.activeExpression(animator.currentTime)
      } else {
        null
      }

    applyPosesAndSkin(poses, activeBone)

    return FaceDebugSnapshot(
      selectedAnimation = selectedAnimation,
      activeBoneName = activeBone,
      activeExpressionName = activeExpr,
      validationErrors = validationErrors,
      bonesWithoutExpressionsCount = bonesWithoutExpressions.size,
      headBoneCount = fittedFaceBones.size,
      vertexCount = mesh.restPositions.size / 3,
      isHeadChildVerified = skeleton.firstOrNull { it.name == "face" }?.parentName == "head",
      isCustomModel = isCustomModel,
      detectedHeadBoneName = mesh.detectedHeadBoneName,
      markers = currentMarkers,
      hasManualMarkers = hasManualMarkers,
    )
  }

  private fun applyPosesAndSkin(poses: Map<String, BonePose>, activeBoneName: String) {
    for (i in skeleton.indices) {
      val bone = skeleton[i]
      val parentHead = if (bone.parentIndex >= 0) skeleton[bone.parentIndex].head else Vec3(0f, 0f, 0f)
      val restOffset = bone.head - parentHead

      val pose =
        if (bone.name == "head" && headMotionEnabled) {
          BonePose(
            move = Vec3(0f, 0f, 0f),
            rotDeg = Vec3(4f * sin(elapsedTime * 1.1f), 0f, 10f * sin(elapsedTime * 0.7f)),
          )
        } else {
          poses[bone.name] ?: BonePose()
        }

      val scaledMove =
        if (bone.isFaceBone) {
          Vec3(
            pose.move.x * headSize.x,
            pose.move.y * headSize.y,
            pose.move.z * headSize.z,
          )
        } else {
          pose.move
        }

      buildLocalMatrix(
        out = localMatrices[i],
        tx = restOffset.x + scaledMove.x,
        ty = restOffset.y + scaledMove.y,
        tz = restOffset.z + scaledMove.z,
        rxDeg = pose.rotDeg.x,
        ryDeg = pose.rotDeg.y,
        rzDeg = pose.rotDeg.z,
      )

      if (bone.parentIndex < 0) {
        System.arraycopy(localMatrices[i], 0, worldMatrices[i], 0, 16)
      } else {
        multiplyMat4(worldMatrices[bone.parentIndex], localMatrices[i], worldMatrices[i])
      }

      multiplyMat4(worldMatrices[i], invBindMatrices[i], skinMatrices[i])
    }

    val rest = mesh.restPositions
    for (vi in skinBindings.indices) {
      val skin = skinBindings[vi]
      val rx = rest[vi * 3]
      val ry = rest[vi * 3 + 1]
      val rz = rest[vi * 3 + 2]

      var dx = 0f
      var dy = 0f
      var dz = 0f

      for (k in skin.boneIndices.indices) {
        val m = skinMatrices[skin.boneIndices[k]]
        val w = skin.weights[k]
        dx += w * (m[0] * rx + m[4] * ry + m[8] * rz + m[12])
        dy += w * (m[1] * rx + m[5] * ry + m[9] * rz + m[13])
        dz += w * (m[2] * rx + m[6] * ry + m[10] * rz + m[14])
      }

      deformedPositions[vi * 3] = dx
      deformedPositions[vi * 3 + 1] = dy
      deformedPositions[vi * 3 + 2] = dz
    }

    recomputeNormals()

    boneSegments.clear()
    for (i in skeleton.indices) {
      val bone = skeleton[i]
      val wm = worldMatrices[i]
      val relTail = bone.tail - bone.head
      val hx = wm[12]
      val hy = wm[13]
      val hz = wm[14]
      val tx = wm[0] * relTail.x + wm[4] * relTail.y + wm[8] * relTail.z + wm[12]
      val ty = wm[1] * relTail.x + wm[5] * relTail.y + wm[9] * relTail.z + wm[13]
      val tz = wm[2] * relTail.x + wm[6] * relTail.y + wm[10] * relTail.z + wm[14]

      boneSegments +=
        RenderBoneSegment(
          name = bone.name,
          isFaceBone = bone.isFaceBone,
          isActive = selectedAnimation == DebugAnimationType.BONE_TEST && bone.name == activeBoneName,
          isParentHead = bone.name == "head" || bone.name == "face",
          hx = hx,
          hy = hy,
          hz = hz,
          tx = tx,
          ty = ty,
          tz = tz,
        )
    }
  }

  private fun recomputeNormals() {
    deformedNormals.fill(0f)
    val idx = mesh.triangleIndices
    val pos = deformedPositions
    var i = 0
    while (i < idx.size) {
      val ia = idx[i].toInt() and 0xFFFF
      val ib = idx[i + 1].toInt() and 0xFFFF
      val ic = idx[i + 2].toInt() and 0xFFFF

      val ax = pos[ia * 3]
      val ay = pos[ia * 3 + 1]
      val az = pos[ia * 3 + 2]

      val abx = pos[ib * 3] - ax
      val aby = pos[ib * 3 + 1] - ay
      val abz = pos[ib * 3 + 2] - az

      val acx = pos[ic * 3] - ax
      val acy = pos[ic * 3 + 1] - ay
      val acz = pos[ic * 3 + 2] - az

      val nx = aby * acz - abz * acy
      val ny = abz * acx - abx * acz
      val nz = abx * acy - aby * acx

      deformedNormals[ia * 3] += nx
      deformedNormals[ia * 3 + 1] += ny
      deformedNormals[ia * 3 + 2] += nz

      deformedNormals[ib * 3] += nx
      deformedNormals[ib * 3 + 1] += ny
      deformedNormals[ib * 3 + 2] += nz

      deformedNormals[ic * 3] += nx
      deformedNormals[ic * 3 + 1] += ny
      deformedNormals[ic * 3 + 2] += nz

      i += 3
    }

    var v = 0
    while (v < deformedNormals.size) {
      val nx = deformedNormals[v]
      val ny = deformedNormals[v + 1]
      val nz = deformedNormals[v + 2]
      val len = sqrt(nx * nx + ny * ny + nz * nz)
      if (len > 1e-6f) {
        deformedNormals[v] = nx / len
        deformedNormals[v + 1] = ny / len
        deformedNormals[v + 2] = nz / len
      } else {
        deformedNormals[v + 2] = 1f
      }
      v += 3
    }
  }

  private fun translationMatrix(tx: Float, ty: Float, tz: Float): FloatArray =
    floatArrayOf(
      1f, 0f, 0f, 0f,
      0f, 1f, 0f, 0f,
      0f, 0f, 1f, 0f,
      tx, ty, tz, 1f,
    )

  private fun buildLocalMatrix(
    out: FloatArray,
    tx: Float,
    ty: Float,
    tz: Float,
    rxDeg: Float,
    ryDeg: Float,
    rzDeg: Float,
  ) {
    val deg2Rad = (PI / 180.0).toFloat()
    val cx = cos(rxDeg * deg2Rad)
    val sx = sin(rxDeg * deg2Rad)
    val cy = cos(ryDeg * deg2Rad)
    val sy = sin(ryDeg * deg2Rad)
    val cz = cos(rzDeg * deg2Rad)
    val sz = sin(rzDeg * deg2Rad)

    out[0] = cz * cy
    out[1] = sz * cy
    out[2] = -sy
    out[3] = 0f

    out[4] = cz * sy * sx - sz * cx
    out[5] = sz * sy * sx + cz * cx
    out[6] = cy * sx
    out[7] = 0f

    out[8] = cz * sy * cx + sz * sx
    out[9] = sz * sy * cx - cz * sx
    out[10] = cy * cx
    out[11] = 0f

    out[12] = tx
    out[13] = ty
    out[14] = tz
    out[15] = 1f
  }

  private fun multiplyMat4(a: FloatArray, b: FloatArray, out: FloatArray) {
    for (col in 0 until 4) {
      val b0 = b[col * 4]
      val b1 = b[col * 4 + 1]
      val b2 = b[col * 4 + 2]
      val b3 = b[col * 4 + 3]
      out[col * 4] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3
      out[col * 4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3
      out[col * 4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3
      out[col * 4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3
    }
  }
}
