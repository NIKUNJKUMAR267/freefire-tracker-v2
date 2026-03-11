package com.livewin.freefiretracker.models

data class GameStats(
    val kills: Int = 0,
    val rank: Int = 0,
    val playersAlive: Int = 0,
    val playerDead: Boolean = false,
    val cheatFlagged: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class LiveScorePayload(
    val kills: Int,
    val rank: Int,
    val playersAlive: Int,
    val playerDead: Boolean,
    val cheatFlagged: Boolean,
    val lastUpdated: Long
)

data class ContestInfo(
    val contestId: String,
    val roomId: String,
    val status: String
)

enum class GameState {
    IDLE,
    ROOM_JOIN,
    PRE_MATCH,
    IN_MATCH,
    PLAYER_DEAD,
    MATCH_ENDED
}
