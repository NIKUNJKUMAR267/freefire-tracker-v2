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
    val activePlayers: List<String>,
    val deadPlayers: List<String>,
    val rawKillsText: String,
    val rawAliveText: String,
    val rawRoomIdText: String,
    val rawPlayerListText: String
)

class OcrProcessor {

    companion object {
        private const val TAG = "OcrProcessor"

        // LANDSCAPE mode coordinates
        // Players Alive — teal box top-right
        private val ALIVE_REGION = floatArrayOf(0.78f, 0.01f, 0.88f, 0.10f)
        // Kills — red/skull box top-right
        private val KILLS_REGION = floatArrayOf(0.88f, 0.01f, 0.99f, 0.10f)
        // Room ID — bottom of screen (room code)
        private val ROOM_ID_REGION = floatArrayOf(0.00f, 0.88f, 0.50f, 1.00f)
        // Death banner — center screen
        private val DEATH_REGION = floatArrayOf(0.20f, 0.35f, 0.80f, 0.65f)
        // Player list — left side
        private val PLAYER_LIST_REGION = floatArrayOf(0.00f, 0.12f, 0.22f, 0.48f)

        private val NUMBER_REGEX = Regex("\\d+")
        private val ROOM_ID_REGEX = Regex("[A-Z0-9]{6,12}")

        private val DEATH_KEYWORDS = listOf(
            "knocked", "eliminated", "defeated", "you died", "killed by",
            "knocked out", "you have been", "finish", "revive", "spectate",
            "safe", "booyah", "winner", "knocked down"
        )
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun process(bitmap: Bitmap): OcrResult {
        val width = bitmap.width
        val height = bitmap.height
        Log.d(TAG, "Bitmap: ${width}x${height}")

        val killsText    = runOcr(cropRegion(bitmap, KILLS_REGION, width, height))
        val aliveText    = runOcr(cropRegion(bitmap, ALIVE_REGION, width, height))
        val roomIdText   = runOcr(cropRegion(bitmap, ROOM_ID_REGION, width, height))
        val deathText    = runOcr(cropRegion(bitmap, DEATH_REGION, width, height))
        val playerListText = runOcr(cropRegion(bitmap, PLAYER_LIST_REGION, width, height))

        Log.d(TAG, "kills='$killsText' alive='$aliveText' players='$playerListText'")

        val kills  = extractFirstNumber(killsText)
        val alive  = extractFirstNumber(aliveText)
        val roomId = extractRoomId(roomIdText)
        val isDead = detectDeath(deathText)

        // Parse player list — alive vs dead
        val (activePlayers, deadPlayers) = parsePlayerList(playerListText)

        return OcrResult(
            kills = kills,
            playersAlive = alive,
            roomId = roomId,
            playerDeathDetected = isDead,
            activePlayers = activePlayers,
            deadPlayers = deadPlayers,
            rawKillsText = killsText,
            rawAliveText = aliveText,
            rawRoomIdText = roomIdText,
            rawPlayerListText = playerListText
        )
    }

    /**
     * Player list parse karta hai.
     * Dead player ke aage flag 🏳️ hota hai — OCR mein alag text pattern
     * Alive players ke aage location pin 📍 hota hai
     */
    private fun parsePlayerList(text: String): Pair<List<String>, List<String>> {
        if (text.isBlank()) return Pair(emptyList(), emptyList())

        val lines = text.lines().filter { it.isNotBlank() }
        val active = mutableListOf<String>()
        val dead   = mutableListOf<String>()

        // Dead player indicators jo OCR mein aate hain
        val deadIndicators = listOf("eliminated", "dead", "x", "out")

        for (line in lines) {
            val cleanLine = line.trim()
            // Number prefix hata do (1,2,3,4)
            val playerName = cleanLine.replace(Regex("^\\d+\\s*"), "").trim()
            if (playerName.isBlank()) continue

            // Agar line mein dead indicator ho
            val isDead = deadIndicators.any { cleanLine.lowercase().contains(it) }
            if (isDead) {
                dead.add(playerName)
            } else {
                active.add(playerName)
            }
        }

        return Pair(active, dead)
    }

    private fun cropRegion(bitmap: Bitmap, region: FloatArray, w: Int, h: Int): Bitmap {
        val left   = (region[0] * w).toInt().coerceAtLeast(0)
        val top    = (region[1] * h).toInt().coerceAtLeast(0)
        val right  = (region[2] * w).toInt().coerceAtMost(w)
        val bottom = (region[3] * h).toInt().coerceAtMost(h)
        return Bitmap.createBitmap(bitmap, left, top,
            (right - left).coerceAtLeast(1),
            (bottom - top).coerceAtLeast(1))
    }

    private suspend fun runOcr(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it.text.trim()) }
                .addOnFailureListener { cont.resume("") }
        }

    private fun extractFirstNumber(text: String) =
        NUMBER_REGEX.find(text)?.value?.toIntOrNull() ?: 0

    private fun extractRoomId(text: String): String? {
        if (text.isBlank()) return null
        return ROOM_ID_REGEX.find(text.uppercase())?.value?.takeIf { it.length >= 6 }
    }

    private fun detectDeath(text: String): Boolean {
        val lower = text.lowercase()
        return DEATH_KEYWORDS.any { lower.contains(it) }
    }

    fun close() = recognizer.close()
}
