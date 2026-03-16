package com.livewin.freefiretracker

import android.util.Log
import com.livewin.freefiretracker.models.GameState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GameStateMachine {

    private val _state = MutableStateFlow(GameState.IDLE)
    val state: StateFlow<GameState> = _state

    private var lastRoomId: String? = null
    private var matchStartTime: Long = 0L

    // ── Clash Squad tracking ──
    private var lastMyScore: Int    = 0
    private var lastEnemyScore: Int = 0
    private var roundEndCooldown: Int = 0

    companion object {
        private const val TAG = "GameStateMachine"
        private const val MATCH_END_ALIVE_THRESHOLD = 1
        // CS: ~45 frames = ~3 sec cooldown between rounds
        private const val CS_ROUND_END_COOLDOWN_FRAMES = 45
    }

    fun getCurrent(): GameState = _state.value

    // ─────────────────────────────────────────────────────────────────────────
    // Main entry point — mode ke hisaab se route karta hai
    // ─────────────────────────────────────────────────────────────────────────

    fun onOcrResult(
        detectedRoomId: String?,
        kills: Int,
        playersAlive: Int,
        playerDead: Boolean,
        // Clash Squad fields (BR mein default 0)
        gameMode: GameMode   = GameMode.BATTLE_ROYALE,
        myTeamScore: Int     = 0,
        enemyTeamScore: Int  = 0,
        totalRounds: Int     = 0
    ) {
        when (gameMode) {
            GameMode.CLASH_SQUAD   -> onClashSquadOcr(detectedRoomId, myTeamScore, enemyTeamScore, totalRounds)
            GameMode.BATTLE_ROYALE -> onBattleRoyaleOcr(detectedRoomId, kills, playersAlive, playerDead)
            GameMode.UNKNOWN       -> onBattleRoyaleOcr(detectedRoomId, kills, playersAlive, playerDead)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Battle Royale — original logic unchanged
    // ─────────────────────────────────────────────────────────────────────────

    private fun onBattleRoyaleOcr(
        detectedRoomId: String?,
        kills: Int,
        playersAlive: Int,
        playerDead: Boolean
    ) {
        val current = _state.value

        when (current) {
            GameState.IDLE -> {
                if (!detectedRoomId.isNullOrBlank()) {
                    lastRoomId = detectedRoomId
                    transitionTo(GameState.ROOM_JOIN)
                }
            }

            GameState.ROOM_JOIN -> {
                if (!detectedRoomId.isNullOrBlank()) lastRoomId = detectedRoomId
                if (playersAlive > 0) transitionTo(GameState.PRE_MATCH)
            }

            GameState.PRE_MATCH -> {
                if (playersAlive in 1..60 && kills >= 0) {
                    if (playersAlive < 50 || kills > 0) {
                        matchStartTime = System.currentTimeMillis()
                        transitionTo(GameState.IN_MATCH)
                    }
                }
            }

            GameState.IN_MATCH -> {
                when {
                    playerDead ->
                        transitionTo(GameState.PLAYER_DEAD)
                    playersAlive <= MATCH_END_ALIVE_THRESHOLD && matchStartTime > 0 ->
                        transitionTo(GameState.MATCH_ENDED)
                }
            }

            GameState.PLAYER_DEAD -> {
                if (playersAlive <= MATCH_END_ALIVE_THRESHOLD)
                    transitionTo(GameState.MATCH_ENDED)
            }

            GameState.MATCH_ENDED -> {
                if (detectedRoomId.isNullOrBlank() && playersAlive == 0) reset()
            }

            else -> { /* CS states — BR mein ignore */ }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Clash Squad — round system
    //
    // Flow:
    //   IDLE → ROOM_JOIN → IN_MATCH
    //                          ↓ score change (round end)
    //                      ROUND_END  ← cooldown 45 frames
    //                          ↓ cooldown over
    //                      IN_MATCH   ← next round
    //                          ↓ koi team totalRounds/2+1 wins tak pahunche
    //                      MATCH_ENDED
    //
    // ⚠️ PLAYER_DEAD use nahi hoti — round khatam → sab respawn
    // ─────────────────────────────────────────────────────────────────────────

    private fun onClashSquadOcr(
        detectedRoomId: String?,
        myTeamScore: Int,
        enemyTeamScore: Int,
        totalRounds: Int
    ) {
        val current = _state.value

        when (current) {
            GameState.IDLE -> {
                if (!detectedRoomId.isNullOrBlank()) {
                    lastRoomId     = detectedRoomId
                    lastMyScore    = myTeamScore
                    lastEnemyScore = enemyTeamScore
                    transitionTo(GameState.ROOM_JOIN)
                }
            }

            GameState.ROOM_JOIN -> {
                if (!detectedRoomId.isNullOrBlank()) lastRoomId = detectedRoomId
                matchStartTime = System.currentTimeMillis()
                lastMyScore    = myTeamScore
                lastEnemyScore = enemyTeamScore
                transitionTo(GameState.IN_MATCH)
            }

            GameState.IN_MATCH -> {
                // Score badla = round khatam hua
                val scoreChanged = myTeamScore != lastMyScore || enemyTeamScore != lastEnemyScore
                if (scoreChanged) {
                    Log.i(TAG, "[CS] Round end! $lastMyScore-$lastEnemyScore → $myTeamScore-$enemyTeamScore")
                    lastMyScore      = myTeamScore
                    lastEnemyScore   = enemyTeamScore
                    roundEndCooldown = CS_ROUND_END_COOLDOWN_FRAMES
                    transitionTo(GameState.ROUND_END)
                }
                checkClashSquadMatchEnd(myTeamScore, enemyTeamScore, totalRounds)
            }

            GameState.ROUND_END -> {
                if (roundEndCooldown > 0) {
                    roundEndCooldown--
                } else {
                    Log.i(TAG, "[CS] Next round starting")
                    transitionTo(GameState.IN_MATCH)
                }
                checkClashSquadMatchEnd(myTeamScore, enemyTeamScore, totalRounds)
            }

            GameState.MATCH_ENDED -> {
                if (detectedRoomId.isNullOrBlank()) reset()
            }

            else -> { /* BR states — CS mein ignore */ }
        }
    }

    private fun checkClashSquadMatchEnd(my: Int, enemy: Int, total: Int) {
        if (total <= 0) return
        val winsNeeded = (total / 2) + 1   // e.g. Objective:5 → 3 wins chahiye
        if (my >= winsNeeded || enemy >= winsNeeded) {
            Log.i(TAG, "[CS] Match over! My=$my Enemy=$enemy (need $winsNeeded)")
            transitionTo(GameState.MATCH_ENDED)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun getLastRoomId(): String? = lastRoomId

    fun reset() {
        lastRoomId       = null
        matchStartTime   = 0L
        lastMyScore      = 0
        lastEnemyScore   = 0
        roundEndCooldown = 0
        transitionTo(GameState.IDLE)
    }

    private fun transitionTo(newState: GameState) {
        val old = _state.value
        if (old != newState) {
            Log.i(TAG, "State: $old → $newState")
            _state.value = newState
        }
    }
}
