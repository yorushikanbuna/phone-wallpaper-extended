package com.example.extendwallpaper

import android.app.Dialog
import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Job
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.extendwallpaper.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private data class Resolution(val width: Int, val height: Int)

    private companion object {
        const val PREFERENCES_NAME = "wallpaper_settings"
        const val LAST_WIDTH = "last_phone_width"
        const val LAST_HEIGHT = "last_phone_height"
        const val DEFAULT_PHONE_WIDTH = 1216
        const val DEFAULT_PHONE_HEIGHT = 2640
    }

    private lateinit var binding: ActivityMainBinding
    private val preferences by lazy {
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
    }
    private var sourceBitmap: Bitmap? = null
    private var fillColor = Color.BLACK
    private var fillColor2 = Color.BLACK
    private var customTopColor = Color.BLACK
    private var customBottomColor = Color.BLACK
    private var customColorEnabled = false
    private var gradientPercent = 10
    private var generateJob: Job? = null
    private var maskJob: Job? = null
    private var maskRequestId = 0
    private var protectionMode = ProtectionMode.OFF
    private var personMask: PersonMask? = null
    private val personMasker by lazy { PersonMasker(applicationContext) }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { onImagePicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!BuildConfig.PERSON_DETECTION) {
            binding.personProtectionControls.visibility = View.GONE
            binding.personProtectionStatus.visibility = View.GONE
        }

        val initialResolution = loadInitialResolution()
        binding.etPhoneWidth.setText(initialResolution.width.toString())
        binding.etPhoneHeight.setText(initialResolution.height.toString())

        binding.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        binding.previewView.setOnClickListener { showPreviewDialog() }
        binding.btnGenerate.setOnClickListener { generate() }

        binding.rgProtection.setOnCheckedChangeListener { _, id ->
            protectionMode = when (id) {
                R.id.rbProtectionReal -> ProtectionMode.REAL
                R.id.rbProtectionAnime -> ProtectionMode.ANIME
                else -> ProtectionMode.OFF
            }
            startMaskDetection()
        }

        val resolutionWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updatePreviewRatio() }
        }
        binding.etPhoneWidth.addTextChangedListener(resolutionWatcher)
        binding.etPhoneHeight.addTextChangedListener(resolutionWatcher)
        val resolutionFocusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveCurrentResolution()
        }
        binding.etPhoneWidth.onFocusChangeListener = resolutionFocusListener
        binding.etPhoneHeight.onFocusChangeListener = resolutionFocusListener
        val resolutionEditorListener = TextView.OnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                saveCurrentResolution()
            }
            false
        }
        binding.etPhoneWidth.setOnEditorActionListener(resolutionEditorListener)
        binding.etPhoneHeight.setOnEditorActionListener(resolutionEditorListener)

        binding.cbSameColor.setOnCheckedChangeListener { _, _ ->
            updateColorSwatch()
            updatePreviewColors()
        }

        binding.rgPosition.setOnCheckedChangeListener { _, id ->
            val pos = when (id) {
                R.id.rbCenter -> "center"
                R.id.rbBottom -> "bottom"
                else -> "top"
            }
            binding.cbSameColor.visibility =
                if (pos == "center") android.view.View.VISIBLE else android.view.View.GONE
            binding.previewView.setPosition(pos)
            updateColorSwatch()
            updatePreviewColors()
        }

        binding.tvColorMode.setOnClickListener { showColorDialog() }

        binding.etModifyZone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                s?.toString()?.toIntOrNull()?.let { updateGradientPercent(it) }
            }
        })
        binding.etModifyZone.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateGradientPercent(
                    binding.etModifyZone.text.toString().toIntOrNull() ?: gradientPercent
                )
            }
        }
        binding.sbModifyZone.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateGradientPercent(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        updateGradientPercent(gradientPercent)

        updateColorSwatch()
    }

    private fun onImagePicked(uri: Uri) {
        sourceUri = uri
        val bmp = loadBitmap(uri, sample = true) ?: return
        maskJob?.cancel()
        maskRequestId++
        val previous = sourceBitmap
        sourceBitmap = bmp
        personMask = null
        binding.previewView.setPersonMask(null)
        previous?.takeIf { it !== bmp }?.recycle()
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
                updateColorSwatch()
                updatePreviewColors()
            }
        }
        startMaskDetection()
    }

    private fun showPreviewDialog() {
        val previewBitmap = binding.previewView.copyRenderedBitmap() ?: return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        val imageView = ZoomableImageView(this).apply {
            setImageBitmap(previewBitmap)
            contentDescription = getString(R.string.preview_image_content_description)
        }
        root.addView(imageView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            ).let { attributes ->
                val drawable = attributes.getDrawable(0)
                attributes.recycle()
                drawable
            }
            contentDescription = getString(R.string.close_preview)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(closeButton, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(12)
            marginEnd = dp(12)
        })

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            imageView.setImageDrawable(null)
            if (!previewBitmap.isRecycled) previewBitmap.recycle()
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
                setDimAmount(0f)
                statusBarColor = Color.BLACK
                navigationBarColor = Color.BLACK
                decorView.systemUiVisibility = decorView.systemUiVisibility and
                    (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun startMaskDetection() {
        maskJob?.cancel()
        val requestId = ++maskRequestId
        val bmp = sourceBitmap
        val mode = protectionMode
        personMask = null
        binding.previewView.setPersonMask(null)
        if (bmp == null || mode == ProtectionMode.OFF) {
            binding.pbProtection.visibility = android.view.View.GONE
            binding.tvProtectionStatus.text = if (bmp == null) "选择图片后识别人物" else "人物保护已关闭"
            binding.btnGenerate.isEnabled = bmp != null
            return
        }
        binding.btnGenerate.isEnabled = false
        binding.pbProtection.visibility = android.view.View.VISIBLE
        binding.pbProtection.progress = 0
        binding.tvProtectionStatus.text = "准备人物识别模型…"
        maskJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                val mask = personMasker.detect(bmp, mode) { message ->
                    runOnUiThread {
                        if (requestId == maskRequestId) {
                            binding.tvProtectionStatus.text = message
                            Regex("(\\d+)%").find(message)?.groupValues?.get(1)?.toIntOrNull()?.let {
                                binding.pbProtection.progress = it
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (requestId != maskRequestId) return@withContext
                    personMask = mask
                    binding.previewView.setPersonMask(mask)
                    binding.pbProtection.visibility = android.view.View.GONE
                    binding.tvProtectionStatus.text = "人物区域已保护（可继续调整参数）"
                    binding.btnGenerate.isEnabled = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (requestId != maskRequestId) return@withContext
                    personMask = null
                    binding.previewView.setPersonMask(null)
                    binding.pbProtection.visibility = android.view.View.GONE
                    binding.tvProtectionStatus.text = "识别失败：${e.message ?: "请重试或关闭保护"}"
                    binding.btnGenerate.isEnabled = true
                }
            }
        }
    }

    private fun updatePreviewColors() {
        val customActive = customColorEnabled
        val top = if (customActive) customTopColor else fillColor
        val bottom = if (customActive) customBottomColor else fillColor2
        val centerMode = binding.rgPosition.checkedRadioButtonId == R.id.rbCenter
        val same = centerMode && binding.cbSameColor.isChecked
        binding.previewView.setFillColor(top)
        binding.previewView.setFillColor2(if (same) top else bottom, same)
    }

    private fun readResolution(): Resolution? {
        val width = binding.etPhoneWidth.text.toString().trim().toIntOrNull()
        val height = binding.etPhoneHeight.text.toString().trim().toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) return null
        return Resolution(width, height)
    }

    private fun loadInitialResolution(): Resolution {
        val saved = Resolution(
            preferences.getInt(LAST_WIDTH, 0),
            preferences.getInt(LAST_HEIGHT, 0)
        )
        if (saved.width > 0 && saved.height > 0) return saved

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        return if (screenWidth > 0 && screenHeight > 0) {
            Resolution(minOf(screenWidth, screenHeight), maxOf(screenWidth, screenHeight))
        } else {
            Resolution(DEFAULT_PHONE_WIDTH, DEFAULT_PHONE_HEIGHT)
        }
    }

    private fun saveCurrentResolution() {
        readResolution()?.let { resolution ->
            preferences.edit()
                .putInt(LAST_WIDTH, resolution.width)
                .putInt(LAST_HEIGHT, resolution.height)
                .apply()
        }
    }

    private fun updatePreviewRatio() {
        val resolution = readResolution() ?: return
        val ratio = resolution.width.toFloat() / resolution.height.toFloat()
        if (ratio.isFinite() && ratio > 0f) binding.previewView.setPhoneRatio(ratio)
    }

    private fun updateGradientPercent(value: Int) {
        val normalized = value.coerceIn(0, 100)
        gradientPercent = normalized
        if (binding.sbModifyZone.progress != normalized) {
            binding.sbModifyZone.progress = normalized
        }
        if (binding.etModifyZone.text.toString() != normalized.toString()) {
            binding.etModifyZone.setText(normalized.toString())
            binding.etModifyZone.setSelection(binding.etModifyZone.text.length)
        }
        binding.previewView.setFraction(normalized / 100f)
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
        val customActive = customColorEnabled
        if (protectionMode != ProtectionMode.OFF && personMask == null) {
            Toast.makeText(this, "人物识别尚未完成，请等待或关闭人物保护", Toast.LENGTH_SHORT).show()
            return
        }
        val detectedMask = personMask

        val resolution = readResolution()
        if (resolution == null) {
            Toast.makeText(this, "请输入有效的手机分辨率", Toast.LENGTH_SHORT).show()
            return
        }
        saveCurrentResolution()
        updateGradientPercent(
            binding.etModifyZone.text.toString().toIntOrNull() ?: gradientPercent
        )

        // Reload at full resolution for output quality
        val bmp = sourceUri?.let { loadBitmap(it, sample = false) } ?: sourceBitmap ?: return
        val pw = resolution.width
        val ph = resolution.height
        updatePreviewRatio()
        val modifyPx = (bmp.height * gradientPercent / 100f).roundToInt()

        // Read UI state on main thread (not from Dispatchers.IO)
        val pos = when (binding.rgPosition.checkedRadioButtonId) {
            R.id.rbCenter -> "center"; R.id.rbBottom -> "bottom"; else -> "top"
        }
        val sameColor = pos == "center" && binding.cbSameColor.isChecked

        binding.btnGenerate.isEnabled = false
        binding.btnGenerate.alpha = 0.5f
        binding.progressBar.visibility = android.view.View.VISIBLE

        generateJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Compute fill colours from the full-resolution edge rows.
                // Keep the background pass identical to recognition-off mode.
                val topBase = sampleEdgeColor(bmp, fromTop = pos != "bottom")
                val botBase = sampleEdgeColor(bmp, fromTop = false)

                val topC = if (customActive) customTopColor else topBase
                val botC = if (customActive) customBottomColor else botBase
                val fc2 = if (sameColor) topC else botC

                val result = WallpaperExtender.extend(
                    bmp, pw, ph, modifyPx, pos, sameColor, topC, fc2, detectedMask
                )
                withContext(Dispatchers.Main) {
                    saveToGallery(result.bitmap)
                    if (result.bitmap !== bmp) result.bitmap.recycle()
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

    private fun showColorDialog() {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun addColorInput(label: String, color: Int): EditText {
            val labelView = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(resources.getColor(R.color.text_muted, theme))
            }
            container.addView(labelView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (container.childCount > 0) topMargin = (8 * density).roundToInt()
            })

            val input = EditText(this).apply {
                hint = "#RRGGBB"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                filters = arrayOf(InputFilter.LengthFilter(7))
                setSingleLine(true)
                minHeight = (44 * density).roundToInt()
                setPadding(
                    (12 * density).roundToInt(), 0,
                    (12 * density).roundToInt(), 0
                )
                setBackgroundResource(R.drawable.input_field_bg)
                setText(String.format("#%06X", color and 0x00FFFFFF))
                selectAll()
            }
            container.addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4 * density).roundToInt()
            })
            return input
        }

        val topColor = if (customColorEnabled) customTopColor else fillColor
        val bottomColor = if (customColorEnabled) customBottomColor else fillColor2
        val topInput = addColorInput("上方填充色", topColor)
        val bottomInput = addColorInput("下方填充色", bottomColor)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("设置上下填充色")
            .setMessage("勾选“上下同色”时只使用上方颜色；取消勾选后会分别使用两种颜色。")
            .setView(container)
            .setNegativeButton("取消", null)
            .setNeutralButton("自动取色", null)
            .setPositiveButton("使用颜色", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                customColorEnabled = false
                updateColorSwatch()
                updatePreviewColors()
                dialog.dismiss()
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val top = parseHexColor(topInput.text.toString())
                if (top == null) {
                    topInput.error = "请输入 6 位 HEX 颜色"
                    return@setOnClickListener
                }
                val bottom = parseHexColor(bottomInput.text.toString())
                if (bottom == null) {
                    bottomInput.error = "请输入 6 位 HEX 颜色"
                    return@setOnClickListener
                }
                customTopColor = top
                customBottomColor = bottom
                customColorEnabled = true
                updateColorSwatch()
                updatePreviewColors()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateColorSwatch() {
        val top = if (customColorEnabled) customTopColor else fillColor
        val bottom = if (customColorEnabled) customBottomColor else fillColor2
        val centerSame = binding.rgPosition.checkedRadioButtonId == R.id.rbCenter &&
            binding.cbSameColor.isChecked
        val effectiveBottom = if (centerSame) top else bottom
        binding.vColorSwatch.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, effectiveBottom)
        ).apply {
            cornerRadius = 8f
            setStroke(1, Color.LTGRAY)
        }
        binding.tvColorMode.text = if (customColorEnabled) {
            "自定义上下颜色"
        } else {
            "自动取色（上下）"
        }
    }

    // ── Fill-colour helpers (reusable across preview & generation) ──

    /** Median colour of [height] rows starting from the top or bottom edge. */
    private fun sampleEdgeColor(bmp: Bitmap, fromTop: Boolean, height: Int = 15): Int {
        val h = bmp.height; val w = bmp.width
        val startY = if (fromTop) 0 else (h - height).coerceAtLeast(0)
        val sh = minOf(height, h - startY)
        if (sh <= 0) return Color.BLACK
        val values = ArrayList<Int>(w * sh)
        for (y in 0 until sh) for (x in 0 until w) values.add(bmp.getPixel(x, startY + y))
        val r = IntArray(values.size); val g = IntArray(values.size); val b = IntArray(values.size)
        values.forEachIndexed { i, c -> r[i] = Color.red(c); g[i] = Color.green(c); b[i] = Color.blue(c) }
        r.sort(); g.sort(); b.sort(); val m = values.size / 2
        return Color.rgb(r[m], g[m], b[m])
    }

    override fun onPause() {
        saveCurrentResolution()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy(); generateJob?.cancel(); sourceBitmap?.recycle()
    }
}
