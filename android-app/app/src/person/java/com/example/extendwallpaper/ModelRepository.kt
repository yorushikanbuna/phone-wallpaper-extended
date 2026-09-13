package com.example.extendwallpaper

import android.content.Context
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Downloads the optional segmentation models once and keeps them in app-private storage. */
class ModelRepository(context: Context) {
    enum class Model { REAL, ANIME }

    data class Progress(val model: Model, val downloaded: Long, val total: Long)

    private data class Spec(
        val model: Model,
        val fileName: String,
        val url: String,
        val bytes: Long,
        val sha256: String,
    )

    private val directory = File(context.applicationContext.filesDir, "person-models").apply { mkdirs() }

    private val specs = listOf(
        Spec(
            Model.REAL,
            "selfie_multiclass_256x256.tflite",
            "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite",
            16_371_837L,
            "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0",
        ),
        Spec(
            Model.ANIME,
            "isnetis.onnx",
            "https://huggingface.co/skytnt/anime-seg/resolve/main/isnetis.onnx?download=true",
            176_069_933L,
            "f15622d853e8260172812b657053460e20806f04b9e05147d49af7bed31a6e99",
        ),
    )

    fun isReady(model: Model): Boolean {
        val spec = specs.first { it.model == model }
        val file = File(directory, spec.fileName)
        return file.length() == spec.bytes && sha256(file) == effectiveSha(spec)
    }

    fun file(model: Model): File {
        val spec = specs.first { it.model == model }
        return File(directory, spec.fileName)
    }

    suspend fun ensure(models: List<Model>, onProgress: (Progress) -> Unit = {}) {
        for (model in models.distinct()) {
            coroutineContext.ensureActive()
            val spec = specs.first { it.model == model }
            val destination = File(directory, spec.fileName)
            if (destination.length() == spec.bytes && sha256(destination) == effectiveSha(spec)) continue
            if (destination.exists()) destination.delete()
            download(spec, destination, onProgress)
        }
    }

    private suspend fun download(spec: Spec, destination: File, onProgress: (Progress) -> Unit) {
        val temporary = File(destination.parentFile, "${destination.name}.download")
        if (temporary.exists()) temporary.delete()
        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("模型下载失败（HTTP ${connection.responseCode}）")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: spec.bytes
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReport >= 256 * 1024 || downloaded == total) {
                            onProgress(Progress(spec.model, downloaded, total))
                            lastReport = downloaded
                        }
                    }
                }
            }
            if (temporary.length() != spec.bytes || sha256(temporary) != effectiveSha(spec)) {
                throw IllegalStateException("模型校验失败，请重试")
            }
            if (!temporary.renameTo(destination)) throw IllegalStateException("无法保存模型文件")
        } catch (e: Exception) {
            temporary.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun effectiveSha(spec: Spec): String = spec.sha256

    private fun sha256(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
