package com.mastertipsy.androidopencv.nidedgedetection

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.mastertipsy.androidopencv.databinding.ActivityNidEdgeDetectionBinding
import com.mastertipsy.androidopencv.dpToPx
import com.mastertipsy.androidopencv.setSystemUiVisibility
import com.mastertipsy.androidopencv.updateInsetsPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resumeWithException

@Suppress("PrivatePropertyName")
class NIDEdgeDetectionActivity : AppCompatActivity(), ImageAnalysis.Analyzer {
    companion object {
        fun open(context: Context) {
            val intent = Intent(context, NIDEdgeDetectionActivity::class.java)
            context.startActivity(intent)
        }
    }

    private val TAG: String = "NIDEdgeDetection"
    private val isFrontCamera: Boolean = false

    private lateinit var binding: ActivityNidEdgeDetectionBinding
    private lateinit var pictureDirectory: File
    private lateinit var scaleType: PreviewView.ScaleType
    private lateinit var imageOptimizer: ImageOptimizer

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var captureJob: Job? = null

    private val cameraExecutors: ExecutorService by lazy { Executors.newFixedThreadPool(2) }
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) binding.previewView.post { createCamera() }
        }
    private val imageAnalysis: ImageAnalysis by lazy {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    private val imageCapture: ImageCapture by lazy {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSystemUiVisibility()
        binding = ActivityNidEdgeDetectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.main.updateInsetsPadding(16.dpToPx)
        scaleType = binding.previewView.scaleType

        OpenCVLoader.initLocal()
        imageOptimizer = ImageOptimizer()
        checkAndRequestCameraPermission()
        getPictureDirectory()
        setupListener()
    }

    override fun onDestroy() {
        imageOptimizer.release()
        cameraProvider?.unbindAll()
        cameraProvider = null
        captureJob?.cancel()
        captureJob = null
        cameraExecutors.shutdown()
        imageAnalysis.clearAnalyzer()
        super.onDestroy()
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val image = imageProxy.image ?: return imageProxy.close()
        if (image.format != ImageFormat.YUV_420_888) return imageProxy.close()
        val grayMat = imageToGrayMat(imageProxy)
        val optimized = imageOptimizer.optimizeGrayMatForContour(grayMat)
        val (contour, validSize) = findBiggestContour(optimized)
        if (contour != null) {
            val computed = computeTransformParams(grayMat, binding.previewView, scaleType)
            val mapped = mapImagePointsToView(contour.toArray(), computed)
            val nearCenter = isContourNearCenter(mapped, binding.previewView)
            binding.overlayView.updateRectangle(mapped, validSize)
            contour.release()
            if (nearCenter && validSize) {
                if (captureJob == null) {
                    captureJob = captureAndCompressImageFromCamera(delay = 1000) { uri ->
                        analyzeUriForCropping(uri)
                    }
                }
            }
        }
        optimized.release()
        grayMat.release()
    }

    private fun setupListener() = binding.apply {
        buttonCapture.setOnClickListener {
            captureAndCompressImageFromCamera { uri ->
                analyzeUriForCropping(uri)
            }
        }
        switchView.setOnCheckedChangeListener { button, bool ->
            val type =
                if (bool) PreviewView.ScaleType.FIT_CENTER else PreviewView.ScaleType.FILL_CENTER
            binding.previewView.scaleType = type
            scaleType = type
        }
    }

    private fun checkAndRequestCameraPermission() {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (camera == PackageManager.PERMISSION_GRANTED) {
            binding.previewView.post { createCamera() }
            return
        }
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun getPictureDirectory() {
        val parent = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val subfolder = File(parent, "AndroidOpenCV")
        if (!subfolder.exists()) subfolder.mkdirs()
        pictureDirectory = subfolder
    }

    private fun createCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            preview = Preview.Builder()
                .setTargetRotation(binding.previewView.display.rotation)
                .build()
            preview?.surfaceProvider = binding.previewView.surfaceProvider
            pauseCamera()
            resumeCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun pauseCamera() {
        imageAnalysis.clearAnalyzer()
        cameraProvider?.unbindAll()
    }

    private fun resumeCamera() {
        imageAnalysis.setAnalyzer(cameraExecutors, this)
        cameraProvider?.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis,
            imageCapture
        )
    }

    private fun cancelJobAndResume(resumeCamera: Boolean = true) {
        captureJob?.cancel()
        captureJob = null
        if (resumeCamera) resumeCamera()
    }

    private fun analyzeUriForCropping(uri: Uri?) {
        try {
            if (uri == null) return cancelJobAndResume()
            val (grayMat, colorMat) = convertUriToMats(contentResolver, uri)
            val optimized = imageOptimizer.optimizeGrayMatForContour(grayMat)
            val (contour, validSize, horizontal) = findBiggestContour(optimized)
            if (contour != null && validSize) {
                val cropped = cropIdCardWithPadding(colorMat, contour, horizontal)
                if (cropped != null) {
                    val bitmap = matToBitmap(cropped)
                    lifecycleScope.launch {
                        copyImageBitmapToAppPictures(bitmap)?.let { saved ->
                            showImagePreviewDialog(saved)
                        }
                    }
                    cropped.release()
                }
            } else {
                showImagePreviewDialog(uri)
            }
            contour?.release()
            optimized.release()
            grayMat.release()
            colorMat.release()
        } catch (exception: Exception) {
            cancelJobAndResume()
            Log.e(TAG, "analyzeUriForCropping", exception)
        }
    }

    private fun captureAndCompressImageFromCamera(
        pauseCam: Boolean = true,
        delay: Long = 0L,
        onImageSaved: (Uri) -> Unit,
    ): Job = lifecycleScope.launch {
        delay(delay)
        var imageProxy: ImageProxy? = null
        try {
            imageProxy = imageCapture.takePictureSuspend(pauseCam)
            val savedUri = withContext(Dispatchers.IO) {
                val photoFile = File(pictureDirectory, "${System.currentTimeMillis()}.jpg")
                val bitmap = imageProxy.toBitmap()
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val rotated = applyRotation(bitmap, rotationDegrees, isFrontCamera)
                if (rotated !== bitmap) bitmap.recycle()
                val scaled = scaleToFit(rotated)
                if (scaled !== rotated) rotated.recycle()
                FileOutputStream(photoFile).use { fos ->
                    val success = scaled.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                    if (!success) throw IllegalStateException("Compression failed")
                    fos.flush()
                    fos.fd.sync()
                }
                applyNormalExif(photoFile)
                scaled.recycle()
                photoFile.toUri()
            }
            onImageSaved(savedUri)
        } catch (exception: Exception) {
            resumeCamera()
            Log.e(TAG, "captureAndCompressImageFromCamera", exception)
        } finally {
            imageProxy?.close()
        }
    }

    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun ImageCapture.takePictureSuspend(pauseCam: Boolean): ImageProxy {
        return suspendCancellableCoroutine { continuation ->
            takePicture(
                ContextCompat.getMainExecutor(this@NIDEdgeDetectionActivity),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        if (continuation.isActive) {
                            if (pauseCam) pauseCamera()
                            continuation.resume(imageProxy) {
                                imageProxy.close()
                            }
                        } else {
                            imageProxy.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(exception)
                        }
                    }
                }
            )
            continuation.invokeOnCancellation { }
        }
    }

    private suspend fun copyImageBitmapToAppPictures(source: Bitmap): Uri? {
        return withContext(Dispatchers.IO) {
            val fileName = "${System.currentTimeMillis()}.jpg"
            val outFile = File(pictureDirectory, fileName)
            try {
                FileOutputStream(outFile).use { fos ->
                    val success = source.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                    if (!success) throw IllegalStateException("Bitmap compression failed")
                    fos.flush()
                    fos.fd.sync()
                }
                try {
                    ExifInterface(outFile.absolutePath).apply {
                        setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL.toString()
                        )
                        saveAttributes()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set EXIF orientation", e)
                }
                outFile.toUri()
            } catch (e: Exception) {
                if (outFile.exists()) outFile.delete()
                Log.e(TAG, "Error saving image to app pictures", e)
                null
            }
        }
    }

    private fun showImagePreviewDialog(source: Uri) {
        val dialog = ImagePreviewDialog(source) {
            cancelJobAndResume()
            binding.overlayView.updateRectangle(emptyArray(), false)
        }
        dialog.show(supportFragmentManager, TAG)
    }
}