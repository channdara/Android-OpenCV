package com.mastertipsy.androidopencv

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.mastertipsy.androidopencv.databinding.ActivityMainBinding
import com.mastertipsy.androidopencv.nidedgedetection.NIDEdgeDetectionActivity
import com.mastertipsy.androidopencv.opencv.OpenCVActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSystemUiVisibility()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.main.updateInsetsPadding(16.dpToPx)

        setupListener()
        copyTesseractFiles()
    }

    private fun setupListener() = binding.apply {
        buttonNID.setOnClickListener { NIDEdgeDetectionActivity.open(this@MainActivity) }
        buttonOpenCV.setOnClickListener { OpenCVActivity.open(this@MainActivity) }
    }

    private fun copyTesseractFiles() {
        try {
            val filesDir = File(filesDir, "tesseract/tessdata")
            if (!filesDir.exists()) filesDir.mkdirs()
            val fileNames = assets.list("tesseract") ?: return
            for (name in fileNames) {
                val outFile = File(filesDir, name)
                if (!outFile.exists()) {
                    assets.open("tesseract/$name").use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            Log.e("MainActivity", "copyTesseractFiles", exception)
        }
    }
}