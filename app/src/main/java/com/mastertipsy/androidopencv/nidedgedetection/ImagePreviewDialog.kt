package com.mastertipsy.androidopencv.nidedgedetection

import android.content.DialogInterface
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.mastertipsy.androidopencv.R
import com.mastertipsy.androidopencv.databinding.DialogImagePreviewBinding
import com.mastertipsy.androidopencv.updateInsetsPadding

class ImagePreviewDialog(
    private val source: Uri,
    private val content: String? = null,
    private val onClose: (() -> Unit)? = null,
) : DialogFragment() {
    private lateinit var binding: DialogImagePreviewBinding

    override fun getTheme(): Int = R.style.FullScreenDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DialogImagePreviewBinding.inflate(inflater, container, false)
        dialog?.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        binding.main.updateInsetsPadding()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.imageView.setImageURI(source)
        binding.textView.isVisible = !content.isNullOrEmpty()
        content?.let { binding.textView.text = it }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onClose?.invoke()
    }
}