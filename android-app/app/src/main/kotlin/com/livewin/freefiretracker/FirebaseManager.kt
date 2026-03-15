package com.livewin.freefiretracker

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.livewin.freefiretracker.models.ContestInfo
import com.livewin.freefiretracker.models.GameStats
import com.livewin.freefiretracker.models.JoinedPlayer
import com.livewin.freefiretracker.models.LiveScoreEntry
import com.livewin.freefiretracker.models.WinnerEntry
import kotlinx.coroutines.tasks.await

/**
 * FirebaseManager handles all Firestore operations matching the Live2Win web app schema:
 *
 * READ:
 *   - contests WHERE roomId == X AND status == "running"
 *   - userRooms WHERE roomId == X AND status == "running"
 *
 * LIVE SCORE WRITE (during match):
 *   - contests/{id}/liveScores/{playerId}
 *   - userRooms/{id}/liveScores/{playerId}
 *
 * MATCH END — winner declaration:
 *   1. Fetch all liveScores from the contest/room
 *   2. Sort by kills descending → assign ranks
 *   3. Apply prizeDistribution from contest document
 *   4. Write winners[] array to contest/userRoom document
 *   5. Set status = "completed"
 *   6. For each winner with uid:
 *      - users/{uid}: winningCoins += prize, totalWon += prize
 *      - publicLeaderboard/{uid}: totalWon += prize
 *      - users/{uid}.notifications: arrayUnion(winner notification)
 */
class FirebaseManager(private val playerId: String) {

    companion object {
        private const val TAG = "FirebaseManager"
        private const val COL_CONTESTS = "contests"
        private const val COL_USER_ROOMS = "userRooms"
        private const val COL_LIVE_SCORES = "liveScores"
        private const val COL_USERS = "users"
        private const val COL_LEADERBOARD = "publicLeaderboard"
    }

    private val db = FirebaseFirestore.getInstance()

    // Active contest found for this session
    private var activeContest: ContestInfo? = null

    // Whether we've already declared winners (prevent duplicate runs)
    private var winnersDeclared = false

    // ─────────────────────────────────────────────────────────────────────────
    // 1. FIND RUNNING CONTEST (checks both contests + userRooms)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Searches both 'contests' and 'userRooms' collections for a document
     * with matching roomId and status == "running".
     * Returns the first match found, or null.
     */
    suspend fun findRunningContest(roomId: String): ContestInfo? {
        if (roomId.isBlank()) return null

        // Check cache first
        activeContest?.let { cached ->
            if (cached.roomId == roomId) return cached
        }

        val result = searchCollection(COL_CONTESTS, roomId)
            ?: searchCollection(COL_USER_ROOMS, roomId)

        if (result != null) {
            activeContest = result
            winnersDeclared = false
            Log.i(TAG, "Found contest in '${result.collectionName}': ${result.contestId} for roomId=$roomId")
        }
        return result
    }

    private suspend fun searchCollection(collectionName: String, roomId: String): ContestInfo? {
        return try {
            val snap = db.collection(collectionName)
                .whereEqualTo("roomId", roomId)
                .whereEqualTo("status", "running")
                .limit(1)
                .get()
                .await()

            if (snap.isEmpty) return null

            val doc = snap.documents[0]
            val data = doc.data ?: return null

            // Parse joinedPlayersData
            val joinedRaw = data["joinedPlayersData"] as? List<*> ?: emptyList<Any>()
            val joinedPlayers = joinedRaw.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                JoinedPlayer(
                    uid = map["uid"]?.toString() ?: "",
                    name = map["name"]?.toString() ?: "",
                    playerId = map["playerId"]?.toString() ?: ""
                )
            }

            // Parse prizeDistribution
            val prizeDist = (data["prizeDistribution"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toInt() }
                ?: emptyList()

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
            Log.e(TAG, "Error searching $collectionName for roomId=$roomId", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. WRITE LIVE SCORE (during match — every 2 seconds)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes this player's current stats to:
     *   {collectionName}/{contestId}/liveScores/{playerId}
     */
    suspend fun writeLiveScore(
        contest: ContestInfo,
        stats: GameStats,
        cheatFlagged: Boolean,
        playerName: String = ""
    ) {
        val payload = hashMapOf(
            "playerId" to playerId,
            "name" to playerName,
            "kills" to stats.kills,
            "rank" to stats.rank,
            "playersAlive" to stats.playersAlive,
            "playerDead" to stats.playerDead,
            "cheatFlagged" to cheatFlagged,
            "lastUpdated" to stats.lastUpdated
        )

        try {
            db.collection(contest.collectionName)
                .document(contest.contestId)
                .collection(COL_LIVE_SCORES)
                .document(playerId)
                .set(payload)
                .await()

            Log.d(TAG, "[${contest.collectionName}] Score written: kills=${stats.kills} dead=${stats.playerDead}")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing live score", e)
        }
    }

    /**
     * Convenience: find contest (or use cached) then write live score.
     */
    suspend fun updateLiveScore(
        detectedRoomId: String?,
        stats: GameStats,
        cheatFlagged: Boolean,
        playerName: String = ""
    ) {
        val contest = activeContest ?: run {
            if (detectedRoomId.isNullOrBlank()) return
            findRunningContest(detectedRoomId) ?: return
        }
        writeLiveScore(contest, stats, cheatFlagged, playerName)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. DECLARE WINNERS (called once when MATCH_ENDED state is reached)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full winner declaration pipeline:
     * 1. Fetch all liveScores
     * 2. Sort by kills → determine ranks
     * 3. Apply prizeDistribution
     * 4. Write winners[] to contest document + set status = "completed"
     * 5. Credit winningCoins + totalWon to each winner's user document
     * 6. Update publicLeaderboard
     * 7. Send winner notification
     *
     * Returns true if winners were successfully declared.
     */
    suspend fun declareWinners(contest: ContestInfo): Boolean {
        if (winnersDeclared) {
            Log.d(TAG, "Winners already declared for this session, skipping")
            return true
        }

        Log.i(TAG, "Declaring winners for contest: ${contest.contestId} in ${contest.collectionName}")

        return try {
            // Step 1: Fetch all live scores
            val liveScoresSnap = db.collection(contest.collectionName)
                .document(contest.contestId)
                .collection(COL_LIVE_SCORES)
                .get()
                .await()

            if (liveScoresSnap.isEmpty) {
                Log.w(TAG, "No live scores found — cannot declare winners")
                return false
            }

            // Step 2: Parse live scores
            val scores = liveScoresSnap.documents.mapNotNull { doc ->
                val d = doc.data ?: return@mapNotNull null
                val kills = (d["kills"] as? Number)?.toInt() ?: 0
                val dead = d["playerDead"] as? Boolean ?: false
                val cheat = d["cheatFlagged"] as? Boolean ?: false
                if (cheat) return@mapNotNull null   // skip cheaters

                LiveScoreEntry(
                    playerId = d["playerId"]?.toString() ?: doc.id,
                    uid = "",   // will be resolved from joinedPlayersData
                    name = d["name"]?.toString() ?: "Unknown",
                    kills = kills,
                    rank = 0,
                    playerDead = dead,
                    cheatFlagged = false,
                    lastUpdated = (d["lastUpdated"] as? Number)?.toLong() ?: 0L
                )
            }

            // Step 3: Sort by kills descending → assign rank
            val ranked = scores.sortedByDescending { it.kills }

            // Step 4: Apply prize distribution
            val prizes = contest.prizeDistribution
            val winners = ranked.mapIndexed { index, score ->
                // Match player to joinedPlayersData to get their uid and L2W playerId
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

            Log.i(TAG, "Determined ${winners.size} winners. Top: ${winners.firstOrNull()?.name} (${winners.firstOrNull()?.kills} kills)")

            // Step 5: Write winners[] to contest + set status = "completed"
            val winnersPayload = winners.map { w ->
                hashMapOf(
                    "rank" to w.rank,
                    "name" to w.name,
                    "kills" to w.kills,
                    "prize" to w.prize,
                    "playerId" to w.playerId
                )
            }

            db.collection(contest.collectionName)
                .document(contest.contestId)
                .update(
                    mapOf(
                        "winners" to winnersPayload,
                        "status" to "completed"
                    )
                )
                .await()

            Log.i(TAG, "Contest marked completed with ${winners.size} winners")

            // Step 6: Credit coins to each winner with a uid
            for (winner in winners) {
                if (winner.uid.isBlank() || winner.prize <= 0) continue
                creditWinnerCoins(winner, contest.name)
            }

            winnersDeclared = true
            true

        } catch (e: Exception) {
            Log.e(TAG, "Error declaring winners for ${contest.contestId}", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. CREDIT COINS TO WINNER
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Credits prize coins to the winner's user document.
     * Mirrors the exact Firestore logic from the web app's saveWinners():
     *
     *   users/{uid}: winningCoins += prize, totalWon += prize
     *   publicLeaderboard/{uid}: totalWon += prize
     *   users/{uid}.notifications: arrayUnion(winner notification)
     */
    private suspend fun creditWinnerCoins(winner: WinnerEntry, contestName: String) {
        try {
            // Update user wallet — only winningCoins, NOT deposit coins (matching web app logic)
            db.collection(COL_USERS)
                .document(winner.uid)
                .update(
                    mapOf(
                        "winningCoins" to FieldValue.increment(winner.prize.toLong()),
                        "totalWon" to FieldValue.increment(winner.prize.toLong())
                    )
                )
                .await()

            // Update public leaderboard
            try {
                db.collection(COL_LEADERBOARD)
                    .document(winner.uid)
                    .update("totalWon", FieldValue.increment(winner.prize.toLong()))
                    .await()
            } catch (e: Exception) {
                // leaderboard doc may not exist yet — use set with merge
                db.collection(COL_LEADERBOARD)
                    .document(winner.uid)
                    .set(
                        mapOf(
                            "displayName" to winner.name,
                            "playerId" to winner.playerId,
                            "totalWon" to winner.prize,
                            "banned" to false
                        )
                    )
                    .await()
            }

            // Send winner notification (matches web app notification format)
            val notification = hashMapOf(
                "icon" to "🏆",
                "title" to "You Won ₹${winner.prize}! Rank #${winner.rank}",
                "message" to "Congrats! You finished Rank #${winner.rank} in \"$contestName\" with ${winner.kills} kills. ₹${winner.prize} credited to your Winning Wallet!",
                "read" to false,
                "time" to System.currentTimeMillis()
            )

            db.collection(COL_USERS)
                .document(winner.uid)
                .update("notifications", FieldValue.arrayUnion(notification))
                .await()

            Log.i(TAG, "Credited ${winner.prize} coins to ${winner.name} (uid=${winner.uid}) + notification sent")

        } catch (e: Exception) {
            Log.e(TAG, "Error crediting coins to winner uid=${winner.uid}", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun getActiveContest(): ContestInfo? = activeContest
    fun getActiveContestId(): String? = activeContest?.contestId

    fun clearActiveContest() {
        activeContest = null
        winnersDeclared = false
    }

    fun resetSession() {
        winnersDeclared = false
    }
}
