package com.hybrid.whatsappbot.service

import android.app.Notification
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
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WhatsAppNotificationListener : NotificationListenerService() {

    private val listenerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        listenerScope.launch {
            try {
                evaluateAndTriggerAutoRules(
                    detectedGroupName = detectedGroupName,
                    incomingSnippet = messageText ?: ""
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing group notification for '$detectedGroupName'", e)
            }
        }
    }

    private suspend fun evaluateAndTriggerAutoRules(
        detectedGroupName: String,
        incomingSnippet: String
    ) {
        // Re-check active/recent automation inside coroutine
        if (AutomationStateManager.wasRecentlyActive(15_000L)) {
            return
        }

        val db = AppDatabase.getInstance(applicationContext)
        val ruleDao = db.autoReplyRuleDao()
        val taskDao = db.taskDao()

        val enabledRules = ruleDao.getEnabledRules()
        if (enabledRules.isEmpty()) return

        val now = System.currentTimeMillis()

        for (rule in enabledRules) {
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

            if (!isMatch) continue

            // Per-Rule + Per-Group Cooldown Check (in-memory + Room DB fallback)
            val cooldownKey = "${rule.ruleId}_${detectedGroupName.lowercase()}"
            val inMemoryLast = lastTriggeredMap[cooldownKey] ?: 0L
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
                    "Auto-Rule ${rule.ruleId} matched group '$detectedGroupName', " +
                        "but cooldown active (${remainingSec}s remaining)."
                )
                continue
            }

            val imageFile = File(rule.imageLocalPath)
            if (!imageFile.exists() || imageFile.length() == 0L) {
                Log.w(TAG, "Auto-Rule ${rule.ruleId} image missing at ${rule.imageLocalPath}")
                continue
            }

            // Lock cooldown & anti-self-trigger timestamps immediately
            lastTriggeredMap[cooldownKey] = now
            AutomationStateManager.markBotActivityNow()
            ruleDao.recordRuleTriggered(
                ruleId = rule.ruleId,
                triggeredAtMillis = now,
                triggeredGroup = detectedGroupName
            )

            // Resolve the target group name & JID to dispatch into
            val targetGroupToSend = if (rule.matchAllGroups) {
                detectedGroupName
            } else {
                rule.targetGroupName.ifBlank { detectedGroupName }
            }
            val targetJidToSend = if (rule.matchAllGroups) null else rule.targetGroupJid

            val generatedTaskId = "auto_${UUID.randomUUID()}"
            val previewSnippet = incomingSnippet.take(36).ifBlank { "Group post" }

            val autoTask = ScheduledTaskEntity(
                taskId = generatedTaskId,
                imageLocalPath = rule.imageLocalPath,
                caption = rule.caption,
                targetGroupName = targetGroupToSend,
                targetGroupJid = targetJidToSend,
                scheduledTimeMillis = now,
                status = TaskStatus.PENDING,
                executionLog = "Auto-Responder triggered by post in \"$detectedGroupName\" ($previewSnippet)"
            )

            taskDao.upsertTask(autoTask)

            Log.i(
                TAG,
                "Auto-Responder Rule ${rule.ruleId} triggered for group '$targetGroupToSend'! " +
                    "Dispatching taskId=$generatedTaskId via Smart State Detector..."
            )

            // Dispatch immediately through AlarmReceiver's Smart State Detector:
            // - Unlocked + Screen ON -> Engine 1 (WhatsAppAccessibilityService UI Bot + 15s Watchdog)
            // - Locked or Screen OFF -> Engine 2 (ExecutionForegroundService -> Node.js Baileys Bot)
            AlarmSchedulerHelper.triggerTaskImmediately(applicationContext, generatedTaskId)

            // Only trigger one rule per notification event to avoid colliding UI intents
            break
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

        // Remove trailing "(3 messages)" or "(5 new messages)" badges
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
        listenerScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WANotifListener"
        private val lastTriggeredMap = ConcurrentHashMap<String, Long>()
    }
}
