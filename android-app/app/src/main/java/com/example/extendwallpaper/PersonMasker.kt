package com.example.extendwallpaper

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.FileInputStream
import java.lang.reflect.Array
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ProtectionMode { OFF, AUTO, REAL, ANIME }

/** A soft foreground mask. Values are 0..255 and can be sampled at another bitmap size. */
data class PersonMask(val width: Int, val height: Int, val alpha: ByteArray) {
    init { require(alpha.size == width * height) }

    fun valueAt(x: Int, y: Int, targetWidth: Int, targetHeight: Int): Int {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return 0
        val sx = ((x + 0.5f) * width / targetWidth).toInt().coerceIn(0, width - 1)
        val sy = ((y + 0.5f) * height / targetHeight).toInt().coerceIn(0, height - 1)
        return alpha[sy * width + sx].toInt() and 0xff
    }

    fun union(other: PersonMask): PersonMask {
        require(width == other.width && height == other.height)
        val result = ByteArray(alpha.size)
        for (i in alpha.indices) result[i] = maxOf(alpha[i].toInt() and 0xff, other.alpha[i].toInt() and 0xff).toByte()
        return PersonMask(width, height, result)
    }
}

/** Runs the on-device real-person and anime segmentation models. */
class PersonMasker(context: Context) {
    private val appContext = context.applicationContext
    private val repository = ModelRepository(appContext)

    suspend fun detect(
        bitmap: Bitmap,
        mode: ProtectionMode,
        onProgress: (String) -> Unit = {},
    ): PersonMask? {
        if (mode == ProtectionMode.OFF) return null
        val models = when (mode) {
            ProtectionMode.REAL -> listOf(ModelRepository.Model.REAL)
            ProtectionMode.ANIME -> listOf(ModelRepository.Model.ANIME)
            ProtectionMode.AUTO -> listOf(ModelRepository.Model.REAL, ModelRepository.Model.ANIME)
            ProtectionMode.OFF -> emptyList()
        }
        repository.ensure(models) { progress ->
            val label = if (progress.model == ModelRepository.Model.REAL) "真人模型" else "二次元模型"
            val percent = (progress.downloaded * 100 / progress.total).toInt().coerceIn(0, 100)
            onProgress("下载$label $percent%")
        }
        val real = if (mode == ProtectionMode.REAL || mode == ProtectionMode.AUTO) {
            onProgress("正在识别真人区域…")
            segmentReal(bitmap, repository.file(ModelRepository.Model.REAL))
        } else null
        val anime = if (mode == ProtectionMode.ANIME || mode == ProtectionMode.AUTO) {
            onProgress("正在识别二次元人物…")
            segmentAnime(bitmap, repository.file(ModelRepository.Model.ANIME))
        } else null
        return when {
            real != null && anime != null -> real.union(anime)
            real != null -> real
            else -> anime
        }
    }

    private fun segmentReal(bitmap: Bitmap, model: java.io.File): PersonMask {
        FileInputStream(model).use { input ->
            val mapped = input.channel.map(FileChannel.MapMode.READ_ONLY, 0, model.length())
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(mapped).build())
                .setRunningMode(RunningMode.IMAGE)
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(false)
                .build()
            val segmenter = ImageSegmenter.createFromOptions(appContext, options)
            try {
                val result = segmenter.segment(BitmapImageBuilder(bitmap).build())
                val maskImage = result.categoryMask().orElseThrow { IllegalStateException("真人分割没有返回蒙版") }
                val buffer = ByteBufferExtractor.extract(maskImage).apply { rewind() }
                val alpha = ByteArray(maskImage.width * maskImage.height)
                for (i in alpha.indices) alpha[i] = if ((buffer.get().toInt() and 0xff) > 0) 255.toByte() else 0
                return PersonMask(maskImage.width, maskImage.height, soften(alpha, maskImage.width, maskImage.height))
            } finally {
                segmenter.close()
            }
        }
    }

    private fun segmentAnime(bitmap: Bitmap, model: java.io.File): PersonMask {
        val inputSize = 640
        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputData = FloatArray(1 * 3 * inputSize * inputSize)
        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val plane = inputSize * inputSize
        for (i in pixels.indices) {
            val c = pixels[i]
            inputData[i] = ((c shr 16) and 0xff) / 255f
            inputData[plane + i] = ((c shr 8) and 0xff) / 255f
            inputData[plane * 2 + i] = (c and 0xff) / 255f
        }
        if (scaled !== bitmap) scaled.recycle()

        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(model.absolutePath, OrtSession.SessionOptions())
        try {
            val inputName = session.inputInfo.keys.first()
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))
            try {
                val outputs = session.run(mapOf(inputName to tensor))
                try {
                    val outputName = session.outputInfo.keys.first()
                    val output = outputs[outputName].value
                    val values = ArrayList<Float>()
                    flatten(output, values)
                    if (values.isEmpty()) throw IllegalStateException("二次元分割没有返回蒙版")
                    val info = session.outputInfo[outputName]?.info as? TensorInfo
                    val shape = info?.shape
                    val outW = shape?.getOrNull(shape.size - 1)?.toInt()?.takeIf { it > 0 } ?: sqrt(values.size.toDouble()).roundToInt()
                    val outH = shape?.getOrNull(shape.size - 2)?.toInt()?.takeIf { it > 0 } ?: outW
                    val alpha = ByteArray(bitmap.width * bitmap.height)
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val sx = ((x + 0.5f) * outW / bitmap.width).toInt().coerceIn(0, outW - 1)
                        val sy = ((y + 0.5f) * outH / bitmap.height).toInt().coerceIn(0, outH - 1)
                        val score = values[(sy * outW + sx).coerceIn(0, values.lastIndex)]
                        val normalized = ((score - 0.25f) / 0.5f).coerceIn(0f, 1f)
                        alpha[y * bitmap.width + x] = (normalized * normalized * (3f - 2f * normalized) * 255f).roundToInt().toByte()
                    }
                    return PersonMask(bitmap.width, bitmap.height, alpha)
                } finally {
                    outputs.close()
                }
            } finally {
                tensor.close()
            }
        } finally {
            session.close()
        }
    }

    private fun flatten(value: Any?, output: MutableList<Float>) {
        when (value) {
            null -> Unit
            is FloatArray -> value.forEach(output::add)
            is DoubleArray -> value.forEach { output.add(it.toFloat()) }
            is IntArray -> value.forEach { output.add(it.toFloat()) }
            is FloatBuffer -> { while (value.hasRemaining()) output.add(value.get()) }
            is Number -> output.add(value.toFloat())
            else -> if (value.javaClass.isArray) {
                for (i in 0 until Array.getLength(value)) flatten(Array.get(value, i), output)
            }
        }
    }

    private fun soften(input: ByteArray, width: Int, height: Int): ByteArray {
        val result = ByteArray(input.size)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0
            var count = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, width - 1)
                val yy = (y + dy).coerceIn(0, height - 1)
                sum += input[yy * width + xx].toInt() and 0xff
                count++
            }
            result[y * width + x] = (sum / count).toByte()
        }
        return result
    }
}
