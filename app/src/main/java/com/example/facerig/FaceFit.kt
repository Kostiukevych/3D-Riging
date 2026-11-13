package com.example.facerig

import com.example.FaceBone
import com.example.FaceRig
import com.example.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Подгонка лицевого рига под модель:
 * - авто (по габаритам + привязка к реальной поверхности меша головы)
 * - ручная (до 10 опорных маркеров лица с поддержкой симметрии L↔R).
 */

fun blenderToGltf(v: Vec3) = Vec3(v.x, v.z, -v.y)
fun gltfToBlender(v: Vec3) = Vec3(v.x, -v.z, v.y)

/** Опорные точки лица. "Левый" = сторона .L (+X в пространстве Blender). */
data class FaceMarkers(
  val eyeL: Vec3,
  val eyeR: Vec3,
  val noseTip: Vec3,
  val mouthL: Vec3,
  val mouthR: Vec3,
  val chin: Vec3,
  val browL: Vec3? = null,
  val browR: Vec3? = null,
  val earL: Vec3? = null,
  val earR: Vec3? = null,
) {
  fun toList() = listOf(eyeL, eyeR, noseTip, mouthL, mouthR, chin)

  fun toFullList(fallback: FaceMarkers? = null): List<Vec3> =
    listOf(
      eyeL,
      eyeR,
      noseTip,
      mouthL,
      mouthR,
      chin,
      browL ?: fallback?.browL ?: eyeL + Vec3(0f, -0.02f, 0.08f),
      browR ?: fallback?.browR ?: eyeR + Vec3(0f, -0.02f, 0.08f),
      earL ?: fallback?.earL ?: Vec3(eyeL.x + 0.16f, eyeL.y + 0.35f, eyeL.z - 0.02f),
      earR ?: fallback?.earR ?: Vec3(eyeR.x - 0.16f, eyeR.y + 0.35f, eyeR.z - 0.02f),
    )

  fun map(f: (Vec3) -> Vec3) =
    FaceMarkers(
      eyeL = f(eyeL),
      eyeR = f(eyeR),
      noseTip = f(noseTip),
      mouthL = f(mouthL),
      mouthR = f(mouthR),
      chin = f(chin),
      browL = browL?.let(f),
      browR = browR?.let(f),
      earL = earL?.let(f),
      earR = earR?.let(f),
    )

  fun withPoint(
    index: Int,
    point: Vec3,
    symmetric: Boolean,
    centerX: Float,
    fallback: FaceMarkers? = null,
  ): FaceMarkers {
    val full = toFullList(fallback).toMutableList()
    if (index !in full.indices) return this
    full[index] = point
    if (symmetric) {
      val mirrored = Vec3(2f * centerX - point.x, point.y, point.z)
      when (index) {
        0 -> full[1] = mirrored
        1 -> full[0] = mirrored
        3 -> full[4] = mirrored
        4 -> full[3] = mirrored
        6 -> full[7] = mirrored
        7 -> full[6] = mirrored
        8 -> full[9] = mirrored
        9 -> full[8] = mirrored
      }
    }
    return fromFullList(full)
  }

  companion object {
    const val IDX_EYE_L = 0
    const val IDX_EYE_R = 1
    const val IDX_NOSE = 2
    const val IDX_MOUTH_L = 3
    const val IDX_MOUTH_R = 4
    const val IDX_CHIN = 5
    const val IDX_BROW_L = 6
    const val IDX_BROW_R = 7
    const val IDX_EAR_L = 8
    const val IDX_EAR_R = 9
    const val FULL_COUNT = 10

    fun fromList(l: List<Vec3>) =
      if (l.size >= FULL_COUNT) {
        fromFullList(l)
      } else {
        FaceMarkers(l[0], l[1], l[2], l[3], l[4], l[5])
      }

    fun fromFullList(l: List<Vec3>) =
      FaceMarkers(
        eyeL = l[0],
        eyeR = l[1],
        noseTip = l[2],
        mouthL = l[3],
        mouthR = l[4],
        chin = l[5],
        browL = l.getOrNull(6),
        browR = l.getOrNull(7),
        earL = l.getOrNull(8),
        earR = l.getOrNull(9),
      )

    /** Положение маркеров по текущим костям (начальные точки для перетаскивания). */
    fun fromBones(bones: List<FaceBone>): FaceMarkers {
      val by = bones.associateBy { it.name }
      fun b(n: String) = by.getValue(n)
      return FaceMarkers(
        eyeL = b("eye.L").head,
        eyeR = b("eye.R").head,
        noseTip = b("nose.003").head,
        mouthL = b("lip.T.L.001").tail,
        mouthR = b("lip.T.R.001").tail,
        chin = b("chin.001").tail,
        browL = by["brow.B.L.001"]?.tail,
        browR = by["brow.B.R.001"]?.tail,
        earL = by["ear.L.001"]?.tail,
        earR = by["ear.R.001"]?.tail,
      )
    }
  }
}

object FaceFit {

  /** АВТО: шаблон из костей, растянутый под габариты головы (в координатах Blender). */
  fun autoFit(bboxMinBlender: Vec3, bboxMaxBlender: Vec3): List<FaceBone> =
    FaceRig.fitTo(FaceRig.build(), bboxMinBlender, bboxMaxBlender)

  /**
   * АВТО С ПРИВЯЗКОЙ К ПОВЕРХНОСТИ:
   * подгоняет кости так, чтобы передняя маска (нос, губы, подбородок, брови, веки, щёки)
   * прилегала к реальной поверхности 3D-меча головы, а не висела в воздухе.
   */
  fun autoFitToMesh(mesh: CharacterMeshData): List<FaceBone> {
    val base = autoFit(mesh.headBboxMin, mesh.headBboxMax)
    val headVerts = mesh.headVertexIndices
    if (headVerts.size < 40) return base

    val pos = mesh.restPositions
    val headMin = mesh.headBboxMin
    val headMax = mesh.headBboxMax
    val size = headMax - headMin
    val width = size.x.coerceAtLeast(1e-4f)
    val depth = size.y.coerceAtLeast(1e-4f)
    val height = size.z.coerceAtLeast(1e-4f)
    val centerX = (headMin.x + headMax.x) * 0.5f

    // Collect head vertices for fast surface depth lookups
    val hx = FloatArray(headVerts.size)
    val hy = FloatArray(headVerts.size)
    val hz = FloatArray(headVerts.size)
    var count = 0
    for (vi in headVerts) {
      hx[count] = pos[vi * 3]
      hy[count] = pos[vi * 3 + 1]
      hz[count] = pos[vi * 3 + 2]
      count++
    }

    // Find actual nose tip in the front center band to calibrate overall front Y
    var noseY = Float.POSITIVE_INFINITY
    var noseZ = headMin.z + height * 0.48f
    for (i in 0 until count) {
      if (abs(hx[i] - centerX) < width * 0.11f &&
        hz[i] > headMin.z + height * 0.30f &&
        hz[i] < headMin.z + height * 0.65f
      ) {
        if (hy[i] < noseY) {
          noseY = hy[i]
          noseZ = hz[i]
        }
      }
    }
    if (!noseY.isFinite()) return base

    // Compute median/front-most Y near a given (x, z) point
    fun frontSurfaceY(px: Float, pz: Float, radius: Float, fallbackY: Float): Float {
      var bestY = Float.POSITIVE_INFINITY
      var found = false
      val r2 = radius * radius
      for (i in 0 until count) {
        val dx = hx[i] - px
        val dz = hz[i] - pz
        if (dx * dx + dz * dz <= r2) {
          if (hy[i] < bestY) {
            bestY = hy[i]
            found = true
          }
        }
      }
      return if (found) bestY else fallbackY
    }

    // Shift template Y so its nose.003 aligns with the actual mesh nose tip
    val templateByName = base.associateBy { it.name }
    val tplNose = templateByName["nose.003"]?.head ?: Vec3(centerX, noseY, noseZ)
    val globalShiftY = noseY - tplNose.y
    val globalShiftZ = (noseZ - tplNose.z).coerceIn(-height * 0.14f, height * 0.14f)
    val searchR = max(width, height) * 0.085f
    val skinOffset = depth * 0.012f

    fun conformPoint(boneName: String, pt: Vec3): Vec3 {
      // Keep internal / pivot / back-ear bones anchored without snapping to front skin
      if (boneName == "face" ||
        boneName == "jaw_master" ||
        boneName == "jaw" ||
        boneName.startsWith("tongue") ||
        boneName.startsWith("teeth")
      ) {
        return Vec3(pt.x, pt.y + globalShiftY * 0.4f, pt.z + globalShiftZ * 0.5f)
      }
      if (boneName.startsWith("ear")) {
        // Place ear loop around the outer lateral silhouette near head center Y
        val earBaseY = (headMin.y + headMax.y) * 0.5f - depth * 0.02f
        val shifted = Vec3(pt.x, earBaseY + (pt.y - (templateByName["ear.L"]?.head?.y ?: pt.y)), pt.z + globalShiftZ * 0.4f)
        return shifted
      }
      if (boneName.startsWith("eye.")) {
        // Eye sockets sit slightly recessed inside the face surface
        val shiftedZ = pt.z + globalShiftZ
        val surfY = frontSurfaceY(pt.x, shiftedZ, searchR, pt.y + globalShiftY)
        val depthOffset = if (boneName == "eye.L" || boneName == "eye.R") {
          // head inside eye socket, tail at pupil surface
          0f
        } else {
          0f
        }
        return Vec3(pt.x, surfY + skinOffset * 1.5f + depthOffset, shiftedZ)
      }

      val shiftedZ = pt.z + globalShiftZ
      val roughY = pt.y + globalShiftY
      val surfY = frontSurfaceY(pt.x, shiftedZ, searchR, roughY)
      // Blend toward actual surface Y so bones hug the face contour cleanly
      val targetY = surfY - skinOffset
      val blendedY = roughY * 0.20f + targetY * 0.80f
      return Vec3(pt.x, blendedY, shiftedZ)
    }

    return base.map { bone ->
      val newHead =
        if (bone.name.startsWith("eye.")) {
          val shiftedZ = bone.head.z + globalShiftZ
          val surfY = frontSurfaceY(bone.head.x, shiftedZ, searchR, bone.head.y + globalShiftY)
          Vec3(bone.head.x, surfY + depth * 0.055f, shiftedZ)
        } else {
          conformPoint(bone.name, bone.head)
        }
      val newTail =
        if (bone.name.startsWith("eye.")) {
          val shiftedZ = bone.tail.z + globalShiftZ
          val surfY = frontSurfaceY(bone.tail.x, shiftedZ, searchR, bone.tail.y + globalShiftY)
          Vec3(bone.tail.x, surfY - skinOffset, shiftedZ)
        } else {
          conformPoint(bone.name, bone.tail)
        }
      bone.copy(head = newHead, tail = newTail)
    }
  }

  /**
   * РУЧНАЯ: сдвигает все кости так, чтобы опорные точки совпали с маркерами.
   * Остальные кости смещаются плавно (метод Шепарда).
   */
  fun manualFit(fitted: List<FaceBone>, markers: FaceMarkers): List<FaceBone> {
    val srcFull = FaceMarkers.fromBones(fitted)
    val srcList = mutableListOf<Vec3>()
    val dstList = mutableListOf<Vec3>()

    fun addPair(s: Vec3, d: Vec3?) {
      if (d != null) {
        srcList += s
        dstList += d
      }
    }

    addPair(srcFull.eyeL, markers.eyeL)
    addPair(srcFull.eyeR, markers.eyeR)
    addPair(srcFull.noseTip, markers.noseTip)
    addPair(srcFull.mouthL, markers.mouthL)
    addPair(srcFull.mouthR, markers.mouthR)
    addPair(srcFull.chin, markers.chin)
    srcFull.browL?.let { addPair(it, markers.browL) }
    srcFull.browR?.let { addPair(it, markers.browR) }
    srcFull.earL?.let { addPair(it, markers.earL) }
    srcFull.earR?.let { addPair(it, markers.earR) }

    val by = fitted.associateBy { it.name }
    val anchorNames =
      buildList {
        add("face")
        add("temple.L")
        add("temple.R")
        if (markers.earL == null) add("ear.L")
        if (markers.earR == null) add("ear.R")
        if (markers.browL == null) add("forehead.L.002")
        if (markers.browR == null) add("forehead.R.002")
      }
    val anchors = anchorNames.mapNotNull { by[it]?.let { b -> if (b.name.startsWith("forehead")) b.tail else b.head } }

    val pts = srcList + anchors
    val offs = dstList.indices.map { dstList[it] - srcList[it] } + anchors.map { Vec3(0f, 0f, 0f) }
    val scale = srcFull.eyeL.dist(srcFull.chin).coerceAtLeast(1e-4f)
    val eps = (0.002f * scale) * (0.002f * scale)

    fun disp(p: Vec3): Vec3 {
      var sx = 0f
      var sy = 0f
      var sz = 0f
      var sw = 0f
      for (i in pts.indices) {
        val d = p.dist(pts[i])
        val w = 1f / (d * d + eps)
        sx += offs[i].x * w
        sy += offs[i].y * w
        sz += offs[i].z * w
        sw += w
      }
      return Vec3(sx / sw, sy / sw, sz / sw)
    }

    return fitted.map { bone ->
      if (bone.name == "face") bone else bone.copy(head = bone.head + disp(bone.head), tail = bone.tail + disp(bone.tail))
    }
  }
}
