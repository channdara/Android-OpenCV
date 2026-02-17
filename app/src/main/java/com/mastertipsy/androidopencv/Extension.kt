package com.mastertipsy.androidopencv

import android.content.res.Resources
import android.graphics.Color
import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

val Int.dpToPx: Int get() = (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

val Float.dpToPx: Int get() = (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

val Double.dpToPx: Int get() = (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

val Int.pxToDp: Int get() = (this / Resources.getSystem().displayMetrics.density + 0.5f).toInt()

val Float.pxToDp: Int get() = (this / Resources.getSystem().displayMetrics.density + 0.5f).toInt()

val Double.pxToDp: Int get() = (this / Resources.getSystem().displayMetrics.density + 0.5f).toInt()

fun View.updateInsetsPadding(
    extraLeft: Int = 0,
    extraTop: Int = 0,
    extraRight: Int = 0,
    extraBottom: Int = 0
) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(
            systemBars.left + extraLeft,
            systemBars.top + extraTop,
            systemBars.right + extraRight,
            systemBars.bottom + extraBottom
        )
        insets
    }
}

fun View.updateInsetsPadding(extra: Int = 0) = this.updateInsetsPadding(extra, extra, extra, extra)

fun Window.setSystemUiVisibility() {
    decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    statusBarColor = Color.TRANSPARENT
    navigationBarColor = Color.TRANSPARENT
    WindowCompat.setDecorFitsSystemWindows(this, false)
}