package com.example.extendwallpaper

import android.content.Context
import android.graphics.Bitmap

/** Lightweight build placeholder: this variant intentionally has no AI runtime. */
class PersonMasker(@Suppress("UNUSED_PARAMETER") context: Context) {
    suspend fun detect(
        @Suppress("UNUSED_PARAMETER") bitmap: Bitmap,
        @Suppress("UNUSED_PARAMETER") mode: ProtectionMode,
        @Suppress("UNUSED_PARAMETER") onProgress: (String) -> Unit = {},
    ): PersonMask? = null
}
