package com.lubis.cctvstreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class StreamingService : LifecycleService() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SWITCH_CAMERA = "ACTION_SWITCH_CAMERA"
        const val ACTION_STREAMING_STARTED = "ACTION_STREAMING_STARTED"
        const val ACTION_STREAMING_STOPPED = "ACTION_STREAMING_STOPPED"
        const val EXTRA_RESOLUTION_WIDTH = "EXTRA_RESOLUTION_WIDTH"
        const val EXTRA_RESOLUTION_HEIGHT = "EXTRA_RESOLUTION_HEIGHT"
        const val EXTRA_JPEG_QUALITY = "EXTRA_JPEG_QUALITY"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "StreamingChannel"
        private const val TAG = "StreamingService"
    }

    private lateinit var cameraExecutor: ExecutorService
    private var cameraServer: CameraServer? = null
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private var isStreaming = AtomicBoolean(false)
    private var frameCount = 0  // Track frames for logging
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var targetResolution = Size(1280, 720)
    private var jpegQuality = 90

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        intent?.getIntExtra(EXTRA_RESOLUTION_WIDTH, 1280)?.let { width ->
            intent.getIntExtra(EXTRA_RESOLUTION_HEIGHT, 720).let { height ->
                targetResolution = Size(width, height)
            }
        }
        
        intent?.getIntExtra(EXTRA_JPEG_QUALITY, 90)?.let { jpegQuality = it }
        
        when (intent?.action) {
            ACTION_START -> startStreaming()
            ACTION_STOP -> stopStreaming()
            ACTION_SWITCH_CAMERA -> switchCamera()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming() {
        if (isStreaming.get()) return
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Start server first
        try {
            cameraServer = CameraServer().apply { 
                start()
                Log.d(TAG, "Camera server started successfully")
            }
            
            // Give server time to initialize
            Thread.sleep(500)
            
        } catch (e: Exception) {
            Log.e(TAG, "Server error", e)
            stopSelf()
            return
        }

        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                startCamera(cameraProviderFuture.get())
                isStreaming.set(true)
                sendBroadcast(Intent(ACTION_STREAMING_STARTED))
                Log.d(TAG, "Camera started and streaming enabled")
            } catch (e: Exception) {
                Log.e(TAG, "Camera error", e)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopStreaming() {
        try {
            Log.d(TAG, "Stopping streaming service...")
            if (!isStreaming.getAndSet(false)) {
                Log.d(TAG, "Streaming was not active")
                return
            }
            
            // Stop the camera server
            try {
                cameraServer?.stop()
                cameraServer = null
                Log.d(TAG, "Camera server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping camera server", e)
            }
            
            // Unbind camera use cases
            try {
                if (::cameraProviderFuture.isInitialized && cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                    Log.d(TAG, "Camera use cases unbound")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding camera use cases", e)
            }
            
            // Shutdown camera executor
            try {
                if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
                    cameraExecutor.shutdown()
                    Log.d(TAG, "Camera executor shutdown")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error shutting down camera executor", e)
            }
            
            // Stop foreground service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            
            // Notify UI
            sendBroadcast(Intent(ACTION_STREAMING_STOPPED))
            Log.d(TAG, "Streaming service stopped successfully")
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error in stopStreaming", e)
            stopSelf()
        }
    }

    private fun startCamera(cameraProvider: ProcessCameraProvider) {
        try {
            Log.d(TAG, "Starting camera with resolution ${targetResolution.width}x${targetResolution.height}")
            cameraProvider.unbindAll()
            
            // Create analysis use case
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(targetResolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            
            // Create preview use case
            val preview = Preview.Builder()
                .setTargetResolution(targetResolution)
                .build()
            
            // Set up frame analyzer
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isStreaming.get()) {
                    try {
                        Log.d(TAG, "Received frame: ${imageProxy.width}x${imageProxy.height} format=${imageProxy.format}")
                        val jpegBytes = imageProxyToNv21(imageProxy)
                        if (jpegBytes.isNotEmpty()) {
                            frameCount++
                            if (frameCount % 30 == 0) {  // Log every 30 frames
                                Log.d(TAG, "Processed frame $frameCount: ${jpegBytes.size} bytes")
                            }
                            cameraServer?.setLatestJpegFrame(jpegBytes)
                        } else {
                            Log.e(TAG, "Empty JPEG frame generated")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Frame processing error", e)
                        e.printStackTrace()
                    } finally {
                        imageProxy.close()
                    }
                } else {
                    imageProxy.close()
                }
            }
            
            try {
                // Bind both preview and analysis
                cameraProvider.bindToLifecycle(
                    this,
                    currentCameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d(TAG, "Camera use cases bound successfully")
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to bind camera use cases", exc)
                throw exc
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera setup failed", e)
            throw e
        }
    }

    private fun imageProxyToNv21(image: ImageProxy): ByteArray {
        return try {
            val width = image.width
            val height = image.height
            Log.d(TAG, "Converting frame ${width}x${height} format=${image.format}")

            // Get the YUV planes
            val planes = image.planes
            val yPlane = planes[0].buffer
            val uPlane = planes[1].buffer
            val vPlane = planes[2].buffer

            val ySize = yPlane.remaining()
            val uSize = uPlane.remaining()
            val vSize = vPlane.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // Copy Y plane as is
            yPlane.get(nv21, 0, ySize)
            
            // Interleave U and V planes
            var pos = ySize
            val uvPixelStride = planes[1].pixelStride
            if (uvPixelStride == 1) {
                // Copy V plane
                vPlane.get(nv21, pos, vSize)
                pos += vSize
                // Copy U plane
                uPlane.get(nv21, pos, uSize)
            } else {
                // Interleave UV planes
                val uvRowStride = planes[1].rowStride
                val uvHeight = height / 2
                val uvWidth = width / 2
                val uvBuffer = ByteArray(uvRowStride)
                
                var uvPos = 0
                for (row in 0 until uvHeight) {
                    var pos = ySize + row * uvWidth * 2
                    if (vPlane.remaining() >= uvRowStride) {
                        vPlane.get(uvBuffer, 0, uvRowStride)
                    }
                    if (uPlane.remaining() >= uvRowStride) {
                        uPlane.get(uvBuffer, 0, uvRowStride)
                    }
                    
                    for (col in 0 until uvWidth) {
                        nv21[pos++] = uvBuffer[col * uvPixelStride]
                        nv21[pos++] = uvBuffer[col * uvPixelStride + 1]
                    }
                }
            }

            // Convert to JPEG
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            ByteArrayOutputStream().use { out ->
                yuvImage.compressToJpeg(Rect(0, 0, width, height), jpegQuality, out)
                val jpegBytes = out.toByteArray()
                Log.d(TAG, "Successfully converted to JPEG: ${jpegBytes.size} bytes")
                jpegBytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "Convert error", e)
            e.printStackTrace()
            ByteArray(0)
        }
    }

    private fun switchCamera() {
        if (!isStreaming.get()) return
        
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        
        cameraProviderFuture.addListener({
            try {
                startCamera(cameraProviderFuture.get())
            } catch (e: Exception) {
                Log.e(TAG, "Switch error", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val mainIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val switchIntent = PendingIntent.getService(
            this, 1, Intent(this, StreamingService::class.java).apply {
                action = ACTION_SWITCH_CAMERA
            }, PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, StreamingService::class.java).apply {
                action = ACTION_STOP
            }, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CCTV Streamer")
            .setContentText("Streaming active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(ContextCompat.getColor(this, R.color.ic_launcher_background))
            .setContentIntent(mainIntent)
            .addAction(android.R.drawable.ic_menu_rotate, "Switch", switchIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }
}