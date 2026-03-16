package com.livewin.freefiretracker

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class OcrResult(
    val kills: Int,
    val playersAlive: Int,
    val roomId: String?,
    val playerDeathDetected: Boolean,
    val rawKillsText: String,
    val rawAliveText: String,
    val rawRoomIdText: String
)

class OcrProcessor {

    companion object {
        private const val TAG = "OcrProcessor"

        // Wider regions for better detection across all phone sizes
        private val KILLS_REGION = floatArrayOf(0.70f, 0.01f, 0.99f, 0.14f)
        private val ALIVE_REGION = floatArrayOf(0.65f, 0.08f, 0.99f, 0.22f)
        private val ROOM_ID_REGION = floatArrayOf(0.00f, 0.15f, 0.45f, 0.40f)
        private val DEATH_REGION = floatArrayOf(0.20f, 0.35f, 0.80f, 0.65f)

        private val NUMBER_REGEX = Regex("\\d+")
        private val ROOM_ID_REGEX = Regex("[A-Z0-9]{6,12}")

        private val DEATH_KEYWORDS = listOf(
            "knocked", "eliminated", "defeated", "you died", "killed by",
            "knocked out", "you have been", "finish", "revive", "spectate",
            "safe", "booyah", "winner", "vehicle", "knocked down"
        )
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun process(bitmap: Bitmap): OcrResult {
        val width = bitmap.width
        val height = bitmap.height

        Log.d(TAG, "Processing bitmap: ${width}x${height}")

        val killsText = runOcr(cropRegion(bitmap, KILLS_REGION, width, height))
        val aliveText = runOcr(cropRegion(bitmap, ALIVE_REGION, width, height))
        val roomIdText = runOcr(cropRegion(bitmap, ROOM_ID_REGION, width, height))
        val deathText = runOcr(cropRegion(bitmap, DEATH_REGION, width, height))

        Log.d(TAG, "Raw OCR → kills='$killsText' alive='$aliveText' roomId='$roomIdText' death='$deathText'")

        val kills = extractFirstNumber(killsText)
        val alive = extractFirstNumber(aliveText)
        val roomId = extractRoomId(roomIdText)
        val isDead = detectDeath(deathText)

        Log.d(TAG, "Parsed → kills=$kills alive=$alive roomId=$roomId dead=$isDead")

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
