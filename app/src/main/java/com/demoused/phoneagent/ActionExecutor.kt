package com.demoused.phoneagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.coroutines.resume

class ActionExecutor(private val context: Context) {

    suspend fun execute(command: JSONObject, mediaProjection: MediaProjection?): JSONObject =
        withContext(Dispatchers.IO) {
            val action = command.getString("action")
            try {
                when (action) {
                    "screenshot" -> takeScreenshot(mediaProjection)
                    "read_file" -> readFile(command.getString("path"))
                    "write_file" -> writeFile(command.getString("path"), command.getString("content"))
                    "create_file" -> createFile(
                        command.getString("path"),
                        command.optString("content", "")
                    )
                    "list_dir" -> listDir(command.optString("path", "/sdcard"))
                    "delete_file" -> deleteFile(command.getString("path"))
                    else -> error("Unknown action: $action")
                }
            } catch (e: Exception) {
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }
            }
        }

    private suspend fun takeScreenshot(mediaProjection: MediaProjection?): JSONObject {
        if (mediaProjection == null) {
            return JSONObject().apply {
                put("success", false)
                put("error", "MediaProjection not granted")
            }
        }

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null

        return try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "PhoneAgentCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )

            delay(500)

            val image: Image? = imageReader.acquireLatestImage()
                ?: return JSONObject().apply {
                    put("success", false)
                    put("error", "Failed to capture frame")
                }

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()

            val baos = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
            croppedBitmap.recycle()

            val base64 = Base64.getEncoder().encodeToString(baos.toByteArray())

            JSONObject().apply {
                put("success", true)
                put("data", base64)
            }
        } finally {
            virtualDisplay?.release()
            imageReader.close()
        }
    }

    private fun readFile(path: String): JSONObject {
        val file = File(resolvePath(path))
        if (!file.exists()) return error("File not found: $path")
        if (!file.isFile) return error("Not a file: $path")
        val content = file.readText()
        return JSONObject().apply {
            put("success", true)
            put("data", content)
        }
    }

    private fun writeFile(path: String, content: String): JSONObject {
        val file = File(resolvePath(path))
        file.parentFile?.mkdirs()
        file.writeText(content)
        return JSONObject().apply { put("success", true) }
    }

    private fun createFile(path: String, content: String): JSONObject {
        val file = File(resolvePath(path))
        if (file.exists()) return error("File already exists: $path")
        file.parentFile?.mkdirs()
        file.createNewFile()
        if (content.isNotEmpty()) file.writeText(content)
        return JSONObject().apply { put("success", true) }
    }

    private fun listDir(path: String): JSONObject {
        val dir = File(resolvePath(path))
        if (!dir.exists()) return error("Directory not found: $path")
        if (!dir.isDirectory) return error("Not a directory: $path")
        val entries = JSONArray()
        dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))?.forEach { f ->
            entries.put(JSONObject().apply {
                put("name", f.name)
                put("is_dir", f.isDirectory)
                put("size", if (f.isFile) f.length() else 0)
            })
        }
        return JSONObject().apply {
            put("success", true)
            put("data", entries)
        }
    }

    private fun deleteFile(path: String): JSONObject {
        val file = File(resolvePath(path))
        if (!file.exists()) return error("File not found: $path")
        file.delete()
        return JSONObject().apply { put("success", true) }
    }

    private fun resolvePath(path: String): String {
        if (path.startsWith("/")) return path
        return "${Environment.getExternalStorageDirectory()}/$path"
    }

    private fun error(msg: String) = JSONObject().apply {
        put("success", false)
        put("error", msg)
    }
}
