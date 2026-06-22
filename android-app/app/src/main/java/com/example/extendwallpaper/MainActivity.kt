package com.example.extendwallpaper

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.extendwallpaper.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var sourceBitmap: Bitmap? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let {
        sourceBitmap = loadBitmap(it)
        sourceBitmap?.let { bmp ->
            binding.tvImageInfo.text = "$\u5df2\u9009\u62e9: ${bmp.width} \u00d7 ${bmp.height}"
            binding.btnGenerate.isEnabled = true
        }
    }}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        binding.btnGenerate.setOnClickListener { generate() }
    }

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        Toast.makeText(this, "\u65e0\u6cd5\u52a0\u8f7d\u56fe\u7247", Toast.LENGTH_SHORT).show(); null
    }

    private fun generate() {
        val bmp = sourceBitmap ?: return
        val pw = binding.etPhoneWidth.text.toString().toIntOrNull() ?: 1216
        val ph = binding.etPhoneHeight.text.toString().toIntOrNull() ?: 2640
        binding.btnGenerate.isEnabled = false
        binding.btnGenerate.text = "\u5904\u7406\u4e2d\u2026"
        binding.progressBar.visibility = android.view.View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = WallpaperExtender.extend(bmp, pw, ph)
                withContext(Dispatchers.Main) {
                    saveToGallery(result.bitmap)
                    binding.tvResult.text =
                        "${bmp.width}\u00d7${bmp.height} \u2192 ${bmp.width}\u00d7${bmp.height + result.extendPx}  (+${result.extendPx}px)"
                    binding.btnGenerate.text = "\u751f\u6210\u58c1\u7eb8"
                    binding.btnGenerate.isEnabled = true
                    binding.progressBar.visibility = android.view.View.GONE
                    Toast.makeText(this@MainActivity, "\u5df2\u4fdd\u5b58\u5230\u76f8\u518c", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnGenerate.text = "\u751f\u6210\u58c1\u7eb8"
                    binding.btnGenerate.isEnabled = true
                    binding.progressBar.visibility = android.view.View.GONE
                    Toast.makeText(this@MainActivity, "\u5904\u7406\u5931\u8d25: ${e.message}", Toast.LENGTH_LONG).show()
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
            ?: throw Exception("\u65e0\u6cd5\u521b\u5efa\u8f93\u51fa\u6587\u4ef6")
        contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
    }

    override fun onDestroy() { super.onDestroy(); sourceBitmap?.recycle() }
}
