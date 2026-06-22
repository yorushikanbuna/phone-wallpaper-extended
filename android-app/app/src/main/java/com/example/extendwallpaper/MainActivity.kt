package com.example.extendwallpaper

import android.content.ContentValues
import android.graphics.*
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
    private var gradientPercent = 10
    private var generateJob: Job? = null

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
            binding.cbSameColor.visibility = if (pos == "center") android.view.View.VISIBLE else android.view.View.GONE
            binding.previewView.setPosition(pos)
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

        // Compute fill colours (same as WallpaperExtender logic for preview accuracy)
        CoroutineScope(Dispatchers.Default).launch {
            val h = bmp.height; val w = bmp.width
            val zone = (h * 0.1f).toInt(); val halfZone = zone / 2
            // Base fill from top 30px
            val topH = minOf(30, h)
            val top = Bitmap.createBitmap(bmp, 0, 0, w, topH)
            val small = Bitmap.createScaledBitmap(top, (w * 0.1f).toInt().coerceAtLeast(1),
                (topH * 0.1f).toInt().coerceAtLeast(1), true)
            val c = medianColor(small)
            top.recycle(); small.recycle()
            // Brightness match to boundary
            fun matchLum(y: Int): Int {
                val t = maxOf(0, y - 10); val hh = minOf(20, h - t)
                if (hh <= 0) return c
                val strip = Bitmap.createBitmap(bmp, 0, t, w, hh)
                val px = IntArray(w * hh); strip.getPixels(px, 0, w, 0, 0, w, hh)
                var sum = 0f; for (p in px) sum += 0.299f*(p shr 16 and 0xFF)+0.587f*(p shr 8 and 0xFF)+0.114f*(p and 0xFF)
                val bLum = sum / px.size / 255f; strip.recycle()
                val bR=c shr 16 and 0xFF; val bG=c shr 8 and 0xFF; val bB=c and 0xFF
                val fRf=bR/255f;val fGf=bG/255f;val fBf=bB/255f
                val mx=maxOf(fRf,fGf,fBf);val mn=minOf(fRf,fGf,fBf);val d=mx-mn
                var fH=0f;var fS=0f
                if(d>0f){fS=if((mx+mn)/2f>.5f)d/(2f-mx-mn) else d/(mx+mn)
                    fH=if(mx==fRf)((fGf-fBf)/d+(if(fGf<fBf)6f else 0f))/6f
                    else if(mx==fGf)((fBf-fRf)/d+2f)/6f else((fRf-fGf)/d+4f)/6f}
                val q=if(bLum<.5f)bLum*(1f+fS)else bLum+fS-bLum*fS;val p=2f*bLum-q
                fun hue(h:Float):Int{var t=h;if(t<0f)t+=1f;if(t>1f)t-=1f
                    return (if(t<1f/6f)p+(q-p)*6f*t else if(t<.5f)q else if(t<2f/3f)p+(q-p)*(2f/3f-t)*6f else p).times(255f).roundToInt().coerceIn(0,255)}
                return 0xFF shl 24 or (hue(fH+1f/3f) shl 16) or (hue(fH) shl 8) or hue(fH-1f/3f)
            }
            val topFill = matchLum(zone)
            val botFill = matchLum(h - halfZone)
            withContext(Dispatchers.Main) {
                fillColor = topFill
                fillColor2 = botFill
                binding.previewView.setFillColor(topFill)
                updatePreviewColors()
            }
        }
    }

    private fun updatePreviewColors() {
        val same = binding.cbSameColor.isChecked
        binding.previewView.setFillColor2(if (same) fillColor else fillColor2, same)
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
        // Reload at full resolution for output quality
        val bmp = sourceUri?.let { loadBitmap(it, sample = false) } ?: sourceBitmap ?: return
        val pw = binding.etPhoneWidth.text.toString().toIntOrNull() ?: 1216
        val ph = binding.etPhoneHeight.text.toString().toIntOrNull() ?: 2640
        updatePreviewRatio()
        val modifyPx = (bmp.height * gradientPercent / 100f).roundToInt()

        binding.btnGenerate.isEnabled = false
        binding.btnGenerate.alpha = 0.5f
        binding.progressBar.visibility = android.view.View.VISIBLE

        generateJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val pos = when (binding.rgPosition.checkedRadioButtonId) {
                    R.id.rbCenter -> "center"; R.id.rbBottom -> "bottom"; else -> "top"
                }
                val sameColor = binding.cbSameColor.isChecked
                val result = WallpaperExtender.extend(bmp, pw, ph, modifyPx, pos, sameColor, fillColor, fillColor2)
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

    override fun onDestroy() {
        super.onDestroy(); generateJob?.cancel(); sourceBitmap?.recycle()
    }
}
