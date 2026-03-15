package com.livewin.freefiretracker.models

data class GameStats(
    val kills: Int = 0,
    val rank: Int = 0,
    val playersAlive: Int = 0,
    val playerDead: Boolean = false,
    val cheatFlagged: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class ContestInfo(
    val contestId: String,
    val roomId: String,
    val status: String,
    val collectionName: String,     // "contests" or "userRooms"
    val prizeDistribution: List<Int> = emptyList(),
    val joinedPlayersData: List<JoinedPlayer> = emptyList(),
    val name: String = ""
)

data class JoinedPlayer(
    val uid: String = "",
    val name: String = "",
    val playerId: String = ""
)

data class LiveScoreEntry(
    val playerId: String,
    val uid: String,
    val name: String,
    val kills: Int,
    val rank: Int,
    val playerDead: Boolean,
    val cheatFlagged: Boolean,
    val lastUpdated: Long
)

data class WinnerEntry(
    val rank: Int,
    val name: String,
    val kills: Int,
    val prize: Int,
    val playerId: String = "",
    val uid: String = ""
)

enum class GameState {
    IDLE,
    ROOM_JOIN,
    PRE_MATCH,
    IN_MATCH,
    PLAYER_DEAD,
    MATCH_ENDED
}
