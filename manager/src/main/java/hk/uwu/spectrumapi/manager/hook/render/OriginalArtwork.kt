// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
// Modified for SpectrumAPI: relocated display-only helper; no capture backend.

package hk.uwu.spectrumapi.manager.hook.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** Match the host's <=64px DrawableKt bitmap conversion using private pixel buffers. */
object OriginalArtwork {
    @JvmStatic
    fun fingerprint(bitmap: Bitmap): Long {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return CoverColors.fingerprint(pixels) xor (width.toLong() shl 32) xor height.toLong()
    }

    @JvmStatic
    fun fingerprint(drawable: Drawable): Long {
        val width = dimension(drawable.intrinsicWidth)
        val height = dimension(drawable.intrinsicHeight)
        val bitmap: Bitmap
        val recycle: Boolean
        if (drawable is BitmapDrawable) {
            val source = drawable.bitmap
            bitmap = Bitmap.createScaledBitmap(source, width, height, true)
            recycle = bitmap !== source
        } else {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            recycle = true
            val bounds = Rect(drawable.bounds)
            try {
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(bitmap))
            } finally {
                drawable.bounds = bounds
            }
        }
        try {
            return fingerprint(bitmap)
        } finally {
            if (recycle) bitmap.recycle()
        }
    }

    private fun dimension(size: Int): Int = if (size in 1..64) size else 64
}
