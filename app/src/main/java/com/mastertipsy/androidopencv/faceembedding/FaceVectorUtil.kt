package com.mastertipsy.androidopencv.faceembedding

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier

fun convertUriToRgbMat(contentResolver: ContentResolver, uri: Uri): Mat {
    val exifOrientation = contentResolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } ?: ExifInterface.ORIENTATION_NORMAL
    val bitmap = contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it)
    } ?: throw IllegalArgumentException("Could not decode image")
    val rgbaMat = Mat()
    Utils.bitmapToMat(bitmap, rgbaMat)
    bitmap.recycle()
    when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> Core.rotate(
            rgbaMat,
            rgbaMat,
            Core.ROTATE_90_CLOCKWISE
        )

        ExifInterface.ORIENTATION_ROTATE_180 -> Core.rotate(rgbaMat, rgbaMat, Core.ROTATE_180)
        ExifInterface.ORIENTATION_ROTATE_270 -> Core.rotate(
            rgbaMat,
            rgbaMat,
            Core.ROTATE_90_COUNTERCLOCKWISE
        )
    }
    val rgbMat = Mat()
    Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
    rgbaMat.release()
    return rgbMat
}

fun detectFace(rgbMat: Mat, faceCascade: CascadeClassifier): Rect? {
    val grayMat = Mat()
    Imgproc.cvtColor(rgbMat, grayMat, Imgproc.COLOR_RGB2GRAY)
    val faceDetections = MatOfRect()
    faceCascade.detectMultiScale(
        grayMat,
        faceDetections,
        1.1,
        3,
        0,
        Size(100.0, 100.0),
        Size()
    )
    val detections = faceDetections.toArray()
    grayMat.release()
    return detections.maxByOrNull { it.width * it.height }
}

fun getPaddedSafeSquareCrop(fullMat: Mat, faceRect: Rect, paddingPercent: Double = 0.15): Mat {
    val padding = (faceRect.width * paddingPercent).toInt()
    val centerX = faceRect.x + faceRect.width / 2
    val centerY = faceRect.y + faceRect.height / 2
    val side = maxOf(faceRect.width, faceRect.height) + (padding * 2)
    val left = maxOf(0, centerX - side / 2)
    val top = maxOf(0, centerY - side / 2)
    val right = minOf(fullMat.cols(), left + side)
    val bottom = minOf(fullMat.rows(), top + side)
    val finalRect = Rect(left, top, right - left, bottom - top)
    return Mat(fullMat, finalRect)
}
