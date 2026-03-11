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

/**
 * ScreenCaptureService is a Foreground Service that:
 * 1. Sets up a MediaProjection virtual display
 * 2. Captures a screenshot every CAPTURE_INTERVAL_MS (2 seconds)
 * 3. Passes each frame to OcrProcessor
 * 4. Feeds OCR results into GameStateMachine and AntiCheatManager
 * 5. Writes live scores to Firebase via FirebaseManager
 * 6. Updates the OverlayHudService with latest stats
 */
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
        const val ACTION_STOP = "com.livewin.freefiretracker.STOP_CAPTURE"

        fun buildStartIntent(
            context: Context,
            resultCode: Int,
            projectionData: Intent,
            playerId: String
        ): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_PROJECTION_DATA, projectionData)
                putExtra(EXTRA_PLAYER_ID, playerId)
            }
        }
    }

    // --- Coroutine scope for all async work ---
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null

    // --- MediaProjection components ---
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // --- Screen metrics ---
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // --- Core business logic ---
    private lateinit var ocrProcessor: OcrProcessor
    private lateinit var gameStateMachine: GameStateMachine
    private lateinit var antiCheatManager: AntiCheatManager
    private lateinit var firebaseManager: FirebaseManager

    // --- Accumulated stats ---
    private var currentStats = GameStats()
    private var playerId: String = "unknown_player"

    // --- Main thread handler (WindowManager updates must be on main thread) ---
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ScreenCaptureService created")

        ocrProcessor = OcrProcessor()
        gameStateMachine = GameStateMachine()
        antiCheatManager = AntiCheatManager()

        createNotificationChannel()
        startForeground(NOTIF_ID_CAPTURE, buildNotification("Initializing..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop command received")
            tearDown()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val projectionData = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        playerId = intent?.getStringExtra(EXTRA_PLAYER_ID) ?: "unknown_player"

        if (resultCode == -1 || projectionData == null) {
            Log.e(TAG, "Invalid MediaProjection data received. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        firebaseManager = FirebaseManager(playerId)
        antiCheatManager.startNewSession()

        setupMediaProjection(resultCode, projectionData)
        startCaptureLoop()

        // Start the overlay HUD
        val hudIntent = Intent(this, OverlayHudService::class.java)
        startForegroundService(hudIntent)

        updateNotification("Tracking active — watching for game...")
        return START_STICKY
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaProjection Setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        Log.i(TAG, "Screen: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null — cannot capture screen")
            stopSelf()
            return
        }

        // Register callback to handle projection stop from the system
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped externally")
                tearDown()
                stopSelf()
            }
        }, mainHandler)

        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )

        Log.i(TAG, "VirtualDisplay created: ${screenWidth}x${screenHeight}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Capture Loop
    // ─────────────────────────────────────────────────────────────────────────

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            Log.i(TAG, "Capture loop started — interval: ${CAPTURE_INTERVAL_MS}ms")
            while (true) {
                try {
                    captureAndProcess()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during capture cycle", e)
                }
                delay(CAPTURE_INTERVAL_MS)
            }
        }
    }

    private suspend fun captureAndProcess() {
        val bitmap = acquireLatestFrame() ?: return

        try {
            // 1. Run OCR on the captured frame
            val ocr = ocrProcessor.process(bitmap)

            // 2. Anti-cheat: validate alive count monotonicity
            val aliveValid = antiCheatManager.validateAliveCount(ocr.playersAlive)
            val cheatFlagged = antiCheatManager.cheatFlagged

            // 3. Update game state machine
            gameStateMachine.onOcrResult(
                detectedRoomId = ocr.roomId,
                kills = ocr.kills,
                playersAlive = if (aliveValid) ocr.playersAlive else currentStats.playersAlive,
                playerDead = ocr.playerDeathDetected
            )

            val gameState = gameStateMachine.getCurrent()

            // 4. Build updated stats
            currentStats = GameStats(
                kills = ocr.kills,
                rank = estimateRank(ocr.playersAlive),
                playersAlive = if (aliveValid) ocr.playersAlive else currentStats.playersAlive,
                playerDead = ocr.playerDeathDetected || gameState == GameState.PLAYER_DEAD,
                cheatFlagged = cheatFlagged,
                lastUpdated = System.currentTimeMillis()
            )

            // 5. Write to Firebase (only during active game states)
            if (shouldUploadStats(gameState)) {
                firebaseManager.updateLiveScore(
                    detectedRoomId = ocr.roomId ?: gameStateMachine.getLastRoomId(),
                    stats = currentStats,
                    cheatFlagged = cheatFlagged
                )
            }

            // 6. Update the overlay HUD
            val hudIntent = OverlayHudService.buildUpdateIntent(
                context = this@ScreenCaptureService,
                stats = currentStats,
                state = gameState,
                cheatFlagged = cheatFlagged
            )
            startService(hudIntent)

            // 7. Broadcast state update to MainActivity
            sendStateBroadcast(gameState, currentStats, cheatFlagged)

            Log.v(TAG, "Cycle done: state=$gameState kills=${ocr.kills} alive=${ocr.playersAlive} roomId=${ocr.roomId}")

        } finally {
            bitmap.recycle()
        }
    }

    private fun acquireLatestFrame(): Bitmap? {
        val reader = imageReader ?: return null

        val image: Image? = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire image", e)
            return null
        } ?: return null

        return try {
            imageToBitmap(image)
        } finally {
            image.close()
        }
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
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to exact screen size (removes row padding)
            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight).also {
                    bitmap.recycle()
                }
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting Image to Bitmap", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun shouldUploadStats(state: GameState): Boolean {
        return state in listOf(
            GameState.IN_MATCH,
            GameState.PLAYER_DEAD,
            GameState.MATCH_ENDED
        )
    }

    /**
     * Estimate rank from alive count (simple heuristic: rank ≈ playersAlive).
     * In Free Fire, when you die, your rank is approximately how many players were alive.
     */
    private fun estimateRank(playersAlive: Int): Int {
        return if (playersAlive > 0) playersAlive else currentStats.rank
    }

    private fun sendStateBroadcast(state: GameState, stats: GameStats, cheatFlagged: Boolean) {
        val broadcast = Intent(MainActivity.ACTION_STATS_UPDATE).apply {
            putExtra(MainActivity.EXTRA_GAME_STATE, state.name)
            putExtra(MainActivity.EXTRA_KILLS, stats.kills)
            putExtra(MainActivity.EXTRA_ALIVE, stats.playersAlive)
            putExtra(MainActivity.EXTRA_DEAD, stats.playerDead)
            putExtra(MainActivity.EXTRA_CHEAT, cheatFlagged)
            putExtra(MainActivity.EXTRA_CONTEST_ID, firebaseManager.getActiveContestId() ?: "")
        }
        sendBroadcast(broadcast)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_CAPTURE,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "FreeFire screen capture service"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_CAPTURE)
            .setContentTitle("FreeFire Stats Tracker")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_delete,
                "Stop",
                android.app.PendingIntent.getService(
                    this,
                    0,
                    Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP },
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_CAPTURE, buildNotification(text))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    private fun tearDown() {
        captureJob?.cancel()
        captureJob = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        ocrProcessor.close()

        // Stop HUD
        stopService(Intent(this, OverlayHudService::class.java))

        Log.i(TAG, "ScreenCaptureService torn down")
    }

    override fun onDestroy() {
        super.onDestroy()
        tearDown()
        serviceScope.cancel()
    }
}
