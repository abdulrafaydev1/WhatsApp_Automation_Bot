package com.hybrid.whatsappbot.service

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.hybrid.whatsappbot.automation.AutomationStateManager
import com.hybrid.whatsappbot.data.AppDatabase
import com.hybrid.whatsappbot.data.ScheduledTaskEntity
import com.hybrid.whatsappbot.data.TaskStatus
import com.hybrid.whatsappbot.scheduler.AlarmSchedulerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WhatsAppNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // 1. Package Filtering: Only process WhatsApp & WhatsApp Business notifications
        val pkg = sbn.packageName ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") {
            return
        }

        // 2. Anti-Loop Guardrail A: Ignore Ongoing & Group Summary bundle notifications
        val notification = sbn.notification ?: return
        if (sbn.isOngoing || (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }

        // 3. Anti-Loop Guardrail B: Ignore notifications while active automation is running
        //    or within 15 seconds after a bot task completed
        if (AutomationStateManager.wasRecentlyActive(15_000L)) {
            Log.d(TAG, "Ignoring WhatsApp notification during active/recent bot automation window.")
            return
        }

        val extras = notification.extras ?: return
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()
            ?.trim()
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            ?.trim()
        val messageText = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()
            ?.trim()

        val isGroupFlag = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val hasColonInTitle = rawTitle?.contains(":") == true
        val isGroupConversation = isGroupFlag || !conversationTitle.isNullOrBlank() || hasColonInTitle

        if (!isGroupConversation) {
            return
        }

        val detectedGroupName = cleanExtractedGroupName(
            conversationTitle = conversationTitle,
            rawTitle = rawTitle
        )
        if (detectedGroupName.isBlank() || isSystemStatusTitle(detectedGroupName, messageText)) {
            return
        }

        serviceScope.launch {
            try {
                evaluateAndScheduleFiveSecondDelayedPost(
                    detectedGroupName = detectedGroupName,
                    incomingSnippet = messageText ?: ""
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing group notification for '$detectedGroupName'", e)
            }
        }
    }

    private suspend fun evaluateAndScheduleFiveSecondDelayedPost(
        detectedGroupName: String,
        incomingSnippet: String
    ) {
        if (AutomationStateManager.wasRecentlyActive(15_000L)) {
            return
        }

        val groupKey = detectedGroupName.trim().lowercase()

        // 1. Pre-Delay Pending Lock Check: Ignore burst messages while 5s timer is already active
        if (pendingGroupJobs[groupKey] == true) {
            Log.d(TAG, "Group '$detectedGroupName' already has a pending 5-second auto-reply job. Ignoring burst message.")
            return
        }

        val db = AppDatabase.getInstance(applicationContext)
        val ruleDao = db.autoReplyRuleDao()
        val taskDao = db.taskDao()

        val enabledRules = withContext(Dispatchers.IO) {
            ruleDao.getEnabledRules()
        }
        if (enabledRules.isEmpty()) return

        val now = System.currentTimeMillis()

        val matchedRule = enabledRules.firstOrNull { rule ->
            val isMatch = if (rule.matchAllGroups) {
                true
            } else {
                val target = rule.targetGroupName.trim()
                target.isNotEmpty() && (
                    detectedGroupName.equals(target, ignoreCase = true) ||
                        detectedGroupName.contains(target, ignoreCase = true) ||
                        target.contains(detectedGroupName, ignoreCase = true)
                    )
            }
            if (!isMatch) return@firstOrNull false

            val cooldownKey = "${rule.ruleId}_$groupKey"
            val inMemoryLast = lastTriggeredMap[cooldownKey] ?: lastTriggeredMap[groupKey] ?: 0L
            val dbLast = if (!rule.matchAllGroups ||
                rule.lastTriggeredGroup.equals(detectedGroupName, ignoreCase = true)
            ) {
                rule.lastTriggeredAtMillis
            } else {
                0L
            }
            val effectiveLastTriggered = maxOf(inMemoryLast, dbLast)

            if (now - effectiveLastTriggered < rule.cooldownMillis) {
                val remainingSec = (rule.cooldownMillis - (now - effectiveLastTriggered)) / 1000L
                Log.d(
                    TAG,
                    "Auto-Rule ${rule.ruleId} matched '$detectedGroupName', " +
                        "but cooldown active (${remainingSec}s remaining)."
                )
                return@firstOrNull false
            }

            val imageFile = File(rule.imageLocalPath)
            imageFile.exists() && imageFile.length() > 0L
        } ?: return

        // =====================================================================
        // STEP 1: Lock pendingGroupJobs & Cooldown BEFORE the 5-Second Delay
        // =====================================================================
        val cooldownKey = "${matchedRule.ruleId}_$groupKey"
        if (pendingGroupJobs.putIfAbsent(groupKey, true) == true) {
            return
        }
        lastTriggeredMap[groupKey] = now
        lastTriggeredMap[cooldownKey] = now

        val targetGroupToSend = if (matchedRule.matchAllGroups) {
            detectedGroupName
        } else {
            matchedRule.targetGroupName.ifBlank { detectedGroupName }
        }
        val targetJidToSend = if (matchedRule.matchAllGroups) null else matchedRule.targetGroupJid

        val generatedTaskId = "auto_${UUID.randomUUID()}"
        val previewSnippet = incomingSnippet.take(36).ifBlank { "Group post" }

        withContext(Dispatchers.IO) {
            ruleDao.recordRuleTriggered(
                ruleId = matchedRule.ruleId,
                triggeredAtMillis = now,
                triggeredGroup = detectedGroupName
            )
            val autoTask = ScheduledTaskEntity(
                taskId = generatedTaskId,
                imageLocalPath = matchedRule.imageLocalPath,
                caption = matchedRule.caption,
                targetGroupName = targetGroupToSend,
                targetGroupJid = targetJidToSend,
                scheduledTimeMillis = now + SMART_DELAY_MS,
                status = TaskStatus.PENDING,
                executionLog = "Group activity detected in [$detectedGroupName] ($previewSnippet). Waiting 5 seconds before sending..."
            )
            taskDao.upsertTask(autoTask)
        }

        Log.i(TAG, "Group activity detected in [$detectedGroupName]. Waiting 5 seconds before sending...")

        try {
            // Wait exactly 5 seconds (5000ms) before triggering the reply post
            delay(SMART_DELAY_MS)

            // =================================================================
            // STEP 2: Acquire Sequential Execution Mutex & Re-Evaluate Lock State
            // =================================================================
            executionMutex.withLock {
                // If another UI automation task is currently running, wait up to 15s + 1.5s buffer
                var waitTicks = 0
                while (AutomationStateManager.isTaskActive() && waitTicks < 30) {
                    delay(500L)
                    waitTicks++
                }
                if (waitTicks > 0) {
                    delay(1_500L)
                }

                // Re-evaluate PowerManager.isInteractive & KeyguardManager.isKeyguardLocked right now
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                val isUnlockedAndScreenOn = powerManager.isInteractive && !keyguardManager.isKeyguardLocked

                Log.i(
                    TAG,
                    "5-second delay completed for [$detectedGroupName]. " +
                        "Re-evaluated state: unlockedAndScreenOn=$isUnlockedAndScreenOn. Dispatching taskId=$generatedTaskId..."
                )

                // Set 15-second self-post ignore window before dispatching
                AutomationStateManager.markBotActivityNow()
                AlarmSchedulerHelper.triggerTaskImmediately(applicationContext, generatedTaskId)

                // If UI automation started, wait inside the mutex until it finishes so queued groups never collide
                if (isUnlockedAndScreenOn) {
                    delay(800L)
                    var uiRunTicks = 0
                    while (AutomationStateManager.isTaskActive() && uiRunTicks < 40) {
                        delay(500L)
                        uiRunTicks++
                    }
                    delay(1_500L)
                }
            }
        } finally {
            pendingGroupJobs[groupKey] = false
            AutomationStateManager.markBotActivityNow()
        }
    }

    /**
     * Cleans WhatsApp group notification titles by removing sender suffixes
     * (e.g. "Group Name: Sender Name") and unread message count badges (e.g. "(3 messages)").
     */
    private fun cleanExtractedGroupName(
        conversationTitle: String?,
        rawTitle: String?
    ): String {
        val baseTitle = if (!conversationTitle.isNullOrBlank()) {
            conversationTitle
        } else {
            val fromRaw = rawTitle ?: return ""
            if (fromRaw.contains(":")) {
                fromRaw.substringBefore(":")
            } else {
                fromRaw
            }
        }

        val withoutCountBadge = baseTitle.replace(
            Regex("""\s*\(\d+\s+[^)]*messages?\)\s*$""", RegexOption.IGNORE_CASE),
            ""
        )
        return withoutCountBadge.trim()
    }

    private fun isSystemStatusTitle(groupName: String, messageText: String?): Boolean {
        val lowerTitle = groupName.lowercase()
        val ignoredTitles = listOf(
            "whatsapp",
            "whatsapp business",
            "whatsapp web",
            "checking for new messages",
            "backup in progress",
            "restoring media",
            "sending media"
        )
        if (ignoredTitles.any { lowerTitle == it || lowerTitle.startsWith(it) }) {
            return true
        }
        val lowerText = messageText?.lowercase() ?: ""
        if (lowerText.contains("checking for new messages") ||
            lowerText.contains("whatsapp web is currently active")
        ) {
            return true
        }
        return false
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WANotifListener"
        private const val SMART_DELAY_MS = 5_000L

        private val pendingGroupJobs = ConcurrentHashMap<String, Boolean>()
        private val lastTriggeredMap = ConcurrentHashMap<String, Long>()
        private val executionMutex = Mutex()
    }
}
