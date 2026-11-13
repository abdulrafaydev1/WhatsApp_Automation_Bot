package com.hybrid.whatsappbot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoReplyRuleDao {

    @Query("SELECT * FROM auto_reply_rules ORDER BY createdAtMillis DESC")
    fun observeAllRules(): Flow<List<AutoReplyRuleEntity>>

    @Query("SELECT * FROM auto_reply_rules WHERE isEnabled = 1 ORDER BY createdAtMillis DESC")
    suspend fun getEnabledRules(): List<AutoReplyRuleEntity>

    @Query("SELECT * FROM auto_reply_rules WHERE ruleId = :ruleId LIMIT 1")
    suspend fun getRuleById(ruleId: String): AutoReplyRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: AutoReplyRuleEntity)

    @Query("UPDATE auto_reply_rules SET isEnabled = :enabled WHERE ruleId = :ruleId")
    suspend fun setRuleEnabled(ruleId: String, enabled: Boolean)

    @Query(
        """
        UPDATE auto_reply_rules 
        SET triggerCount = triggerCount + 1,
            lastTriggeredAtMillis = :triggeredAtMillis,
            lastTriggeredGroup = :triggeredGroup
        WHERE ruleId = :ruleId
        """
    )
    suspend fun recordRuleTriggered(
        ruleId: String,
        triggeredAtMillis: Long,
        triggeredGroup: String
    )

    @Query("DELETE FROM auto_reply_rules WHERE ruleId = :ruleId")
    suspend fun deleteRuleById(ruleId: String)
}
