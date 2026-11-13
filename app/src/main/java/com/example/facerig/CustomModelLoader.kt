package com.example.facerig

import android.content.Context
import android.net.Uri
import android.opengl.Matrix
import android.provider.OpenableColumns
import android.util.Base64
import com.example.Vec3
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

object CustomModelStorage {
  private const val DIR_NAME = "custom_models"
  private const val FILE_PREFIX = "active_model"
  private const val META_FILE = "active_model.ext"

  fun hasSavedModel(context: Context): Boolean {
    val extFile = File(File(context.filesDir, DIR_NAME), META_FILE)
    if (!extFile.exists()) return false
    val ext = extFile.readText().trim().lowercase()
    val modelFile = File(File(context.filesDir, DIR_NAME), "$FILE_PREFIX.$ext")
    return modelFile.exists() && modelFile.length() > 12L
  }

  fun getSavedModelFile(context: Context): Pair<File, String>? {
    val dir = File(context.filesDir, DIR_NAME)
    val extFile = File(dir, META_FILE)
    if (!extFile.exists()) return null
    val ext = extFile.readText().trim().lowercase()
    val modelFile = File(dir, "$FILE_PREFIX.$ext")
    return if (modelFile.exists() && modelFile.length() > 12L) modelFile to ext else null
  }

  fun saveFromUri(context: Context, uri: Uri): Pair<File, String> {
    val displayName = queryDisplayName(context, uri)
    val rawBytes =
      context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Не удалось открыть выбранный файл")

    val ext = detectFormatExtension(displayName, rawBytes)
    val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    // Clean old model files before saving new one
    dir.listFiles()?.forEach { f ->
      if (f.name.startsWith(FILE_PREFIX)) f.delete()
    }

    val target = File(dir, "$FILE_PREFIX.$ext")
    val tmp = File(dir, "$FILE_PREFIX.$ext.tmp")
    tmp.writeBytes(rawBytes)
    if (!tmp.renameTo(target)) {
      tmp.copyTo(target, overwrite = true)
      tmp.delete()
    }
    File(dir, META_FILE).writeText(ext)
    return target to ext
  }

  fun clearSavedModel(context: Context) {
    val dir = File(context.filesDir, DIR_NAME)
    if (dir.exists()) {
      dir.listFiles()?.forEach { it.delete() }
    }
  }

  private fun queryDisplayName(context: Context, uri: Uri): String {
    if (uri.scheme == "content") {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) return cursor.getString(idx) ?: ""
        }
      }
    }
    return uri.lastPathSegment ?: ""
  }

  fun detectFormatExtension(fileName: String, bytes: ByteArray): String {
    val lower = fileName.lowercase()
    when {
      lower.endsWith(".glb") -> return "glb"
      lower.endsWith(".gltf") -> return "gltf"
      lower.endsWith(".fbx") -> return "fbx"
    }
    if (bytes.size >= 4) {
      val magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
      if (magic == 0x46546C67) return "glb" // "glTF"
    }
    val headText = String(bytes, 0, min(bytes.size, 64), Charsets.UTF_8)
    if (headText.startsWith("Kaydara FBX Binary") || headText.contains("; FBX")) return "fbx"
    if (headText.trimStart().startsWith("{")) return "gltf"
    throw IllegalArgumentException("Неподдерживаемый формат файла. Выберите .glb, .gltf или .fbx")
  }
}

object CustomModelLoader {
  private val bundledDefaultCandidates =
    listOf(
      "models/Trevor.fbx" to "fbx",
      "models/trevor.fbx" to "fbx",
      "models/Trevor.glb" to "glb",
      "models/trevor.glb" to "glb",
    )

  fun loadFromFile(file: File, extHint: String? = null): CharacterMeshData {
    val bytes = file.readBytes()
    val ext = (extHint ?: CustomModelStorage.detectFormatExtension(file.name, bytes)).lowercase()
    return parseBytes(bytes, ext)
  }

  fun parseBytes(bytes: ByteArray, ext: String): CharacterMeshData =
    when (ext.lowercase()) {
      "glb" -> GlbMeshLoader.parseGlb(bytes)
      "gltf" -> GlbMeshLoader.parseGltf(bytes)
      "fbx" -> FbxMeshLoader.parseFbx(bytes)
      else -> throw IllegalArgumentException("Неподдерживаемый формат: .$ext")
    }

  fun loadDefaultAssetModel(context: Context): CharacterMeshData {
    val assetFiles = runCatching { context.assets.list("models")?.toSet() ?: emptySet() }.getOrDefault(emptySet())
    for ((assetPath, ext) in bundledDefaultCandidates) {
      val fileName = assetPath.substringAfterLast('/')
      if (fileName !in assetFiles) continue
      val parsed =
        runCatching {
          context.assets.open(assetPath).use { stream ->
            parseBytes(stream.readBytes(), ext)
          }
        }
      if (parsed.isSuccess) {
        return parsed.getOrThrow()
      }
      android.util.Log.w("CustomModelLoader", "Failed to load bundled $assetPath, falling back", parsed.exceptionOrNull())
    }
    return CharacterObjLoader.loadFromAssets(context)
  }
}

internal fun finalizeNormalizedMesh(
  rawPositions: FloatArray,
  rawIndices: IntArray,
  detectedHeadJointZ: Float?,
  detectedHeadBoneName: String?,
): CharacterMeshData {
  val totalVerts = rawPositions.size / 3
  require(totalVerts >= 3 && rawIndices.size >= 3) {
    "В файле не найдена треугольная геометрия"
  }
  require(totalVerts <= 65535) {
    "Модель слишком тяжёлая ($totalVerts вершин; максимум 65535)"
  }

  var minX = Float.POSITIVE_INFINITY
  var minY = Float.POSITIVE_INFINITY
  var minZ = Float.POSITIVE_INFINITY
  var maxX = Float.NEGATIVE_INFINITY
  var maxY = Float.NEGATIVE_INFINITY
  var maxZ = Float.NEGATIVE_INFINITY

  for (vi in 0 until totalVerts) {
    val x = rawPositions[vi * 3]
    val y = rawPositions[vi * 3 + 1]
    val z = rawPositions[vi * 3 + 2]
    minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
    maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
  }

  val totalHeight = (maxZ - minZ).coerceAtLeast(1e-4f)
  val totalWidth = (maxX - minX).coerceAtLeast(1e-4f)
  val totalDepth = (maxY - minY).coerceAtLeast(1e-4f)

  val headThresholdZ =
    when {
      detectedHeadJointZ != null &&
        detectedHeadJointZ > minZ + 0.35f * totalHeight &&
        detectedHeadJointZ < maxZ - 0.05f * totalHeight -> detectedHeadJointZ
      totalHeight > 1.85f * max(totalWidth, totalDepth) -> maxZ - 0.19f * totalHeight
      totalHeight > 1.35f * max(totalWidth, totalDepth) -> minZ + 0.36f * totalHeight
      else -> minZ
    }

  val headVerts = HashSet<Int>()
  var hMinX = Float.POSITIVE_INFINITY
  var hMinY = Float.POSITIVE_INFINITY
  var hMinZ = Float.POSITIVE_INFINITY
  var hMaxX = Float.NEGATIVE_INFINITY
  var hMaxY = Float.NEGATIVE_INFINITY
  var hMaxZ = Float.NEGATIVE_INFINITY

  for (vi in 0 until totalVerts) {
    val z = rawPositions[vi * 3 + 2]
    if (z >= headThresholdZ) {
      headVerts.add(vi)
      val x = rawPositions[vi * 3]
      val y = rawPositions[vi * 3 + 1]
      hMinX = min(hMinX, x); hMinY = min(hMinY, y); hMinZ = min(hMinZ, z)
      hMaxX = max(hMaxX, x); hMaxY = max(hMaxY, y); hMaxZ = max(hMaxZ, z)
    }
  }

  if (headVerts.isEmpty()) {
    for (vi in 0 until totalVerts) headVerts.add(vi)
    hMinX = minX; hMinY = minY; hMinZ = minZ
    hMaxX = maxX; hMaxY = maxY; hMaxZ = maxZ
  }

  val rawHeadHeight = (hMaxZ - hMinZ).coerceAtLeast(1e-4f)
  val scale = 1.0f / rawHeadHeight
  val rawHeadCenterX = (hMinX + hMaxX) * 0.5f
  val rawHeadCenterY = (hMinY + hMaxY) * 0.5f
  val rawHeadCenterZ = (hMinZ + hMaxZ) * 0.5f

  val normPositions = FloatArray(rawPositions.size)
  for (vi in 0 until totalVerts) {
    normPositions[vi * 3] = (rawPositions[vi * 3] - rawHeadCenterX) * scale
    normPositions[vi * 3 + 1] = (rawPositions[vi * 3 + 1] - rawHeadCenterY) * scale
    normPositions[vi * 3 + 2] = (rawPositions[vi * 3 + 2] - rawHeadCenterZ) * scale + 0.85f
  }

  val normHeadMin =
    Vec3(
      (hMinX - rawHeadCenterX) * scale,
      (hMinY - rawHeadCenterY) * scale,
      (hMinZ - rawHeadCenterZ) * scale + 0.85f,
    )
  val normHeadMax =
    Vec3(
      (hMaxX - rawHeadCenterX) * scale,
      (hMaxY - rawHeadCenterY) * scale,
      (hMaxZ - rawHeadCenterZ) * scale + 0.85f,
    )

  val triCount = rawIndices.size / 3
  val shortIndices = ShortArray(triCount * 3)
  val triIsHead = BooleanArray(triCount)
  for (t in 0 until triCount) {
    val ia = rawIndices[t * 3]
    val ib = rawIndices[t * 3 + 1]
    val ic = rawIndices[t * 3 + 2]
    shortIndices[t * 3] = ia.toShort()
    shortIndices[t * 3 + 1] = ib.toShort()
    shortIndices[t * 3 + 2] = ic.toShort()
    triIsHead[t] = (ia in headVerts) || (ib in headVerts) || (ic in headVerts)
  }

  return CharacterMeshData(
    restPositions = normPositions,
    triangleIndices = shortIndices,
    isHeadTriangle = triIsHead,
    headVertexIndices = headVerts,
    headBboxMin = normHeadMin,
    headBboxMax = normHeadMax,
    detectedHeadBoneName = detectedHeadBoneName,
  )
}

internal fun isHeadBoneName(rawName: String): Boolean {
  val clean = rawName.substringAfterLast(':').substringAfterLast('|').trim().lowercase()
  return clean == "head" || clean.endsWith("_head") || clean.endsWith(".head") || clean.contains("head")
}

object GlbMeshLoader {
  private const val GLB_MAGIC = 0x46546C67 // "glTF"
  private const val CHUNK_JSON = 0x4E4F534A // "JSON"
  private const val CHUNK_BIN = 0x004E4942 // "BIN\0"

  fun loadFromFile(file: File): CharacterMeshData = parseGlb(file.readBytes())

  fun parseGlb(bytes: ByteArray): CharacterMeshData {
    require(bytes.size >= 12) { "Файл слишком мал для формата GLB" }
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val magic = buf.getInt(0)
    val version = buf.getInt(4)
    val totalLen = buf.getInt(8)
    require(magic == GLB_MAGIC) { "Некорректный заголовок GLB" }
    require(version == 2) { "Неподдерживаемая версия glTF: $version" }
    require(totalLen <= bytes.size) { "Повреждённый или обрезанный GLB-файл" }

    var jsonText: String? = null
    var binBytes: ByteArray? = null

    var offset = 12
    while (offset + 8 <= totalLen) {
      val chunkLen = buf.getInt(offset)
      val chunkType = buf.getInt(offset + 4)
      val dataStart = offset + 8
      val dataEnd = dataStart + chunkLen
      require(chunkLen >= 0 && dataEnd <= totalLen) { "Повреждённый чанк GLB" }

      when (chunkType) {
        CHUNK_JSON -> jsonText = String(bytes, dataStart, chunkLen, Charsets.UTF_8)
        CHUNK_BIN -> binBytes = bytes.copyOfRange(dataStart, dataEnd)
      }
      offset = dataEnd
    }

    val root = JSONObject(jsonText ?: throw IllegalStateException("В GLB отсутствует JSON-секция"))
    val bin = binBytes ?: throw IllegalStateException("В GLB отсутствует бинарный буфер геометрии")
    return parseGltfDocument(root, bin)
  }

  fun parseGltf(bytes: ByteArray): CharacterMeshData {
    val root =
      runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
        .getOrElse { throw IllegalArgumentException("Некорректный JSON в файле .gltf") }

    val buffers = root.optJSONArray("buffers") ?: throw IllegalArgumentException("В .gltf нет буферов")
    if (buffers.length() == 0) throw IllegalArgumentException("В .gltf отсутствуют данные буфера")
    val firstBuffer = buffers.getJSONObject(0)
    val uri = firstBuffer.optString("uri", "")
    if (!uri.startsWith("data:", ignoreCase = true) || !uri.contains(";base64,", ignoreCase = true)) {
      throw IllegalArgumentException(
        "Файл .gltf ссылается на внешний .bin. Экспортируйте модель в .glb или .gltf со встроенным буфером",
      )
    }
    val b64 = uri.substringAfter("base64,")
    val bin = Base64.decode(b64, Base64.DEFAULT)
    return parseGltfDocument(root, bin)
  }

  private fun parseGltfDocument(root: JSONObject, bin: ByteArray): CharacterMeshData {
    val binBuf = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
    val accessors = root.optJSONArray("accessors") ?: JSONArray()
    val bufferViews = root.optJSONArray("bufferViews") ?: JSONArray()
    val meshes = root.optJSONArray("meshes") ?: JSONArray()
    val nodes = root.optJSONArray("nodes") ?: JSONArray()

    val rawPositions = ArrayList<Float>()
    val rawIndices = ArrayList<Int>()
    var detectedHeadJointZ: Float? = null
    var detectedHeadBoneName: String? = null

    val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    val visitedNodes = HashSet<Int>()

    fun traverseNode(nodeIdx: Int, parentWorld: FloatArray) {
      if (nodeIdx < 0 || nodeIdx >= nodes.length() || !visitedNodes.add(nodeIdx)) return
      val node = nodes.getJSONObject(nodeIdx)
      val local = readNodeMatrix(node)
      val world = FloatArray(16)
      Matrix.multiplyMM(world, 0, parentWorld, 0, local, 0)

      val nodeName = node.optString("name", "")
      if (nodeName.isNotBlank() && isHeadBoneName(nodeName)) {
        if (detectedHeadBoneName == null || nodeName.equals("head", ignoreCase = true) || nodeName.equals("mixamorig:Head", ignoreCase = true)) {
          detectedHeadBoneName = nodeName
          detectedHeadJointZ = world[13]
        }
      }

      if (node.has("mesh")) {
        val meshIdx = node.getInt("mesh")
        if (meshIdx in 0 until meshes.length()) {
          val meshObj = meshes.getJSONObject(meshIdx)
          val primitives = meshObj.optJSONArray("primitives") ?: JSONArray()
          for (pIdx in 0 until primitives.length()) {
            val prim = primitives.getJSONObject(pIdx)
            val mode = prim.optInt("mode", 4)
            if (mode != 4) continue
            val attrs = prim.optJSONObject("attributes") ?: continue
            if (!attrs.has("POSITION")) continue

            val posAccessorIdx = attrs.getInt("POSITION")
            val primVerts = readVec3Accessor(accessors, bufferViews, binBuf, posAccessorIdx)
            val baseVertex = rawPositions.size / 3

            for (v in primVerts) {
              val gx = world[0] * v[0] + world[4] * v[1] + world[8] * v[2] + world[12]
              val gy = world[1] * v[0] + world[5] * v[1] + world[9] * v[2] + world[13]
              val gz = world[2] * v[0] + world[6] * v[1] + world[10] * v[2] + world[14]
              val b = gltfToBlender(Vec3(gx, gy, gz))
              rawPositions.add(b.x)
              rawPositions.add(b.y)
              rawPositions.add(b.z)
            }

            if (prim.has("indices")) {
              val idxAccessorIdx = prim.getInt("indices")
              val primIndices = readScalarAccessor(accessors, bufferViews, binBuf, idxAccessorIdx)
              for (idx in primIndices) rawIndices.add(baseVertex + idx)
            } else {
              for (k in primVerts.indices) rawIndices.add(baseVertex + k)
            }
          }
        }
      }

      val children = node.optJSONArray("children")
      if (children != null) {
        for (c in 0 until children.length()) traverseNode(children.getInt(c), world)
      }
    }

    val scenes = root.optJSONArray("scenes")
    val defaultScene = root.optInt("scene", 0)
    if (scenes != null && scenes.length() > 0) {
      val sceneObj = scenes.getJSONObject(defaultScene.coerceIn(0, scenes.length() - 1))
      val rootNodes = sceneObj.optJSONArray("nodes") ?: JSONArray()
      for (i in 0 until rootNodes.length()) traverseNode(rootNodes.getInt(i), identity)
    } else {
      for (i in 0 until nodes.length()) {
        if (i !in visitedNodes) traverseNode(i, identity)
      }
    }

    return finalizeNormalizedMesh(
      rawPositions = rawPositions.toFloatArray(),
      rawIndices = rawIndices.toIntArray(),
      detectedHeadJointZ = detectedHeadJointZ,
      detectedHeadBoneName = detectedHeadBoneName,
    )
  }

  private fun readNodeMatrix(node: JSONObject): FloatArray {
    val out = FloatArray(16)
    if (node.has("matrix")) {
      val arr = node.getJSONArray("matrix")
      for (i in 0 until 16) out[i] = arr.getDouble(i).toFloat()
      return out
    }
    Matrix.setIdentityM(out, 0)
    if (node.has("translation")) {
      val t = node.getJSONArray("translation")
      Matrix.translateM(out, 0, t.getDouble(0).toFloat(), t.getDouble(1).toFloat(), t.getDouble(2).toFloat())
    }
    if (node.has("rotation")) {
      val r = node.getJSONArray("rotation")
      val qMat = quatToMatrix(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat())
      val mul = FloatArray(16)
      Matrix.multiplyMM(mul, 0, out, 0, qMat, 0)
      System.arraycopy(mul, 0, out, 0, 16)
    }
    if (node.has("scale")) {
      val s = node.getJSONArray("scale")
      Matrix.scaleM(out, 0, s.getDouble(0).toFloat(), s.getDouble(1).toFloat(), s.getDouble(2).toFloat())
    }
    return out
  }

  private fun quatToMatrix(x: Float, y: Float, z: Float, w: Float): FloatArray {
    val xx = x * x; val yy = y * y; val zz = z * z
    val xy = x * y; val xz = x * z; val yz = y * z
    val wx = w * x; val wy = w * y; val wz = w * z
    return floatArrayOf(
      1f - 2f * (yy + zz), 2f * (xy + wz), 2f * (xz - wy), 0f,
      2f * (xy - wz), 1f - 2f * (xx + zz), 2f * (yz + wx), 0f,
      2f * (xz + wy), 2f * (yz - wx), 1f - 2f * (xx + yy), 0f,
      0f, 0f, 0f, 1f,
    )
  }

  private fun readVec3Accessor(
    accessors: JSONArray,
    bufferViews: JSONArray,
    binBuf: ByteBuffer,
    accessorIdx: Int,
  ): Array<FloatArray> {
    val acc = accessors.getJSONObject(accessorIdx)
    val count = acc.getInt("count")
    val bv = bufferViews.getJSONObject(acc.getInt("bufferView"))
    val baseOffset = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
    val stride = bv.optInt("byteStride", 12).let { if (it <= 0) 12 else it }
    return Array(count) { i ->
      val off = baseOffset + i * stride
      floatArrayOf(binBuf.getFloat(off), binBuf.getFloat(off + 4), binBuf.getFloat(off + 8))
    }
  }

  private fun readScalarAccessor(
    accessors: JSONArray,
    bufferViews: JSONArray,
    binBuf: ByteBuffer,
    accessorIdx: Int,
  ): IntArray {
    val acc = accessors.getJSONObject(accessorIdx)
    val count = acc.getInt("count")
    val componentType = acc.getInt("componentType")
    val bv = bufferViews.getJSONObject(acc.getInt("bufferView"))
    val baseOffset = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
    val defaultStride =
      when (componentType) {
        5121 -> 1
        5123 -> 2
        5125 -> 4
        else -> throw IllegalArgumentException("Неподдерживаемый тип индексов glTF: $componentType")
      }
    val stride = bv.optInt("byteStride", defaultStride).let { if (it <= 0) defaultStride else it }
    return IntArray(count) { i ->
      val off = baseOffset + i * stride
      when (componentType) {
        5121 -> binBuf.get(off).toInt() and 0xFF
        5123 -> binBuf.getShort(off).toInt() and 0xFFFF
        5125 -> binBuf.getInt(off)
        else -> 0
      }
    }
  }
}

object FbxMeshLoader {
  private val BINARY_MAGIC = "Kaydara FBX Binary  \u0000".toByteArray(Charsets.US_ASCII)

  fun parseFbx(bytes: ByteArray): CharacterMeshData {
    val isBinary =
      bytes.size >= 27 &&
        bytes.sliceArray(0 until BINARY_MAGIC.size).contentEquals(BINARY_MAGIC)
    return if (isBinary) parseBinaryFbx(bytes) else parseAsciiFbx(String(bytes, Charsets.UTF_8))
  }

  private fun parseAsciiFbx(text: String): CharacterMeshData {
    val positions = ArrayList<Float>()
    val indices = ArrayList<Int>()
    var upAxis = 1 // 1 = Y-up, 2 = Z-up
    var detectedHeadBoneName: String? = null

    val upMatch = Regex("""P:\s*"UpAxis"\s*,\s*"int"\s*,\s*"Integer"\s*,\s*""\s*,\s*(\d+)""").find(text)
    if (upMatch != null) {
      upAxis = upMatch.groupValues[1].toIntOrNull() ?: 1
    }

    val headBoneRegex = Regex("""Model:\s*\d+\s*,\s*"Model::([^"]+)"\s*,\s*"LimbNode"""")
    for (match in headBoneRegex.findAll(text)) {
      val name = match.groupValues[1]
      if (isHeadBoneName(name)) {
        detectedHeadBoneName = name
        break
      }
    }

    // Parse all Geometry blocks (Vertices + PolygonVertexIndex)
    val verticesBlockRegex = Regex("""Vertices:\s*\*\d+\s*\{\s*a:\s*([^}]+)\}""", setOf(RegexOption.DOT_MATCHES_ALL))
    val polyBlockRegex = Regex("""PolygonVertexIndex:\s*\*\d+\s*\{\s*a:\s*([^}]+)\}""", setOf(RegexOption.DOT_MATCHES_ALL))

    val vertMatches = verticesBlockRegex.findAll(text).toList()
    val polyMatches = polyBlockRegex.findAll(text).toList()

    require(vertMatches.isNotEmpty() && polyMatches.isNotEmpty()) {
      "В ASCII FBX не найдены секции Vertices и PolygonVertexIndex"
    }

    val count = min(vertMatches.size, polyMatches.size)
    for (mIdx in 0 until count) {
      val baseVertex = positions.size / 3
      val vTokens = vertMatches[mIdx].groupValues[1].split(',').mapNotNull { it.trim().toFloatOrNull() }
      var k = 0
      while (k + 2 < vTokens.size) {
        val x = vTokens[k]
        val y = vTokens[k + 1]
        val z = vTokens[k + 2]
        if (upAxis == 2) {
          positions.add(x); positions.add(y); positions.add(z)
        } else {
          positions.add(x); positions.add(-z); positions.add(y)
        }
        k += 3
      }

      val pTokens = polyMatches[mIdx].groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() }
      appendTriangulatedPolygons(pTokens, baseVertex, indices)
    }

    return finalizeNormalizedMesh(
      rawPositions = positions.toFloatArray(),
      rawIndices = indices.toIntArray(),
      detectedHeadJointZ = null,
      detectedHeadBoneName = detectedHeadBoneName,
    )
  }

  private fun parseBinaryFbx(bytes: ByteArray): CharacterMeshData {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val version = buf.getInt(23)
    val is64Bit = version >= 7500

    val positions = ArrayList<Float>()
    val indices = ArrayList<Int>()
    var upAxis = 1
    var detectedHeadBoneName: String? = null

    class FbxReader(var pos: Int) {
      fun readNode(): FbxNode? {
        val endOffset: Int
        val numProps: Int
        val propListLen: Int
        val nameLen: Int
        if (is64Bit) {
          if (pos + 25 > bytes.size) return null
          endOffset = buf.getInt(pos)
          numProps = buf.getInt(pos + 8)
          propListLen = buf.getInt(pos + 16)
          nameLen = bytes[pos + 24].toInt() and 0xFF
          pos += 25
        } else {
          if (pos + 13 > bytes.size) return null
          endOffset = buf.getInt(pos)
          numProps = buf.getInt(pos + 4)
          propListLen = buf.getInt(pos + 8)
          nameLen = bytes[pos + 12].toInt() and 0xFF
          pos += 13
        }
        if (endOffset == 0 && numProps == 0 && propListLen == 0 && nameLen == 0) {
          return null
        }
        val name = String(bytes, pos, nameLen, Charsets.UTF_8)
        pos += nameLen

        val propsEnd = pos + propListLen
        val props = ArrayList<Any?>(numProps)
        for (p in 0 until numProps) {
          props += readProperty()
        }
        pos = propsEnd

        val children = ArrayList<FbxNode>()
        val sentinelSize = if (is64Bit) 25 else 13
        while (pos + sentinelSize <= endOffset) {
          val child = readNode() ?: break
          children += child
        }
        pos = endOffset
        return FbxNode(name, props, children)
      }

      fun readProperty(): Any? {
        if (pos >= bytes.size) return null
        val typeCode = bytes[pos++].toInt().toChar()
        return when (typeCode) {
          'Y' -> buf.getShort(pos).also { pos += 2 }
          'C' -> (bytes[pos] != 0.toByte()).also { pos += 1 }
          'I' -> buf.getInt(pos).also { pos += 4 }
          'F' -> buf.getFloat(pos).also { pos += 4 }
          'D' -> buf.getDouble(pos).also { pos += 8 }
          'L' -> buf.getLong(pos).also { pos += 8 }
          'S', 'R' -> {
            val len = buf.getInt(pos)
            pos += 4
            val slice = bytes.copyOfRange(pos, pos + len)
            pos += len
            if (typeCode == 'S') String(slice, Charsets.UTF_8) else slice
          }
          'f', 'd', 'l', 'i', 'b' -> {
            val arrayLen = buf.getInt(pos)
            val encoding = buf.getInt(pos + 4)
            val compLen = buf.getInt(pos + 8)
            pos += 12
            val payload = bytes.copyOfRange(pos, pos + compLen)
            pos += compLen
            val elemSize =
              when (typeCode) {
                'd', 'l' -> 8
                'f', 'i' -> 4
                else -> 1
              }
            val raw =
              if (encoding == 1) inflateZlib(payload, arrayLen * elemSize) else payload
            val abuf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            when (typeCode) {
              'd' -> FloatArray(arrayLen) { idx -> abuf.getDouble(idx * 8).toFloat() }
              'f' -> FloatArray(arrayLen) { idx -> abuf.getFloat(idx * 4) }
              'i' -> IntArray(arrayLen) { idx -> abuf.getInt(idx * 4) }
              else -> raw
            }
          }
          else -> null
        }
      }
    }

    val reader = FbxReader(27)
    val rootNodes = ArrayList<FbxNode>()
    while (reader.pos < bytes.size - 13) {
      val node = reader.readNode() ?: break
      rootNodes += node
    }

    // Check GlobalSettings UpAxis
    rootNodes.firstOrNull { it.name == "GlobalSettings" }
      ?.children?.firstOrNull { it.name == "Properties70" }
      ?.children?.forEach { p ->
        if (p.props.firstOrNull() == "UpAxis") {
          val v = p.props.getOrNull(4) as? Int
          if (v != null) upAxis = v
        }
      }

    val objects = rootNodes.firstOrNull { it.name == "Objects" }
      ?: throw IllegalArgumentException("В бинарном FBX отсутствует секция Objects")

    for (child in objects.children) {
      if (child.name == "Model") {
        val rawName = (child.props.getOrNull(1) as? String)?.substringBefore('\u0000') ?: ""
        val type = child.props.getOrNull(2) as? String ?: ""
        if (type.equals("LimbNode", ignoreCase = true) && isHeadBoneName(rawName)) {
          detectedHeadBoneName = rawName
        }
      } else if (child.name == "Geometry") {
        val vertsNode = child.children.firstOrNull { it.name == "Vertices" }
        val polyNode = child.children.firstOrNull { it.name == "PolygonVertexIndex" }
        val verts = vertsNode?.props?.firstOrNull() as? FloatArray
        val poly = polyNode?.props?.firstOrNull() as? IntArray
        if (verts != null && poly != null) {
          val baseVertex = positions.size / 3
          var k = 0
          while (k + 2 < verts.size) {
            val x = verts[k]; val y = verts[k + 1]; val z = verts[k + 2]
            if (upAxis == 2) {
              positions.add(x); positions.add(y); positions.add(z)
            } else {
              positions.add(x); positions.add(-z); positions.add(y)
            }
            k += 3
          }
          appendTriangulatedPolygons(poly.toList(), baseVertex, indices)
        }
      }
    }

    return finalizeNormalizedMesh(
      rawPositions = positions.toFloatArray(),
      rawIndices = indices.toIntArray(),
      detectedHeadJointZ = null,
      detectedHeadBoneName = detectedHeadBoneName,
    )
  }

  private fun appendTriangulatedPolygons(
    polyTokens: List<Int>,
    baseVertex: Int,
    outIndices: MutableList<Int>,
  ) {
    val currentPoly = ArrayList<Int>(4)
    for (raw in polyTokens) {
      if (raw < 0) {
        currentPoly.add(baseVertex + (-raw - 1))
        if (currentPoly.size >= 3) {
          for (k in 1 until currentPoly.size - 1) {
            outIndices.add(currentPoly[0])
            outIndices.add(currentPoly[k])
            outIndices.add(currentPoly[k + 1])
          }
        }
        currentPoly.clear()
      } else {
        currentPoly.add(baseVertex + raw)
      }
    }
  }

  private fun inflateZlib(compressed: ByteArray, expectedBytes: Int): ByteArray {
    val inflater = Inflater()
    inflater.setInput(compressed)
    val out = ByteArrayOutputStream(expectedBytes.coerceAtLeast(1024))
    val tmp = ByteArray(4096)
    try {
      while (!inflater.finished()) {
        val count = inflater.inflate(tmp)
        if (count == 0) break
        out.write(tmp, 0, count)
      }
    } finally {
      inflater.end()
    }
    return out.toByteArray()
  }

  private class FbxNode(
    val name: String,
    val props: List<Any?>,
    val children: List<FbxNode>,
  )
}
