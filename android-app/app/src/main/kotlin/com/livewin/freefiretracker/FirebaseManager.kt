package com.livewin.freefiretracker

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.livewin.freefiretracker.models.ContestInfo
import com.livewin.freefiretracker.models.GameStats
import com.livewin.freefiretracker.models.LiveScorePayload
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * FirebaseManager handles all Firestore read/write operations:
 *
 * READ:  contests WHERE roomId == detectedRoomId AND status == "running"
 * WRITE: contests/{contestId}/liveScores/{playerId}
 *        Fields: kills, rank, playersAlive, playerDead, cheatFlagged, lastUpdated
 */
class FirebaseManager(
    private val playerId: String
) {

    companion object {
        private const val TAG = "FirebaseManager"
        private const val CONTESTS_COLLECTION = "contests"
        private const val LIVE_SCORES_COLLECTION = "liveScores"
    }

    private val db = FirebaseFirestore.getInstance()

    private var activeContestId: String? = null
    private var contestListenerReg: ListenerRegistration? = null

    /**
     * Query Firestore for a running contest matching the given roomId.
     * Returns the ContestInfo if found, or null if none.
     */
    suspend fun findRunningContest(roomId: String): ContestInfo? {
        if (roomId.isBlank()) return null

        return try {
            val snapshot = db.collection(CONTESTS_COLLECTION)
                .whereEqualTo("roomId", roomId)
                .whereEqualTo("status", "running")
                .limit(1)
                .get()
                .await()

            if (snapshot.isEmpty) {
                Log.d(TAG, "No running contest found for roomId=$roomId")
                null
            } else {
                val doc = snapshot.documents[0]
                ContestInfo(
                    contestId = doc.id,
                    roomId = doc.getString("roomId") ?: roomId,
                    status = doc.getString("status") ?: "running"
                ).also {
                    Log.i(TAG, "Found contest: ${it.contestId} for roomId=$roomId")
                    activeContestId = it.contestId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contest for roomId=$roomId", e)
            null
        }
    }

    /**
     * Write live score data to Firestore.
     * Path: contests/{contestId}/liveScores/{playerId}
     */
    suspend fun writeLiveScore(
        contestId: String,
        stats: GameStats,
        cheatFlagged: Boolean
    ) {
        val payload = hashMapOf(
            "kills" to stats.kills,
            "rank" to stats.rank,
            "playersAlive" to stats.playersAlive,
            "playerDead" to stats.playerDead,
            "cheatFlagged" to cheatFlagged,
            "lastUpdated" to stats.lastUpdated
        )

        try {
            db.collection(CONTESTS_COLLECTION)
                .document(contestId)
                .collection(LIVE_SCORES_COLLECTION)
                .document(playerId)
                .set(payload)
                .await()

            Log.d(TAG, "Score written: kills=${stats.kills} alive=${stats.playersAlive} dead=${stats.playerDead} cheat=$cheatFlagged")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing live score to contestId=$contestId", e)
        }
    }

    /**
     * Convenience method that combines finding and writing in one call.
     * Uses the cached activeContestId if already resolved.
     */
    suspend fun updateLiveScore(
        detectedRoomId: String?,
        stats: GameStats,
        cheatFlagged: Boolean
    ) {
        val contestId = activeContestId ?: run {
            if (detectedRoomId.isNullOrBlank()) return
            findRunningContest(detectedRoomId)?.contestId ?: return
        }
        writeLiveScore(contestId, stats, cheatFlagged)
    }

    /**
     * Listen to a contest document in real-time.
     * Useful for detecting if contest status changes externally.
     */
    fun observeContest(contestId: String): Flow<Map<String, Any?>?> = callbackFlow {
        val docRef = db.collection(CONTESTS_COLLECTION).document(contestId)
        val reg = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Contest listener error", error)
                return@addSnapshotListener
            }
            trySend(snapshot?.data)
        }
        contestListenerReg = reg
        awaitClose { reg.remove() }
    }

    fun clearActiveContest() {
        activeContestId = null
        contestListenerReg?.remove()
        contestListenerReg = null
    }

    fun getActiveContestId(): String? = activeContestId
}
