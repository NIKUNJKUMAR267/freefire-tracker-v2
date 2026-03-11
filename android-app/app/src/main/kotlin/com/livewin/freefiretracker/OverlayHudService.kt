package com.livewin.freefiretracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.livewin.freefiretracker.models.GameState
import com.livewin.freefiretracker.models.GameStats

/**
 * OverlayHudService draws a floating HUD widget over the game using WindowManager.
 * It shows kills, players alive, current game state, and cheat flag status.
 *
 * The overlay is touch-draggable and collapsible.
 */
class OverlayHudService : Service() {

    companion object {
        private const val TAG = "OverlayHudService"
        private const val NOTIF_CHANNEL_HUD = "hud_channel"
        private const val NOTIF_ID_HUD = 2002

        const val ACTION_UPDATE_STATS = "com.livewin.freefiretracker.UPDATE_STATS"
        const val EXTRA_KILLS = "extra_kills"
        const val EXTRA_ALIVE = "extra_alive"
        const val EXTRA_RANK = "extra_rank"
        const val EXTRA_DEAD = "extra_dead"
        const val EXTRA_CHEAT = "extra_cheat"
        const val EXTRA_STATE = "extra_state"

        fun buildUpdateIntent(
            context: Context,
            stats: GameStats,
            state: GameState,
            cheatFlagged: Boolean
        ): Intent {
            return Intent(context, OverlayHudService::class.java).apply {
                action = ACTION_UPDATE_STATS
                putExtra(EXTRA_KILLS, stats.kills)
                putExtra(EXTRA_ALIVE, stats.playersAlive)
                putExtra(EXTRA_RANK, stats.rank)
                putExtra(EXTRA_DEAD, stats.playerDead)
                putExtra(EXTRA_CHEAT, cheatFlagged)
                putExtra(EXTRA_STATE, state.name)
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var hudView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // HUD TextViews
    private var tvKills: TextView? = null
    private var tvAlive: TextView? = null
    private var tvRank: TextView? = null
    private var tvState: TextView? = null
    private var tvCheat: TextView? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID_HUD, buildNotification())
        createHudView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_STATS) {
            val kills = intent.getIntExtra(EXTRA_KILLS, 0)
            val alive = intent.getIntExtra(EXTRA_ALIVE, 0)
            val rank = intent.getIntExtra(EXTRA_RANK, 0)
            val dead = intent.getBooleanExtra(EXTRA_DEAD, false)
            val cheat = intent.getBooleanExtra(EXTRA_CHEAT, false)
            val stateName = intent.getStringExtra(EXTRA_STATE) ?: "IDLE"
            updateHud(kills, alive, rank, dead, cheat, stateName)
        }
        return START_STICKY
    }

    private fun createHudView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 100
        }

        // Build the HUD view programmatically (no XML needed)
        hudView = buildHudView()

        // Make the HUD draggable
        hudView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(hudView, layoutParams)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(hudView, layoutParams)
            Log.i(TAG, "HUD overlay added to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add HUD overlay", e)
        }
    }

    private fun buildHudView(): View {
        // Create a simple but visually styled overlay layout programmatically
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#CC0D1117"))
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#FF6B35"))
            }
            setPadding(20, 14, 20, 14)
        }

        // Title row
        val tvTitle = TextView(this).apply {
            text = "FF TRACKER"
            textSize = 9f
            setTextColor(Color.parseColor("#FF6B35"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.15f
        }

        tvKills = TextView(this).apply {
            text = "KILLS  0"
            textSize = 13f
            setTextColor(Color.parseColor("#FF4444"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        tvAlive = TextView(this).apply {
            text = "ALIVE  --"
            textSize = 13f
            setTextColor(Color.parseColor("#00D2A0"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        tvRank = TextView(this).apply {
            text = "RANK  --"
            textSize = 11f
            setTextColor(Color.parseColor("#FFD700"))
        }

        tvState = TextView(this).apply {
            text = "IDLE"
            textSize = 9f
            setTextColor(Color.parseColor("#AAAAAA"))
            letterSpacing = 0.1f
        }

        tvCheat = TextView(this).apply {
            text = ""
            textSize = 9f
            setTextColor(Color.parseColor("#FF0000"))
            visibility = View.GONE
        }

        container.addView(tvTitle)
        container.addView(createDivider())
        container.addView(tvKills)
        container.addView(tvAlive)
        container.addView(tvRank)
        container.addView(createDivider())
        container.addView(tvState)
        container.addView(tvCheat)

        return container
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply { setMargins(0, 6, 0, 6) }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }
    }

    private fun updateHud(
        kills: Int,
        alive: Int,
        rank: Int,
        dead: Boolean,
        cheat: Boolean,
        stateName: String
    ) {
        hudView?.post {
            tvKills?.text = "KILLS  $kills"
            tvAlive?.text = if (alive > 0) "ALIVE  $alive" else "ALIVE  --"
            tvRank?.text = if (rank > 0) "RANK   #$rank" else "RANK   --"

            val stateDisplay = when (stateName) {
                "IDLE" -> "• IDLE"
                "ROOM_JOIN" -> "• ROOM JOINED"
                "PRE_MATCH" -> "• LOBBY"
                "IN_MATCH" -> "• IN MATCH"
                "PLAYER_DEAD" -> "• YOU DIED"
                "MATCH_ENDED" -> "• MATCH ENDED"
                else -> "• $stateName"
            }
            tvState?.text = stateDisplay

            if (dead) {
                tvKills?.setTextColor(Color.parseColor("#888888"))
                tvState?.setTextColor(Color.parseColor("#FF4444"))
            } else {
                tvKills?.setTextColor(Color.parseColor("#FF4444"))
                tvState?.setTextColor(Color.parseColor("#AAAAAA"))
            }

            if (cheat) {
                tvCheat?.text = "⚠ FLAGGED"
                tvCheat?.visibility = View.VISIBLE
            } else {
                tvCheat?.visibility = View.GONE
            }

            windowManager?.updateViewLayout(hudView, layoutParams)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_HUD,
            "HUD Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "FreeFire Stats HUD overlay"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_HUD)
            .setContentTitle("FF Stats HUD Active")
            .setContentText("Overlay showing live game stats")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            hudView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing HUD view", e)
        }
        hudView = null
    }
}
