package com.hybrid.whatsappbot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "auto_reply_rules")
data class AutoReplyRuleEntity(
    @PrimaryKey
    val ruleId: String,
    val imageLocalPath: String,
    val caption: String,
    val targetGroupName: String,
    val targetGroupJid: String? = null,
    val matchAllGroups: Boolean = false,
    val cooldownMillis: Long = 120_000L, // Default: 2 minutes
    val isEnabled: Boolean = true,
    val triggerCount: Int = 0,
    val lastTriggeredAtMillis: Long = 0L,
    val lastTriggeredGroup: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
)
