package com.lubis.cctvstreamer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : AppCompatActivity() {

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var ipAddressTextView: TextView
    private lateinit var previewView: PreviewView
    private lateinit var qualitySeekBar: SeekBar
    private lateinit var qualityTextView: TextView
    private lateinit var resolutionSpinner: Spinner
    private lateinit var streamingStateReceiver: BroadcastReceiver
    private var isStreaming = false

    private val requiredPermissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }
    private val requestCodePermissions = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        ipAddressTextView = findViewById(R.id.ipAddressTextView)
        previewView = findViewById(R.id.previewView)
        qualitySeekBar = findViewById(R.id.qualitySeekBar)
        qualityTextView = findViewById(R.id.qualityTextView)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)

        // Inisialisasi receiver
        streamingStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    StreamingService.ACTION_STREAMING_STARTED -> {
                        updateStreamingState(true)
                    }
                    StreamingService.ACTION_STREAMING_STOPPED -> {
                        updateStreamingState(false)
                    }
                }
            }
        }

        if (allPermissionsGranted()) {
            setupUI()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, requestCodePermissions)
        }
    }

    override fun onResume() {
        super.onResume()
        // Register receiver
        val filter = IntentFilter().apply {
            addAction(StreamingService.ACTION_STREAMING_STARTED)
            addAction(StreamingService.ACTION_STREAMING_STOPPED)
        }
        registerReceiver(streamingStateReceiver, filter)
        
        // Restart camera preview if permissions are granted
        if (allPermissionsGranted()) {
            setupCameraPreview()
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister receiver
        unregisterReceiver(streamingStateReceiver)
    }

    private fun setupUI() {
        val ipAddress = getIpAddress()
        ipAddressTextView.text = "http://$ipAddress:8080/stream"

        val serviceIntent = Intent(this, StreamingService::class.java)

        // Setup quality controls
        qualitySeekBar.max = 100
        qualitySeekBar.progress = 90 // Default ke kualitas tinggi
        qualityTextView.text = "Kualitas: 90%"
        
        qualitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                qualityTextView.text = "Kualitas: $progress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Setup resolution spinner
        val resolutions = arrayOf("640x480", "1280x720", "1920x1080")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        resolutionSpinner.adapter = adapter

        startButton.setOnClickListener {
            // Update UI state
            startButton.isEnabled = false
            stopButton.isEnabled = true
            resolutionSpinner.isEnabled = false
            qualitySeekBar.isEnabled = false
            
            serviceIntent.action = StreamingService.ACTION_START
            
            // Ambil resolusi yang dipilih
            val resolution = resolutions[resolutionSpinner.selectedItemPosition].split("x")
            serviceIntent.putExtra(StreamingService.EXTRA_RESOLUTION_WIDTH, resolution[0].toInt())
            serviceIntent.putExtra(StreamingService.EXTRA_RESOLUTION_HEIGHT, resolution[1].toInt())
            
            // Ambil kualitas yang dipilih
            serviceIntent.putExtra(StreamingService.EXTRA_JPEG_QUALITY, qualitySeekBar.progress)
            
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "Streaming Server Started", Toast.LENGTH_SHORT).show()
        }

        stopButton.setOnClickListener {
            // Update UI state
            startButton.isEnabled = true
            stopButton.isEnabled = false
            resolutionSpinner.isEnabled = true
            qualitySeekBar.isEnabled = true
            
            serviceIntent.action = StreamingService.ACTION_STOP
            stopService(serviceIntent)
            Toast.makeText(this, "Streaming Server Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupCameraPreview() {
        Log.d("MainActivity", "Setting up camera preview...")
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                Log.d("MainActivity", "Camera provider obtained successfully")
                
                // Create Preview use case
                val preview = Preview.Builder().build()
                Log.d("MainActivity", "Preview use case created")
                
                // Set the surface provider from our PreviewView
                preview.setSurfaceProvider(previewView.surfaceProvider)
                Log.d("MainActivity", "Surface provider set to PreviewView")
                
                // Create ImageAnalysis use case
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), { image: ImageProxy ->
                    try {
                        // Get the streaming service
                        val serviceIntent = Intent(this, StreamingService::class.java)
                        Log.d("MainActivity", "Processing frame: ${image.width}x${image.height} format=${image.format}")
                        
                        // Send frame dimensions instead of the ImageProxy
                        serviceIntent.putExtra("width", image.width)
                        serviceIntent.putExtra("height", image.height)
                        serviceIntent.putExtra("format", image.format)
                        
                        if (!isFinishing) {
                            startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Frame processing error", e)
                    } finally {
                        image.close()
                    }
                })
                
                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                // Unbind any bound use cases before rebinding
                try {
                    cameraProvider.unbindAll()
                    Log.d("MainActivity", "Successfully unbound previous use cases")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error unbinding camera use cases", e)
                }
                
                // Bind both preview and analysis use cases to the camera
                try {
                    val camera = cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                    Log.d("MainActivity", "Camera preview and analysis started successfully")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to bind camera use cases", e)
                    Toast.makeText(this, "Failed to start camera preview: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to get camera provider", e)
                Toast.makeText(this, "Failed to initialize camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestCodePermissions) {
            if (allPermissionsGranted()) {
                setupUI()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    // Metode untuk update UI state
    fun updateStreamingState(isStreaming: Boolean) {
        runOnUiThread {
            startButton.isEnabled = !isStreaming
            stopButton.isEnabled = isStreaming
            resolutionSpinner.isEnabled = !isStreaming
            qualitySeekBar.isEnabled = !isStreaming
            
            if (isStreaming) {
                Toast.makeText(this, "Streaming aktif", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Streaming dihentikan", Toast.LENGTH_SHORT).show()
            }
        }
    }
}