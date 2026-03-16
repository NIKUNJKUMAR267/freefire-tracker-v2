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

// ─────────────────────────────────────────────────────────────────────────────
// Game Mode
// ─────────────────────────────────────────────────────────────────────────────

enum class GameMode {
    UNKNOWN,
    BATTLE_ROYALE,   // Top-right: Teal (alive) + Red (kills)
    CLASH_SQUAD      // Top-center: Blue score | Timer | Orange score
}

// ─────────────────────────────────────────────────────────────────────────────
// OCR Result
// ─────────────────────────────────────────────────────────────────────────────

data class OcrResult(
    val gameMode: GameMode,

    // ── Battle Royale fields ──
    val kills: Int,
    val playersAlive: Int,
    val playerDeathDetected: Boolean,
    val activePlayers: List<String>,
    val deadPlayers: List<String>,

    // ── Clash Squad fields ──
    val myTeamScore: Int,        // Blue box (left)
    val enemyTeamScore: Int,     // Orange box (right)
    val totalRounds: Int,        // "Objective: N" se
    val roundTimeLeft: String,   // Timer (center red text)

    // ── Common ──
    val roomId: String?,

    // ── Raw text (debug) ──
    val rawKillsText: String,
    val rawAliveText: String,
    val rawRoomIdText: String,
    val rawPlayerListText: String,
    val rawMyScoreText: String,
    val rawEnemyScoreText: String,
    val rawTimerText: String,

    // ── Debug regions ──
    val detectedAliveRegion: Rect?,
    val detectedKillsRegion: Rect?
)

// ─────────────────────────────────────────────────────────────────────────────
// OcrProcessor
// ─────────────────────────────────────────────────────────────────────────────

class OcrProcessor {

    companion object {
        private const val TAG = "OcrProcessor"

        // ── Battle Royale Colors ──
        private const val TEAL_R = 0;   private const val TEAL_G = 195; private const val TEAL_B = 180
        private const val RED_R  = 220; private const val RED_G  = 30;  private const val RED_B  = 30
        private const val COLOR_TOLERANCE = 45

        // ── Clash Squad Colors (top-center) ──
        // Blue team box
        private const val CS_BLUE_R = 30;  private const val CS_BLUE_G = 120; private const val CS_BLUE_B = 220
        // Orange team box
        private const val CS_ORANGE_R = 220; private const val CS_ORANGE_G = 100; private const val CS_ORANGE_B = 20
        private const val CS_COLOR_TOLERANCE = 55

        // ── Battle Royale Fallback Regions ──
        private val BR_ALIVE_FALLBACK    = floatArrayOf(0.75f, 0.01f, 0.88f, 0.12f)
        private val BR_KILLS_FALLBACK    = floatArrayOf(0.88f, 0.01f, 0.99f, 0.12f)

        // ── Clash Squad Regions (landscape) ──
        // Blue score:   left of center timer
        private val CS_MY_SCORE_REGION     = floatArrayOf(0.30f, 0.00f, 0.45f, 0.08f)
        // Orange score: right of center timer
        private val CS_ENEMY_SCORE_REGION  = floatArrayOf(0.55f, 0.00f, 0.70f, 0.08f)
        // Timer (red, center top)
        private val CS_TIMER_REGION        = floatArrayOf(0.42f, 0.00f, 0.58f, 0.07f)
        // "Objective: N" — just below timer
        private val CS_OBJECTIVE_REGION    = floatArrayOf(0.38f, 0.05f, 0.62f, 0.12f)

        // ── Common Regions ──
        private val ROOM_ID_REGION       = floatArrayOf(0.00f, 0.88f, 0.50f, 1.00f)
        private val DEATH_REGION         = floatArrayOf(0.20f, 0.35f, 0.80f, 0.65f)
        private val PLAYER_LIST_REGION   = floatArrayOf(0.00f, 0.12f, 0.22f, 0.48f)

        private val NUMBER_REGEX  = Regex("\\d+")
        private val ROOM_ID_REGEX = Regex("[A-Z0-9]{6,12}")
        private val TIMER_REGEX   = Regex("\\d{1,2}:\\d{2}")

        private val DEATH_KEYWORDS = listOf(
            "knocked", "eliminated", "defeated", "you died", "killed by",
            "knocked out", "finish", "revive", "spectate", "booyah",
            "winner", "knocked down", "safe"
        )
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // ── BR Cache ──
    private var cachedAliveRect: Rect? = null
    private var cachedKillsRect: Rect? = null
    private var cacheValidFrames = 0

    // ── Mode Cache ──
    private var detectedMode: GameMode = GameMode.UNKNOWN
    private var modeDetectFrames = 0   // Re-detect mode every 60 frames

    // ─────────────────────────────────────────────────────────────────────────
    // Main Process
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun process(bitmap: Bitmap): OcrResult {
        val w = bitmap.width
        val h = bitmap.height
        Log.d(TAG, "Processing: ${w}x${h}")

        // Step 1: Mode detect karo (every 60 frames)
        if (modeDetectFrames <= 0) {
            detectedMode = detectGameMode(bitmap, w, h)
            modeDetectFrames = 60
            Log.i(TAG, "Game mode detected: $detectedMode")
        } else {
            modeDetectFrames--
        }

        // Step 2: Mode ke hisaab se process karo
        return when (detectedMode) {
            GameMode.CLASH_SQUAD   -> processClashSquad(bitmap, w, h)
            GameMode.BATTLE_ROYALE -> processBattleRoyale(bitmap, w, h)
            GameMode.UNKNOWN       -> processBattleRoyale(bitmap, w, h) // fallback
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mode Detection
    // Top-right mein TEAL mile → BR
    // Top-center mein BLUE + ORANGE mile → CS
    // ─────────────────────────────────────────────────────────────────────────

    private fun detectGameMode(bitmap: Bitmap, w: Int, h: Int): GameMode {
        // Check top-right for TEAL (BR)
        val topRightRect = Rect((w * 0.70f).toInt(), 0, w, (h * 0.15f).toInt())
        val tealFound = hasColor(bitmap, topRightRect,
            TEAL_R, TEAL_G, TEAL_B, COLOR_TOLERANCE, minPixels = 15)

        if (tealFound) return GameMode.BATTLE_ROYALE

        // Check top-center for BLUE + ORANGE (CS)
        val topCenterRect = Rect((w * 0.25f).toInt(), 0, (w * 0.75f).toInt(), (h * 0.10f).toInt())
        val blueFound   = hasColor(bitmap, topCenterRect,
            CS_BLUE_R, CS_BLUE_G, CS_BLUE_B, CS_COLOR_TOLERANCE, minPixels = 10)
        val orangeFound = hasColor(bitmap, topCenterRect,
            CS_ORANGE_R, CS_ORANGE_G, CS_ORANGE_B, CS_COLOR_TOLERANCE, minPixels = 10)

        if (blueFound && orangeFound) return GameMode.CLASH_SQUAD

        return GameMode.UNKNOWN
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Battle Royale Processing
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun processBattleRoyale(bitmap: Bitmap, w: Int, h: Int): OcrResult {

        // Region detect (cache 30 frames)
        if (cacheValidFrames <= 0) {
            cachedAliveRect = findColorRegion(bitmap, TEAL_R, TEAL_G, TEAL_B, COLOR_TOLERANCE, h)
            cachedKillsRect = findColorRegion(bitmap, RED_R,  RED_G,  RED_B,  COLOR_TOLERANCE, h)
            cacheValidFrames = 30
        } else cacheValidFrames--

        val aliveBmp = if (cachedAliveRect != null) cropRect(bitmap, cachedAliveRect!!)
                       else cropRegion(bitmap, BR_ALIVE_FALLBACK, w, h)
        val killsBmp = if (cachedKillsRect != null) cropRect(bitmap, cachedKillsRect!!)
                       else cropRegion(bitmap, BR_KILLS_FALLBACK, w, h)

        val aliveText      = runOcr(aliveBmp)
        val killsText      = runOcr(killsBmp)
        val roomIdText     = runOcr(cropRegion(bitmap, ROOM_ID_REGION, w, h))
        val deathText      = runOcr(cropRegion(bitmap, DEATH_REGION, w, h))
        val playerListText = runOcr(cropRegion(bitmap, PLAYER_LIST_REGION, w, h))

        val alive  = extractFirstNumber(aliveText)
        val kills  = extractFirstNumber(killsText)

        // Cache invalidate agar zeros aaye
        if (alive == 0 && kills == 0) {
            cacheValidFrames = 0
            modeDetectFrames = 0 // Re-detect mode bhi
        }

        val (active, dead) = parsePlayerList(playerListText)

        Log.d(TAG, "[BR] alive=$alive kills=$kills roomId=${extractRoomId(roomIdText)}")

        return OcrResult(
            gameMode            = GameMode.BATTLE_ROYALE,
            kills               = kills,
            playersAlive        = alive,
            playerDeathDetected = detectDeath(deathText),
            activePlayers       = active,
            deadPlayers         = dead,
            myTeamScore         = 0,
            enemyTeamScore      = 0,
            totalRounds         = 0,
            roundTimeLeft       = "",
            roomId              = extractRoomId(roomIdText),
            rawKillsText        = killsText,
            rawAliveText        = aliveText,
            rawRoomIdText       = roomIdText,
            rawPlayerListText   = playerListText,
            rawMyScoreText      = "",
            rawEnemyScoreText   = "",
            rawTimerText        = "",
            detectedAliveRegion = cachedAliveRect,
            detectedKillsRegion = cachedKillsRect
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Clash Squad Processing
    // Round system — player dead nahi hota permanently
    // Blue = aapki team, Orange = enemy team
    // Objective:N = total rounds
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun processClashSquad(bitmap: Bitmap, w: Int, h: Int): OcrResult {

        val myScoreText    = runOcr(cropRegion(bitmap, CS_MY_SCORE_REGION, w, h))
        val enemyScoreText = runOcr(cropRegion(bitmap, CS_ENEMY_SCORE_REGION, w, h))
        val timerText      = runOcr(cropRegion(bitmap, CS_TIMER_REGION, w, h))
        val objectiveText  = runOcr(cropRegion(bitmap, CS_OBJECTIVE_REGION, w, h))
        val roomIdText     = runOcr(cropRegion(bitmap, ROOM_ID_REGION, w, h))

        val myScore    = extractFirstNumber(myScoreText)
        val enemyScore = extractFirstNumber(enemyScoreText)
        val totalRounds = extractObjectiveRounds(objectiveText)
        val timer      = TIMER_REGEX.find(timerText)?.value ?: timerText.trim()

        Log.d(TAG, "[CS] myScore=$myScore enemyScore=$enemyScore " +
                "rounds=$totalRounds timer=$timer")

        // CS mein player death permanent nahi — round khatam = sab respawn
        // Isliye playerDeathDetected = false, activePlayers = empty (BR feature)
        return OcrResult(
            gameMode            = GameMode.CLASH_SQUAD,
            kills               = 0,          // CS mein individual kills track nahi hote
            playersAlive        = 0,           // CS mein alive count nahi hota
            playerDeathDetected = false,       // CS mein permanent death nahi
            activePlayers       = emptyList(),
            deadPlayers         = emptyList(),
            myTeamScore         = myScore,
            enemyTeamScore      = enemyScore,
            totalRounds         = totalRounds,
            roundTimeLeft       = timer,
            roomId              = extractRoomId(roomIdText),
            rawKillsText        = "",
            rawAliveText        = "",
            rawRoomIdText       = roomIdText,
            rawPlayerListText   = "",
            rawMyScoreText      = myScoreText,
            rawEnemyScoreText   = enemyScoreText,
            rawTimerText        = timerText,
            detectedAliveRegion = null,
            detectedKillsRegion = null
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Color Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun hasColor(
        bitmap: Bitmap, rect: Rect,
        targetR: Int, targetG: Int, targetB: Int,
        tolerance: Int, minPixels: Int
    ): Boolean {
        var count = 0
        for (y in rect.top until rect.bottom step 3) {
            for (x in rect.left until rect.right step 3) {
                if (x >= bitmap.width || y >= bitmap.height) continue
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8)  and 0xFF
                val b = pixel and 0xFF
                if (abs(r - targetR) < tolerance &&
                    abs(g - targetG) < tolerance &&
                    abs(b - targetB) < tolerance) {
                    count++
                    if (count >= minPixels) return true
                }
            }
        }
        return false
    }

    private fun findColorRegion(
        bitmap: Bitmap,
        targetR: Int, targetG: Int, targetB: Int,
        tolerance: Int,
        screenHeight: Int
    ): Rect? {
        val w = bitmap.width
        val scanH = (screenHeight * 0.20).toInt()

        var minX = w; var minY = scanH; var maxX = 0; var maxY = 0
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

        if (pixelCount < 20) return null
        if (maxX - minX < 10 || maxY - minY < 5) return null

        return Rect(
            (minX - 5).coerceAtLeast(0),
            (minY - 3).coerceAtLeast(0),
            (maxX + 25).coerceAtMost(w),
            (maxY + 10).coerceAtMost(scanH)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OCR + Parse Helpers
    // ─────────────────────────────────────────────────────────────────────────

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

    // "Objective: 5" → 5
    private fun extractObjectiveRounds(text: String): Int {
        val lower = text.lowercase()
        val idx = lower.indexOf("objective")
        if (idx >= 0) {
            return NUMBER_REGEX.find(text.substring(idx))?.value?.toIntOrNull() ?: 0
        }
        return extractFirstNumber(text)
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
