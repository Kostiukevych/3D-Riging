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
import kotlin.math.hypot
import kotlin.math.tan

class FaceRigGlSurfaceView(
  context: Context,
  private val engine: CharacterRigEngine,
  private val onSnapshot: (FaceDebugSnapshot) -> Unit,
  private val onMarkerSelected: (Int) -> Unit = {},
  private val onMarkerDragged: (Int, Vec3) -> Unit = { _, _ -> },
) : GLSurfaceView(context) {

  private val density = context.resources.displayMetrics.density
  private val renderer = FaceRigRenderer(engine) { snapshot -> post { onSnapshot(snapshot) } }
  private var lastX = 0f
  private var lastY = 0f
  private var isDragging = false
  private var draggingMarker = false

  private val scaleDetector =
    ScaleGestureDetector(
      context,
      object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
          renderer.zoom = (renderer.zoom / detector.scaleFactor).coerceIn(1.0f, 5.0f)
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

  var selectedMarkerIndex: Int
    get() = renderer.selectedMarkerIndex
    set(value) {
      renderer.selectedMarkerIndex = value
    }

  fun resetCamera() {
    renderer.yawDeg = 18f
    renderer.pitchDeg = 8f
    renderer.zoom = 1.95f
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
          val hitIdx = renderer.pickMarker(event.x, event.y, 44f * density)
          if (hitIdx >= 0) {
            renderer.selectedMarkerIndex = hitIdx
            onMarkerSelected(hitIdx)
          }
          draggingMarker = true
        }
      }
      MotionEvent.ACTION_MOVE -> {
        if (isDragging && !scaleDetector.isInProgress && event.pointerCount == 1) {
          val dx = event.x - lastX
          val dy = event.y - lastY
          if (draggingMarker && editMarkersMode) {
            val delta = renderer.screenDeltaToBlender(dx, dy)
            val idx = renderer.selectedMarkerIndex
            if (idx in 0..5) {
              onMarkerDragged(idx, delta)
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
) : GLSurfaceView.Renderer {

  @Volatile var yawDeg = 18f
  @Volatile var pitchDeg = 8f
  @Volatile var zoom = 1.95f
  @Volatile var showSkeleton = true
  @Volatile var showWireframe = false
  @Volatile var showMarkers = false
  @Volatile var selectedMarkerIndex = 2

  private var lastFrameNanos = 0L
  private var frameCounter = 0
  private var uploadedRevision = -1
  private var hasVertexColors = false

  private var viewportWidth = 1
  private var viewportHeight = 1

  private var meshProgram = 0
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
  private val projectedMarkerX = FloatArray(6)
  private val projectedMarkerY = FloatArray(6)
  private var projectedMarkersValid = false

  private lateinit var posBuffer: FloatBuffer
  private lateinit var normBuffer: FloatBuffer
  private lateinit var colorBuffer: FloatBuffer
  private lateinit var headIndexBuffer: ShortBuffer
  private lateinit var bodyIndexBuffer: ShortBuffer
  private var headIndexCount = 0
  private var bodyIndexCount = 0

  private lateinit var boneLineBuffer: FloatBuffer
  private val markerPointBuffer: FloatBuffer =
    ByteBuffer.allocateDirect(6 * 3 * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()

  fun pickMarker(touchX: Float, touchY: Float, maxRadiusPx: Float): Int {
    synchronized(mvSnapshotLock) {
      if (!projectedMarkersValid) return -1
      var bestIdx = -1
      var bestDist = maxRadiusPx
      for (i in 0 until 6) {
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

  override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
    GLES20.glClearColor(0.051f, 0.063f, 0.090f, 1f)
    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
    GLES20.glCullFace(GLES20.GL_BACK)

    meshProgram = createProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
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
            put(FloatArray(vertFloatCount) { 0.8f })
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

    boneLineBuffer =
      ByteBuffer.allocateDirect(engine.skeleton.size * 2 * 3 * 4)
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
    var targetY = 0.78f

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
      targetY - 0.06f,
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
      val pts = markers.toList()
      val inVec = FloatArray(4)
      val outVec = FloatArray(4)
      synchronized(mvSnapshotLock) {
        System.arraycopy(mvMatrix, 0, mvSnapshot, 0, 16)
        for (i in pts.indices) {
          inVec[0] = pts[i].x
          inVec[1] = pts[i].y
          inVec[2] = pts[i].z
          inVec[3] = 1f
          Matrix.multiplyMV(outVec, 0, mvpMatrix, 0, inVec, 0)
          val w = if (kotlin.math.abs(outVec[3]) > 1e-5f) outVec[3] else 1f
          val ndcX = outVec[0] / w
          val ndcY = outVec[1] / w
          projectedMarkerX[i] = (ndcX * 0.5f + 0.5f) * viewportWidth
          projectedMarkerY[i] = (0.5f - ndcY * 0.5f) * viewportHeight
        }
        projectedMarkersValid = true
      }
    }

    posBuffer.position(0)
    posBuffer.put(posData)
    posBuffer.position(0)

    normBuffer.position(0)
    normBuffer.put(normData)
    normBuffer.position(0)

    colorBuffer.position(0)

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
      GLES20.glUniform3f(uColor, 0.78f, 0.84f, 0.90f)
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

    if (showSkeleton && localSegments.isNotEmpty()) {
      GLES20.glDisable(GLES20.GL_DEPTH_TEST)
      GLES20.glUseProgram(lineProgram)
      val lPosAttr = GLES20.glGetAttribLocation(lineProgram, "aPosition")
      val lMvp = GLES20.glGetUniformLocation(lineProgram, "uMvpMatrix")
      val lColor = GLES20.glGetUniformLocation(lineProgram, "uColor")
      val lPointSize = GLES20.glGetUniformLocation(lineProgram, "uPointSize")

      GLES20.glUniformMatrix4fv(lMvp, 1, false, mvpMatrix, 0)

      val coords = FloatArray(localSegments.size * 6)
      for (i in localSegments.indices) {
        val s = localSegments[i]
        coords[i * 6] = s.hx
        coords[i * 6 + 1] = s.hy
        coords[i * 6 + 2] = s.hz
        coords[i * 6 + 3] = s.tx
        coords[i * 6 + 4] = s.ty
        coords[i * 6 + 5] = s.tz
      }
      boneLineBuffer.position(0)
      boneLineBuffer.put(coords)
      boneLineBuffer.position(0)

      GLES20.glEnableVertexAttribArray(lPosAttr)
      GLES20.glVertexAttribPointer(lPosAttr, 3, GLES20.GL_FLOAT, false, 0, boneLineBuffer)

      for (i in localSegments.indices) {
        val s = localSegments[i]
        when {
          s.isActive -> {
            GLES20.glLineWidth(6f)
            GLES20.glUniform4f(lColor, 0.98f, 0.75f, 0.14f, 1f)
            GLES20.glUniform1f(lPointSize, 14f)
          }
          s.isParentHead -> {
            GLES20.glLineWidth(4f)
            GLES20.glUniform4f(lColor, 0.51f, 0.55f, 0.97f, 1f)
            GLES20.glUniform1f(lPointSize, 9f)
          }
          !s.isFaceBone -> {
            GLES20.glLineWidth(3.5f)
            GLES20.glUniform4f(lColor, 0.51f, 0.55f, 0.97f, 0.9f)
            GLES20.glUniform1f(lPointSize, 8f)
          }
          else -> {
            GLES20.glLineWidth(2.2f)
            GLES20.glUniform4f(lColor, 0.13f, 0.83f, 0.93f, 0.85f)
            GLES20.glUniform1f(lPointSize, 6f)
          }
        }
        GLES20.glDrawArrays(GLES20.GL_LINES, i * 2, 2)
        GLES20.glDrawArrays(GLES20.GL_POINTS, i * 2, 1)
      }

      GLES20.glDisableVertexAttribArray(lPosAttr)
      GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    if (showMarkers && localMarkers != null) {
      val pts = localMarkers!!.toList()
      val markerCoords = FloatArray(18)
      for (i in 0 until 6) {
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

      for (i in 0 until 6) {
        val isSelected = i == selectedMarkerIndex
        // Outer white halo
        GLES20.glUniform4f(lColor, 1f, 1f, 1f, 0.95f)
        GLES20.glUniform1f(lPointSize, if (isSelected) 30f else 22f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, i, 1)

        // Inner marker dot
        if (isSelected) {
          GLES20.glUniform4f(lColor, 1.0f, 0.76f, 0.03f, 1f)
          GLES20.glUniform1f(lPointSize, 22f)
        } else {
          GLES20.glUniform4f(lColor, 0.96f, 0.26f, 0.56f, 1f)
          GLES20.glUniform1f(lPointSize, 15f)
        }
        GLES20.glDrawArrays(GLES20.GL_POINTS, i, 1)
      }

      GLES20.glDisableVertexAttribArray(lPosAttr)
      GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
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
                 + vec3(0.13, 0.83, 0.93) * (0.12 * rimDiff + 0.14 * fresnel);
        gl_FragColor = vec4(lit, 1.0);
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
