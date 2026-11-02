package com.lubis.cctvstreamer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StreamingStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            StreamingService.ACTION_STREAMING_STARTED -> {
                // Update UI ketika streaming dimulai
                if (context is MainActivity) {
                    context.updateStreamingState(true)
                }
            }
            StreamingService.ACTION_STREAMING_STOPPED -> {
                // Update UI ketika streaming dihentikan
                if (context is MainActivity) {
                    context.updateStreamingState(false)
                }
            }
        }
    }
}