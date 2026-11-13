package com.example.facerig

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class FaceRigGlSurfaceView(
  context: Context,
  private val engine: CharacterRigEngine,
  private val onSnapshot: (FaceDebugSnapshot) -> Unit,
) : GLSurfaceView(context) {

  private val renderer = FaceRigRenderer(engine) { snapshot -> post { onSnapshot(snapshot) } }
  private var lastX = 0f
  private var lastY = 0f
  private var isDragging = false

  private val scaleDetector =
    ScaleGestureDetector(
      context,
      object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
          renderer.zoom = (renderer.zoom / detector.scaleFactor).coerceIn(1.1f, 4.5f)
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
      }
      MotionEvent.ACTION_MOVE -> {
        if (isDragging && !scaleDetector.isInProgress && event.pointerCount == 1) {
          val dx = event.x - lastX
          val dy = event.y - lastY
          renderer.yawDeg += dx * 0.45f
          renderer.pitchDeg = (renderer.pitchDeg + dy * 0.35f).coerceIn(-55f, 55f)
          lastX = event.x
          lastY = event.y
        }
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        isDragging = false
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

  private var lastFrameNanos = 0L
  private var frameCounter = 0

  private var meshProgram = 0
  private var lineProgram = 0

  private val projMatrix = FloatArray(16)
  private val viewMatrix = FloatArray(16)
  private val blenderToGlMatrix =
    floatArrayOf(
      // Converts Blender (x, y, z) -> OpenGL Y-up (x, z, -y)
      1f, 0f, 0f, 0f,
      0f, 0f, -1f, 0f,
      0f, 1f, 0f, 0f,
      0f, 0f, 0f, 1f,
    )
  private val modelMatrix = FloatArray(16)
  private val mvMatrix = FloatArray(16)
  private val mvpMatrix = FloatArray(16)

  private lateinit var posBuffer: FloatBuffer
  private lateinit var normBuffer: FloatBuffer
  private lateinit var headIndexBuffer: ShortBuffer
  private lateinit var bodyIndexBuffer: ShortBuffer
  private var headIndexCount = 0
  private var bodyIndexCount = 0

  private lateinit var boneLineBuffer: FloatBuffer

  override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
    GLES20.glClearColor(0.051f, 0.063f, 0.090f, 1f)
    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
    GLES20.glCullFace(GLES20.GL_BACK)

    meshProgram = createProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
    lineProgram = createProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)

    val vertCount = engine.deformedPositions.size
    posBuffer =
      ByteBuffer.allocateDirect(vertCount * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    normBuffer =
      ByteBuffer.allocateDirect(vertCount * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    val headList = ArrayList<Short>()
    val bodyList = ArrayList<Short>()
    val allIndices = engine.mesh.triangleIndices
    val isHead = engine.mesh.isHeadTriangle
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
      ByteBuffer.allocateDirect(headIndexCount * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply {
          put(headList.toShortArray())
          position(0)
        }
    bodyIndexBuffer =
      ByteBuffer.allocateDirect(bodyIndexCount * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply {
          put(bodyList.toShortArray())
          position(0)
        }

    // 2 vertices * 3 floats per bone
    boneLineBuffer =
      ByteBuffer.allocateDirect(engine.skeleton.size * 2 * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    lastFrameNanos = System.nanoTime()
  }

  override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
    GLES20.glViewport(0, 0, width, height)
    val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
    Matrix.perspectiveM(projMatrix, 0, 38f, aspect, 0.1f, 25f)
  }

  override fun onDrawFrame(unused: GL10?) {
    val now = System.nanoTime()
    val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1_000_000_000.0).toFloat()
    lastFrameNanos = now

    val snapshot = engine.step(dt)
    if (frameCounter++ % 3 == 0) {
      onSnapshot(snapshot)
    }

    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

    // Camera targets head center (y = 0.78 in OpenGL Y-up)
    Matrix.setLookAtM(
      viewMatrix,
      0,
      0f,
      0.78f,
      zoom,
      0f,
      0.72f,
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

    posBuffer.position(0)
    posBuffer.put(engine.deformedPositions)
    posBuffer.position(0)

    normBuffer.position(0)
    normBuffer.put(engine.deformedNormals)
    normBuffer.position(0)

    // 1. Draw shaded character mesh (head + body)
    GLES20.glUseProgram(meshProgram)
    val posAttr = GLES20.glGetAttribLocation(meshProgram, "aPosition")
    val normAttr = GLES20.glGetAttribLocation(meshProgram, "aNormal")
    val uMvp = GLES20.glGetUniformLocation(meshProgram, "uMvpMatrix")
    val uMv = GLES20.glGetUniformLocation(meshProgram, "uMvMatrix")
    val uColor = GLES20.glGetUniformLocation(meshProgram, "uBaseColor")

    GLES20.glEnableVertexAttribArray(posAttr)
    GLES20.glVertexAttribPointer(posAttr, 3, GLES20.GL_FLOAT, false, 0, posBuffer)
    GLES20.glEnableVertexAttribArray(normAttr)
    GLES20.glVertexAttribPointer(normAttr, 3, GLES20.GL_FLOAT, false, 0, normBuffer)

    GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
    GLES20.glUniformMatrix4fv(uMv, 1, false, mvMatrix, 0)

    // Draw torso/neck
    GLES20.glUniform3f(uColor, 0.30f, 0.38f, 0.50f)
    bodyIndexBuffer.position(0)
    GLES20.glDrawElements(GLES20.GL_TRIANGLES, bodyIndexCount, GLES20.GL_UNSIGNED_SHORT, bodyIndexBuffer)

    // Draw head
    GLES20.glUniform3f(uColor, 0.78f, 0.84f, 0.90f)
    headIndexBuffer.position(0)
    GLES20.glDrawElements(GLES20.GL_TRIANGLES, headIndexCount, GLES20.GL_UNSIGNED_SHORT, headIndexBuffer)

    if (showWireframe) {
      GLES20.glUniform3f(uColor, 0.13f, 0.83f, 0.93f)
      GLES20.glLineWidth(1.2f)
      headIndexBuffer.position(0)
      GLES20.glDrawElements(GLES20.GL_LINES, headIndexCount, GLES20.GL_UNSIGNED_SHORT, headIndexBuffer)
    }

    GLES20.glDisableVertexAttribArray(posAttr)
    GLES20.glDisableVertexAttribArray(normAttr)

    // 2. Draw skeleton bones overlay (body -> head -> face -> facial bones)
    if (showSkeleton && engine.boneSegments.isNotEmpty()) {
      GLES20.glDisable(GLES20.GL_DEPTH_TEST)
      GLES20.glUseProgram(lineProgram)
      val lPosAttr = GLES20.glGetAttribLocation(lineProgram, "aPosition")
      val lMvp = GLES20.glGetUniformLocation(lineProgram, "uMvpMatrix")
      val lColor = GLES20.glGetUniformLocation(lineProgram, "uColor")
      val lPointSize = GLES20.glGetUniformLocation(lineProgram, "uPointSize")

      GLES20.glUniformMatrix4fv(lMvp, 1, false, mvpMatrix, 0)

      val segments = engine.boneSegments
      val coords = FloatArray(segments.size * 6)
      for (i in segments.indices) {
        val s = segments[i]
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

      // Draw each category cleanly
      for (i in segments.indices) {
        val s = segments[i]
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
      varying vec3 vNormalView;
      varying vec3 vPosView;
      void main() {
        vec4 pos4 = vec4(aPosition, 1.0);
        vPosView = (uMvMatrix * pos4).xyz;
        vNormalView = normalize((uMvMatrix * vec4(aNormal, 0.0)).xyz);
        gl_Position = uMvpMatrix * pos4;
      }
    """

    private const val MESH_FRAGMENT_SHADER = """
      precision mediump float;
      uniform vec3 uBaseColor;
      varying vec3 vNormalView;
      varying vec3 vPosView;
      void main() {
        vec3 N = normalize(vNormalView);
        vec3 V = normalize(-vPosView);
        vec3 L1 = normalize(vec3(0.6, 0.8, 1.0));
        vec3 L2 = normalize(vec3(-0.8, 0.2, -0.4));
        float keyDiff = max(dot(N, L1), 0.0);
        float rimDiff = max(dot(N, L2), 0.0);
        float fresnel = pow(1.0 - max(dot(N, V), 0.0), 3.0);
        vec3 lit = uBaseColor * (0.28 + 0.68 * keyDiff)
                 + vec3(0.13, 0.83, 0.93) * (0.18 * rimDiff + 0.22 * fresnel);
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
