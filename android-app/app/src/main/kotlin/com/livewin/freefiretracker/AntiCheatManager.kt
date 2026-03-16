package com.livewin.freefiretracker

import android.util.Log

class AntiCheatManager {

    companion object {
        private const val TAG = "AntiCheatManager"

        fun generateWatermark(): String {
            val timestamp = System.currentTimeMillis()
            val random = (Math.random() * 1_000_000).toLong()
            return "WM-${timestamp}-${random}"
        }
    }

    private var sessionWatermark: String = generateWatermark()
    private val usedWatermarks = mutableSetOf<String>()
    private var lastPlayersAlive: Int = Int.MAX_VALUE
    private var monotonicViolationCount: Int = 0

    private var _cheatFlagged: Boolean = false
    val cheatFlagged: Boolean get() = _cheatFlagged

    fun startNewSession() {
        sessionWatermark = generateWatermark()
        lastPlayersAlive = Int.MAX_VALUE
        monotonicViolationCount = 0
        _cheatFlagged = false
        Log.d(TAG, "New session started. Watermark: $sessionWatermark")
    }

    fun getWatermark(): String = sessionWatermark

    fun validateAliveCount(newAliveCount: Int): Boolean {
        if (newAliveCount < 0) {
            Log.w(TAG, "Invalid alive count: $newAliveCount")
            return false
        }

        if (lastPlayersAlive == Int.MAX_VALUE) {
            lastPlayersAlive = newAliveCount
            return true
        }

        if (newAliveCount > lastPlayersAlive) {
            monotonicViolationCount++
            Log.w(TAG, "Monotone violation #$monotonicViolationCount: $lastPlayersAlive → $newAliveCount")
            if (monotonicViolationCount >= 3) {
                _cheatFlagged = true
                Log.e(TAG, "CHEAT FLAGGED: alive count increased $monotonicViolationCount times")
            }
            return false
        }

        lastPlayersAlive = newAliveCount
        return true
    }

    fun validateWatermarkUniqueness(watermark: String): Boolean {
        if (usedWatermarks.contains(watermark)) {
            _cheatFlagged = true
            Log.e(TAG, "CHEAT FLAGGED: duplicate session watermark detected: $watermark")
            return false
        }
        usedWatermarks.add(watermark)
        return true
    }

    fun reset() {
        lastPlayersAlive = Int.MAX_VALUE
        monotonicViolationCount = 0
        _cheatFlagged = false
    }
}
