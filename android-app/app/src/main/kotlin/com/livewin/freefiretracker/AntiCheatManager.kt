package com.livewin.freefiretracker

import android.util.Log

/**
 * Basic anti-cheat validation:
 * 1. Session watermark uniqueness — each session gets a unique watermark; duplicate
 *    submissions for the same session are flagged.
 * 2. Monotone alive-count check — alive count can only decrease during a match.
 *    If it increases, it flags as potential manipulation.
 */
class AntiCheatManager {

    companion object {
        private const val TAG = "AntiCheatManager"
    }

    private var sessionWatermark: String = generateWatermark()
    private val usedWatermarks = mutableSetOf<String>()
    private var lastPlayersAlive: Int = Int.MAX_VALUE
    private var monotonicViolationCount: Int = 0

    private var _cheatFlagged: Boolean = false
    val cheatFlagged: Boolean get() = _cheatFlagged

    /**
     * Call at the start of each new match session.
     */
    fun startNewSession() {
        sessionWatermark = generateWatermark()
        lastPlayersAlive = Int.MAX_VALUE
        monotonicViolationCount = 0
        _cheatFlagged = false
        Log.d(TAG, "New session started. Watermark: $sessionWatermark")
    }

    /**
     * Returns current session watermark.
     */
    fun getWatermark(): String = sessionWatermark

    /**
     * Validates an incoming alive count against the monotone constraint.
     * Alive count must only decrease (or stay same) once the match starts.
     * Returns true if the count is valid, false if it's a cheat signal.
     */
    fun validateAliveCount(newAliveCount: Int): Boolean {
        if (newAliveCount < 0) {
            Log.w(TAG, "Invalid alive count: $newAliveCount")
            return false
        }

        // Ignore initial reads (MAX_VALUE sentinel)
        if (lastPlayersAlive == Int.MAX_VALUE) {
            lastPlayersAlive = newAliveCount
            return true
        }

        if (newAliveCount > lastPlayersAlive) {
            monotonicViolationCount++
            Log.w(TAG, "Monotone violation #$monotonicViolationCount: $lastPlayersAlive → $newAliveCount")
            // Allow a small tolerance (OCR noise), flag after 3 violations
            if (monotonicViolationCount >= 3) {
                _cheatFlagged = true
                Log.e(TAG, "CHEAT FLAGGED: alive count increased $monotonicViolationCount times")
            }
            return false
        }

        lastPlayersAlive = newAliveCount
        return true
    }

    /**
     * Validates session watermark uniqueness.
     * Call before writing each score batch.
     * Returns true if this watermark is unique (not replayed), false if duplicate.
     */
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

    companion object {
        fun generateWatermark(): String {
            val timestamp = System.currentTimeMillis()
            val random = (Math.random() * 1_000_000).toLong()
            return "WM-${timestamp}-${random}"
        }
    }
}
