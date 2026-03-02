package com.mastertipsy.androidopencv.faceembedding

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.mastertipsy.androidopencv.databinding.ActivityFaceVectorEmbeddingBinding
import com.mastertipsy.androidopencv.dpToPx
import com.mastertipsy.androidopencv.nidedgedetection.ImagePreviewDialog
import com.mastertipsy.androidopencv.nidedgedetection.matToBitmap
import com.mastertipsy.androidopencv.setSystemUiVisibility
import com.mastertipsy.androidopencv.updateInsetsPadding
import org.opencv.android.OpenCVLoader

class FaceVectorEmbeddingActivity : AppCompatActivity() {
    companion object {
        private const val TAG: String = "FaceVectorEmbeddingActivity"

        fun open(context: Context) {
            val intent = Intent(context, FaceVectorEmbeddingActivity::class.java)
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityFaceVectorEmbeddingBinding

    private var extractor: FaceVectorExtractor? = null

    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            val rgbMat = convertUriToRgbMat(contentResolver, uri)
            if (extractor?.faceCascade == null) return@registerForActivityResult
            val face = detectFace(rgbMat, extractor?.faceCascade!!)
            if (face == null || face.empty()) return@registerForActivityResult
            val cropped = getPaddedSafeSquareCrop(rgbMat, face)
            val vector = extractor?.extractVector(cropped)
            if (vector.isNullOrEmpty()) return@registerForActivityResult
            binding.textViewEmbedding.text = vector.joinToString(", ")
            showImagePreviewDialog(matToBitmap(cropped))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSystemUiVisibility()
        binding = ActivityFaceVectorEmbeddingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.main.updateInsetsPadding(extraTop = 16.dpToPx)

        OpenCVLoader.initLocal()
        extractor = FaceVectorExtractor(this)
        setupListener()
    }

    private fun setupListener() = binding.apply {
        buttonPickImage.setOnClickListener {
            val mediaType = ActivityResultContracts.PickVisualMedia.ImageOnly
            pickMedia.launch(PickVisualMediaRequest(mediaType))
        }
        buttonClearLogging.setOnClickListener {
            textViewEmbedding.text = ""
        }
    }

    private fun showImagePreviewDialog(source: Bitmap, content: String? = null) {
        val dialog = ImagePreviewDialog(source, content) {}
        dialog.show(supportFragmentManager, TAG)
    }
}