package com.mastertipsy.androidopencv.nidedgedetection

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.view.View
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@OptIn(ExperimentalGetImage::class)
fun imageToGrayMat(imageProxy: ImageProxy, closeImageProxy: Boolean = true): Mat {
    val image = imageProxy.image ?: return Mat()
    val width = image.width
    val height = image.height
    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
    val yPlane = image.planes[0]
    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    val gray = Mat(height, width, CvType.CV_8UC1)
    if (yRowStride == width) {
        val data = ByteArray(width * height)
        yBuffer.rewind()
        yBuffer.get(data)
        gray.put(0, 0, data)
    } else {
        val rowData = ByteArray(width)
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(rowData)
            gray.put(row, 0, rowData)
        }
    }
    if (closeImageProxy) imageProxy.close()
    return if (rotationDegrees != 0) {
        val rotated = rotateMat(gray, rotationDegrees)
        gray.release()
        rotated
    } else {
        gray
    }
}

@OptIn(ExperimentalGetImage::class)
fun imageToColorMat(imageProxy: ImageProxy, closeImageProxy: Boolean = true): Mat {
    val image = imageProxy.image ?: return Mat()
    val width = image.width
    val height = image.height
    val planes = image.planes
    val rgba = Mat()
    try {
        if (planes[1].pixelStride == 2) {
            val yMat =
                Mat(height, width, CvType.CV_8UC1, planes[0].buffer, planes[0].rowStride.toLong())
            val uvMat1 = Mat(
                height / 2,
                width / 2,
                CvType.CV_8UC2,
                planes[1].buffer,
                planes[1].rowStride.toLong()
            )
            val uvMat2 = Mat(
                height / 2,
                width / 2,
                CvType.CV_8UC2,
                planes[2].buffer,
                planes[2].rowStride.toLong()
            )
            val format =
                if (uvMat2.dataAddr() - uvMat1.dataAddr() == 1L) Imgproc.COLOR_YUV2RGBA_NV12
                else Imgproc.COLOR_YUV2RGBA_NV21
            Imgproc.cvtColorTwoPlane(yMat, uvMat1, rgba, format)
            yMat.release()
            uvMat1.release()
            uvMat2.release()
        } else {
            val yuvMat = Mat(height + height / 2, width, CvType.CV_8UC1)
            Imgproc.cvtColor(yuvMat, rgba, Imgproc.COLOR_YUV2RGBA_I420)
            yuvMat.release()
        }
    } finally {
        if (closeImageProxy) imageProxy.close()
    }
    val rotation = imageProxy.imageInfo.rotationDegrees
    return if (rotation != 0) {
        val rotated = rotateMat(rgba, rotation)
        rgba.release()
        rotated
    } else {
        rgba
    }
}

fun convertUriToMats(
    contentResolver: ContentResolver,
    uri: Uri,
    maxSide: Int = 1080,
): Pair<Mat, Mat> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    val exifOrientation = contentResolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } ?: ExifInterface.ORIENTATION_NORMAL
    options.inJustDecodeBounds = false
    options.inSampleSize = calculateInSampleSize(options, maxSide, maxSide)
    val bitmap =
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Could not decode image")
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
    val grayMat = Mat()
    Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
    return Pair(grayMat, rgbaMat)
}

fun findBiggestContour(mat: Mat): Triple<MatOfPoint2f?, Boolean, Boolean> {
    if (mat.empty()) return Triple(null, false, true)
    val contours = mutableListOf<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(
        mat,
        contours,
        hierarchy,
        Imgproc.RETR_EXTERNAL,
        Imgproc.CHAIN_APPROX_SIMPLE
    )
    hierarchy.release()
    var biggest: MatOfPoint2f? = null
    var maxArea = 0.0
    val frameArea = (mat.width() * mat.height()).toDouble()
    for (contour in contours) {
        val area = Imgproc.contourArea(contour)
        if (area > frameArea * 0.05 && area > maxArea) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)
            if (approx.total() == 4L) {
                biggest?.release()
                maxArea = area
                biggest = approx
            } else {
                approx.release()
            }
            contour2f.release()
        }
        contour.release()
    }
    var valid = false
    var horizontal = true
    biggest?.let { poly ->
        val rect = Imgproc.minAreaRect(poly)
        val w = rect.size.width
        val h = rect.size.height
        horizontal = isHorizontal(rect)
        val ratio = max(w, h) / min(w, h)
        val extent = maxArea / (w * h)
        valid = maxArea < frameArea * 0.9 &&
                ratio in 1.3..1.8 &&
                extent > 0.6
    }
    return Triple(biggest, valid, horizontal)
}

data class TransformParams(
    val scaleX: Double,
    val scaleY: Double,
    val offsetX: Double,
    val offsetY: Double,
)

fun computeTransformParams(
    grayMat: Mat,
    previewView: View,
    scaleType: PreviewView.ScaleType,
): TransformParams {
    val viewW = previewView.width.toDouble()
    val viewH = previewView.height.toDouble()
    val srcW = grayMat.width().toDouble()
    val srcH = grayMat.height().toDouble()
    val scale = when (scaleType) {
        PreviewView.ScaleType.FILL_START,
        PreviewView.ScaleType.FILL_CENTER,
        PreviewView.ScaleType.FILL_END -> maxOf(viewW / srcW, viewH / srcH)

        PreviewView.ScaleType.FIT_START,
        PreviewView.ScaleType.FIT_CENTER,
        PreviewView.ScaleType.FIT_END -> minOf(viewW / srcW, viewH / srcH)
    }
    val scaledW = srcW * scale
    val scaledH = srcH * scale
    val offsetX = when (scaleType) {
        PreviewView.ScaleType.FILL_START,
        PreviewView.ScaleType.FIT_START -> 0.0

        PreviewView.ScaleType.FILL_CENTER,
        PreviewView.ScaleType.FIT_CENTER -> (viewW - scaledW) / 2.0

        PreviewView.ScaleType.FILL_END,
        PreviewView.ScaleType.FIT_END -> (viewW - scaledW)
    }
    val offsetY = when (scaleType) {
        PreviewView.ScaleType.FILL_START,
        PreviewView.ScaleType.FIT_START -> 0.0

        PreviewView.ScaleType.FILL_CENTER,
        PreviewView.ScaleType.FIT_CENTER -> (viewH - scaledH) / 2.0

        PreviewView.ScaleType.FILL_END,
        PreviewView.ScaleType.FIT_END -> (viewH - scaledH)
    }
    return TransformParams(scale, scale, offsetX, offsetY)
}

fun mapImagePointsToView(
    pts: Array<Point>,
    params: TransformParams,
): Array<Point> {
    val points = pts.map { p ->
        val x = p.x * params.scaleX + params.offsetX
        val y = p.y * params.scaleY + params.offsetY
        Point(x, y)
    }.toTypedArray()
    return orderPoints(points)
}

fun isContourNearCenter(
    points: Array<Point>,
    previewView: View,
    thresholdRatio: Float = 0.1f,
): Boolean {
    if (points.size != 4) return false
    val sumX = points.sumOf { it.x }
    val sumY = points.sumOf { it.y }
    val centroid = Point(sumX / points.size, sumY / points.size)
    val centerX = previewView.width / 2.0
    val centerY = previewView.height / 2.0
    val dx = centroid.x - centerX
    val dy = centroid.y - centerY
    val distance = sqrt(dx * dx + dy * dy)
    val threshold = minOf(previewView.width, previewView.height) * thresholdRatio
    return distance < threshold
}

fun applyRotation(
    bitmap: Bitmap,
    rotationDegrees: Int,
    isFrontCamera: Boolean
): Bitmap {
    if (rotationDegrees == 0 && !isFrontCamera) return bitmap
    val matrix = Matrix().apply {
        if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        if (isFrontCamera) postScale(-1f, 1f)
    }
    return try {
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        rotated
    } catch (_: Exception) {
        bitmap
    }
}

fun scaleToFit(
    source: Bitmap,
    maxWidth: Int = 1080,
    maxHeight: Int = 1080
): Bitmap {
    val w = source.width
    val h = source.height
    if (w <= maxWidth && h <= maxHeight) return source
    val ratio = min(maxWidth.toFloat() / w, maxHeight.toFloat() / h)
    val newW = (w * ratio).toInt().coerceAtLeast(1)
    val newH = (h * ratio).toInt().coerceAtLeast(1)
    val scaled = source.scale(newW, newH)
    if (scaled !== source) source.recycle()
    return scaled
}

fun applyNormalExif(source: File) {
    if (!source.exists() || source.length() == 0L) return
    try {
        val exif = ExifInterface(source.absolutePath)
        exif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString()
        )
        exif.saveAttributes()
    } catch (_: Exception) {
    }
}

private const val CARD_WIDTH: Double = 1000.0
private const val CARD_HEIGHT: Double = CARD_WIDTH / 1.586

fun cropIdCardWithPadding(
    src: Mat,
    contour: MatOfPoint2f,
    isHorizontal: Boolean,
    padding: Int = 64
): Mat? {
    if (contour.total() != 4L) return null
    val ordered = orderPoints(contour.toArray())
    val (baseW, baseH) = if (isHorizontal) CARD_WIDTH to CARD_HEIGHT else CARD_HEIGHT to CARD_WIDTH
    val paddedWidth = baseW + (2 * padding)
    val paddedHeight = baseH + (2 * padding)
    val dstPoints = arrayOf(
        Point(padding.toDouble(), padding.toDouble()),
        Point(baseW + padding, padding.toDouble()),
        Point(baseW + padding, baseH + padding),
        Point(padding.toDouble(), baseH + padding)
    )
    val srcMat = MatOfPoint2f(*ordered)
    val dstMat = MatOfPoint2f(*dstPoints)
    val transform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
    val output = Mat()
    Imgproc.warpPerspective(
        src,
        output,
        transform,
        Size(paddedWidth, paddedHeight),
        Imgproc.INTER_LANCZOS4,
    )
    srcMat.release()
    dstMat.release()
    transform.release()
    return if (!isHorizontal) {
        val finalMat = rotateMat(output, 90)
        output.release()
        finalMat
    } else {
        output
    }
}

fun matToBitmap(src: Mat): Bitmap {
    val bitmap = createBitmap(src.cols(), src.rows())
    Utils.matToBitmap(src, bitmap)
    return bitmap
}

private fun rotateMat(src: Mat, rotationDegrees: Int): Mat {
    val dst = Mat()
    when (rotationDegrees % 360) {
        0 -> return src
        90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
        180 -> Core.rotate(src, dst, Core.ROTATE_180)
        270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
        else -> {
            val center = Point(src.cols() / 2.0, src.rows() / 2.0)
            val m = Imgproc.getRotationMatrix2D(center, rotationDegrees.toDouble(), 1.0)
            Imgproc.warpAffine(src, dst, m, Size(src.cols().toDouble(), src.rows().toDouble()))
        }
    }
    return dst
}

private fun isHorizontal(rect: RotatedRect): Boolean {
    var w = rect.size.width
    var h = rect.size.height
    if (rect.angle < -45) {
        val tmp = w
        w = h
        h = tmp
    }
    return w >= h
}

private fun orderPoints(points: Array<Point>): Array<Point> {
    val sorted = points.sortedWith(compareBy({ it.y }, { it.x }))
    val (top, bottom) = sorted.take(2) to sorted.takeLast(2)
    val topLeft = top.minByOrNull { it.x }!!
    val topRight = top.maxByOrNull { it.x }!!
    val bottomLeft = bottom.minByOrNull { it.x }!!
    val bottomRight = bottom.maxByOrNull { it.x }!!
    return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
}

private fun calculateInSampleSize(
    options: BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int
): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}