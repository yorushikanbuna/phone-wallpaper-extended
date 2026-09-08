package com.example.extendwallpaper

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.extendwallpaper.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var sourceBitmap: Bitmap? = null
    private var sourceUri: Uri? = null
    private var fillColor = Color.BLACK
    private var fillColor2 = Color.BLACK
    private var gradientPercent = 10
    private var extensionMode = WallpaperExtender.MODE_SOLID
    private var customColor = Color.BLACK
    private var customColorEnabled = false
    private var generateJob: Job? = null
    private var sampleJob: Job? = null
    private var updatingColorField = false

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? -> uri?.let { onImagePicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvPreviewEmpty.visibility = View.VISIBLE
        binding.btnGenerate.isEnabled = false
        binding.progressBar.visibility = View.GONE

        binding.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        binding.btnGenerate.setOnClickListener { generate() }

        val resolutionWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updatePreviewRatio()
        }
        binding.etPhoneWidth.addTextChangedListener(resolutionWatcher)
        binding.etPhoneHeight.addTextChangedListener(resolutionWatcher)

        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            extensionMode = when (checkedId) {
                binding.rbBlur.id -> WallpaperExtender.MODE_BLUR
                binding.rbMirror.id -> WallpaperExtender.MODE_MIRROR
                else -> WallpaperExtender.MODE_SOLID
            }
            updateModeUi()
            updatePreviewColors()
        }

        binding.rgPosition.setOnCheckedChangeListener { _, checkedId ->
            val position = positionFromUi()
            binding.cbSameColor.visibility =
                if (position == WallpaperExtender.POSITION_CENTER) View.VISIBLE else View.GONE
            binding.previewView.setPosition(position)
            updatePreviewColors()
        }

        binding.cbSameColor.setOnCheckedChangeListener { _, _ -> updatePreviewColors() }

        binding.swCustomColor.setOnCheckedChangeListener { _, checked ->
            customColorEnabled = checked
            updateColorPanelState()
            updatePreviewColors()
        }

        binding.etColorHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (updatingColorField) return
                val parsed = parseHexColor(s?.toString().orEmpty())
                if (parsed != null) {
                    customColor = parsed
                    binding.tilColorHex.error = null
                    updateColorSwatch()
                    updatePreviewColors()
                } else if (!s.isNullOrBlank() && s.length >= 6) {
                    binding.tilColorHex.error = "请输入 6 位 HEX 颜色"
                }
            }
        })

        binding.btnUseAutoColor.setOnClickListener {
            customColorEnabled = false
            binding.swCustomColor.isChecked = false
            customColor = fillColor
            setHexColorField(customColor)
            updateColorPanelState()
            updatePreviewColors()
        }

        binding.sbModifyZone.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: android.widget.SeekBar?,
                progress: Int,
                fromUser: Boolean,
            ) {
                gradientPercent = progress.coerceIn(1, 100)
                binding.tvModifyZone.text = "$gradientPercent%"
                binding.previewView.setFraction(gradientPercent / 100f)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })

        updateModeUi()
        updateColorPanelState()
        updateColorSwatch()
        updatePreviewRatio()
    }

    private fun onImagePicked(uri: Uri) {
        generateJob?.cancel()
        sampleJob?.cancel()
        val bitmap = loadBitmap(uri, sample = true) ?: return
        sourceUri = uri
        sourceBitmap?.takeIf { it !== bitmap }?.recycle()
        sourceBitmap = bitmap

        binding.tvPreviewEmpty.visibility = View.GONE
        binding.tvImageInfo.text = "已选择：${bitmap.width} × ${bitmap.height}"
        binding.btnGenerate.isEnabled = true
        binding.previewView.setBitmap(bitmap)
        updatePreviewRatio()

        sampleJob?.cancel()
        sampleJob = lifecycleScope.launch(Dispatchers.Default) {
            val topColor = sampleEdgeColor(bitmap, fromTop = true)
            val bottomColor = sampleEdgeColor(bitmap, fromTop = false)
            withContext(Dispatchers.Main) {
                if (sourceBitmap !== bitmap) return@withContext
                fillColor = topColor
                fillColor2 = bottomColor
                if (!customColorEnabled) {
                    customColor = topColor
                    setHexColorField(topColor)
                }
                updateColorSwatch()
                updatePreviewColors()
            }
        }
    }

    private fun updateModeUi() {
        binding.tvModeHint.setText(
            when (extensionMode) {
                WallpaperExtender.MODE_BLUR -> R.string.mode_blur_hint
                WallpaperExtender.MODE_MIRROR -> R.string.mode_mirror_hint
                else -> R.string.mode_solid_hint
            },
        )
        binding.customColorPanel.visibility =
            if (extensionMode == WallpaperExtender.MODE_SOLID) View.VISIBLE else View.GONE
        updateColorPanelState()
    }

    private fun updateColorPanelState() {
        val enabled = extensionMode == WallpaperExtender.MODE_SOLID
        binding.swCustomColor.isEnabled = enabled
        binding.etColorHex.isEnabled = enabled && customColorEnabled
        binding.btnUseAutoColor.isEnabled = enabled
        binding.customColorPanel.alpha = if (enabled) 1f else 0.55f
    }

    private fun updatePreviewColors() {
        val customActive = customColorEnabled && extensionMode == WallpaperExtender.MODE_SOLID
        val top = if (customActive) customColor else fillColor
        val same = customActive || binding.cbSameColor.isChecked
        val bottom = if (same) top else fillColor2
        binding.previewView.setMode(extensionMode)
        binding.previewView.setFillColor(top)
        binding.previewView.setFillColor2(bottom, same)
        binding.previewView.setPosition(positionFromUi())
        binding.previewView.setFraction(gradientPercent / 100f)
    }

    private fun updatePreviewRatio() {
        val phoneW = binding.etPhoneWidth.text?.toString()?.toFloatOrNull() ?: 1216f
        val phoneH = binding.etPhoneHeight.text?.toString()?.toFloatOrNull() ?: 2640f
        if (phoneW > 0f && phoneH > 0f) {
            binding.previewView.setPhoneRatio(phoneW / phoneH)
        }
    }

    private fun positionFromUi(): String = when (binding.rgPosition.checkedRadioButtonId) {
        binding.rbCenter.id -> WallpaperExtender.POSITION_CENTER
        binding.rbBottom.id -> WallpaperExtender.POSITION_BOTTOM
        else -> WallpaperExtender.POSITION_TOP
    }

    private fun loadBitmap(uri: Uri, sample: Boolean): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }

        val maxDimension = max(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        if (sample) {
            while (maxDimension / sampleSize > 2048 && sampleSize < 8) sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    } catch (exception: Exception) {
        Toast.makeText(this, "无法加载图片：${exception.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        null
    }

    private fun generate() {
        val previewBitmap = sourceBitmap ?: return
        val inputUri = sourceUri
        val phoneW = binding.etPhoneWidth.text?.toString()?.toIntOrNull() ?: 0
        val phoneH = binding.etPhoneHeight.text?.toString()?.toIntOrNull() ?: 0
        if (phoneW <= 0 || phoneH <= 0) {
            Toast.makeText(this, "请输入有效的目标比例", Toast.LENGTH_SHORT).show()
            return
        }

        val position = positionFromUi()
        val customActive = customColorEnabled && extensionMode == WallpaperExtender.MODE_SOLID
        if (customActive && parseHexColor(binding.etColorHex.text?.toString().orEmpty()) == null) {
            binding.tilColorHex.error = "请输入 6 位 HEX 颜色"
            return
        }
        val sameColor = customActive || binding.cbSameColor.isChecked
        val originalButtonText = binding.btnGenerate.text

        generateJob?.cancel()
        binding.btnGenerate.isEnabled = false
        binding.btnGenerate.text = ""
        binding.progressBar.visibility = View.VISIBLE

        generateJob = lifecycleScope.launch(Dispatchers.Default) {
            var input: Bitmap? = null
            var output: Bitmap? = null
            try {
                input = inputUri?.let { loadBitmap(it, sample = false) } ?: previewBitmap
                val bitmap = input ?: throw Exception("无法读取原图")
                val modifyPx =
                    (bitmap.height * gradientPercent / 100f).roundToInt().coerceAtLeast(1)
                val topColor = if (customActive) {
                    customColor
                } else {
                    sampleEdgeColor(bitmap, fromTop = position != WallpaperExtender.POSITION_BOTTOM)
                }
                val bottomColor = if (customActive) {
                    customColor
                } else {
                    sampleEdgeColor(bitmap, fromTop = false)
                }

                val result = WallpaperExtender.extend(
                    source = bitmap,
                    phoneW = phoneW,
                    phoneH = phoneH,
                    modifyZone = modifyPx,
                    position = position,
                    sameColor = sameColor,
                    presetFill = topColor,
                    presetFill2 = bottomColor,
                    mode = extensionMode,
                )
                output = result.bitmap

                withContext(Dispatchers.Main) {
                    saveToGallery(result.bitmap)
                    binding.tvResult.text =
                        "${bitmap.width}×${bitmap.height} → ${result.bitmap.width}×${result.bitmap.height}  (+${result.extendPx}px)"
                    Toast.makeText(this@MainActivity, "已保存到相册", Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "处理失败：${exception.message ?: "未知错误"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                output?.takeIf { it !== input }?.recycle()
                input?.takeIf { it !== previewBitmap }?.recycle()
                withContext(Dispatchers.Main) {
                    binding.btnGenerate.isEnabled = sourceBitmap != null
                    binding.btnGenerate.text = originalButtonText
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun saveToGallery(bitmap: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "wallpaper_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/壁纸延展")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("无法创建输出文件")
        try {
            contentResolver.openOutputStream(uri).use { stream ->
                if (stream == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw Exception("无法写入输出文件")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completed = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, completed, null, null)
            }
        } catch (exception: Exception) {
            contentResolver.delete(uri, null, null)
            throw exception
        }
    }

    private fun parseHexColor(value: String): Int? {
        val normalized = value.trim().removePrefix("#")
        if (!normalized.matches(Regex("[0-9a-fA-F]{6}"))) return null
        return try {
            Color.parseColor("#$normalized")
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun setHexColorField(color: Int) {
        updatingColorField = true
        binding.etColorHex.setText(
            String.format("#%06X", color and 0x00FFFFFF),
        )
        binding.etColorHex.setSelection(binding.etColorHex.text?.length ?: 0)
        updatingColorField = false
    }

    private fun updateColorSwatch() {
        binding.vColorSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f
            setColor(if (customColorEnabled) customColor else fillColor)
            setStroke(1, getColorCompat(com.example.extendwallpaper.R.color.border))
        }
        binding.tvAutoColor.text = if (fillColor != Color.BLACK) {
            "自动颜色：${String.format("#%06X", fillColor and 0x00FFFFFF)}"
        } else {
            getString(R.string.auto_color_hint)
        }
    }

    @Suppress("DEPRECATION")
    private fun getColorCompat(colorRes: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(colorRes)
        else resources.getColor(colorRes)

    private fun sampleEdgeColor(bitmap: Bitmap, fromTop: Boolean, height: Int = 15): Int {
        val imageHeight = bitmap.height
        val imageWidth = bitmap.width
        val startY = if (fromTop) 0 else (imageHeight - height).coerceAtLeast(0)
        val stripHeight = minOf(height, imageHeight - startY)
        if (stripHeight <= 0) return Color.BLACK
        val strip = Bitmap.createBitmap(bitmap, 0, startY, imageWidth, stripHeight)
        val small = Bitmap.createScaledBitmap(
            strip,
            max(1, (imageWidth * 0.05f).roundToInt()),
            max(1, (stripHeight * 0.05f).roundToInt()),
            true,
        )
        val color = medianColor(small)
        strip.recycle()
        small.recycle()
        return color
    }

    private fun medianColor(bitmap: Bitmap): Int {
        val count = bitmap.width * bitmap.height
        val pixels = IntArray(count)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val red = IntArray(count)
        val green = IntArray(count)
        val blue = IntArray(count)
        for (i in 0 until count) {
            val color = pixels[i]
            red[i] = color shr 16 and 0xFF
            green[i] = color shr 8 and 0xFF
            blue[i] = color and 0xFF
        }
        red.sort()
        green.sort()
        blue.sort()
        val middle = count / 2
        return (0xFF shl 24) or (red[middle] shl 16) or
            (green[middle] shl 8) or blue[middle]
    }

    override fun onDestroy() {
        sampleJob?.cancel()
        generateJob?.cancel()
        sourceBitmap?.recycle()
        sourceBitmap = null
        super.onDestroy()
    }
}
