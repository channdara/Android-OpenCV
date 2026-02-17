package com.mastertipsy.androidopencv.nidedgedetection

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
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
import org.opencv.core.MatOfDouble
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
    // Get the underlying Image object from ImageProxy
    val image = imageProxy.image ?: return Mat() // If null, return an empty Mat

    // Rotation metadata (degrees) from the camera
    val rotationDegrees = imageProxy.imageInfo.rotationDegrees

    // Access the Y (luminance) plane of the YUV image
    val yPlane = image.planes[0]
    val yBuffer = yPlane.buffer
    yBuffer.rewind() // Reset buffer position to start

    // Image dimensions
    val width = image.width
    val height = image.height

    // Stride values: how data is laid out in memory
    val yRowStride = yPlane.rowStride     // Bytes per row
    val yPixelStride = yPlane.pixelStride // Bytes per pixel

    // Create an OpenCV Mat to hold grayscale data (8-bit single channel)
    val gray = Mat(height, width, CvType.CV_8UC1)

    // Destination byte array for grayscale pixel values
    val dst = ByteArray(width * height)

    // Case 1: Pixel stride is 1 → data is contiguous per row
    if (yPixelStride == 1) {
        for (row in 0 until height) {
            val srcOffset = row * yRowStride   // Start of row in source buffer
            val dstOffset = row * width        // Start of row in destination array
            yBuffer.position(srcOffset)        // Move buffer pointer
            yBuffer.get(dst, dstOffset, width) // Copy row into destination
        }
    } else {
        // Case 2: Pixel stride > 1 → pixels are spaced apart in memory
        var dstIndex = 0
        for (row in 0 until height) {
            var srcIndex = row * yRowStride    // Start of row in source buffer
            (0 until width).forEach { _ ->
                dst[dstIndex++] = yBuffer.get(srcIndex) // Copy pixel
                srcIndex += yPixelStride                // Move to next pixel
            }
        }
    }

    // Optionally close the ImageProxy to release resources
    if (closeImageProxy) imageProxy.close()

    // Put the grayscale data into the Mat
    gray.put(0, 0, dst)

    // Rotate the Mat according to camera metadata
    return rotateMat(gray, rotationDegrees)
}

fun findBiggestContour(mat: Mat): Triple<MatOfPoint2f?, Boolean, Boolean> {
    // If the input Mat is empty, return a Triple with:
    // - null contour
    // - valid = false
    // - horizontal = true (default assumption)
    if (mat.empty()) return Triple(null, false, true)

    // Prepare a list to hold contours and a Mat for hierarchy info
    val contours = mutableListOf<MatOfPoint>()
    val hierarchy = Mat()

    // Find external contours in the image
    Imgproc.findContours(
        mat,
        contours,
        hierarchy,
        Imgproc.RETR_EXTERNAL,      // Only outermost contours
        Imgproc.CHAIN_APPROX_SIMPLE // Compresses horizontal/vertical segments
    )
    hierarchy.release() // Free hierarchy memory

    var biggest: MatOfPoint2f? = null // Will hold the largest quadrilateral contour
    var maxArea = 0.0                 // Track maximum contour area

    // Iterate through all contours
    for (contour in contours) {
        // Convert contour points to floating-point representation
        val contour2f = MatOfPoint2f(*contour.toArray())

        // Calculate perimeter (arc length) of contour
        val peri = Imgproc.arcLength(contour2f, true)

        // Approximate contour shape with fewer points
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

        // Calculate area of contour
        val area = Imgproc.contourArea(contour2f)

        // Check if contour is a quadrilateral (4 points) and larger than previous max
        if (approx.total() == 4L && area > maxArea) {
            biggest?.release() // Release previous biggest contour if any
            maxArea = area
            biggest = approx   // Save new biggest quadrilateral
        } else {
            approx.release()   // Discard unused approximation
        }

        contour2f.release()    // Release temporary contour
    }

    // Flags for validation and orientation
    var valid = false
    var horizontal = true

    // If a biggest contour was found
    if (biggest != null) {
        // Get minimum area rectangle around contour
        val rect = Imgproc.minAreaRect(biggest)
        val w = rect.size.width
        val h = rect.size.height

        // Determine orientation (horizontal vs vertical)
        horizontal = isHorizontal(rect)

        // Aspect ratio of rectangle sides
        val ratio = max(w, h) / min(w, h)

        // Area of contour and frame
        val area = Imgproc.contourArea(biggest)
        val frameArea = mat.width() * mat.height()

        // Extent = contour area relative to bounding rectangle area
        val extent = area / (w * h)

        // Validate contour based on size, ratio, and extent thresholds
        valid = area > frameArea * 0.05 &&   // Not too small
                area < frameArea * 0.9 &&    // Not too large
                ratio in 1.3..1.8 &&         // Aspect ratio within expected range
                extent > 0.6                 // Contour fills most of its rectangle
    }

    // Return Triple:
    // - biggest contour (MatOfPoint2f or null)
    // - valid flag
    // - horizontal flag
    return Triple(biggest, valid, horizontal)
}

fun optimizeGrayMatForContour(grayMat: Mat): Mat {
    // Resolution
    val height = grayMat.rows()
    val width = grayMat.cols()
    val resolution = height * width

    // Brightness
    val intensity = Core.mean(grayMat).`val`[0]
    val considerBrightIntensity = intensity > 130.0

    // Contrast
    val mean = MatOfDouble()
    val stdDev = MatOfDouble()
    Core.meanStdDev(grayMat, mean, stdDev)
    val contrast = stdDev.toArray()[0]

    // Sharpness (variance of Laplacian)
    val lap = Mat()
    Imgproc.Laplacian(grayMat, lap, CvType.CV_64F)
    val meanLap = MatOfDouble()
    val stdLap = MatOfDouble()
    Core.meanStdDev(lap, meanLap, stdLap)
    val sharpness = stdLap.toArray()[0]

    // High quality if resolution large AND sharpness high
    val considerHighQuality = resolution > 1_000_000 && sharpness > 100.0

    // CLAHE only if contrast is low AND not overly bright
    val considerCLAHE = contrast < 30.0 && !considerBrightIntensity
    val processed = grayMat.clone()
    if (considerCLAHE) {
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(grayMat, processed)
    }

    // Apply filter base on bright intensity
    val filter = Mat()
    if (considerBrightIntensity) {
        // Sobel + Laplacian fusion
        val sobelX = Mat()
        val sobelY = Mat()
        Imgproc.Sobel(processed, sobelX, CvType.CV_16S, 1, 0)
        Imgproc.Sobel(processed, sobelY, CvType.CV_16S, 0, 1)
        val laplacian = Mat()
        Imgproc.Laplacian(processed, laplacian, CvType.CV_16S, 3, 1.0, 0.0)

        val absX = Mat()
        val absY = Mat()
        val absLap = Mat()
        Core.convertScaleAbs(sobelX, absX)
        Core.convertScaleAbs(sobelY, absY)
        Core.convertScaleAbs(laplacian, absLap)
        Core.addWeighted(absX, 0.4, absY, 0.4, 0.0, filter)

        // Adaptive Laplacian weight
        val lapWeight = if (considerHighQuality) 0.1 else 0.25
        Core.addWeighted(filter, 1.0, absLap, lapWeight, 0.0, filter)
    } else {
        // Adaptive blur size
        val blurSize = if (considerHighQuality) 9.0 else 7.0
        Imgproc.GaussianBlur(processed, filter, Size(blurSize, blurSize), 0.0)
    }

    // Adaptive Canny thresholds
    val lower = 30.0.coerceAtLeast(0.4 * intensity)
    val upper = 220.0.coerceAtMost(1.4 * intensity)
    val edges = Mat()
    Imgproc.Canny(filter, edges, lower, upper)

    // Adaptive kernel size
    val eleSize = when {
        considerBrightIntensity && considerHighQuality -> 11.0
        considerBrightIntensity -> 9.0
        else -> 7.0
    }
    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(eleSize, eleSize))
    val closed = Mat()
    Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
    return closed
}

data class TransformParams(
    val scaleX: Double,
    val scaleY: Double,
    val offsetX: Double,
    val offsetY: Double,
)

fun computeTransformParams(
    grayMat: Mat,
    previewView: PreviewView,
    scaleType: PreviewView.ScaleType
): TransformParams {
    // Dimensions of the PreviewView (UI component showing camera feed)
    val viewW = previewView.width.toDouble()
    val viewH = previewView.height.toDouble()

    // Dimensions of the source image (OpenCV Mat)
    val srcW = grayMat.width().toDouble()
    val srcH = grayMat.height().toDouble()

    // Compute scale factor depending on scale type
    val scale = when (scaleType) {
        // FILL modes → scale up until both width and height fill the view
        PreviewView.ScaleType.FILL_START,
        PreviewView.ScaleType.FILL_CENTER,
        PreviewView.ScaleType.FILL_END -> maxOf(viewW / srcW, viewH / srcH)

        // FIT modes → scale down until the whole image fits inside the view
        PreviewView.ScaleType.FIT_START,
        PreviewView.ScaleType.FIT_CENTER,
        PreviewView.ScaleType.FIT_END -> minOf(viewW / srcW, viewH / srcH)
    }

    // Scaled dimensions of the source image
    val scaledW = srcW * scale
    val scaledH = srcH * scale

    // Horizontal offset (X translation) based on alignment
    val offsetX = when (scaleType) {
        PreviewView.ScaleType.FILL_START,
        PreviewView.ScaleType.FIT_START -> 0.0 // Align left

        PreviewView.ScaleType.FILL_CENTER,
        PreviewView.ScaleType.FIT_CENTER -> (viewW - scaledW) / 2.0 // Center horizontally

        PreviewView.ScaleType.FILL_END,
        PreviewView.ScaleType.FIT_END -> (viewW - scaledW) // Align right
    }

    // Vertical offset (Y translation) based on alignment
    val offsetY = when (scaleType) {
        PreviewView.ScaleType.FILL_START,
        PreviewView.ScaleType.FIT_START -> 0.0 // Align top

        PreviewView.ScaleType.FILL_CENTER,
        PreviewView.ScaleType.FIT_CENTER -> (viewH - scaledH) / 2.0 // Center vertically

        PreviewView.ScaleType.FILL_END,
        PreviewView.ScaleType.FIT_END -> (viewH - scaledH) // Align bottom
    }

    // Return transformation parameters:
    // - scaleX, scaleY (same here since uniform scaling)
    // - offsetX, offsetY (translation to align image in view)
    return TransformParams(scale, scale, offsetX, offsetY)
}

fun mapImagePointsToView(
    pts: Array<Point>,
    params: TransformParams,
): Array<Point> {
    // Transform each point from image space → view space
    val points = pts.map { p ->
        // Apply scale and offset to X coordinate
        val x = p.x * params.scaleX + params.offsetX
        // Apply scale and offset to Y coordinate
        val y = p.y * params.scaleY + params.offsetY
        // Create new point in view coordinates
        Point(x, y)
    }.toTypedArray()

    // Reorder points (likely to ensure consistent order: top-left, top-right, etc.)
    return orderPoints(points)
}

fun isContourNearCenter(
    points: Array<Point>,
    previewView: PreviewView,
    thresholdRatio: Float = 0.1f,
): Boolean {
    // Ensure we have exactly 4 points (a quadrilateral)
    if (points.size != 4) return false

    // Compute centroid (average of all point coordinates)
    val sumX = points.sumOf { it.x }
    val sumY = points.sumOf { it.y }
    val centroid = Point(sumX / points.size, sumY / points.size)

    // Compute center of the PreviewView
    val centerX = previewView.width / 2.0
    val centerY = previewView.height / 2.0

    // Distance between contour centroid and view center
    val dx = centroid.x - centerX
    val dy = centroid.y - centerY
    val distance = sqrt(dx * dx + dy * dy)

    // Threshold distance: fraction of the smaller view dimension
    val threshold = minOf(previewView.width, previewView.height) * thresholdRatio

    // Return true if centroid is within threshold distance of view center
    return distance < threshold
}

fun applyRotation(
    bitmap: Bitmap,
    rotationDegrees: Int,
    isFrontCamera: Boolean
): Bitmap {
    // Create a transformation matrix
    val matrix = Matrix()

    // Apply rotation to the matrix
    matrix.postRotate(rotationDegrees.toFloat())

    // If using the front camera, apply a horizontal flip (mirror effect)
    if (isFrontCamera) matrix.postScale(-1f, 1f)

    // Create and return a new rotated (and possibly mirrored) Bitmap
    return Bitmap.createBitmap(
        bitmap,                // Source bitmap
        0, 0,                  // Starting x,y coordinates
        bitmap.width,          // Width of source
        bitmap.height,         // Height of source
        matrix,                // Transformation matrix
        true                   // Apply filtering for smoother result
    )
}

fun scaleToFit(
    source: Bitmap,
    maxWidth: Int = 1080,
    maxHeight: Int = 1080
): Bitmap {
    val w = source.width          // Original width
    val h = source.height         // Original height

    // If the image already fits within the max dimensions, return it unchanged
    if (w <= maxWidth && h <= maxHeight) return source

    // Compute scaling ratio based on the smaller dimension constraint
    val ratio = min(
        maxWidth.toFloat() / w,   // Scale factor to fit width
        maxHeight.toFloat() / h   // Scale factor to fit height
    )

    // Calculate new dimensions, ensuring at least 1 pixel
    val newW = max(1, (w * ratio).toInt())
    val newH = max(1, (h * ratio).toInt())

    // Scale the bitmap to new dimensions
    // Recycle the original if a new bitmap was created (to free memory)
    return source.scale(newW, newH).also { if (it !== source) source.recycle() }
}

fun applyNormalExif(source: File) {
    // Create an ExifInterface object for the given image file
    val exif = ExifInterface(source.absolutePath)

    // Set the orientation tag to "normal"
    // This means the image should be displayed without rotation or mirroring
    exif.setAttribute(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL.toString()
    )

    // Save the updated EXIF attributes back into the file
    exif.saveAttributes()
}

fun uriToColorMat(uri: Uri, contentResolver: ContentResolver): Mat {
    // Decode the image from the given URI into a Bitmap (ARGB_8888 for full color)
    val bitmap = contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input)?.copy(Bitmap.Config.ARGB_8888, true)
    } ?: throw IllegalArgumentException("Unable to decode bitmap from $uri")

    // Convert the Bitmap into an OpenCV Mat
    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)

    // Free the Bitmap memory since we now have it as a Mat
    bitmap.recycle()

    // Default rotation (no rotation)
    var rotationDegrees = 0

    try {
        // Open file descriptor to read EXIF metadata
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)

            // Read orientation tag from EXIF
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            // Map EXIF orientation to rotation degrees
            rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
    } catch (exception: Exception) {
        // If EXIF reading fails, print stack trace and default to 0 rotation
        exception.printStackTrace()
        rotationDegrees = 0
    }

    // Rotate the Mat according to EXIF orientation
    return rotateMat(mat, rotationDegrees)
}

fun colorMatToGrayMat(colorMat: Mat): Mat {
    // Create an empty Mat to hold the grayscale result
    val grayMat = Mat()

    // Convert the input color Mat (RGBA) into a grayscale Mat
    // COLOR_RGBA2GRAY tells OpenCV to drop color channels and keep intensity
    Imgproc.cvtColor(colorMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

    // Return the grayscale Mat
    return grayMat
}

private const val CARD_WIDTH: Double = 990.0
private const val CARD_HEIGHT: Double = CARD_WIDTH / 1.586

fun cropIdCardWithPadding(
    src: Mat,
    contour: MatOfPoint2f,
    isHorizontal: Boolean,
    padding: Int = 64
): Mat? {
    // Ensure contour has exactly 4 points (a quadrilateral)
    if (contour.total() != 4L) return null

    // Order the contour points consistently (e.g., top-left → clockwise)
    val ordered = orderPoints(contour.toArray())

    // Choose target card dimensions depending on orientation
    val (width, height) = if (isHorizontal) CARD_WIDTH to CARD_HEIGHT else CARD_HEIGHT to CARD_WIDTH

    // Add padding around the card dimensions
    val paddedWidth = width + 2 * padding
    val paddedHeight = height + 2 * padding

    // Destination points (rectangle corners with padding applied)
    val dstPoints = arrayOf(
        Point(padding.toDouble(), padding.toDouble()),                        // Top-left
        Point(paddedWidth - padding - 1, padding.toDouble()),                 // Top-right
        Point(paddedWidth - padding - 1, paddedHeight - padding - 1),         // Bottom-right
        Point(padding.toDouble(), paddedHeight - padding - 1)                 // Bottom-left
    )

    // Convert source and destination points into MatOfPoint2f
    val srcMat = MatOfPoint2f(*ordered)
    val dstMat = MatOfPoint2f(*dstPoints)

    // Compute perspective transform matrix (maps contour → rectangle)
    val transform = Imgproc.getPerspectiveTransform(srcMat, dstMat)

    // Apply perspective warp to crop and align the card
    val output = Mat()
    Imgproc.warpPerspective(src, output, transform, Size(paddedWidth, paddedHeight))

    // If card is vertical, rotate result by 90 degrees
    return if (isHorizontal) output else rotateMat(output, 90)
}

fun matToBitmap(src: Mat): Bitmap {
    // Create a new Bitmap with the same dimensions as the source Mat
    val bitmap = createBitmap(src.cols(), src.rows())

    // Copy pixel data from the OpenCV Mat into the Bitmap
    Utils.matToBitmap(src, bitmap)

    // Return the resulting Bitmap
    return bitmap
}

private fun rotateMat(src: Mat, rotationDegrees: Int): Mat {
    val dst = Mat()

    // Normalize rotation to [0, 360) range
    when (rotationDegrees % 360) {
        // Case 0°: no rotation, return original Mat
        0 -> return src

        // Case 90°: rotate clockwise
        90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)

        // Case 180°: rotate upside down
        180 -> Core.rotate(src, dst, Core.ROTATE_180)

        // Case 270°: rotate counterclockwise
        270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)

        // Case arbitrary angle: use affine transform
        else -> {
            // Compute center of the image
            val center = Point(src.cols() / 2.0, src.rows() / 2.0)

            // Build rotation matrix for given angle (scale = 1.0)
            val m = Imgproc.getRotationMatrix2D(center, rotationDegrees.toDouble(), 1.0)

            // Apply affine warp to rotate image
            Imgproc.warpAffine(src, dst, m, Size(src.cols().toDouble(), src.rows().toDouble()))
        }
    }

    // Return rotated Mat
    return dst
}

private fun isHorizontal(rect: RotatedRect): Boolean {
    // Extract width and height of the rotated rectangle
    var w = rect.size.width
    var h = rect.size.height

    // OpenCV's RotatedRect angle can cause width/height to be swapped
    // If angle < -45, swap width and height to normalize orientation
    if (rect.angle < -45) {
        val tmp = w
        w = h
        h = tmp
    }

    // Return true if the rectangle is wider than it is tall
    return w >= h
}

private fun orderPoints(points: Array<Point>): Array<Point> {
    // Sort points first by Y (row), then by X (column)
    // This ensures top points come first, bottom points last
    val sorted = points.sortedWith(compareBy({ it.y }, { it.x }))

    // Split into top two points and bottom two points
    val (top, bottom) = sorted.take(2) to sorted.takeLast(2)

    // Among the top points, leftmost is top-left, rightmost is top-right
    val topLeft = top.minByOrNull { it.x }!!
    val topRight = top.maxByOrNull { it.x }!!

    // Among the bottom points, leftmost is bottom-left, rightmost is bottom-right
    val bottomLeft = bottom.minByOrNull { it.x }!!
    val bottomRight = bottom.maxByOrNull { it.x }!!

    // Return points in consistent order: TL → TR → BR → BL
    return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
}
