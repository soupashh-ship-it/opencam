package com.opencam.ui

import android.app.Application
import android.graphics.SurfaceTexture
import androidx.lifecycle.AndroidViewModel
import com.opencam.OpenCamApplication
import com.opencam.StreamConfig
import com.opencam.WhiteBalance
import com.opencam.stream.StreamManager
import com.opencam.stream.StreamManagerHolder
import com.opencam.stream.StreamState
import kotlinx.coroutines.flow.StateFlow

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val stream: StreamManager =
        (application as? OpenCamApplication)?.streamManager
            ?: StreamManagerHolder.instance
            ?: StreamManager(application.applicationContext).also { StreamManagerHolder.instance = it }

    val state: StateFlow<StreamState> = stream.state
    val config: StateFlow<StreamConfig> = stream.config

    fun updateConfig(transform: (StreamConfig) -> StreamConfig) = stream.updateConfig(transform)
    fun flipCamera() = stream.flipCamera()
    fun toggleMirror() = stream.toggleMirror()
    fun reassertPreviewBuffer() = stream.reassertPreviewBuffer()
    fun toggleTorch() = stream.toggleTorch()
    fun setZoom(scale: Float) = stream.setZoom(scale)
    fun focusAt(nx: Float, ny: Float) = stream.focusAt(nx, ny)
    fun setExposure(ev: Int) = stream.setExposure(ev)
    fun setWhiteBalance(wb: WhiteBalance) = stream.setWhiteBalance(wb)
    fun setEisEnabled(on: Boolean) = stream.setEisEnabled(on)
    fun attachPreview(texture: SurfaceTexture) = stream.attachPreview(texture)
    fun detachPreview() = stream.detachPreview()
}
