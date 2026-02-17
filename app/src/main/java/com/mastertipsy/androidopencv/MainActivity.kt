package com.mastertipsy.androidopencv

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mastertipsy.androidopencv.databinding.ActivityMainBinding
import com.mastertipsy.androidopencv.nidedgedetection.NIDEdgeDetectionActivity

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSystemUiVisibility()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.main.updateInsetsPadding(16.dpToPx)

        setupListener()
    }

    private fun setupListener() = binding.apply {
        buttonNID.setOnClickListener { NIDEdgeDetectionActivity.open(this@MainActivity) }
    }
}