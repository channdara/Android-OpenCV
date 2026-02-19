package com.mastertipsy.androidopencv.nidedgedetection

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class ImageOptimizer {
    private val stdLap = MatOfDouble()
    private val meanMat = MatOfDouble()
    private val meanLap = MatOfDouble()
    private val stdDevMat = MatOfDouble()

    private val lap = Mat()
    private val absX = Mat()
    private val absY = Mat()
    private val edges = Mat()
    private val filter = Mat()
    private val closed = Mat()
    private val sobelX = Mat()
    private val sobelY = Mat()
    private val absLap = Mat()
    private val laplacian = Mat()
    private val processed = Mat()

    fun optimizeGrayMatForContour(grayMat: Mat): Mat {
        Core.meanStdDev(grayMat, meanMat, stdDevMat)
        val intensity = meanMat.toArray()[0]
        val contrast = stdDevMat.toArray()[0]
        val considerBrightIntensity = intensity > 130.0
        Imgproc.Laplacian(grayMat, lap, CvType.CV_64F)
        Core.meanStdDev(lap, meanLap, stdLap)
        val sharpness = stdLap.toArray()[0]
        val resolution = grayMat.rows() * grayMat.cols()
        val considerHighQuality = resolution > 1_000_000 && sharpness > 100.0
        val considerCLAHE = contrast < 30.0 && !considerBrightIntensity
        if (considerCLAHE) {
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(grayMat, processed)
        } else {
            grayMat.copyTo(processed)
        }
        if (considerBrightIntensity) {
            Imgproc.Sobel(processed, sobelX, CvType.CV_16S, 1, 0)
            Imgproc.Sobel(processed, sobelY, CvType.CV_16S, 0, 1)
            Imgproc.Laplacian(processed, laplacian, CvType.CV_16S, 3, 1.0, 0.0)
            Core.convertScaleAbs(sobelX, absX)
            Core.convertScaleAbs(sobelY, absY)
            Core.convertScaleAbs(laplacian, absLap)
            Core.addWeighted(absX, 0.4, absY, 0.4, 0.0, filter)
            val lapWeight = if (considerHighQuality) 0.1 else 0.25
            Core.addWeighted(filter, 1.0, absLap, lapWeight, 0.0, filter)
        } else {
            val blurSize = if (considerHighQuality) 9.0 else 7.0
            Imgproc.GaussianBlur(processed, filter, Size(blurSize, blurSize), 0.0)
        }
        val lower = 30.0.coerceAtLeast(0.4 * intensity)
        val upper = 220.0.coerceAtMost(1.4 * intensity)
        Imgproc.Canny(filter, edges, lower, upper)
        val eleSize = if (considerBrightIntensity) (if (considerHighQuality) 11.0 else 9.0) else 7.0
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(eleSize, eleSize))
        Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()
        return closed
    }

    fun release() {
        listOf(
            stdLap,
            meanMat,
            meanLap,
            stdDevMat,
            lap,
            absX,
            absY,
            edges,
            filter,
            closed,
            sobelX,
            sobelY,
            absLap,
            laplacian,
            processed,
        ).forEach { it.release() }
    }
}
