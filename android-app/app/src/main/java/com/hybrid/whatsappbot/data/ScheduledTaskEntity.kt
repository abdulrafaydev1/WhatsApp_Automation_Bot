package com.hybrid.whatsappbot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey
    val taskId: String,
    val imageLocalPath: String,
    val caption: String,
    val targetGroupName: String,
    val targetGroupJid: String? = null,
    val scheduledTimeMillis: Long,
    val status: TaskStatus = TaskStatus.PENDING,
    val executionLog: String = "Scheduled and waiting for trigger.",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val completedAtMillis: Long? = null
)
