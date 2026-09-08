package com.example.extendwallpaper

import android.content.ContentValues
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.widget.SeekBar
import android.widget.Toast
import kotlinx.coroutines.Job
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.extendwallpaper.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var sourceBitmap: Bitmap? = null
    private var fillColor = Color.BLACK
    private var fillColor2 = Color.BLACK
    private var customColor = Color.BLACK
    private var customColorEnabled = false
    private var gradientPercent = 10
    private var generateJob: Job? = null
    private var updatingColorField = false

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { onImagePicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        binding.btnGenerate.setOnClickListener { generate() }

        val resolutionWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updatePreviewRatio() }
        }
        binding.etPhoneWidth.addTextChangedListener(resolutionWatcher)
        binding.etPhoneHeight.addTextChangedListener(resolutionWatcher)

        binding.cbSameColor.setOnCheckedChangeListener { _, _ -> updatePreviewColors() }

        binding.rgPosition.setOnCheckedChangeListener { _, id ->
            val pos = when (id) {
                R.id.rbCenter -> "center"
                R.id.rbBottom -> "bottom"
                else -> "top"
            }
            binding.cbSameColor.visibility =
                if (pos == "center") android.view.View.VISIBLE else android.view.View.GONE
            binding.previewView.setPosition(pos)
            updatePreviewColors()
        }

        binding.cbCustomColor.setOnCheckedChangeListener { _, checked ->
            customColorEnabled = checked
            binding.etFillColor.isEnabled = checked
            updateColorSwatch()
            updatePreviewColors()
        }

        binding.etFillColor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (updatingColorField) return
                val color = parseHexColor(s?.toString().orEmpty())
                if (color != null) {
                    customColor = color
                    binding.etFillColor.error = null
                    updateColorSwatch()
                    updatePreviewColors()
                } else if (!s.isNullOrBlank() && s.length >= 6) {
                    binding.etFillColor.error = "请输入 6 位 HEX 颜色"
                }
            }
        })

        binding.btnAutoColor.setOnClickListener {
            customColorEnabled = false
            binding.cbCustomColor.isChecked = false
            customColor = fillColor
            setFillColorField(customColor)
            binding.etFillColor.error = null
            updateColorSwatch()
            updatePreviewColors()
        }

        binding.sbModifyZone.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                gradientPercent = progress.coerceIn(1, 100)
                binding.tvModifyZone.text = "${gradientPercent}%"
                binding.previewView.setFraction(gradientPercent / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.etFillColor.isEnabled = false
        updateColorSwatch()
    }

    private fun onImagePicked(uri: Uri) {
        sourceUri = uri
        val bmp = loadBitmap(uri, sample = true) ?: return
        sourceBitmap = bmp
        binding.tvImageInfo.text = "已选择: ${bmp.width} × ${bmp.height}"
        binding.btnGenerate.isEnabled = true

        // Show preview
        binding.previewView.setBitmap(bmp)
        updatePreviewRatio()

        // Compute preview fill colours — blur+median of image edges
        CoroutineScope(Dispatchers.Default).launch {
            val topC = sampleEdgeColor(bmp, fromTop = true)
            val botC = sampleEdgeColor(bmp, fromTop = false)
            withContext(Dispatchers.Main) {
                fillColor = topC
                fillColor2 = botC
                if (!customColorEnabled) {
                    customColor = topC
                    setFillColorField(topC)
                }
                updateColorSwatch()
                updatePreviewColors()
            }
        }
    }

    private fun updatePreviewColors() {
        val customActive = customColorEnabled
        val top = if (customActive) customColor else fillColor
        val same = customActive || binding.cbSameColor.isChecked
        val bottom = if (same) top else fillColor2
        binding.previewView.setFillColor(top)
        binding.previewView.setFillColor2(bottom, same)
    }

    private fun updatePreviewRatio() {
        val pw = binding.etPhoneWidth.text.toString().toFloatOrNull() ?: 1216f
        val ph = binding.etPhoneHeight.text.toString().toFloatOrNull() ?: 2640f
        if (ph > 0) binding.previewView.setPhoneRatio(pw / ph)
    }

    private var sourceUri: Uri? = null

    private fun loadBitmap(uri: Uri, sample: Boolean = true): Bitmap? = try {
        contentResolver.openInputStream(uri)?.use { stream ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, opts)
            stream.close()

            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            val sampleSize = if (sample && maxDim > 2048)
                (maxDim / 2048).coerceAtMost(8) else 1

            contentResolver.openInputStream(uri)?.use { s ->
                BitmapFactory.decodeStream(s, null,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize })
            }
        }
    } catch (e: Exception) {
        Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show(); null
    }

    private fun generate() {
        val customActive = binding.cbCustomColor.isChecked
        if (customActive && parseHexColor(binding.etFillColor.text.toString()) == null) {
            binding.etFillColor.error = "请输入 6 位 HEX 颜色"
            return
        }

        // Reload at full resolution for output quality
        val bmp = sourceUri?.let { loadBitmap(it, sample = false) } ?: sourceBitmap ?: return
        val pw = binding.etPhoneWidth.text.toString().toIntOrNull() ?: 1216
        val ph = binding.etPhoneHeight.text.toString().toIntOrNull() ?: 2640
        updatePreviewRatio()
        val modifyPx = (bmp.height * gradientPercent / 100f).roundToInt()

        // Read UI state on main thread (not from Dispatchers.IO)
        val pos = when (binding.rgPosition.checkedRadioButtonId) {
            R.id.rbCenter -> "center"; R.id.rbBottom -> "bottom"; else -> "top"
        }
        val sameColor = binding.cbSameColor.isChecked || customActive

        binding.btnGenerate.isEnabled = false
        binding.btnGenerate.alpha = 0.5f
        binding.progressBar.visibility = android.view.View.VISIBLE

        generateJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Compute fill colours from FULL-RES bitmap — blur+median of image edges
                val topBase = sampleEdgeColor(bmp, fromTop = pos != "bottom")
                val botBase = sampleEdgeColor(bmp, fromTop = false)

                val topC = if (customActive) customColor else topBase
                val botC = if (customActive) customColor else botBase
                val fc2 = if (sameColor) topC else botC

                val result = WallpaperExtender.extend(bmp, pw, ph, modifyPx, pos, sameColor, topC, fc2)
                withContext(Dispatchers.Main) {
                    saveToGallery(result.bitmap)
                    if (bmp != sourceBitmap) bmp.recycle()
                    binding.tvResult.text =
                        "${bmp.width}×${bmp.height} → ${bmp.width}×${bmp.height + result.extendPx}  (+${result.extendPx}px)"
                    binding.btnGenerate.isEnabled = true
                    binding.btnGenerate.alpha = 1f
                    binding.progressBar.visibility = android.view.View.GONE
                    Toast.makeText(this@MainActivity, "已保存到相册", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnGenerate.isEnabled = true
                    binding.btnGenerate.alpha = 1f
                    binding.progressBar.visibility = android.view.View.GONE
                    Toast.makeText(this@MainActivity, "处理失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveToGallery(bitmap: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "wallpaper_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("无法创建输出文件")
        contentResolver.openOutputStream(uri).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it!!)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
    }

    private fun parseHexColor(value: String): Int? {
        val normalized = value.trim().removePrefix("#")
        if (!normalized.matches(Regex("[0-9a-fA-F]{6}"))) return null
        return try { Color.parseColor("#$normalized") } catch (_: IllegalArgumentException) { null }
    }

    private fun setFillColorField(color: Int) {
        updatingColorField = true
        binding.etFillColor.setText(String.format("#%06X", color and 0x00FFFFFF))
        binding.etFillColor.setSelection(binding.etFillColor.text.length)
        updatingColorField = false
    }

    private fun updateColorSwatch() {
        val color = if (customColorEnabled) customColor else fillColor
        binding.vColorSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f
            setColor(color)
            setStroke(1, Color.LTGRAY)
        }
        binding.tvAutoColor.text =
            if (fillColor == Color.BLACK) "自动取色" else "自动：${String.format("#%06X", fillColor and 0x00FFFFFF)}"
    }

    private fun medianColor(bmp: Bitmap): Int {
        val n = bmp.width * bmp.height
        val p = IntArray(n); bmp.getPixels(p, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val r = IntArray(n); val g = IntArray(n); val b = IntArray(n)
        for (i in 0 until n) {
            val c = p[i]; r[i] = c shr 16 and 0xFF; g[i] = c shr 8 and 0xFF; b[i] = c and 0xFF
        }
        r.sort(); g.sort(); b.sort()
        val m = n / 2
        return 0xFF shl 24 or (r[m] shl 16) or (g[m] shl 8) or b[m]
    }

    // ── Fill-colour helpers (reusable across preview & generation) ──

    /** Blur+median of [height] rows starting from the top or bottom edge. */
    private fun sampleEdgeColor(bmp: Bitmap, fromTop: Boolean, height: Int = 15): Int {
        val h = bmp.height; val w = bmp.width
        val startY = if (fromTop) 0 else (h - height).coerceAtLeast(0)
        val sh = minOf(height, h - startY)
        if (sh <= 0) return Color.BLACK
        val strip = Bitmap.createBitmap(bmp, 0, startY, w, sh)
        val small = Bitmap.createScaledBitmap(strip, (w * 0.05f).toInt().coerceAtLeast(1),
            (sh * 0.05f).toInt().coerceAtLeast(1), true)
        val c = medianColor(small); strip.recycle(); small.recycle(); return c
    }

    override fun onDestroy() {
        super.onDestroy(); generateJob?.cancel(); sourceBitmap?.recycle()
    }
}
