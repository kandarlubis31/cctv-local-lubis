package com.lubis.cctvstreamer

import androidx.camera.core.CameraSelector
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    private val _isStreaming = MutableLiveData<Boolean>()
    val isStreaming: LiveData<Boolean> = _isStreaming
    
    private val _currentCamera = MutableLiveData<CameraSelector>()
    val currentCamera: LiveData<CameraSelector> = _currentCamera
    
    private val _resolutionWidth = MutableLiveData<Int>()
    val resolutionWidth: LiveData<Int> = _resolutionWidth
    
    private val _resolutionHeight = MutableLiveData<Int>()
    val resolutionHeight: LiveData<Int> = _resolutionHeight
    
    private val _jpegQuality = MutableLiveData<Int>()
    val jpegQuality: LiveData<Int> = _jpegQuality
    
    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage
    
    init {
        _isStreaming.value = false
        _currentCamera.value = CameraSelector.DEFAULT_BACK_CAMERA
        _resolutionWidth.value = 640
        _resolutionHeight.value = 480
        _jpegQuality.value = 75
        _statusMessage.value = "Ready to stream"
    }
    
    fun setStreaming(isStreaming: Boolean) {
        _isStreaming.value = isStreaming
    }
    
    fun switchCamera() {
        _currentCamera.value = if (_currentCamera.value == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }
    
    fun setResolution(width: Int, height: Int) {
        _resolutionWidth.value = width
        _resolutionHeight.value = height
    }
    
    fun setJpegQuality(quality: Int) {
        _jpegQuality.value = quality
    }
    
    fun setStatus(message: String) {
        _statusMessage.value = message
    }
}