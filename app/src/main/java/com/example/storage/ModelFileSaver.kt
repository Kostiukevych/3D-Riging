package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface SaveResult {
  data class Saved(val fileName: String) : SaveResult

  data class RequiresDocumentPicker(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
  ) : SaveResult

  data class Failed(val reason: String) : SaveResult
}

object ModelFileSaver {

  suspend fun saveBase64Export(
    context: Context,
    base64Payload: String,
    rawFileName: String,
    rawMimeType: String,
  ): SaveResult =
    withContext(Dispatchers.IO) {
      try {
        val cleanBase64 =
          base64Payload.substringAfter("base64,", base64Payload).replace("\\s+".toRegex(), "")
        if (cleanBase64.isEmpty()) {
          return@withContext SaveResult.Failed("Empty export payload")
        }

        val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
        val mimeType = normalizeMimeType(rawMimeType, rawFileName)
        val fileName = sanitizeFileName(rawFileName, mimeType)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          saveViaMediaStoreDownloads(context, fileName, mimeType, bytes)
        } else {
          SaveResult.RequiresDocumentPicker(
            fileName = fileName,
            mimeType = mimeType,
            bytes = bytes,
          )
        }
      } catch (e: IllegalArgumentException) {
        SaveResult.Failed(e.localizedMessage ?: "Invalid Base64 data")
      } catch (e: OutOfMemoryError) {
        SaveResult.Failed("Exported 3D file exceeds available memory")
      } catch (e: Exception) {
        SaveResult.Failed(e.localizedMessage ?: "Unable to write file")
      }
    }

  suspend fun writeBytesToUri(
    context: Context,
    targetUri: Uri,
    bytes: ByteArray,
    fileName: String,
  ): SaveResult =
    withContext(Dispatchers.IO) {
      try {
        context.contentResolver.openOutputStream(targetUri)?.use { output ->
          output.write(bytes)
          output.flush()
        } ?: return@withContext SaveResult.Failed("Unable to open output destination")
        SaveResult.Saved(fileName)
      } catch (e: Exception) {
        SaveResult.Failed(e.localizedMessage ?: "Failed writing to selected file")
      }
    }

  private fun saveViaMediaStoreDownloads(
    context: Context,
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
  ): SaveResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      return SaveResult.Failed("MediaStore.Downloads requires Android 10+")
    }

    val resolver = context.contentResolver
    val values =
      ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
      }

    val uri =
      resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: return SaveResult.Failed("Could not create file in Downloads")

    return try {
      resolver.openOutputStream(uri)?.use { output ->
        output.write(bytes)
        output.flush()
      } ?: throw IllegalStateException("Output stream was null")

      val completed = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
      resolver.update(uri, completed, null, null)
      SaveResult.Saved(fileName)
    } catch (e: Exception) {
      resolver.delete(uri, null, null)
      SaveResult.Failed(e.localizedMessage ?: "Failed writing file to Downloads")
    }
  }

  fun sanitizeFileName(rawName: String, mimeType: String): String {
    val trimmed =
      rawName
        .trim()
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        .trimStart('.')
        .ifEmpty { "mesh2motion_export_${System.currentTimeMillis()}" }

    if (trimmed.contains('.')) {
      return trimmed
    }

    val ext =
      when {
        mimeType.contains("gltf-binary", ignoreCase = true) -> "glb"
        mimeType.contains("gltf+json", ignoreCase = true) -> "gltf"
        mimeType.contains("zip", ignoreCase = true) -> "zip"
        mimeType.contains("fbx", ignoreCase = true) -> "fbx"
        else -> "glb"
      }
    return "$trimmed.$ext"
  }

  fun normalizeMimeType(rawMimeType: String, fileName: String): String {
    val lowerName = fileName.lowercase()
    return when {
      lowerName.endsWith(".glb") -> "model/gltf-binary"
      lowerName.endsWith(".gltf") -> "model/gltf+json"
      lowerName.endsWith(".zip") -> "application/zip"
      rawMimeType.isNotBlank() && rawMimeType != "application/octet-stream" -> rawMimeType
      else -> "application/octet-stream"
    }
  }
}
