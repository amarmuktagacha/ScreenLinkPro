package com.screenlink.pro.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RemoteControlAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: RemoteControlAccessibilityService? = null
        fun dispatch(payload: ByteArray) { instance?.perform(payload) }
        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    private fun perform(payload: ByteArray) {
        if (payload.size < 21) return
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val action = input.int
        val x1 = input.float.coerceAtLeast(0f).coerceAtMost(1f)
        val y1 = input.float.coerceAtLeast(0f).coerceAtMost(1f)
        val x2 = input.float.coerceAtLeast(0f).coerceAtMost(1f)
        val y2 = input.float.coerceAtLeast(0f).coerceAtMost(1f)
        val duration = input.long.coerceIn(50L, 3_000L)
        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(x1 * dm.widthPixels, y1 * dm.heightPixels)
            if (action == 1) lineTo(x2 * dm.widthPixels, y2 * dm.heightPixels) else lineTo(x1 * dm.widthPixels, y1 * dm.heightPixels)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, if (action == 1) duration else duration.coerceAtMost(180L))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
