package com.mastertipsy.androidopencv.opencv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.camera.view.PreviewView
import com.mastertipsy.androidopencv.databinding.ActivityOpenCvBinding
import com.mastertipsy.androidopencv.dpToPx
import com.mastertipsy.androidopencv.nidedgedetection.ImageOptimizer
import com.mastertipsy.androidopencv.nidedgedetection.computeTransformParams
import com.mastertipsy.androidopencv.nidedgedetection.findBiggestContour
import com.mastertipsy.androidopencv.nidedgedetection.mapImagePointsToView
import com.mastertipsy.androidopencv.setSystemUiVisibility
import com.mastertipsy.androidopencv.updateInsetsPadding
import org.opencv.android.CameraActivity
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat

@Suppress("PrivatePropertyName")
class OpenCVActivity : CameraActivity(), CameraBridgeViewBase.CvCameraViewListener2 {
    companion object {
        fun open(context: Context) {
            val intent = Intent(context, OpenCVActivity::class.java)
            context.startActivity(intent)
        }
    }

    private val TAG: String = "OpenCVActivity"

    private lateinit var binding: ActivityOpenCvBinding
    private lateinit var imageOptimizer: ImageOptimizer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSystemUiVisibility()
        binding = ActivityOpenCvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.main.updateInsetsPadding(16.dpToPx)

        OpenCVLoader.initLocal()
        imageOptimizer = ImageOptimizer()
        binding.previewView.setCvCameraViewListener(this)
    }

    override fun onPause() {
        super.onPause()
        binding.previewView.disableView()
    }

    override fun onResume() {
        super.onResume()
        binding.previewView.enableView()
    }

    override fun onDestroy() {
        binding.previewView.disableView()
        imageOptimizer.release()
        super.onDestroy()
    }

    override fun onCameraViewStarted(width: Int, height: Int) {}

    override fun onCameraViewStopped() {}

    override fun getCameraViewList(): List<CameraBridgeViewBase?> = listOf(binding.previewView)


    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame): Mat {
        val grayMat = inputFrame.gray()
        val optimized = imageOptimizer.optimizeGrayMatForContour(grayMat)
        val (contour, validSize) = findBiggestContour(optimized)
        if (contour != null) {
            val computed = computeTransformParams(
                grayMat,
                binding.previewView,
                PreviewView.ScaleType.FIT_CENTER
            )
            val mapped = mapImagePointsToView(contour.toArray(), computed)
            binding.overlayView.updateRectangle(mapped, validSize)
            contour.release()
        } else {
            binding.overlayView.updateRectangle(emptyArray(), false)
        }
        return optimized
    }
}