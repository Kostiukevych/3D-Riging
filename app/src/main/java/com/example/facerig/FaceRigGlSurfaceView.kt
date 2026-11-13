package com.example.facerig

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.example.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan

data class ProjectedMarker(
  val index: Int,
  val screenX: Float,
  val screenY: Float,
  val visible: Boolean,
)

enum class CameraPreset(val yawDeg: Float, val pitchDeg: Float, val zoom: Float) {
  FRONT(0f, 4f, 1.85f),
  THREE_QUARTER(24f, 6f, 1.90f),
  SIDE(88f, 3f, 1.95f),
}

class FaceRigGlSurfaceView(
  context: Context,
  private val engine: CharacterRigEngine,
  private val onSnapshot: (FaceDebugSnapshot) -> Unit,
  private val onMarkerSelected: (Int) -> Unit = {},
  private val onMarkerDragged: (Int, Vec3) -> Unit = { _, _ -> },
  private val onMarkerPlaced: (Int, Vec3) -> Unit = { _, _ -> },
  private val onMarkersProjected: (List<ProjectedMarker>) -> Unit = {},
) : GLSurfaceView(context) {

  private val density = context.resources.displayMetrics.density
  private val renderer =
    FaceRigRenderer(
      engine = engine,
      onSnapshot = { snapshot -> post { onSnapshot(snapshot) } },
      onMarkersProjected = { projected -> post { onMarkersProjected(projected) } },
    )
  private var lastX = 0f
  private var lastY = 0f
  private var isDragging = false
  private var draggingMarker = false

  private val scaleDetector =
    ScaleGestureDetector(
      context,
      object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
          renderer.zoom = (renderer.zoom / detector.scaleFactor).coerceIn(0.95f, 4.5f)
          return true
        }
      },
    )

  init {
    setEGLContextClientVersion(2)
    setRenderer(renderer)
    renderMode = RENDERMODE_CONTINUOUSLY
  }

  var showSkeleton: Boolean
    get() = renderer.showSkeleton
    set(value) {
      renderer.showSkeleton = value
    }

  var showWireframe: Boolean
    get() = renderer.showWireframe
    set(value) {
      renderer.showWireframe = value
    }

  var showMarkers: Boolean
    get() = renderer.showMarkers
    set(value) {
      renderer.showMarkers = value
    }

  var editMarkersMode: Boolean = false
  var surfaceSnapEnabled: Boolean = true

  var selectedMarkerIndex: Int
    get() = renderer.selectedMarkerIndex
    set(value) {
      renderer.selectedMarkerIndex = value
    }

  fun setCameraPreset(preset: CameraPreset) {
    renderer.yawDeg = preset.yawDeg
    renderer.pitchDeg = preset.pitchDeg
    renderer.zoom = preset.zoom
  }

  fun resetCamera() {
    setCameraPreset(CameraPreset.THREE_QUARTER)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    scaleDetector.onTouchEvent(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        lastX = event.x
        lastY = event.y
        isDragging = true
        draggingMarker = false
        if (editMarkersMode && renderer.showMarkers) {
          val hitIdx = renderer.pickMarker(event.x, event.y, 46f * density)
          if (hitIdx >= 0) {
            renderer.selectedMarkerIndex = hitIdx
            onMarkerSelected(hitIdx)
            draggingMarker = true
          }
        }
      }
      MotionEvent.ACTION_MOVE -> {
        if (isDragging && !scaleDetector.isInProgress && event.pointerCount == 1) {
          val dx = event.x - lastX
          val dy = event.y - lastY
          if (draggingMarker && editMarkersMode) {
            val idx = renderer.selectedMarkerIndex
            if (idx in 0 until FaceMarkers.FULL_COUNT) {
              var placed = false
              if (surfaceSnapEnabled) {
                val ray = renderer.screenPointToBlenderRay(event.x, event.y)
                if (ray != null) {
                  val hit = engine.raycastHeadSurface(ray.first, ray.second, surfaceOffset = 0.008f)
                  if (hit != null) {
                    onMarkerPlaced(idx, hit)
                    placed = true
                  }
                }
              }
              if (!placed) {
                val delta = renderer.screenDeltaToBlender(dx, dy)
                onMarkerDragged(idx, delta)
              }
            }
          } else {
            renderer.yawDeg += dx * 0.45f
            renderer.pitchDeg = (renderer.pitchDeg + dy * 0.35f).coerceIn(-55f, 55f)
          }
          lastX = event.x
          lastY = event.y
        }
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        isDragging = false
        draggingMarker = false
      }
    }
    return true
  }
}

private class FaceRigRenderer(
  private val engine: CharacterRigEngine,
  private val onSnapshot: (FaceDebugSnapshot) -> Unit,
  private val onMarkersProjected: (List<ProjectedMarker>) -> Unit,
) : GLSurfaceView.Renderer {

  @Volatile var yawDeg = CameraPreset.THREE_QUARTER.yawDeg
  @Volatile var pitchDeg = CameraPreset.THREE_QUARTER.pitchDeg
  @Volatile var zoom = CameraPreset.THREE_QUARTER.zoom
  @Volatile var showSkeleton = true
  @Volatile var showWireframe = false
  @Volatile var showMarkers = false
  @Volatile var selectedMarkerIndex = FaceMarkers.IDX_NOSE

  private var lastFrameNanos = 0L
  private var frameCounter = 0
  private var uploadedRevision = -1
  private var hasVertexColors = false

  private var viewportWidth = 1
  private var viewportHeight = 1

  private var meshProgram = 0
  private var boneProgram = 0
  private var lineProgram = 0

  private val projMatrix = FloatArray(16)
  private val viewMatrix = FloatArray(16)
  private val blenderToGlMatrix =
    floatArrayOf(
      1f, 0f, 0f, 0f,
      0f, 0f, -1f, 0f,
      0f, 1f, 0f, 0f,
      0f, 0f, 0f, 1f,
    )
  private val modelMatrix = FloatArray(16)
  private val mvMatrix = FloatArray(16)
  private val mvpMatrix = FloatArray(16)

  private val mvSnapshotLock = Any()
  private val mvSnapshot = FloatArray(16)
  private val mvpSnapshot = FloatArray(16)
  private val invMvpSnapshot = FloatArray(16)
  private val projectedMarkerX = FloatArray(FaceMarkers.FULL_COUNT)
  private val projectedMarkerY = FloatArray(FaceMarkers.FULL_COUNT)
  private var projectedMarkersValid = false

  private lateinit var posBuffer: FloatBuffer
  private lateinit var normBuffer: FloatBuffer
  private lateinit var colorBuffer: FloatBuffer
  private lateinit var headIndexBuffer: ShortBuffer
  private lateinit var bodyIndexBuffer: ShortBuffer
  private var headIndexCount = 0
  private var bodyIndexCount = 0

  // Octahedral bone buffers (8 triangles = 24 verts/bone, 12 edges = 24 line verts/bone, 2 joints/bone)
  private lateinit var boneTriPosBuffer: FloatBuffer
  private lateinit var boneTriNormBuffer: FloatBuffer
  private lateinit var boneTriColBuffer: FloatBuffer
  private lateinit var boneEdgeBuffer: FloatBuffer
  private lateinit var boneJointBuffer: FloatBuffer

  private val markerPointBuffer: FloatBuffer =
    ByteBuffer.allocateDirect(FaceMarkers.FULL_COUNT * 3 * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()

  fun pickMarker(touchX: Float, touchY: Float, maxRadiusPx: Float): Int {
    synchronized(mvSnapshotLock) {
      if (!projectedMarkersValid) return -1
      var bestIdx = -1
      var bestDist = maxRadiusPx
      for (i in 0 until FaceMarkers.FULL_COUNT) {
        val d = hypot(touchX - projectedMarkerX[i], touchY - projectedMarkerY[i])
        if (d < bestDist) {
          bestDist = d
          bestIdx = i
        }
      }
      return bestIdx
    }
  }

  fun screenDeltaToBlender(dxPx: Float, dyPx: Float): Vec3 {
    synchronized(mvSnapshotLock) {
      val worldPerPx = (2f * zoom * tan(Math.toRadians(19.0)).toFloat()) / viewportHeight.coerceAtLeast(1)
      val vx = dxPx * worldPerPx
      val vy = -dyPx * worldPerPx
      return Vec3(
        mvSnapshot[0] * vx + mvSnapshot[1] * vy,
        mvSnapshot[4] * vx + mvSnapshot[5] * vy,
        mvSnapshot[8] * vx + mvSnapshot[9] * vy,
      )
    }
  }

  fun screenPointToBlenderRay(touchX: Float, touchY: Float): Pair<Vec3, Vec3>? {
    synchronized(mvSnapshotLock) {
      if (!Matrix.invertM(invMvpSnapshot, 0, mvpSnapshot, 0)) return null
      val ndcX = (2f * touchX / viewportWidth.coerceAtLeast(1)) - 1f
      val ndcY = 1f - (2f * touchY / viewportHeight.coerceAtLeast(1))

      val nearNdc = floatArrayOf(ndcX, ndcY, -1f, 1f)
      val farNdc = floatArrayOf(ndcX, ndcY, 1f, 1f)
      val nearWorld = FloatArray(4)
      val farWorld = FloatArray(4)
      Matrix.multiplyMV(nearWorld, 0, invMvpSnapshot, 0, nearNdc, 0)
      Matrix.multiplyMV(farWorld, 0, invMvpSnapshot, 0, farNdc, 0)
      if (abs(nearWorld[3]) < 1e-6f || abs(farWorld[3]) < 1e-6f) return null

      val origin = Vec3(nearWorld[0] / nearWorld[3], nearWorld[1] / nearWorld[3], nearWorld[2] / nearWorld[3])
      val far = Vec3(farWorld[0] / farWorld[3], farWorld[1] / farWorld[3], farWorld[2] / farWorld[3])
      val dir = far - origin
      val len = dir.length()
      if (len < 1e-6f) return null
      return origin to (dir * (1f / len))
    }
  }

  override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
    GLES20.glClearColor(0.051f, 0.063f, 0.090f, 1f)
    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
    GLES20.glCullFace(GLES20.GL_BACK)

    meshProgram = createProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
    boneProgram = createProgram(BONE_VERTEX_SHADER, BONE_FRAGMENT_SHADER)
    lineProgram = createProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)

    rebuildMeshBuffers()
    lastFrameNanos = System.nanoTime()
  }

  private fun rebuildMeshBuffers() {
    val currentMesh = engine.mesh
    val vertFloatCount = currentMesh.restPositions.size
    posBuffer =
      ByteBuffer.allocateDirect(vertFloatCount * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    normBuffer =
      ByteBuffer.allocateDirect(vertFloatCount * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    val vColors = currentMesh.vertexColors
    hasVertexColors = vColors != null && vColors.size == vertFloatCount
    colorBuffer =
      ByteBuffer.allocateDirect(vertFloatCount * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
          if (vColors != null && vColors.size == vertFloatCount) {
            put(vColors)
          } else {
            put(FloatArray(vertFloatCount) { 0.80f })
          }
          position(0)
        }

    val headList = ArrayList<Short>()
    val bodyList = ArrayList<Short>()
    val allIndices = currentMesh.triangleIndices
    val isHead = currentMesh.isHeadTriangle
    for (t in isHead.indices) {
      val base = t * 3
      val target = if (isHead[t]) headList else bodyList
      target.add(allIndices[base])
      target.add(allIndices[base + 1])
      target.add(allIndices[base + 2])
    }
    headIndexCount = headList.size
    bodyIndexCount = bodyList.size
    headIndexBuffer =
      ByteBuffer.allocateDirect(headIndexCount.coerceAtLeast(1) * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply {
          if (headIndexCount > 0) put(headList.toShortArray())
          position(0)
        }
    bodyIndexBuffer =
      ByteBuffer.allocateDirect(bodyIndexCount.coerceAtLeast(1) * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply {
          if (bodyIndexCount > 0) put(bodyList.toShortArray())
          position(0)
        }

    val maxBones = engine.skeleton.size.coerceAtLeast(1)
    boneTriPosBuffer =
      ByteBuffer.allocateDirect(maxBones * 24 * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    boneTriNormBuffer =
      ByteBuffer.allocateDirect(maxBones * 24 * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    boneTriColBuffer =
      ByteBuffer.allocateDirect(maxBones * 24 * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    boneEdgeBuffer =
      ByteBuffer.allocateDirect(maxBones * 24 * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    boneJointBuffer =
      ByteBuffer.allocateDirect(maxBones * 2 * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    uploadedRevision = engine.meshRevision
  }

  override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
    viewportWidth = width.coerceAtLeast(1)
    viewportHeight = height.coerceAtLeast(1)
    GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
    Matrix.perspectiveM(projMatrix, 0, 38f, aspect, 0.1f, 30f)
  }

  override fun onDrawFrame(unused: GL10?) {
    val now = System.nanoTime()
    val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1_000_000_000.0).toFloat()
    lastFrameNanos = now

    val snapshot = engine.step(dt)
    if (frameCounter++ % 3 == 0) {
      onSnapshot(snapshot)
    }

    var localPositions: FloatArray? = null
    var localNormals: FloatArray? = null
    var localSegments: List<RenderBoneSegment> = emptyList()
    var localMarkers: FaceMarkers? = null
    var targetY = 0.85f

    engine.readRenderState { pos, norm, segments, headCenter, markers, rev ->
      if (rev != uploadedRevision) {
        rebuildMeshBuffers()
      }
      localPositions = pos.copyOf()
      localNormals = norm.copyOf()
      localSegments = segments.toList()
      localMarkers = markers
      targetY = headCenter.z
    }

    val posData = localPositions ?: return
    val normData = localNormals ?: return

    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

    Matrix.setLookAtM(
      viewMatrix,
      0,
      0f,
      targetY,
      zoom,
      0f,
      targetY - 0.04f,
      0f,
      0f,
      1f,
      0f,
    )

    val rotMatrix = FloatArray(16)
    Matrix.setIdentityM(rotMatrix, 0)
    Matrix.rotateM(rotMatrix, 0, pitchDeg, 1f, 0f, 0f)
    Matrix.rotateM(rotMatrix, 0, yawDeg, 0f, 1f, 0f)
    Matrix.multiplyMM(modelMatrix, 0, rotMatrix, 0, blenderToGlMatrix, 0)
    Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
    Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)

    localMarkers?.let { markers ->
      val pts = markers.toFullList()
      val inVec = FloatArray(4)
      val outVec = FloatArray(4)
      val projectedList = ArrayList<ProjectedMarker>(pts.size)
      synchronized(mvSnapshotLock) {
        System.arraycopy(mvMatrix, 0, mvSnapshot, 0, 16)
        System.arraycopy(mvpMatrix, 0, mvpSnapshot, 0, 16)
        for (i in pts.indices) {
          inVec[0] = pts[i].x
          inVec[1] = pts[i].y
          inVec[2] = pts[i].z
          inVec[3] = 1f
          Matrix.multiplyMV(outVec, 0, mvpMatrix, 0, inVec, 0)
          val w = if (abs(outVec[3]) > 1e-5f) outVec[3] else 1f
          val ndcX = outVec[0] / w
          val ndcY = outVec[1] / w
          val sx = (ndcX * 0.5f + 0.5f) * viewportWidth
          val sy = (0.5f - ndcY * 0.5f) * viewportHeight
          projectedMarkerX[i] = sx
          projectedMarkerY[i] = sy
          projectedList += ProjectedMarker(i, sx, sy, w > 0f && ndcX in -1.1f..1.1f && ndcY in -1.1f..1.1f)
        }
        projectedMarkersValid = true
      }
      if (showMarkers) {
        onMarkersProjected(projectedList)
      }
    }

    // 1. Render 3D Character Head Mesh
    posBuffer.position(0)
    posBuffer.put(posData)
    posBuffer.position(0)

    normBuffer.position(0)
    normBuffer.put(normData)
    normBuffer.position(0)

    colorBuffer.position(0)

    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
    GLES20.glUseProgram(meshProgram)
    val posAttr = GLES20.glGetAttribLocation(meshProgram, "aPosition")
    val normAttr = GLES20.glGetAttribLocation(meshProgram, "aNormal")
    val colAttr = GLES20.glGetAttribLocation(meshProgram, "aColor")
    val uMvp = GLES20.glGetUniformLocation(meshProgram, "uMvpMatrix")
    val uMv = GLES20.glGetUniformLocation(meshProgram, "uMvMatrix")
    val uColor = GLES20.glGetUniformLocation(meshProgram, "uBaseColor")
    val uUseVCol = GLES20.glGetUniformLocation(meshProgram, "uUseVertexColor")

    GLES20.glEnableVertexAttribArray(posAttr)
    GLES20.glVertexAttribPointer(posAttr, 3, GLES20.GL_FLOAT, false, 0, posBuffer)
    GLES20.glEnableVertexAttribArray(normAttr)
    GLES20.glVertexAttribPointer(normAttr, 3, GLES20.GL_FLOAT, false, 0, normBuffer)
    GLES20.glEnableVertexAttribArray(colAttr)
    GLES20.glVertexAttribPointer(colAttr, 3, GLES20.GL_FLOAT, false, 0, colorBuffer)

    GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
    GLES20.glUniformMatrix4fv(uMv, 1, false, mvMatrix, 0)
    GLES20.glUniform1f(uUseVCol, if (hasVertexColors) 1.0f else 0.0f)

    if (bodyIndexCount > 0) {
      GLES20.glUniform3f(uColor, 0.30f, 0.38f, 0.50f)
      bodyIndexBuffer.position(0)
      GLES20.glDrawElements(GLES20.GL_TRIANGLES, bodyIndexCount, GLES20.GL_UNSIGNED_SHORT, bodyIndexBuffer)
    }

    if (headIndexCount > 0) {
      GLES20.glUniform3f(uColor, 0.78f, 0.83f, 0.89f)
      headIndexBuffer.position(0)
      GLES20.glDrawElements(GLES20.GL_TRIANGLES, headIndexCount, GLES20.GL_UNSIGNED_SHORT, headIndexBuffer)

      if (showWireframe) {
        GLES20.glUniform1f(uUseVCol, 0.0f)
        GLES20.glUniform3f(uColor, 0.13f, 0.83f, 0.93f)
        GLES20.glLineWidth(1.2f)
        headIndexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_LINES, headIndexCount, GLES20.GL_UNSIGNED_SHORT, headIndexBuffer)
      }
    }

    GLES20.glDisableVertexAttribArray(posAttr)
    GLES20.glDisableVertexAttribArray(normAttr)
    GLES20.glDisableVertexAttribArray(colAttr)

    // 2. Render Blender-style 3D Octahedral Face Bones ("In Front" X-Ray, depth-tested against each other)
    val faceBones = localSegments.filter { it.isFaceBone && it.name != "face" }
    if (showSkeleton && faceBones.isNotEmpty()) {
      GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
      GLES20.glEnable(GLES20.GL_DEPTH_TEST)
      GLES20.glDisable(GLES20.GL_CULL_FACE)
      GLES20.glEnable(GLES20.GL_BLEND)
      GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

      val triPos = FloatArray(faceBones.size * 24 * 3)
      val triNorm = FloatArray(faceBones.size * 24 * 3)
      val triCol = FloatArray(faceBones.size * 24 * 3)
      val edgePos = FloatArray(faceBones.size * 24 * 3)
      val jointPos = FloatArray(faceBones.size * 2 * 3)

      var tFloat = 0
      var eFloat = 0
      var jFloat = 0

      for (bone in faceBones) {
        val hx = bone.hx; val hy = bone.hy; val hz = bone.hz
        val tx = bone.tx; val ty = bone.ty; val tz = bone.tz
        val dx = tx - hx; val dy = ty - hy; val dz = tz - hz
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-5f) continue

        val ux = dx / len; val uy = dy / len; val uz = dz / len
        // Orthogonal basis (rx, ry, rz) & (fx, fy, fz)
        val ax = if (abs(uz) < 0.85f) 0f else 1f
        val ay = 0f
        val az = if (abs(uz) < 0.85f) 1f else 0f
        var rx = uy * az - uz * ay
        var ry = uz * ax - ux * az
        var rz = ux * ay - uy * ax
        val rLen = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(1e-5f)
        rx /= rLen; ry /= rLen; rz /= rLen

        val fx = uy * rz - uz * ry
        val fy = uz * rx - ux * rz
        val fz = ux * ry - uy * rx

        val midX = hx + dx * 0.16f
        val midY = hy + dy * 0.16f
        val midZ = hz + dz * 0.16f
        val radius = (len * 0.135f).coerceIn(0.0045f, 0.020f)

        val p0x = midX + rx * radius; val p0y = midY + ry * radius; val p0z = midZ + rz * radius
        val p1x = midX + fx * radius; val p1y = midY + fy * radius; val p1z = midZ + fz * radius
        val p2x = midX - rx * radius; val p2y = midY - ry * radius; val p2z = midZ - rz * radius
        val p3x = midX - fx * radius; val p3y = midY - fy * radius; val p3z = midZ - fz * radius

        val highlighted = bone.isActive || (showMarkers && isBoneInSelectedZone(bone.name, selectedMarkerIndex))
        val cr: Float
        val cg: Float
        val cb: Float
        when {
          bone.isActive -> { cr = 0.98f; cg = 0.72f; cb = 0.14f }
          highlighted -> { cr = 0.25f; cg = 0.86f; cb = 0.96f }
          else -> { cr = 0.80f; cg = 0.83f; cb = 0.88f }
        }

        fun pushTri(
          ax0: Float, ay0: Float, az0: Float,
          bx0: Float, by0: Float, bz0: Float,
          cx0: Float, cy0: Float, cz0: Float,
        ) {
          val e1x = bx0 - ax0; val e1y = by0 - ay0; val e1z = bz0 - az0
          val e2x = cx0 - ax0; val e2y = cy0 - ay0; val e2z = cz0 - az0
          var nx = e1y * e2z - e1z * e2y
          var ny = e1z * e2x - e1x * e2z
          var nz = e1x * e2y - e1y * e2x
          val nLen = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
          nx /= nLen; ny /= nLen; nz /= nLen

          val verts = floatArrayOf(ax0, ay0, az0, bx0, by0, bz0, cx0, cy0, cz0)
          for (v in 0 until 3) {
            triPos[tFloat] = verts[v * 3]
            triPos[tFloat + 1] = verts[v * 3 + 1]
            triPos[tFloat + 2] = verts[v * 3 + 2]
            triNorm[tFloat] = nx
            triNorm[tFloat + 1] = ny
            triNorm[tFloat + 2] = nz
            triCol[tFloat] = cr
            triCol[tFloat + 1] = cg
            triCol[tFloat + 2] = cb
            tFloat += 3
          }
        }

        // 4 base triangles (H -> ring)
        pushTri(hx, hy, hz, p1x, p1y, p1z, p0x, p0y, p0z)
        pushTri(hx, hy, hz, p2x, p2y, p2z, p1x, p1y, p1z)
        pushTri(hx, hy, hz, p3x, p3y, p3z, p2x, p2y, p2z)
        pushTri(hx, hy, hz, p0x, p0y, p0z, p3x, p3y, p3z)

        // 4 tip triangles (T -> ring)
        pushTri(tx, ty, tz, p0x, p0y, p0z, p1x, p1y, p1z)
        pushTri(tx, ty, tz, p1x, p1y, p1z, p2x, p2y, p2z)
        pushTri(tx, ty, tz, p2x, p2y, p2z, p3x, p3y, p3z)
        pushTri(tx, ty, tz, p3x, p3y, p3z, p0x, p0y, p0z)

        fun pushEdge(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
          edgePos[eFloat++] = x0; edgePos[eFloat++] = y0; edgePos[eFloat++] = z0
          edgePos[eFloat++] = x1; edgePos[eFloat++] = y1; edgePos[eFloat++] = z1
        }
        pushEdge(hx, hy, hz, p0x, p0y, p0z); pushEdge(hx, hy, hz, p1x, p1y, p1z)
        pushEdge(hx, hy, hz, p2x, p2y, p2z); pushEdge(hx, hy, hz, p3x, p3y, p3z)
        pushEdge(tx, ty, tz, p0x, p0y, p0z); pushEdge(tx, ty, tz, p1x, p1y, p1z)
        pushEdge(tx, ty, tz, p2x, p2y, p2z); pushEdge(tx, ty, tz, p3x, p3y, p3z)
        pushEdge(p0x, p0y, p0z, p1x, p1y, p1z); pushEdge(p1x, p1y, p1z, p2x, p2y, p2z)
        pushEdge(p2x, p2y, p2z, p3x, p3y, p3z); pushEdge(p3x, p3y, p3z, p0x, p0y, p0z)

        jointPos[jFloat++] = hx; jointPos[jFloat++] = hy; jointPos[jFloat++] = hz
        jointPos[jFloat++] = tx; jointPos[jFloat++] = ty; jointPos[jFloat++] = tz
      }

      val triVertCount = tFloat / 3
      if (triVertCount > 0) {
        boneTriPosBuffer.position(0); boneTriPosBuffer.put(triPos, 0, tFloat); boneTriPosBuffer.position(0)
        boneTriNormBuffer.position(0); boneTriNormBuffer.put(triNorm, 0, tFloat); boneTriNormBuffer.position(0)
        boneTriColBuffer.position(0); boneTriColBuffer.put(triCol, 0, tFloat); boneTriColBuffer.position(0)

        GLES20.glUseProgram(boneProgram)
        val bPos = GLES20.glGetAttribLocation(boneProgram, "aPosition")
        val bNorm = GLES20.glGetAttribLocation(boneProgram, "aNormal")
        val bCol = GLES20.glGetAttribLocation(boneProgram, "aColor")
        val bMvp = GLES20.glGetUniformLocation(boneProgram, "uMvpMatrix")
        val bMv = GLES20.glGetUniformLocation(boneProgram, "uMvMatrix")
        val bAlpha = GLES20.glGetUniformLocation(boneProgram, "uAlpha")

        GLES20.glEnableVertexAttribArray(bPos)
        GLES20.glVertexAttribPointer(bPos, 3, GLES20.GL_FLOAT, false, 0, boneTriPosBuffer)
        GLES20.glEnableVertexAttribArray(bNorm)
        GLES20.glVertexAttribPointer(bNorm, 3, GLES20.GL_FLOAT, false, 0, boneTriNormBuffer)
        GLES20.glEnableVertexAttribArray(bCol)
        GLES20.glVertexAttribPointer(bCol, 3, GLES20.GL_FLOAT, false, 0, boneTriColBuffer)

        GLES20.glUniformMatrix4fv(bMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(bMv, 1, false, mvMatrix, 0)
        GLES20.glUniform1f(bAlpha, 0.86f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triVertCount)

        GLES20.glDisableVertexAttribArray(bPos)
        GLES20.glDisableVertexAttribArray(bNorm)
        GLES20.glDisableVertexAttribArray(bCol)
      }

      // Draw crisp dark Blender-style octahedral edge outlines + joint dots
      val edgeVertCount = eFloat / 3
      val jointVertCount = jFloat / 3
      GLES20.glUseProgram(lineProgram)
      val lPos = GLES20.glGetAttribLocation(lineProgram, "aPosition")
      val lMvp = GLES20.glGetUniformLocation(lineProgram, "uMvpMatrix")
      val lColor = GLES20.glGetUniformLocation(lineProgram, "uColor")
      val lPointSize = GLES20.glGetUniformLocation(lineProgram, "uPointSize")
      GLES20.glUniformMatrix4fv(lMvp, 1, false, mvpMatrix, 0)

      if (edgeVertCount > 0) {
        boneEdgeBuffer.position(0); boneEdgeBuffer.put(edgePos, 0, eFloat); boneEdgeBuffer.position(0)
        GLES20.glEnableVertexAttribArray(lPos)
        GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 0, boneEdgeBuffer)
        GLES20.glLineWidth(1.5f)
        GLES20.glUniform4f(lColor, 0.08f, 0.11f, 0.15f, 0.90f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, edgeVertCount)
        GLES20.glDisableVertexAttribArray(lPos)
      }

      if (jointVertCount > 0) {
        boneJointBuffer.position(0); boneJointBuffer.put(jointPos, 0, jFloat); boneJointBuffer.position(0)
        GLES20.glEnableVertexAttribArray(lPos)
        GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 0, boneJointBuffer)
        GLES20.glUniform4f(lColor, 0.92f, 0.95f, 0.98f, 0.95f)
        GLES20.glUniform1f(lPointSize, 5.5f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, jointVertCount)
        GLES20.glDisableVertexAttribArray(lPos)
      }

      GLES20.glDisable(GLES20.GL_BLEND)
      GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    // 3. Render Landmark Handles on top in Rig Setup mode
    if (showMarkers && localMarkers != null) {
      val pts = localMarkers!!.toFullList()
      val markerCoords = FloatArray(pts.size * 3)
      for (i in pts.indices) {
        markerCoords[i * 3] = pts[i].x
        markerCoords[i * 3 + 1] = pts[i].y
        markerCoords[i * 3 + 2] = pts[i].z
      }
      markerPointBuffer.position(0)
      markerPointBuffer.put(markerCoords)
      markerPointBuffer.position(0)

      GLES20.glDisable(GLES20.GL_DEPTH_TEST)
      GLES20.glUseProgram(lineProgram)
      val lPosAttr = GLES20.glGetAttribLocation(lineProgram, "aPosition")
      val lMvp = GLES20.glGetUniformLocation(lineProgram, "uMvpMatrix")
      val lColor = GLES20.glGetUniformLocation(lineProgram, "uColor")
      val lPointSize = GLES20.glGetUniformLocation(lineProgram, "uPointSize")

      GLES20.glUniformMatrix4fv(lMvp, 1, false, mvpMatrix, 0)
      GLES20.glEnableVertexAttribArray(lPosAttr)
      GLES20.glVertexAttribPointer(lPosAttr, 3, GLES20.GL_FLOAT, false, 0, markerPointBuffer)

      for (i in pts.indices) {
        val isSelected = i == selectedMarkerIndex
        // Outer halo
        GLES20.glUniform4f(lColor, 0.05f, 0.07f, 0.11f, 0.95f)
        GLES20.glUniform1f(lPointSize, if (isSelected) 36f else 26f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, i, 1)

        // Bright ring
        GLES20.glUniform4f(lColor, 1f, 1f, 1f, 0.95f)
        GLES20.glUniform1f(lPointSize, if (isSelected) 30f else 21f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, i, 1)

        // Inner dot
        if (isSelected) {
          GLES20.glUniform4f(lColor, 1.0f, 0.76f, 0.03f, 1f)
          GLES20.glUniform1f(lPointSize, 22f)
        } else {
          GLES20.glUniform4f(lColor, 0.13f, 0.83f, 0.93f, 1f)
          GLES20.glUniform1f(lPointSize, 14f)
        }
        GLES20.glDrawArrays(GLES20.GL_POINTS, i, 1)
      }

      GLES20.glDisableVertexAttribArray(lPosAttr)
      GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
  }

  private fun isBoneInSelectedZone(boneName: String, markerIdx: Int): Boolean =
    when (markerIdx) {
      FaceMarkers.IDX_EYE_L -> boneName == "eye.L" || boneName.startsWith("lid.T.L") || boneName.startsWith("lid.B.L")
      FaceMarkers.IDX_EYE_R -> boneName == "eye.R" || boneName.startsWith("lid.T.R") || boneName.startsWith("lid.B.R")
      FaceMarkers.IDX_NOSE -> boneName.startsWith("nose")
      FaceMarkers.IDX_MOUTH_L -> boneName.startsWith("lip.T.L") || boneName.startsWith("lip.B.L") || boneName.startsWith("cheek.T.L")
      FaceMarkers.IDX_MOUTH_R -> boneName.startsWith("lip.T.R") || boneName.startsWith("lip.B.R") || boneName.startsWith("cheek.T.R")
      FaceMarkers.IDX_CHIN -> boneName.startsWith("chin") || boneName.startsWith("jaw")
      FaceMarkers.IDX_BROW_L -> boneName.startsWith("brow.B.L") || boneName.startsWith("brow.T.L") || boneName.startsWith("forehead.L")
      FaceMarkers.IDX_BROW_R -> boneName.startsWith("brow.B.R") || boneName.startsWith("brow.T.R") || boneName.startsWith("forehead.R")
      FaceMarkers.IDX_EAR_L -> boneName.startsWith("ear.L")
      FaceMarkers.IDX_EAR_R -> boneName.startsWith("ear.R")
      else -> false
    }

  private fun createProgram(vsCode: String, fsCode: String): Int {
    val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsCode)
    val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsCode)
    return GLES20.glCreateProgram().also { program ->
      GLES20.glAttachShader(program, vs)
      GLES20.glAttachShader(program, fs)
      GLES20.glLinkProgram(program)
    }
  }

  private fun compileShader(type: Int, code: String): Int =
    GLES20.glCreateShader(type).also { shader ->
      GLES20.glShaderSource(shader, code)
      GLES20.glCompileShader(shader)
    }

  companion object {
    private const val MESH_VERTEX_SHADER = """
      uniform mat4 uMvpMatrix;
      uniform mat4 uMvMatrix;
      attribute vec3 aPosition;
      attribute vec3 aNormal;
      attribute vec3 aColor;
      varying vec3 vNormalView;
      varying vec3 vPosView;
      varying vec3 vColor;
      void main() {
        vec4 pos4 = vec4(aPosition, 1.0);
        vPosView = (uMvMatrix * pos4).xyz;
        vNormalView = normalize((uMvMatrix * vec4(aNormal, 0.0)).xyz);
        vColor = aColor;
        gl_Position = uMvpMatrix * pos4;
      }
    """

    private const val MESH_FRAGMENT_SHADER = """
      precision mediump float;
      uniform vec3 uBaseColor;
      uniform float uUseVertexColor;
      varying vec3 vNormalView;
      varying vec3 vPosView;
      varying vec3 vColor;
      void main() {
        vec3 N = normalize(vNormalView);
        vec3 V = normalize(-vPosView);
        vec3 L1 = normalize(vec3(0.55, 0.75, 1.0));
        vec3 L2 = normalize(vec3(-0.8, 0.25, -0.4));
        float keyDiff = max(dot(N, L1), 0.0);
        float rimDiff = max(dot(N, L2), 0.0);
        float fresnel = pow(1.0 - max(dot(N, V), 0.0), 3.0);
        vec3 baseAlbedo = mix(uBaseColor, vColor, uUseVertexColor);
        vec3 lit = baseAlbedo * (0.34 + 0.68 * keyDiff)
                 + vec3(0.13, 0.83, 0.93) * (0.10 * rimDiff + 0.12 * fresnel);
        gl_FragColor = vec4(lit, 1.0);
      }
    """

    private const val BONE_VERTEX_SHADER = """
      uniform mat4 uMvpMatrix;
      uniform mat4 uMvMatrix;
      attribute vec3 aPosition;
      attribute vec3 aNormal;
      attribute vec3 aColor;
      varying vec3 vNormalView;
      varying vec3 vColor;
      void main() {
        vNormalView = normalize((uMvMatrix * vec4(aNormal, 0.0)).xyz);
        vColor = aColor;
        gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
      }
    """

    private const val BONE_FRAGMENT_SHADER = """
      precision mediump float;
      uniform float uAlpha;
      varying vec3 vNormalView;
      varying vec3 vColor;
      void main() {
        vec3 N = normalize(vNormalView);
        if (!gl_FrontFacing) N = -N;
        vec3 L1 = normalize(vec3(0.45, 0.80, 0.90));
        vec3 L2 = normalize(vec3(-0.6, -0.3, 0.5));
        float diff = max(dot(N, L1), 0.0);
        float fill = max(dot(N, L2), 0.0);
        vec3 lit = vColor * (0.42 + 0.56 * diff + 0.18 * fill);
        gl_FragColor = vec4(lit, uAlpha);
      }
    """

    private const val LINE_VERTEX_SHADER = """
      uniform mat4 uMvpMatrix;
      uniform float uPointSize;
      attribute vec3 aPosition;
      void main() {
        gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
        gl_PointSize = uPointSize;
      }
    """

    private const val LINE_FRAGMENT_SHADER = """
      precision mediump float;
      uniform vec4 uColor;
      void main() {
        gl_FragColor = uColor;
      }
    """
  }
}
