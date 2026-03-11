package com.livewin.freefiretracker

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrResult(
    val kills: Int,
    val playersAlive: Int,
    val roomId: String?,
    val playerDeathDetected: Boolean,
    val rawKillsText: String,
    val rawAliveText: String,
    val rawRoomIdText: String
)

/**
 * OcrProcessor uses ML Kit Text Recognition to extract game stats from
 * cropped regions of the Free Fire screenshot.
 *
 * Screen region definitions (based on common 1080p/720p layouts):
 *   - KILLS_REGION    : top-right red area (~82-95% width, 2-10% height)
 *   - ALIVE_REGION    : top-right teal area (~75-90% width, 10-18% height)
 *   - ROOM_ID_REGION  : left 38% of screen, ~20-35% height (join lobby screen)
 *   - DEATH_REGION    : center of screen, ~40-60% height (death banner)
 */
class OcrProcessor {

    companion object {
        private const val TAG = "OcrProcessor"

        // Region ratios (left, top, right, bottom) as fractions of screen dimensions
        private val KILLS_REGION = floatArrayOf(0.78f, 0.02f, 0.98f, 0.12f)
        private val ALIVE_REGION = floatArrayOf(0.72f, 0.10f, 0.92f, 0.20f)
        private val ROOM_ID_REGION = floatArrayOf(0.00f, 0.18f, 0.38f, 0.38f)
        private val DEATH_REGION = floatArrayOf(0.25f, 0.38f, 0.75f, 0.62f)

        // Regex patterns for extracting numbers
        private val NUMBER_REGEX = Regex("\\d+")
        private val ROOM_ID_REGEX = Regex("[A-Z0-9]{6,12}")

        // Keywords indicating player death
        private val DEATH_KEYWORDS = listOf(
            "knocked", "eliminated", "defeated", "you died", "killed by",
            "knocked out", "you have been", "finish"
        )
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Main entry point. Given a full-screen bitmap, crops each region
     * and runs OCR to extract kills, alive count, room ID, and death detection.
     */
    suspend fun process(bitmap: Bitmap): OcrResult {
        val width = bitmap.width
        val height = bitmap.height

        // Run OCR on all four regions concurrently (using coroutines sequentially here for simplicity)
        val killsText = runOcr(cropRegion(bitmap, KILLS_REGION, width, height))
        val aliveText = runOcr(cropRegion(bitmap, ALIVE_REGION, width, height))
        val roomIdText = runOcr(cropRegion(bitmap, ROOM_ID_REGION, width, height))
        val deathText = runOcr(cropRegion(bitmap, DEATH_REGION, width, height))

        val kills = extractFirstNumber(killsText)
        val alive = extractFirstNumber(aliveText)
        val roomId = extractRoomId(roomIdText)
        val isDead = detectDeath(deathText)

        Log.d(TAG, "OCR → kills=$kills alive=$alive roomId=$roomId dead=$isDead")

        return OcrResult(
            kills = kills,
            playersAlive = alive,
            roomId = roomId,
            playerDeathDetected = isDead,
            rawKillsText = killsText,
            rawAliveText = aliveText,
            rawRoomIdText = roomIdText
        )
    }

    private fun cropRegion(
        bitmap: Bitmap,
        region: FloatArray,
        width: Int,
        height: Int
    ): Bitmap {
        val left = (region[0] * width).toInt().coerceAtLeast(0)
        val top = (region[1] * height).toInt().coerceAtLeast(0)
        val right = (region[2] * width).toInt().coerceAtMost(width)
        val bottom = (region[3] * height).toInt().coerceAtMost(height)

        val cropWidth = (right - left).coerceAtLeast(1)
        val cropHeight = (bottom - top).coerceAtLeast(1)

        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }

    private suspend fun runOcr(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    continuation.resume(visionText.text.trim())
                }
                .addOnFailureListener { exception ->
                    Log.w(TAG, "OCR failed for region", exception)
                    continuation.resume("")
                }
        }

    private fun extractFirstNumber(text: String): Int {
        return NUMBER_REGEX.find(text)?.value?.toIntOrNull() ?: 0
    }

    private fun extractRoomId(text: String): String? {
        if (text.isBlank()) return null
        // Look for alphanumeric strings that look like room IDs
        val match = ROOM_ID_REGEX.find(text.uppercase())
        return match?.value?.takeIf { it.length >= 6 }
    }

    private fun detectDeath(text: String): Boolean {
        val lower = text.lowercase()
        return DEATH_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    fun close() {
        recognizer.close()
    }
}
