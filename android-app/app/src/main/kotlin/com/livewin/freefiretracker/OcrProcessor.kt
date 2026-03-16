package com.livewin.freefiretracker

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

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
    val rawPlayerListText: String,
    // Debug ke liye — detected regions
    val detectedAliveRegion: Rect?,
    val detectedKillsRegion: Rect?
)

class OcrProcessor {

    companion object {
        private const val TAG = "OcrProcessor"

        // FreeFire UI Colors (landscape mode)
        // Teal box = Players Alive
        private const val TEAL_R = 0;  private const val TEAL_G = 195; private const val TEAL_B = 180
        // Red/skull box = Kills
        private const val RED_R = 220; private const val RED_G = 30;  private const val RED_B = 30
        // Color tolerance
        private const val COLOR_TOLERANCE = 45

        // Fallback regions agar color detect na ho
        private val ALIVE_FALLBACK  = floatArrayOf(0.75f, 0.01f, 0.88f, 0.12f)
        private val KILLS_FALLBACK  = floatArrayOf(0.88f, 0.01f, 0.99f, 0.12f)
        private val ROOM_ID_REGION  = floatArrayOf(0.00f, 0.88f, 0.50f, 1.00f)
        private val DEATH_REGION    = floatArrayOf(0.20f, 0.35f, 0.80f, 0.65f)
        private val PLAYER_LIST_REGION = floatArrayOf(0.00f, 0.12f, 0.22f, 0.48f)

        private val NUMBER_REGEX  = Regex("\\d+")
        private val ROOM_ID_REGEX = Regex("[A-Z0-9]{6,12}")

        private val DEATH_KEYWORDS = listOf(
            "knocked", "eliminated", "defeated", "you died", "killed by",
            "knocked out", "finish", "revive", "spectate", "booyah",
            "winner", "knocked down", "safe"
        )
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Cache detected regions (baar baar scan na karna pade)
    private var cachedAliveRect: Rect? = null
    private var cachedKillsRect: Rect? = null
    private var cacheValidFrames = 0

    suspend fun process(bitmap: Bitmap): OcrResult {
        val w = bitmap.width
        val h = bitmap.height
        Log.d(TAG, "Processing: ${w}x${h}")

        // Step 1: Color se regions dhundho (cache 30 frames ke liye)
        if (cacheValidFrames <= 0) {
            cachedAliveRect = findColorRegion(bitmap, TEAL_R, TEAL_G, TEAL_B, COLOR_TOLERANCE, h)
            cachedKillsRect = findColorRegion(bitmap, RED_R,  RED_G,  RED_B,  COLOR_TOLERANCE, h)
            cacheValidFrames = 30 // 30 frames = 1 minute
            Log.d(TAG, "Regions detected → alive=$cachedAliveRect kills=$cachedKillsRect")
        } else {
            cacheValidFrames--
        }

        // Step 2: Detected region ya fallback se crop karo
        val aliveBmp = if (cachedAliveRect != null)
            cropRect(bitmap, cachedAliveRect!!)
        else
            cropRegion(bitmap, ALIVE_FALLBACK, w, h)

        val killsBmp = if (cachedKillsRect != null)
            cropRect(bitmap, cachedKillsRect!!)
        else
            cropRegion(bitmap, KILLS_FALLBACK, w, h)

        // Step 3: OCR run karo
        val aliveText      = runOcr(aliveBmp)
        val killsText      = runOcr(killsBmp)
        val roomIdText     = runOcr(cropRegion(bitmap, ROOM_ID_REGION, w, h))
        val deathText      = runOcr(cropRegion(bitmap, DEATH_REGION, w, h))
        val playerListText = runOcr(cropRegion(bitmap, PLAYER_LIST_REGION, w, h))

        Log.d(TAG, "OCR → alive='$aliveText' kills='$killsText' players='$playerListText'")

        val alive  = extractFirstNumber(aliveText)
        val kills  = extractFirstNumber(killsText)
        val roomId = extractRoomId(roomIdText)
        val isDead = detectDeath(deathText)
        val (active, dead) = parsePlayerList(playerListText)

        // Agar OCR fail kiya — cache invalidate karo
        if (alive == 0 && kills == 0) {
            cacheValidFrames = 0
            Log.w(TAG, "OCR returned zeros — cache invalidated, will re-detect next frame")
        }

        return OcrResult(
            kills = kills,
            playersAlive = alive,
            roomId = roomId,
            playerDeathDetected = isDead,
            activePlayers = active,
            deadPlayers = dead,
            rawKillsText = killsText,
            rawAliveText = aliveText,
            rawRoomIdText = roomIdText,
            rawPlayerListText = playerListText,
            detectedAliveRegion = cachedAliveRect,
            detectedKillsRegion = cachedKillsRect
        )
    }

    /**
     * Screen mein specific color dhundho
     * Sirf top 20% scan karta hai (UI wahan hoti hai)
     */
    private fun findColorRegion(
        bitmap: Bitmap,
        targetR: Int, targetG: Int, targetB: Int,
        tolerance: Int,
        screenHeight: Int
    ): Rect? {
        val w = bitmap.width
        // Sirf top 20% scan karo
        val scanH = (screenHeight * 0.20).toInt()

        var minX = w;    var minY = scanH
        var maxX = 0;    var maxY = 0
        var pixelCount = 0

        for (y in 0 until scanH step 3) {
            for (x in 0 until w step 3) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8)  and 0xFF
                val b = pixel and 0xFF

                if (abs(r - targetR) < tolerance &&
                    abs(g - targetG) < tolerance &&
                    abs(b - targetB) < tolerance) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    pixelCount++
                }
            }
        }

        // Kam se kam 20 matching pixels chahiye
        if (pixelCount < 20) return null
        if (maxX - minX < 10 || maxY - minY < 5) return null

        return Rect(
            (minX - 5).coerceAtLeast(0),
            (minY - 3).coerceAtLeast(0),
            (maxX + 25).coerceAtMost(w),      // Right side extend — number wahan hoga
            (maxY + 10).coerceAtMost(scanH)
        )
    }

    private fun cropRect(bitmap: Bitmap, rect: Rect): Bitmap {
        val w = (rect.right - rect.left).coerceAtLeast(1)
        val h = (rect.bottom - rect.top).coerceAtLeast(1)
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, w, h)
    }

    private fun cropRegion(bitmap: Bitmap, region: FloatArray, w: Int, h: Int): Bitmap {
        val l = (region[0] * w).toInt().coerceAtLeast(0)
        val t = (region[1] * h).toInt().coerceAtLeast(0)
        val r = (region[2] * w).toInt().coerceAtMost(w)
        val b = (region[3] * h).toInt().coerceAtMost(h)
        return Bitmap.createBitmap(bitmap, l, t,
            (r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1))
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

    private fun parsePlayerList(text: String): Pair<List<String>, List<String>> {
        if (text.isBlank()) return Pair(emptyList(), emptyList())
        val lines  = text.lines().filter { it.isNotBlank() }
        val active = mutableListOf<String>()
        val dead   = mutableListOf<String>()
        val deadIndicators = listOf("eliminated", "dead", "knocked")

        for (line in lines) {
            val name = line.trim().replace(Regex("^\\d+\\s*"), "").trim()
            if (name.isBlank()) continue
            if (deadIndicators.any { line.lowercase().contains(it) })
                dead.add(name)
            else
                active.add(name)
        }
        return Pair(active, dead)
    }

    fun close() = recognizer.close()
}
