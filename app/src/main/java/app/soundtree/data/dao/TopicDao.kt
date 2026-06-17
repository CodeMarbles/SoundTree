package app.soundtree.data.dao

import androidx.room.*
import app.soundtree.data.entities.TopicEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TopicDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(topic: TopicEntity): Long

    @Update
    suspend fun update(topic: TopicEntity)

    @Delete
    suspend fun delete(topic: TopicEntity)

    @Query("SELECT * FROM topics WHERE id = :id")
    suspend fun getById(id: Long): TopicEntity?

    /** All topics — used to reconstruct the full tree in-memory */
    @Query("SELECT * FROM topics ORDER BY sort_order ASC, name ASC")
    fun getAllTopics(): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics ORDER BY sort_order ASC, name ASC")
    suspend fun getAllTopicsOnce(): List<TopicEntity>

    /** Direct children of a given parent */
    @Query("SELECT * FROM topics WHERE parent_id = :parentId ORDER BY sort_order ASC, name ASC")
    fun getChildren(parentId: Long): Flow<List<TopicEntity>>

    /** Root topics (no parent) */
    @Query("SELECT * FROM topics WHERE parent_id IS NULL ORDER BY sort_order ASC, name ASC")
    fun getRoots(): Flow<List<TopicEntity>>

    @Query("UPDATE topics SET updated_at = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long = System.currentTimeMillis())

    /** Total topic count. Used by the restore wizard summary step. */
    @Query("SELECT COUNT(*) FROM topics")
    suspend fun countAll(): Int

    // ── Frequent topics scoring ───────────────────────────────────────────────

    /** Add [delta] to a single topic's score. Delta may be fractional (ancestor propagation). */
    @Query("UPDATE topics SET topic_score = topic_score + :delta WHERE id = :id")
    suspend fun addScore(id: Long, delta: Double)

    /** Multiply every topic's score by [factor]. Called daily by DecayWorker. */
    @Query("UPDATE topics SET topic_score = topic_score * :factor")
    suspend fun decayAllScores(factor: Double)

    /**
     * Top [limit] topics by score, excluding those at or below [minScore].
     * Emits reactively — the frequent section updates whenever scores change.
     */
    @Query("SELECT * FROM topics WHERE topic_score > :minScore ORDER BY topic_score DESC LIMIT :limit")
    fun getTopScoring(limit: Int, minScore: Double = 0.1): Flow<List<TopicEntity>>
}