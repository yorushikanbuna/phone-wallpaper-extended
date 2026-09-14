package com.example.extendwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
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
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
            ProtectionMode.OFF -> emptyList()
        }
        repository.ensure(models) { progress ->
            val label = if (progress.model == ModelRepository.Model.REAL) "真人模型" else "二次元模型"
            val percent = (progress.downloaded * 100 / progress.total).toInt().coerceIn(0, 100)
            onProgress("下载$label $percent%")
        }
        val real = if (mode == ProtectionMode.REAL) {
            onProgress("正在识别真人区域…")
            segmentReal(bitmap, repository.file(ModelRepository.Model.REAL))
                .resampleTo(bitmap.width, bitmap.height)
        } else null
        val anime = if (mode == ProtectionMode.ANIME) {
            onProgress("正在识别二次元人物…")
            segmentAnime(bitmap, repository.file(ModelRepository.Model.ANIME))
                .resampleTo(bitmap.width, bitmap.height)
        } else null
        val result = real ?: anime
        result?.let { logMask(mode, it) }
        return result
    }

    private fun segmentReal(bitmap: Bitmap, model: java.io.File): PersonMask {
        FileInputStream(model).use { input ->
            val mapped = input.channel.map(FileChannel.MapMode.READ_ONLY, 0, model.length())
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(mapped).build())
                .setRunningMode(RunningMode.IMAGE)
                // Keep the continuous foreground confidence. A category mask turns a
                // one-pixel hair strand into a hard 0/1 value before it is resized.
                .setOutputCategoryMask(false)
                .setOutputConfidenceMasks(true)
                .build()
            val segmenter = ImageSegmenter.createFromOptions(appContext, options)
            try {
                val result = segmenter.segment(BitmapImageBuilder(bitmap).build())
                val masks = result.confidenceMasks().orElseThrow { IllegalStateException("真人分割没有返回置信度蒙版") }
                if (masks.isEmpty()) throw IllegalStateException("真人分割没有返回置信度蒙版")
                val maskWidth = masks.first().width
                val maskHeight = masks.first().height
                val pixelCount = maskWidth * maskHeight
                val confidence = (0 until masks.size).map { index ->
                    val buffer = ByteBufferExtractor.extract(masks[index])
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()
                    buffer.rewind()
                    FloatArray(pixelCount).also { buffer.get(it) }
                }
                val alpha = ByteArray(pixelCount)
                for (i in alpha.indices) {
                    // 只合并头发、皮肤和衣物类别，避免把 others 类别中的背景误判带入保护区。
                    val foreground = if (confidence.size >= 5) {
                        var best = 0f
                        for (category in 1 until minOf(confidence.size, 5)) {
                            best = maxOf(best, confidence[category][i])
                        }
                        best
                    } else {
                        (1f - confidence[0][i]).coerceIn(0f, 1f)
                    }
                    alpha[i] = confidenceToAlpha(foreground, low = 0.15f, high = 0.6f)
                }
                return PersonMask(maskWidth, maskHeight, preserveFringe(alpha, maskWidth, maskHeight))
            } finally {
                segmenter.close()
            }
        }
    }

    private fun segmentAnime(bitmap: Bitmap, model: java.io.File): PersonMask {
        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(model.absolutePath, OrtSession.SessionOptions())
        try {
            val inputName = session.inputInfo.keys.first()
            val inputInfo = session.inputInfo[inputName]?.info as? TensorInfo
            val inputSize = inputInfo?.shape?.lastOrNull()?.toInt()?.takeIf { it > 0 } ?: 1024
            val scale = minOf(inputSize.toFloat() / bitmap.width, inputSize.toFloat() / bitmap.height)
            val scaledWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            val scaledHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
            val resized = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            val padded = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            padded.eraseColor(Color.BLACK)
            Canvas(padded).drawBitmap(
                resized,
                ((inputSize - scaledWidth) / 2f),
                ((inputSize - scaledHeight) / 2f),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            val inputData = FloatArray(1 * 3 * inputSize * inputSize)
            val pixels = IntArray(inputSize * inputSize)
            padded.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
            val plane = inputSize * inputSize
            for (i in pixels.indices) {
                val c = pixels[i]
                inputData[i] = ((c shr 16) and 0xff) / 255f
                inputData[plane + i] = ((c shr 8) and 0xff) / 255f
                inputData[plane * 2 + i] = (c and 0xff) / 255f
            }
            resized.recycle(); padded.recycle()
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))
            try {
                val outputs = session.run(mapOf(inputName to tensor))
                try {
                    val outputName = session.outputInfo.keys.first()
                    val output = outputs[0].getValue()
                    val values = ArrayList<Float>()
                    flatten(output, values)
                    if (values.isEmpty()) throw IllegalStateException("二次元分割没有返回蒙版")
                    val info = session.outputInfo[outputName]?.info as? TensorInfo
                    val shape = info?.shape
                    val outW = shape?.getOrNull(shape.size - 1)?.toInt()?.takeIf { it > 0 } ?: sqrt(values.size.toDouble()).roundToInt()
                    val outH = shape?.getOrNull(shape.size - 2)?.toInt()?.takeIf { it > 0 } ?: outW
                    val alpha = ByteArray(bitmap.width * bitmap.height)
                    val padX = (outW - (scaledWidth * outW / inputSize)).coerceAtLeast(0) / 2
                    val padY = (outH - (scaledHeight * outH / inputSize)).coerceAtLeast(0) / 2
                    val maskWidth = (scaledWidth * outW / inputSize).coerceAtLeast(1)
                    val maskHeight = (scaledHeight * outH / inputSize).coerceAtLeast(1)
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val sx = (padX + (x + 0.5f) * maskWidth / bitmap.width).toInt().coerceIn(0, outW - 1)
                        val sy = (padY + (y + 0.5f) * maskHeight / bitmap.height).toInt().coerceIn(0, outH - 1)
                        val score = values[(sy * outW + sx).coerceIn(0, values.lastIndex)]
                        alpha[y * bitmap.width + x] = confidenceToAlpha(
                            modelScoreToProbability(score), low = 0.06f, high = 0.48f
                        )
                    }
                    return PersonMask(bitmap.width, bitmap.height, preserveFringe(alpha, bitmap.width, bitmap.height))
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

    /** 将模型输出统一为概率，兼容已 sigmoid 的蒙版和原始 logits。 */
    private fun modelScoreToProbability(score: Float): Float {
        if (!score.isFinite()) return 0f
        if (score in 0f..1f) return score
        return 1f / (1f + exp(-score.coerceIn(-20f, 20f)))
    }

    /** Converts a model probability into a soft but conservative protection alpha. */
    private fun confidenceToAlpha(score: Float, low: Float, high: Float): Byte {
        val normalized = ((score - low) / (high - low)).coerceIn(0f, 1f)
        val smooth = normalized * normalized * (3f - 2f * normalized)
        return (smooth.pow(0.72f) * 255f).roundToInt().coerceIn(0, 255).toByte()
    }

    private fun logMask(mode: ProtectionMode, mask: PersonMask) {
        if (mask.alpha.isEmpty()) return
        var nonZero = 0
        var strong = 0
        var sum = 0L
        var maxAlpha = 0
        mask.alpha.forEach { value ->
            val alpha = value.toInt() and 0xff
            if (alpha > 0) nonZero++
            if (alpha >= 128) strong++
            sum += alpha
            maxAlpha = maxOf(maxAlpha, alpha)
        }
        val total = mask.alpha.size.toFloat()
        Log.d(
            TAG,
            "$mode mask: nonZero=${nonZero / total * 100f}% " +
                "strong=${strong / total * 100f}% mean=${sum / total} max=$maxAlpha"
        )
    }

    /** Adds a one-pixel, decaying fringe around confident foreground without blurring the core. */
    private fun preserveFringe(input: ByteArray, width: Int, height: Int): ByteArray {
        val result = ByteArray(input.size)
        input.copyInto(result)
        for (y in 0 until height) for (x in 0 until width) {
            var localMax = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, width - 1)
                val yy = (y + dy).coerceIn(0, height - 1)
                localMax = max(localMax, input[yy * width + xx].toInt() and 0xff)
            }
            val current = input[y * width + x].toInt() and 0xff
            if (localMax >= 180 && current < localMax) {
                result[y * width + x] = max(current, (localMax * 0.58f).roundToInt()).toByte()
            }
        }
        return result
    }

    private companion object {
        const val TAG = "PersonMasker"
    }
}
