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

    companion object {
        private const val TAG = "GameStateMachine"
        // After detecting roomId, wait this many ms before considering PRE_MATCH
        private const val ROOM_JOIN_TO_PRE_MATCH_DELAY_MS = 3000L
        // If alive count drops to 0 from IN_MATCH, mark MATCH_ENDED
        private const val MATCH_END_ALIVE_THRESHOLD = 1
    }

    fun getCurrent(): GameState = _state.value

    /**
     * Called every OCR cycle with the latest detected values.
     * Drives the state machine transitions.
     */
    fun onOcrResult(
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
                if (!detectedRoomId.isNullOrBlank()) {
                    lastRoomId = detectedRoomId
                }
                // Once we see players alive > 0, we are in pre-match lobby
                if (playersAlive > 0) {
                    transitionTo(GameState.PRE_MATCH)
                }
            }

            GameState.PRE_MATCH -> {
                // Match starts when alive count matches full lobby (~60 players) or kills timer fires
                if (playersAlive in 1..60 && kills >= 0) {
                    // Transition to IN_MATCH once alive count starts dropping from high value
                    if (playersAlive < 50 || kills > 0) {
                        matchStartTime = System.currentTimeMillis()
                        transitionTo(GameState.IN_MATCH)
                    }
                }
            }

            GameState.IN_MATCH -> {
                when {
                    playerDead -> {
                        transitionTo(GameState.PLAYER_DEAD)
                    }
                    playersAlive <= MATCH_END_ALIVE_THRESHOLD && matchStartTime > 0 -> {
                        transitionTo(GameState.MATCH_ENDED)
                    }
                }
            }

            GameState.PLAYER_DEAD -> {
                // After player dies, match eventually ends when alive count reaches 1
                if (playersAlive <= MATCH_END_ALIVE_THRESHOLD) {
                    transitionTo(GameState.MATCH_ENDED)
                }
            }

            GameState.MATCH_ENDED -> {
                // Reset once room ID disappears from screen (back to main menu)
                if (detectedRoomId.isNullOrBlank() && playersAlive == 0) {
                    reset()
                }
            }
        }
    }

    fun getLastRoomId(): String? = lastRoomId

    fun reset() {
        lastRoomId = null
        matchStartTime = 0L
        transitionTo(GameState.IDLE)
    }

    private fun transitionTo(newState: GameState) {
        val oldState = _state.value
        if (oldState != newState) {
            Log.i(TAG, "State transition: $oldState → $newState")
            _state.value = newState
        }
    }
}
