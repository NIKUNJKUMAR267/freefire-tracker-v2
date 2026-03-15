package com.livewin.freefiretracker

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.livewin.freefiretracker.models.GameState

/**
 * MainActivity is the launcher activity for FreeFire Live Stats Tracker.
 *
 * Responsibilities:
 * 1. Request required permissions:
 *    - SYSTEM_ALERT_WINDOW (overlay permission)
 *    - POST_NOTIFICATIONS (Android 13+)
 *    - MediaProjection (screen capture)
 * 2. Show permission status and guide user through setup
 * 3. Start / Stop ScreenCaptureService
 * 4. Display live stats received from service via BroadcastReceiver
 * 5. Show current game state machine status
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        // Broadcast actions sent from ScreenCaptureService
        const val ACTION_STATS_UPDATE = "com.livewin.freefiretracker.STATS_UPDATE"
        const val EXTRA_GAME_STATE = "extra_game_state"
        const val EXTRA_KILLS = "extra_kills"
        const val EXTRA_ALIVE = "extra_alive"
        const val EXTRA_DEAD = "extra_dead"
        const val EXTRA_CHEAT = "extra_cheat"
        const val EXTRA_CONTEST_ID = "extra_contest_id"

        // Player ID — in production this would come from auth/login
        private const val DEFAULT_PLAYER_ID = "player_001"
    }

    // ─── UI References (built programmatically, no XML dependency) ───────────
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvPermOverlay: TextView
    private lateinit var tvPermNotif: TextView
    private lateinit var tvPermCapture: TextView
    private lateinit var btnOverlayPerm: Button
    private lateinit var btnNotifPerm: Button
    private lateinit var btnStartStop: Button
    private lateinit var tvStatusLabel: TextView
    private lateinit var tvState: TextView
    private lateinit var tvKills: TextView
    private lateinit var tvAlive: TextView
    private lateinit var tvContestId: TextView
    private lateinit var tvCheat: TextView
    private lateinit var statsCard: LinearLayout

    // ─── Service state ────────────────────────────────────────────────────────
    private var isServiceRunning = false
    private var pendingProjectionData: Intent? = null

    // Player info input
    private lateinit var etPlayerName: android.widget.EditText

    // ─── MediaProjection launcher ─────────────────────────────────────────────
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            pendingProjectionData = result.data
            startCaptureService(result.resultCode, result.data!!)
        } else {
            showToast("Screen capture permission denied.")
        }
    }

    // ─── Notification permission launcher (Android 13+) ──────────────────────
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showToast("Notification permission granted.")
        } else {
            showToast("Notification permission denied — status updates won't show.")
        }
        refreshPermissionStatus()
    }

    // ─── BroadcastReceiver for live stats from ScreenCaptureService ───────────
    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_STATS_UPDATE) return

            val stateName = intent.getStringExtra(EXTRA_GAME_STATE) ?: "IDLE"
            val kills = intent.getIntExtra(EXTRA_KILLS, 0)
            val alive = intent.getIntExtra(EXTRA_ALIVE, 0)
            val dead = intent.getBooleanExtra(EXTRA_DEAD, false)
            val cheat = intent.getBooleanExtra(EXTRA_CHEAT, false)
            val contestId = intent.getStringExtra(EXTRA_CONTEST_ID) ?: ""
            val collection = intent.getStringExtra("extra_collection") ?: ""

            updateStatsDisplay(stateName, kills, alive, dead, cheat, contestId, collection)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setupListeners()
        refreshPermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        // Register receiver for live stats
        val filter = IntentFilter(ACTION_STATS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statsReceiver, filter)
        }
        refreshPermissionStatus()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statsReceiver) } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI Builder (programmatic — no XML layout needed)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1117"))
            setPadding(32, 80, 32, 32)
        }

        // ── Header ──────────────────────────────────────────────────────────
        tvTitle = TextView(this).apply {
            text = "FreeFire Stats Tracker"
            textSize = 22f
            setTextColor(Color.parseColor("#FF6B35"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        tvSubtitle = TextView(this).apply {
            text = "Real-time OCR • Firebase • Anti-Cheat"
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 4, 0, 32)
        }

        // ── Permissions section ──────────────────────────────────────────────
        val permTitle = sectionLabel("PERMISSIONS")

        tvPermOverlay = permRow("Overlay (Draw over apps)")
        btnOverlayPerm = permButton("Grant")

        tvPermNotif = permRow("Notifications")
        btnNotifPerm = permButton("Grant")

        tvPermCapture = permRow("Screen Capture")
        // Screen capture is requested on-demand, no separate button

        // ── Player Name Input ────────────────────────────────────────────────
        val playerSection = sectionLabel("YOUR GAME NAME (optional)")
        etPlayerName = android.widget.EditText(this).apply {
            hint = "Enter your Free Fire username"
            setHintTextColor(Color.parseColor("#555555"))
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 14f
            setBackgroundColor(Color.parseColor("#161B22"))
            setPadding(20, 16, 20, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 0) }
        }

        // ── Start / Stop ────────────────────────────────────────────────────
        btnStartStop = Button(this).apply {
            text = "START TRACKING"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#FF6B35"))
            setPadding(0, 32, 0, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 0) }
        }

        // ── Live Stats Card ──────────────────────────────────────────────────
        tvStatusLabel = sectionLabel("LIVE STATS")

        statsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#161B22"))
                cornerRadius = 16f
                setStroke(1, Color.parseColor("#30363D"))
            }
            setPadding(24, 20, 24, 20)
        }

        tvState = statRow("State", "IDLE")
        tvKills = statRow("Kills", "—")
        tvAlive = statRow("Players Alive", "—")
        tvContestId = statRow("Contest", "—")
        tvCheat = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#FF4444"))
            visibility = View.GONE
        }

        statsCard.addView(tvState)
        statsCard.addView(tvKills)
        statsCard.addView(tvAlive)
        statsCard.addView(tvContestId)
        statsCard.addView(tvCheat)

        // ── Compose the layout ───────────────────────────────────────────────
        root.addView(tvTitle)
        root.addView(tvSubtitle)
        root.addView(permTitle)
        root.addView(permRowContainer(tvPermOverlay, btnOverlayPerm))
        root.addView(permRowContainer(tvPermNotif, btnNotifPerm))
        root.addView(tvPermCapture)
        root.addView(spacer(12))
        root.addView(playerSection)
        root.addView(etPlayerName)
        root.addView(btnStartStop)
        root.addView(spacer(24))
        root.addView(tvStatusLabel)
        root.addView(statsCard)

        setContentView(root)
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.parseColor("#FF6B35"))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        letterSpacing = 0.15f
        setPadding(0, 0, 0, 12)
    }

    private fun permRow(label: String): TextView = TextView(this).apply {
        text = "○  $label"
        textSize = 13f
        setTextColor(Color.parseColor("#CCCCCC"))
    }

    private fun permButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#21262D"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun permRowContainer(label: TextView, btn: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 8) }
        addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(btn)
    }

    private fun statRow(label: String, initial: String): TextView = TextView(this).apply {
        text = "$label:  $initial"
        textSize = 13f
        setTextColor(Color.parseColor("#C9D1D9"))
        setPadding(0, 6, 0, 6)
    }

    private fun spacer(dpHeight: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (dpHeight * resources.displayMetrics.density).toInt()
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Listeners
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupListeners() {
        btnOverlayPerm.setOnClickListener { requestOverlayPermission() }
        btnNotifPerm.setOnClickListener { requestNotificationPermission() }

        btnStartStop.setOnClickListener {
            if (isServiceRunning) {
                stopCaptureService()
            } else {
                initiateStartTracking()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────────────────

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshPermissionStatus() {
        val overlayOk = hasOverlayPermission()
        val notifOk = hasNotificationPermission()

        updatePermRow(tvPermOverlay, btnOverlayPerm, "Overlay (Draw over apps)", overlayOk)
        updatePermRow(tvPermNotif, btnNotifPerm, "Notifications", notifOk)

        val captureNote = if (overlayOk) "Screen Capture  (requested on start)" else "Screen Capture  (needs overlay first)"
        tvPermCapture.text = captureNote
        tvPermCapture.setTextColor(
            if (overlayOk) Color.parseColor("#CCCCCC") else Color.parseColor("#888888")
        )

        val allReady = overlayOk
        btnStartStop.isEnabled = allReady
        btnStartStop.setBackgroundColor(
            if (allReady) Color.parseColor("#FF6B35") else Color.parseColor("#333333")
        )
    }

    private fun updatePermRow(label: TextView, btn: Button, text: String, granted: Boolean) {
        if (granted) {
            label.text = "✓  $text"
            label.setTextColor(Color.parseColor("#00D2A0"))
            btn.text = "Granted"
            btn.isEnabled = false
            btn.setTextColor(Color.parseColor("#00D2A0"))
            btn.setBackgroundColor(Color.parseColor("#0D2620"))
        } else {
            label.text = "○  $text"
            label.setTextColor(Color.parseColor("#CCCCCC"))
            btn.text = "Grant"
            btn.isEnabled = true
            btn.setTextColor(Color.WHITE)
            btn.setBackgroundColor(Color.parseColor("#21262D"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service Control
    // ─────────────────────────────────────────────────────────────────────────

    private fun initiateStartTracking() {
        if (!hasOverlayPermission()) {
            showDialog(
                "Overlay Permission Required",
                "The overlay permission is required to show the HUD over Free Fire. Please grant it in the next screen.",
                onConfirm = { requestOverlayPermission() }
            )
            return
        }

        // Request MediaProjection permission
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val enteredName = etPlayerName.text.toString().trim()
        val serviceIntent = ScreenCaptureService.buildStartIntent(
            context = this,
            resultCode = resultCode,
            projectionData = data,
            playerId = DEFAULT_PLAYER_ID,
            playerName = enteredName
        )
        startForegroundService(serviceIntent)
        isServiceRunning = true
        updateServiceRunningUi(true)
        showToast("Tracking started. Minimize app and open Free Fire.")
        Log.i(TAG, "ScreenCaptureService started")
    }

    private fun stopCaptureService() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(stopIntent)
        isServiceRunning = false
        updateServiceRunningUi(false)
        showToast("Tracking stopped.")
    }

    private fun updateServiceRunningUi(running: Boolean) {
        if (running) {
            btnStartStop.text = "STOP TRACKING"
            btnStartStop.setBackgroundColor(Color.parseColor("#DA3633"))
        } else {
            btnStartStop.text = "START TRACKING"
            btnStartStop.setBackgroundColor(Color.parseColor("#FF6B35"))
            resetStatsDisplay()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats Display
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateStatsDisplay(
        stateName: String,
        kills: Int,
        alive: Int,
        dead: Boolean,
        cheat: Boolean,
        contestId: String,
        collection: String = ""
    ) {
        val stateDisplay = when (stateName) {
            "IDLE" -> "Idle — waiting for game"
            "ROOM_JOIN" -> "Room joined — searching contest..."
            "PRE_MATCH" -> "Pre-match lobby"
            "IN_MATCH" -> "In Match — tracking live"
            "PLAYER_DEAD" -> "Player eliminated"
            "MATCH_ENDED" -> "Match ended — declaring winners..."
            else -> stateName
        }

        tvState.text = "State:  $stateDisplay"
        tvState.setTextColor(stateColor(stateName))

        tvKills.text = "Kills:  $kills"
        tvKills.setTextColor(if (kills > 0) Color.parseColor("#FF4444") else Color.parseColor("#C9D1D9"))

        tvAlive.text = "Players Alive:  ${if (alive > 0) alive.toString() else "—"}"
        tvAlive.setTextColor(if (alive > 0) Color.parseColor("#00D2A0") else Color.parseColor("#C9D1D9"))

        val contestLabel = when {
            contestId.isBlank() -> "Searching..."
            collection == "userRooms" -> "Custom Room: ...${contestId.takeLast(6)}"
            else -> "Contest: ...${contestId.takeLast(6)}"
        }
        tvContestId.text = contestLabel

        if (cheat) {
            tvCheat.text = "⚠  Anti-cheat flag raised"
            tvCheat.visibility = View.VISIBLE
        } else {
            tvCheat.visibility = View.GONE
        }
    }

    private fun resetStatsDisplay() {
        tvState.text = "State:  IDLE"
        tvState.setTextColor(Color.parseColor("#C9D1D9"))
        tvKills.text = "Kills:  —"
        tvKills.setTextColor(Color.parseColor("#C9D1D9"))
        tvAlive.text = "Players Alive:  —"
        tvAlive.setTextColor(Color.parseColor("#C9D1D9"))
        tvContestId.text = "Contest:  —"
        tvCheat.visibility = View.GONE
    }

    private fun stateColor(stateName: String): Int = when (stateName) {
        "IDLE" -> Color.parseColor("#666666")
        "ROOM_JOIN" -> Color.parseColor("#58A6FF")
        "PRE_MATCH" -> Color.parseColor("#FFD700")
        "IN_MATCH" -> Color.parseColor("#00D2A0")
        "PLAYER_DEAD" -> Color.parseColor("#FF4444")
        "MATCH_ENDED" -> Color.parseColor("#FF6B35")
        else -> Color.parseColor("#CCCCCC")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showDialog(
        title: String,
        message: String,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
