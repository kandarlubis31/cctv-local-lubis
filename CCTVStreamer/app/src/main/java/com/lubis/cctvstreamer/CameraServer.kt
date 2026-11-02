package com.lubis.cctvstreamer

import fi.iki.elonen.NanoHTTPD
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

class CameraServer : NanoHTTPD(8080) {

    companion object {
        private const val TAG = "CameraServer"
        private const val FRAME_DELAY_MS = 33L // ~30 fps
    }

    @Volatile
    private var latestJpegFrame: ByteArray? = null
    private val boundary = "frame"
    private val isRunning = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val activeStreams = ConcurrentHashMap<String, Thread>()
    // A tiny placeholder JPEG to send while camera warms up or no frames yet
    private val placeholderJpeg: ByteArray by lazy { createPlaceholderJpeg(320, 240) }

    fun setLatestJpegFrame(jpegData: ByteArray) {
        synchronized(this) {
            this.latestJpegFrame = jpegData
            Log.d(TAG, "Received new frame: ${jpegData.size} bytes")
        }
    }

    override fun start() {
        try {
            super.start()
            isRunning.set(true)
            Log.d(TAG, "Server started on port 8080")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            throw e
        }
    }

    override fun stop() {
        isRunning.set(false)
        
        // Stop all active streams
        activeStreams.values.forEach { thread ->
            try {
                thread.interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "Error interrupting stream thread", e)
            }
        }
        activeStreams.clear()
        
        executor.shutdown()
        try {
            super.stop()
            Log.d(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "Received request for: ${session.uri} from ${session.remoteIpAddress}")
        return when (session.uri) {
            "/stream" -> serveStream(session)
            "/" -> serveHomePage()
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, 
                MIME_PLAINTEXT, 
                "Not Found"
            )
        }
    }

    private fun serveHomePage(): Response {
    val html = """
            <!DOCTYPE html>
            <html>
            <head>
        <title>CCTV Stream</title>
        <!-- Inline favicon (simple square) to avoid missing external resource -->
        <link rel="icon" href="data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='32' height='32'><rect width='32' height='32' fill='%23333'/></svg>">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { 
                        margin: 0; 
                        padding: 20px; 
                        font-family: Arial, sans-serif;
                        background: #1a1a1a;
                        color: white;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        min-height: 100vh;
                    }
                    h1 { margin-bottom: 20px; }
                    #streamImg, #streamCanvas { 
                        max-width: 100%; 
                        height: auto;
                        border: 2px solid #444;
                        border-radius: 8px;
                        box-shadow: 0 4px 6px rgba(0,0,0,0.3);
                        background: #333;
                    }
                    .container {
                        max-width: 1200px;
                        width: 100%;
                    }
                    .status {
                        padding: 10px;
                        margin: 10px 0;
                        background: #2a2a2a;
                        border-radius: 4px;
                        text-align: center;
                    }
                    </style>
                <script>
                    function drawToCanvas() {
                        const img = document.getElementById('streamImg');
                        const canvas = document.getElementById('streamCanvas');
                        const ctx = canvas.getContext('2d');

                        function draw() {
                            try {
                                if (img.complete && img.naturalWidth) {
                                    // Resize canvas to image ratio while keeping max-width CSS
                                    const aspect = img.naturalWidth / img.naturalHeight;
                                    const maxW = Math.min(window.innerWidth * 0.6, 1200);
                                    const w = Math.min(maxW, img.naturalWidth);
                                    const h = Math.max(1, Math.round(w / aspect));
                                    canvas.width = w;
                                    canvas.height = h;
                                    ctx.drawImage(img, 0, 0, w, h);
                                }
                            } catch (e) {
                                // ignore draw errors
                            }
                            requestAnimationFrame(draw);
                        }
                        requestAnimationFrame(draw);
                    }
                </script>
            </head>
            <body>
                <div class="container">
                    <h1>CCTV Live Stream</h1>
                    <div class="status" id="status">Connecting...</div>
                <!-- Hidden img feeds the canvas; browsers treat img src=/stream as MJPEG stream in most cases -->
                <img src="/stream" alt="Live Stream" id="streamImg" style="display:none;"
                    onerror="document.getElementById('status').textContent='Connection Failed - Retrying...'; setTimeout(() => this.src='/stream?t='+Date.now(), 3000);"
                    onload="document.getElementById('status').textContent='Connected - Live'; drawToCanvas();">

                <!-- Canvas shows the live preview and handles resizing better -->
                <canvas id="streamCanvas" width="640" height="480"></canvas>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveStream(session: IHTTPSession): Response {
        if (!isRunning.get()) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "Server not running"
            )
        }

        try {
            val inputStream = PipedInputStream(65536) // Increased buffer
            val outputStream = PipedOutputStream(inputStream)

            val response = newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=$boundary",
                inputStream
            )
            
            response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Expires", "0")
            response.addHeader("Connection", "close")
            response.addHeader("Access-Control-Allow-Origin", "*")

            val streamThread = Thread {
                val streamId = Thread.currentThread().id.toString()
                activeStreams[streamId] = Thread.currentThread()
                
                try {
                    Log.d(TAG, "Stream started for client: ${session.remoteIpAddress}")
                    var frameCount = 0
                    
                    var lastFrameSent = System.currentTimeMillis()
                    val noFrameTimeout = 3000L // ms
                    while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                        val frame = synchronized(this) { latestJpegFrame }

                        val toSend = if (frame != null && frame.isNotEmpty()) {
                            lastFrameSent = System.currentTimeMillis()
                            frame
                        } else {
                            // If no real frame for a while, send placeholder to keep connection alive
                            if (System.currentTimeMillis() - lastFrameSent > noFrameTimeout) placeholderJpeg else null
                        }

                        if (toSend != null) {
                            try {
                                val header = buildString {
                                    append("--$boundary\r\n")
                                    append("Content-Type: image/jpeg\r\n")
                                    append("Content-Length: ${toSend.size}\r\n")
                                    append("\r\n")
                                }

                                outputStream.write(header.toByteArray())
                                outputStream.write(toSend)
                                outputStream.write("\r\n".toByteArray())
                                outputStream.flush()
                                
                                frameCount++
                                if (frameCount % 100 == 0) {
                                    Log.d(TAG, "Sent $frameCount frames to ${session.remoteIpAddress}")
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "Client disconnected: ${e.message}")
                                break
                            }
                        }
                        
                        Thread.sleep(FRAME_DELAY_MS)
                    }
                } catch (e: InterruptedException) {
                    Log.d(TAG, "Stream interrupted")
                } catch (e: Exception) {
                    Log.e(TAG, "Stream error", e)
                } finally {
                    activeStreams.remove(streamId)
                    try {
                        outputStream.close()
                        inputStream.close()
                    } catch (e: Exception) {
                        // Ignore close errors
                    }
                    Log.d(TAG, "Stream ended for client: ${session.remoteIpAddress}")
                }
            }
            
            streamThread.start()
            return response
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create stream", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Internal Server Error: ${e.message}"
            )
        }
    }

    private fun createPlaceholderJpeg(width: Int, height: Int): ByteArray {
        try {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.DKGRAY)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            return out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create placeholder jpeg", e)
            return ByteArray(0)
        }
    }
}