package com.livewin.freefiretracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.livewin.freefiretracker.models.GameState
import com.livewin.freefiretracker.models.GameStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIF_CHANNEL_CAPTURE = "screen_capture_channel"
        private const val NOTIF_ID_CAPTURE = 2001
        private const val CAPTURE_INTERVAL_MS = 2000L
        private const val VIRTUAL_DISPLAY_NAME = "FFTrackerCapture"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val EXTRA_PLAYER_ID = "extra_player_id"
        const val EXTRA_PLAYER_NAME = "extra_player_name"
        const val ACTION_STOP = "com.livewin.freefiretracker.STOP_CAPTURE"

        fun buildStartIntent(
            context: Context,
            resultCode: Int,
            projectionData: Intent,
            playerId: String,
            playerName: String = ""
        ): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_PROJECTION_DATA, projectionData)
                putExtra(EXTRA_PLAYER_ID, playerId)
                putExtra(EXTRA_PLAYER_NAME, playerName)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private lateinit var ocrProcessor: OcrProcessor
    private lateinit var gameStateMachine: GameStateMachine
    private lateinit var antiCheatManager: AntiCheatManager
    private lateinit var firebaseManager: FirebaseManager

    private var currentStats = GameStats()
    private var playerId: String = "unknown_player"
    private var playerName: String = ""

    private var lastDetectedRoomId: String? = null
    private var lastGameState: GameState = GameState.IDLE
    private var winnerDeclarationAttempted = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ocrProcessor = OcrProcessor()
        gameStateMachine = GameStateMachine()
        antiCheatManager = AntiCheatManager()
        createNotificationChannel()
        startForeground(NOTIF_ID_CAPTURE, buildNotification("Initializing..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            tearDown(); stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val projectionData = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        playerId = intent?.getStringExtra(EXTRA_PLAYER_ID) ?: "unknown_player"
        playerName = intent?.getStringExtra(EXTRA_PLAYER_NAME) ?: ""

        if (resultCode == -1 || projectionData == null) {
            Log.e(TAG, "Invalid MediaProjection data. Stopping.")
            stopSelf(); return START_NOT_STICKY
        }

        firebaseManager = FirebaseManager(playerId)
        antiCheatManager.startNewSession()
        winnerDeclarationAttempted = false
        lastDetectedRoomId = null

        setupMediaProjection(resultCode, projectionData)
        startCaptureLoop()
        startForegroundService(Intent(this, OverlayHudService::class.java))
        updateNotification("Tracking active — watching for game...")
        return START_STICKY
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaProjection Setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { tearDown(); stopSelf() }
        }, mainHandler)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME, screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, mainHandler
        )
        Log.i(TAG, "VirtualDisplay created: ${screenWidth}x${screenHeight}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Capture Loop
    // ─────────────────────────────────────────────────────────────────────────

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            Log.i(TAG, "Capture loop started")
            while (true) {
                try { captureAndProcess() }
                catch (e: Exception) { Log.e(TAG, "Capture error", e) }
                delay(CAPTURE_INTERVAL_MS)
            }
        }
    }

    private suspend fun captureAndProcess() {
        val bitmap = acquireLatestFrame() ?: return

        try {
            // 1. OCR
            val ocr = ocrProcessor.process(bitmap)

            // 2. RoomId cache update
            if (!ocr.roomId.isNullOrBlank()) {
                lastDetectedRoomId = ocr.roomId
            }

            // 3. Anti-cheat (BR only — CS mein alive count nahi hota)
            val aliveValid = if (ocr.gameMode == GameMode.BATTLE_ROYALE)
                antiCheatManager.validateAliveCount(ocr.playersAlive)
            else true
            val cheatFlagged = antiCheatManager.cheatFlagged

            // 4. State machine — mode + CS fields bhi pass karo
            gameStateMachine.onOcrResult(
                detectedRoomId = ocr.roomId,
                kills          = ocr.kills,
                playersAlive   = if (aliveValid) ocr.playersAlive else currentStats.playersAlive,
                playerDead     = ocr.playerDeathDetected,
                gameMode       = ocr.gameMode,        // ← NEW
                myTeamScore    = ocr.myTeamScore,     // ← NEW
                enemyTeamScore = ocr.enemyTeamScore,  // ← NEW
                totalRounds    = ocr.totalRounds      // ← NEW
            )
            val gameState = gameStateMachine.getCurrent()

            // 5. Build stats — CS fields bhi store karo
            currentStats = GameStats(
                kills          = ocr.kills,
                rank           = estimateRank(ocr.playersAlive),
                playersAlive   = if (aliveValid) ocr.playersAlive else currentStats.playersAlive,
                playerDead     = ocr.playerDeathDetected || gameState == GameState.PLAYER_DEAD,
                cheatFlagged   = cheatFlagged,
                lastUpdated    = System.currentTimeMillis(),
                myTeamScore    = ocr.myTeamScore,     // ← NEW
                enemyTeamScore = ocr.enemyTeamScore,  // ← NEW
                totalRounds    = ocr.totalRounds,     // ← NEW
                roundTimeLeft  = ocr.roundTimeLeft    // ← NEW
            )

            // 6. State transitions handle karo
            handleStateTransition(gameState, ocr.roomId, cheatFlagged)

            // 7. Firebase upload
            if (shouldUploadStats(gameState)) {
                firebaseManager.updateLiveScore(
                    detectedRoomId = lastDetectedRoomId ?: firebaseManager.getLastRoomId(),
                    stats          = currentStats,
                    cheatFlagged   = cheatFlagged,
                    playerName     = playerName,
                    activePlayers  = ocr.activePlayers,
                    deadPlayers    = ocr.deadPlayers
                )
            }

            // 8. HUD update
            val hudIntent = OverlayHudService.buildUpdateIntent(
                context      = this@ScreenCaptureService,
                stats        = currentStats,
                state        = gameState,
                cheatFlagged = cheatFlagged
            )
            startService(hudIntent)

            // 9. Broadcast to MainActivity
            sendStateBroadcast(gameState, currentStats, cheatFlagged)

            lastGameState = gameState

        } finally {
            bitmap.recycle()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State Transition Handler
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun handleStateTransition(
        gameState: GameState,
        detectedRoomId: String?,
        cheatFlagged: Boolean
    ) {
        if (gameState == GameState.MATCH_ENDED && lastGameState != GameState.MATCH_ENDED) {
            if (!winnerDeclarationAttempted) {
                winnerDeclarationAttempted = true
                triggerWinnerDeclaration()
            }
        }

        if (gameState == GameState.IDLE && lastGameState == GameState.MATCH_ENDED) {
            winnerDeclarationAttempted = false
            lastDetectedRoomId = null
            firebaseManager.clearActiveContest()
            antiCheatManager.startNewSession()
        }

        if (gameState == GameState.ROOM_JOIN && !detectedRoomId.isNullOrBlank()) {
            if (firebaseManager.getActiveContest() == null) {
                val contest = firebaseManager.findRunningContest(detectedRoomId)
                if (contest != null) {
                    updateNotification("Contest found: ${contest.name}")
                    Log.i(TAG, "Auto-found contest: ${contest.contestId}")
                } else {
                    Log.d(TAG, "No running contest for roomId=$detectedRoomId")
                }
            }
        }
    }

    private suspend fun triggerWinnerDeclaration() {
        val contest = firebaseManager.getActiveContest()
        if (contest == null) {
            Log.w(TAG, "MATCH_ENDED but no active contest found")
            updateNotification("Match ended — no linked contest found")
            return
        }

        Log.i(TAG, "Declaring winners for: ${contest.contestId}")
        updateNotification("Match ended! Declaring winners...")

        val success = firebaseManager.declareWinners(contest)
        if (success) {
            updateNotification("Winners declared! Coins credited.")
            Log.i(TAG, "Winner declaration successful")
        } else {
            updateNotification("Winner declaration failed, check logs")
            Log.w(TAG, "Winner declaration returned false")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Frame Capture
    // ─────────────────────────────────────────────────────────────────────────

    private fun acquireLatestFrame(): Bitmap? {
        val image: Image? = try {
            imageReader?.acquireLatestImage()
        } catch (e: Exception) { null } ?: return null
        return try { imageToBitmap(image!!) } finally { image!!.close() }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                    .also { bitmap.recycle() }
            } else bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap conversion error", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    // BR: IN_MATCH + PLAYER_DEAD pe upload
    // CS: IN_MATCH + ROUND_END pe upload
    private fun shouldUploadStats(state: GameState): Boolean = state in listOf(
        GameState.IN_MATCH, GameState.PLAYER_DEAD, GameState.ROUND_END
    )

    private fun estimateRank(playersAlive: Int): Int =
        if (playersAlive > 0) playersAlive else currentStats.rank

    private fun sendStateBroadcast(state: GameState, stats: GameStats, cheatFlagged: Boolean) {
        sendBroadcast(Intent(MainActivity.ACTION_STATS_UPDATE).apply {
            putExtra(MainActivity.EXTRA_GAME_STATE, state.name)
            putExtra(MainActivity.EXTRA_KILLS, stats.kills)
            putExtra(MainActivity.EXTRA_ALIVE, stats.playersAlive)
            putExtra(MainActivity.EXTRA_DEAD, stats.playerDead)
            putExtra(MainActivity.EXTRA_CHEAT, cheatFlagged)
            putExtra(MainActivity.EXTRA_CONTEST_ID, firebaseManager.getActiveContestId() ?: "")
            putExtra("extra_collection", firebaseManager.getActiveContest()?.collectionName ?: "")
            // CS extras
            putExtra("extra_my_score",     stats.myTeamScore)
            putExtra("extra_enemy_score",  stats.enemyTeamScore)
            putExtra("extra_total_rounds", stats.totalRounds)
            putExtra("extra_round_time",   stats.roundTimeLeft)
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_CAPTURE, "Screen Capture", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_CAPTURE)
            .setContentTitle("FreeFire Stats Tracker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_delete, "Stop",
                android.app.PendingIntent.getService(
                    this, 0,
                    Intent(this, ScreenCaptureService::class.java).apply {
                        action = ACTION_STOP
                    },
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID_CAPTURE, buildNotification(text))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    private fun tearDown() {
        captureJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        ocrProcessor.close()
        stopService(Intent(this, OverlayHudService::class.java))
        Log.i(TAG, "Service torn down")
    }

    override fun onDestroy() {
        super.onDestroy()
        tearDown()
        serviceScope.cancel()
    }
}
