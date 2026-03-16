import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.livewin.freefiretracker.models.ContestInfo
import com.livewin.freefiretracker.models.GameStats
import com.livewin.freefiretracker.models.JoinedPlayer
import com.livewin.freefiretracker.models.LiveScoreEntry
import com.livewin.freefiretracker.models.WinnerEntry
import kotlinx.coroutines.tasks.await

class FirebaseManager(private val playerId: String) {

    companion object {
        private const val TAG = "FirebaseManager"
        private const val COL_CONTESTS = "contests"
        private const val COL_USER_ROOMS = "userRooms"
        private const val COL_LIVE_SCORES = "liveScores"
        private const val COL_USERS = "users"
        private const val COL_LEADERBOARD = "publicLeaderboard"
        private const val COL_LIVE_DATA = "liveData"
    }

    private val db = FirebaseFirestore.getInstance()
    private var activeContest: ContestInfo? = null
    private var winnersDeclared = false

    // ─────────────────────────────────────────────────────────────────────────
    // 1. FIND RUNNING CONTEST
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun findRunningContest(roomId: String): ContestInfo? {
        if (roomId.isBlank()) return null
        activeContest?.let { if (it.roomId == roomId) return it }

        val result = searchCollection(COL_CONTESTS, roomId)
            ?: searchCollection(COL_USER_ROOMS, roomId)

        if (result != null) {
            activeContest = result
            winnersDeclared = false
            Log.i(TAG, "Contest found: ${result.contestId}")
        }
        return result
    }

    private suspend fun searchCollection(
        collectionName: String,
        roomId: String
    ): ContestInfo? {
        return try {
            val snap = db.collection(collectionName)
                .whereEqualTo("roomId", roomId)
                .whereEqualTo("status", "running")
                .limit(1).get().await()

            if (snap.isEmpty) return null
            val doc = snap.documents[0]
            val data = doc.data ?: return null

            val joinedRaw = data["joinedPlayersData"] as? List<*> ?: emptyList<Any>()
            val joinedPlayers = joinedRaw.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                JoinedPlayer(
                    uid = map["uid"]?.toString() ?: "",
                    name = map["name"]?.toString() ?: "",
                    playerId = map["playerId"]?.toString() ?: ""
                )
            }

            val prizeDist = (data["prizeDistribution"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()

            ContestInfo(
                contestId = doc.id,
                roomId = roomId,
                status = "running",
                collectionName = collectionName,
                prizeDistribution = prizeDist,
                joinedPlayersData = joinedPlayers,
                name = data["name"]?.toString() ?: "Match"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error searching $collectionName", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. LIVE SCORE WRITE
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun writeLiveScore(
        contest: ContestInfo,
        stats: GameStats,
        cheatFlagged: Boolean,
        playerName: String = "",
        activePlayers: List<String> = emptyList(),
        deadPlayers: List<String> = emptyList()
    ) {
        val payload = hashMapOf(
            "playerId"      to playerId,
            "name"          to playerName,
            "kills"         to stats.kills,
            "rank"          to stats.rank,
            "playersAlive"  to stats.playersAlive,
            "playerDead"    to stats.playerDead,
            "cheatFlagged"  to cheatFlagged,
            "lastUpdated"   to stats.lastUpdated,
            "activePlayers" to activePlayers,
            "deadPlayers"   to deadPlayers
        )

        try {
            // liveScores mein write
            db.collection(contest.collectionName)
                .document(contest.contestId)
                .collection(COL_LIVE_SCORES)
                .document(playerId)
                .set(payload)
                .await()

            // Aggregated liveData update karo (cross-verification + web app)
            updateAggregatedLiveData(
                contest, playerName, stats,
                activePlayers, deadPlayers, cheatFlagged
            )

            Log.d(TAG, "LiveScore written: kills=${stats.kills} " +
                    "alive=${stats.playersAlive} " +
                    "active=${activePlayers.size} dead=${deadPlayers.size}")

        } catch (e: Exception) {
            Log.e(TAG, "Error writing live score", e)
        }
    }

    suspend fun updateLiveScore(
        detectedRoomId: String?,
        stats: GameStats,
        cheatFlagged: Boolean,
        playerName: String = "",
        activePlayers: List<String> = emptyList(),
        deadPlayers: List<String> = emptyList()
    ) {
        val contest = activeContest ?: run {
            if (detectedRoomId.isNullOrBlank()) return
            findRunningContest(detectedRoomId) ?: return
        }
        writeLiveScore(contest, stats, cheatFlagged, playerName, activePlayers, deadPlayers)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. AGGREGATED LIVE DATA — Sare players ke packets combine karo
    //    Web app yahan se real-time data leta hai
    //    Cross-verification bhi yahan hoti hai
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun updateAggregatedLiveData(
        contest: ContestInfo,
        playerName: String,
        stats: GameStats,
        activePlayers: List<String>,
        deadPlayers: List<String>,
        cheatFlagged: Boolean
    ) {
        try {
            // Sare players ke liveScores fetch karo
            val allScores = db.collection(contest.collectionName)
                .document(contest.contestId)
                .collection(COL_LIVE_SCORES)
                .get().await()

            // Cross-verification: sare players ke packets merge karo
            val allActive = mutableSetOf<String>()
            val allDead   = mutableSetOf<String>()
            var totalKills = 0
            var mostRecentAlive = stats.playersAlive

            for (doc in allScores.documents) {
                val d = doc.data ?: continue

                val docActive = (d["activePlayers"] as? List<*>)
                    ?.map { it.toString() } ?: emptyList()
                val docDead = (d["deadPlayers"] as? List<*>)
                    ?.map { it.toString() } ?: emptyList()
                val docKills = (d["kills"] as? Number)?.toInt() ?: 0
                val docAlive = (d["playersAlive"] as? Number)?.toInt() ?: 0

                allActive.addAll(docActive)
                allDead.addAll(docDead)
                totalKills += docKills

                // Sabse trusted alive count
                if (docAlive > 0) mostRecentAlive = docAlive
            }

            // Cross-verify: jo dead hai wo active mein nahi hona chahiye
            allActive.removeAll(allDead)

            val livePayload = hashMapOf(
                "playersAlive"   to mostRecentAlive,
                "totalKills"     to totalKills,
                "activePlayers"  to allActive.toList(),
                "deadPlayers"    to allDead.toList(),
                "playerDead"     to stats.playerDead,
                "cheatFlagged"   to cheatFlagged,
                "trackedBy"      to playerName,
                "trackersCount"  to allScores.documents.size,
                "lastUpdated"    to FieldValue.serverTimestamp(),
                "matchStatus"    to "running"
            )

            db.collection(contest.collectionName)
                .document(contest.contestId)
                .collection(COL_LIVE_DATA)
                .document("current")
                .set(livePayload)
                .await()

            Log.d(TAG, "Aggregated liveData: alive=$mostRecentAlive " +
                    "active=${allActive.size} dead=${allDead.size} " +
                    "trackers=${allScores.documents.size}")

        } catch (e: Exception) {
            Log.e(TAG, "Aggregation error", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. CROSS VERIFY KILL
    //    Player A ne Player B ko kill kiya — verify karo
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun crossVerifyKill(
        contest: ContestInfo,
        killerPlayerId: String,
        targetPlayerName: String
    ): Boolean {
        return try {
            val scores = db.collection(contest.collectionName)
                .document(contest.contestId)
                .collection(COL_LIVE_SCORES)
                .get().await()

            // Target player ka document dhundho
            val targetDoc = scores.documents.find { doc ->
                val name = doc.data?.get("name")?.toString() ?: ""
                name.equals(targetPlayerName, ignoreCase = true)
            }

            val targetDead = targetDoc?.data?.get("playerDead") as? Boolean ?: false

            Log.d(TAG, "CrossVerify: $killerPlayerId killed " +
                    "$targetPlayerName → confirmed=$targetDead")
            targetDead

        } catch (e: Exception) {
            Log.e(TAG, "CrossVerify error", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. DECLARE WINNERS
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun declareWinners(contest: ContestInfo): Boolean {
        if (winnersDeclared) return true

        return try {
            val liveScoresSnap = db.collection(contest.collectionName)
                .document(contest.contestId)
                .collection(COL_LIVE_SCORES)
                .get().await()

            if (liveScoresSnap.isEmpty) return false

            val scores = liveScoresSnap.documents.mapNotNull { doc ->
                val d = doc.data ?: return@mapNotNull null
                val cheat = d["cheatFlagged"] as? Boolean ?: false
                if (cheat) return@mapNotNull null

                LiveScoreEntry(
                    playerId = d["playerId"]?.toString() ?: doc.id,
                    uid = "",
                    name = d["name"]?.toString() ?: "Unknown",
                    kills = (d["kills"] as? Number)?.toInt() ?: 0,
                    rank = 0,
                    playerDead = d["playerDead"] as? Boolean ?: false,
                    cheatFlagged = false,
                    lastUpdated = (d["lastUpdated"] as? Number)?.toLong() ?: 0L
                )
            }

            val ranked = scores.sortedByDescending { it.kills }
            val prizes = contest.prizeDistribution

            val winners = ranked.mapIndexed { index, score ->
                val registered = contest.joinedPlayersData.find { jp ->
                    jp.playerId.equals(score.playerId, ignoreCase = true) ||
                    jp.name.equals(score.name, ignoreCase = true)
                }
                WinnerEntry(
                    rank = index + 1,
                    name = registered?.name ?: score.name,
                    kills = score.kills,
                    prize = prizes.getOrElse(index) { 0 },
                    playerId = registered?.playerId ?: score.playerId,
                    uid = registered?.uid ?: ""
                )
            }

            val winnersPayload = winners.map { w ->
                hashMapOf(
                    "rank" to w.rank, "name" to w.name,
                    "kills" to w.kills, "prize" to w.prize,
                    "playerId" to w.playerId
                )
            }

            // Contest complete mark karo
            db.collection(contest.collectionName)
                .document(contest.contestId)
                .update(mapOf(
                    "winners" to winnersPayload,
                    "status" to "completed"
                )).await()

            // LiveData mein bhi complete mark karo
            db.collection(contest.collectionName)
                .document(contest.contestId)
                .collection(COL_LIVE_DATA)
                .document("current")
                .update(mapOf(
                    "matchStatus" to "completed",
                    "winners"     to winnersPayload,
                    "lastUpdated" to FieldValue.serverTimestamp()
                )).await()

            // Coins credit karo
            for (winner in winners) {
                if (winner.uid.isBlank() || winner.prize <= 0) continue
                creditWinnerCoins(winner, contest.name)
            }

            winnersDeclared = true
            Log.i(TAG, "Winners declared: ${winners.size}")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Error declaring winners", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. CREDIT COINS
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun creditWinnerCoins(winner: WinnerEntry, contestName: String) {
        try {
            db.collection(COL_USERS).document(winner.uid).update(
                mapOf(
                    "winningCoins" to FieldValue.increment(winner.prize.toLong()),
                    "totalWon"     to FieldValue.increment(winner.prize.toLong())
                )
            ).await()

            try {
                db.collection(COL_LEADERBOARD).document(winner.uid)
                    .update("totalWon",
                        FieldValue.increment(winner.prize.toLong())).await()
            } catch (e: Exception) {
                db.collection(COL_LEADERBOARD).document(winner.uid).set(
                    mapOf(
                        "displayName" to winner.name,
                        "playerId"    to winner.playerId,
                        "totalWon"    to winner.prize,
                        "banned"      to false
                    )
                ).await()
            }

            val notification = hashMapOf(
                "icon"    to "🏆",
                "title"   to "You Won ₹${winner.prize}! Rank #${winner.rank}",
                "message" to "Congrats! Rank #${winner.rank} in " +
                        "\"$contestName\" with ${winner.kills} kills. " +
                        "₹${winner.prize} credited!",
                "read" to false,
                "time" to System.currentTimeMillis()
            )

            db.collection(COL_USERS).document(winner.uid)
                .update("notifications",
                    FieldValue.arrayUnion(notification)).await()

            Log.i(TAG, "Coins credited: ${winner.prize} → ${winner.name}")

        } catch (e: Exception) {
            Log.e(TAG, "Error crediting coins", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun getActiveContest(): ContestInfo? = activeContest
    fun getActiveContestId(): String? = activeContest?.contestId
    fun getLastRoomId(): String? = activeContest?.roomId
    fun clearActiveContest() { activeContest = null; winnersDeclared = false }
    fun resetSession() { winnersDeclared = false }
}
