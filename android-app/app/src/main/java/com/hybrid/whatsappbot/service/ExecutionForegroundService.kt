package com.hybrid.whatsappbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hybrid.whatsappbot.automation.AutomationStateManager
import com.hybrid.whatsappbot.data.AppDatabase
import com.hybrid.whatsappbot.data.TaskStatus
import com.hybrid.whatsappbot.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ExecutionForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeJobCount = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PromoteToForeground("Executing scheduled WhatsApp task...")

        val action = intent?.action
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "Background execution"

        if (taskId.isNullOrBlank()) {
            checkAndStopServiceIfIdle()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START_WATCHDOG -> {
                val jobKey = "watchdog_$taskId"
                if (!activeJobs.containsKey(jobKey)) {
                    activeJobCount.incrementAndGet()
                    val job = serviceScope.launch {
                        try {
                            runFifteenSecondWatchdog(taskId)
                        } finally {
                            activeJobs.remove(jobKey)
                            if (activeJobCount.decrementAndGet() <= 0) {
                                checkAndStopServiceIfIdle()
                            }
                        }
                    }
                    activeJobs[jobKey] = job
                }
            }

            ACTION_EXECUTE_BACKGROUND_BOT -> {
                val jobKey = "bg_$taskId"
                if (!activeJobs.containsKey(jobKey)) {
                    activeJobCount.incrementAndGet()
                    val job = serviceScope.launch {
                        try {
                            executeBackgroundBot(taskId, reason)
                        } finally {
                            activeJobs.remove(jobKey)
                            if (activeJobCount.decrementAndGet() <= 0) {
                                checkAndStopServiceIfIdle()
                            }
                        }
                    }
                    activeJobs[jobKey] = job
                }
            }

            else -> {
                checkAndStopServiceIfIdle()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * 15-Second Fail-Safe Watchdog:
     * Monitors Room DB every 500ms after Engine 1 (UI Bot) starts.
     * If the task is not marked COMPLETED_UI within 15 seconds, it immediately aborts
     * AutomationStateManager and triggers Engine 2 (Node.js Background Bot) with the same taskId.
     */
    private suspend fun runFifteenSecondWatchdog(taskId: String) {
        val dao = AppDatabase.getInstance(applicationContext).taskDao()
        val deadline = System.currentTimeMillis() + WATCHDOG_TIMEOUT_MS

        Log.i(TAG, "Started 15-second Fail-Safe Watchdog for taskId=$taskId")

        while (System.currentTimeMillis() < deadline) {
            delay(500L)
            val currentTask = dao.getTaskById(taskId) ?: return
            if (currentTask.status == TaskStatus.COMPLETED_UI ||
                currentTask.status == TaskStatus.COMPLETED_BACKGROUND
            ) {
                Log.i(TAG, "Watchdog confirmed task $taskId finished via ${currentTask.status}. Disarming watchdog.")
                return
            }
        }

        // 15 seconds elapsed without completion: Abort UI automation & fallback to Engine 2
        Log.w(TAG, "Watchdog 15s timeout reached for taskId=$taskId! Aborting UI Bot & falling back to Engine 2.")
        AutomationStateManager.clearSession()

        val afterClearTask = dao.getTaskById(taskId) ?: return
        if (afterClearTask.status == TaskStatus.COMPLETED_UI ||
            afterClearTask.status == TaskStatus.COMPLETED_BACKGROUND
        ) {
            return
        }

        executeBackgroundBot(
            taskId = taskId,
            reason = "15s UI Watchdog Timeout -> Automatic Fallback to Engine 2 (Node.js Bot)"
        )
    }

    /**
     * Engine 2: Sends the scheduled image, caption, groupName, and idempotent taskId to
     * the Node.js Baileys backend over multipart/form-data while the phone stays locked.
     */
    private suspend fun executeBackgroundBot(taskId: String, reason: String) {
        val dao = AppDatabase.getInstance(applicationContext).taskDao()
        val task = dao.getTaskById(taskId) ?: return

        // Atomic check-and-set so we never double-execute if UI Bot just finished
        val updatedRows = dao.updateStatusIfNotCompleted(
            taskId = taskId,
            newStatus = TaskStatus.RUNNING_BACKGROUND,
            logMessage = "$reason | Uploading to Node.js Background Server..."
        )
        if (updatedRows == 0) {
            Log.i(TAG, "Task $taskId already completed prior to background upload. Skipping.")
            return
        }

        val imageFile = File(task.imageLocalPath)
        if (!imageFile.exists() || imageFile.length() == 0L) {
            dao.updateStatusIfNotCompleted(
                taskId = taskId,
                newStatus = TaskStatus.FAILED,
                logMessage = "Image file missing at ${task.imageLocalPath}",
                completedAt = System.currentTimeMillis()
            )
            postOutcomeNotification(
                taskId = taskId,
                title = "Scheduled Task Failed",
                body = "Image file missing for group ${task.targetGroupName}"
            )
            return
        }

        try {
            val textMediaType = "text/plain".toMediaTypeOrNull()
            val imageMediaType = "image/jpeg".toMediaTypeOrNull()

            val taskIdBody = task.taskId.toRequestBody(textMediaType)
            val groupNameBody = task.targetGroupName.toRequestBody(textMediaType)
            val groupJidBody = (task.targetGroupJid ?: "").toRequestBody(textMediaType)
            val captionBody = task.caption.toRequestBody(textMediaType)

            val imageRequestBody = imageFile.asRequestBody(imageMediaType)
            val imagePart = MultipartBody.Part.createFormData(
                "image",
                imageFile.name,
                imageRequestBody
            )

            val api = RetrofitClient.getApiService(applicationContext)
            val response = api.sendGroupMedia(
                taskId = taskIdBody,
                groupName = groupNameBody,
                groupJid = groupJidBody,
                caption = captionBody,
                image = imagePart
            )

            val body = response.body()
            if (response.isSuccessful && body != null && body.success) {
                val msgIdInfo = body.messageId ?: "OK"
                dao.updateTaskStatus(
                    taskId = taskId,
                    newStatus = TaskStatus.COMPLETED_BACKGROUND,
                    logMessage = "Sent silently via Node.js Background Bot (msgId=$msgIdInfo) | Trigger: $reason",
                    completedAt = System.currentTimeMillis()
                )
                postOutcomeNotification(
                    taskId = taskId,
                    title = "WhatsApp Media Sent (Background Bot)",
                    body = "Delivered to \"${task.targetGroupName}\" while phone was locked/idle."
                )
                Log.i(TAG, "Engine 2 succeeded for taskId=$taskId (msgId=$msgIdInfo)")
            } else {
                val errBody = response.errorBody()?.string() ?: body?.error ?: "HTTP ${response.code()}"
                dao.updateStatusIfNotCompleted(
                    taskId = taskId,
                    newStatus = TaskStatus.FAILED,
                    logMessage = "Background Server Error: $errBody",
                    completedAt = System.currentTimeMillis()
                )
                postOutcomeNotification(
                    taskId = taskId,
                    title = "Scheduled WhatsApp Task Failed",
                    body = "Group \"${task.targetGroupName}\": $errBody"
                )
                Log.e(TAG, "Engine 2 HTTP error for taskId=$taskId: $errBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Engine 2 network exception for taskId=$taskId", e)
            dao.updateStatusIfNotCompleted(
                taskId = taskId,
                newStatus = TaskStatus.FAILED,
                logMessage = "Network failure contacting Node.js server: ${e.localizedMessage ?: e.javaClass.simpleName}",
                completedAt = System.currentTimeMillis()
            )
            postOutcomeNotification(
                taskId = taskId,
                title = "Scheduled WhatsApp Task Failed",
                body = "Could not reach Node.js server for \"${task.targetGroupName}\"."
            )
        }
    }

    private fun PromoteToForeground(statusText: String) {
        val notification = buildForegroundNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Hybrid WhatsApp Scheduler")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun postOutcomeNotification(taskId: String, title: String, body: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        manager.notify(taskId.hashCode(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Scheduled WhatsApp Execution",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for Hybrid WhatsApp Media & Text Scheduler"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HybridWhatsAppBot:ExecutionForegroundWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(60_000L) // Max 60-second safety cap
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun checkAndStopServiceIfIdle() {
        if (activeJobCount.get() <= 0 && activeJobs.isEmpty()) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ExecutionFgService"
        private const val CHANNEL_ID = "hybrid_scheduler_execution_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 9001
        private const val WATCHDOG_TIMEOUT_MS = 15_000L

        const val ACTION_START_WATCHDOG = "com.hybrid.whatsappbot.action.START_WATCHDOG"
        const val ACTION_EXECUTE_BACKGROUND_BOT = "com.hybrid.whatsappbot.action.EXECUTE_BACKGROUND_BOT"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_REASON = "extra_reason"

        fun startWatchdog(context: Context, taskId: String) {
            val intent = Intent(context, ExecutionForegroundService::class.java).apply {
                action = ACTION_START_WATCHDOG
                putExtra(EXTRA_TASK_ID, taskId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startBackgroundBot(context: Context, taskId: String, reason: String) {
            val intent = Intent(context, ExecutionForegroundService::class.java).apply {
                action = ACTION_EXECUTE_BACKGROUND_BOT
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_REASON, reason)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
