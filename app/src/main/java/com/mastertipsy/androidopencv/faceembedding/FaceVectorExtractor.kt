package com.mastertipsy.androidopencv.faceembedding

import android.content.Context
import com.mastertipsy.androidopencv.R
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class FaceVectorExtractor(private val context: Context) {
    private var interpreter: Interpreter? = null
    var faceCascade: CascadeClassifier? = null

    init {
        if (interpreter == null) loadModelInterpreter()
        if (faceCascade == null) loadFaceCascadeClassifier()
    }

    fun extractVector(rgbMat: Mat): List<Double> {
        val faceMat = Mat()
        Imgproc.resize(rgbMat, faceMat, Size(160.0, 160.0))
        val inputBuffer = ByteBuffer.allocateDirect(1 * 160 * 160 * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        for (row in 0 until 160) {
            for (col in 0 until 160) {
                val pixel = faceMat.get(row, col)
                inputBuffer.putFloat(((pixel[0] - 127.5) / 128.0).toFloat())
                inputBuffer.putFloat(((pixel[1] - 127.5) / 128.0).toFloat())
                inputBuffer.putFloat(((pixel[2] - 127.5) / 128.0).toFloat())
            }
        }
        val outputArray = Array(1) { FloatArray(512) }
        synchronized(this) { interpreter?.run(inputBuffer, outputArray) }
        faceMat.release()
        val rawVector = outputArray[0]
        val magnitude = kotlin.math.sqrt(rawVector.sumOf { it.toDouble() * it.toDouble() })
        return rawVector.map { it.toDouble() / magnitude }
    }

    private fun loadModelInterpreter() {
        val fileDescriptor = context.assets.openFd("facenet_512.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val model = fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
        val options = Interpreter.Options().apply { setUseNNAPI(true) }
        interpreter = Interpreter(model, options)
    }

    private fun loadFaceCascadeClassifier() {
        val cascadeFile = File(context.cacheDir, "haarcascade_face.xml")
        if (!cascadeFile.exists()) {
            context.resources.openRawResource(R.raw.haarcascade_frontalface_default).use { input ->
                cascadeFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        faceCascade = CascadeClassifier(cascadeFile.absolutePath)
    }
}
