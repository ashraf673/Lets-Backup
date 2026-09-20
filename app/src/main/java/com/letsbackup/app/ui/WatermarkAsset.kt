package com.letsbackup.app.ui

import android.widget.ImageView
import com.letsbackup.app.R

object WatermarkAsset {
    fun load(imageView: ImageView) {
        try {
            imageView.setImageResource(R.drawable.ic_logo_lb)
        } catch (_: Exception) {}
    }
}
