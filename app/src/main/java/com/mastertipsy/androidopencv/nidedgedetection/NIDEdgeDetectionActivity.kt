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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Suppress("PrivatePropertyName")
class NIDEdgeDetectionActivity : AppCompatActivity(), ImageAnalysis.Analyzer {
    private val TAG: String = "NIDEdgeDetection"

    private lateinit var binding: ActivityNidEdgeDetectionBinding
    private lateinit var pictureDirectory: File
    private lateinit var scaleType: PreviewView.ScaleType

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
        checkAndRequestCameraPermission()
        getPictureDirectory()
        setupListener()
    }

    override fun onDestroy() {
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
        val optimized = optimizeGrayMatForContour(grayMat)
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
            val colorMat = uriToColorMat(uri, contentResolver)
            val grayMat = colorMatToGrayMat(colorMat)
            val optimized = optimizeGrayMatForContour(grayMat)
            val (contour, validSize, horizontal) = findBiggestContour(optimized)
            if (contour != null && validSize) {
                val cropped = cropIdCardWithPadding(colorMat, contour, horizontal)
                if (cropped != null) {
                    val bitmap = matToBitmap(cropped)
                    lifecycleScope.launch {
                        copyImageBitmapToAppPictures(bitmap)?.let { saved ->
                            Log.i(TAG, "Cropped Image: ${saved.path}")
                            showImagePreviewDialog(saved)
                        }
                    }
                    cropped.release()
                }
            } else {
                Log.i(
                    TAG,
                    "If it reaches here that mean the analyze section above cannot detect NID edge. Using the uri to show or pass to uCrop or other library for manual crop."
                )
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
        try {
            val imageProxy = imageCapture.takePictureSuspend(pauseCam)
            val captured = withContext(Dispatchers.IO) {
                val photoFile = File(pictureDirectory, "${System.currentTimeMillis()}.jpg")
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val bitmap = imageProxy.toBitmap()
                val rotated = applyRotation(bitmap, rotationDegrees, false)
                val scaled = scaleToFit(rotated)
                val outputStream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                val jpegBytes = outputStream.toByteArray()
                FileOutputStream(photoFile).use { fos ->
                    fos.write(jpegBytes)
                    fos.fd.sync()
                }
                applyNormalExif(photoFile)
                if (!scaled.isRecycled) scaled.recycle()
                if (!rotated.isRecycled) rotated.recycle()
                if (!bitmap.isRecycled) bitmap.recycle()
                imageProxy.close()
                photoFile.toUri()
            }
            onImageSaved(captured)
        } catch (exception: Exception) {
            resumeCamera()
            Log.e(TAG, "captureAndCompressImageFromCamera", exception)
        }
    }

    private suspend fun ImageCapture.takePictureSuspend(pauseCam: Boolean): ImageProxy {
        return suspendCancellableCoroutine { block ->
            try {
                takePicture(
                    ContextCompat.getMainExecutor(this@NIDEdgeDetectionActivity),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(imageProxy: ImageProxy) {
                            if (!block.isCompleted) {
                                if (pauseCam) pauseCamera()
                                block.resume(imageProxy)
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (!block.isCompleted) block.resumeWithException(exception)
                            Log.e(TAG, "takePictureSuspend.onError", exception)
                        }
                    },
                )
            } catch (exception: Exception) {
                if (!block.isCompleted) block.resumeWithException(exception)
                Log.e(TAG, "takePictureSuspend", exception)
            }
            block.invokeOnCancellation { }
        }
    }

    private suspend fun copyImageBitmapToAppPictures(source: Bitmap): Uri? {
        return withContext(Dispatchers.IO) {
            val outFile = File(pictureDirectory, "${System.currentTimeMillis()}.jpg")
            try {
                FileOutputStream(outFile).use { fos ->
                    if (!source.compress(
                            Bitmap.CompressFormat.JPEG,
                            100,
                            fos
                        )
                    ) throw IllegalStateException("Bitmap compress failed")
                    fos.fd.sync()
                }
                try {
                    val exif = ExifInterface(outFile.absolutePath)
                    exif.setAttribute(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL.toString()
                    )
                    exif.saveAttributes()
                } catch (exception: Exception) {
                    Log.e(TAG, "copyImageBitmapToAppPictures", exception)
                }
                outFile.toUri()
            } catch (exception: Exception) {
                outFile.delete()
                Log.e(TAG, "copyImageBitmapToAppPictures", exception)
                return@withContext null
            }
        }
    }

    private fun showImagePreviewDialog(source: Uri) {
        val dialog = ImagePreviewDialog(source) { cancelJobAndResume() }
        dialog.show(supportFragmentManager, TAG)
    }

    companion object {
        fun open(context: Context) {
            val intent = Intent(context, NIDEdgeDetectionActivity::class.java)
            context.startActivity(intent)
        }
    }
}