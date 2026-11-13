package com.hybrid.whatsappbot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM scheduled_tasks ORDER BY scheduledTimeMillis DESC")
    fun observeAllTasks(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE status = 'PENDING' AND scheduledTimeMillis > :minTimeMillis ORDER BY scheduledTimeMillis ASC")
    suspend fun getPendingFutureTasks(minTimeMillis: Long): List<ScheduledTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: ScheduledTaskEntity)

    @Query(
        """
        UPDATE scheduled_tasks 
        SET status = :newStatus, 
            executionLog = :logMessage, 
            completedAtMillis = :completedAt 
        WHERE taskId = :taskId
        """
    )
    suspend fun updateTaskStatus(
        taskId: String,
        newStatus: TaskStatus,
        logMessage: String,
        completedAt: Long? = null
    )

    /**
     * Atomic update that only transitions to a new status if the task has NOT already
     * completed via UI or Background Bot. Prevents race conditions between Engine 1 & Watchdog.
     */
    @Query(
        """
        UPDATE scheduled_tasks 
        SET status = :newStatus, 
            executionLog = :logMessage, 
            completedAtMillis = :completedAt 
        WHERE taskId = :taskId 
          AND status NOT IN ('COMPLETED_UI', 'COMPLETED_BACKGROUND')
        """
    )
    suspend fun updateStatusIfNotCompleted(
        taskId: String,
        newStatus: TaskStatus,
        logMessage: String,
        completedAt: Long? = null
    ): Int

    @Query("DELETE FROM scheduled_tasks WHERE taskId = :taskId")
    suspend fun deleteTaskById(taskId: String)
}
