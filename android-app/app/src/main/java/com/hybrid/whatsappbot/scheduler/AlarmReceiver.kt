package com.hybrid.whatsappbot.scheduler

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.FileProvider
import com.hybrid.whatsappbot.automation.AutomationStateManager
import com.hybrid.whatsappbot.data.AppDatabase
import com.hybrid.whatsappbot.data.TaskStatus
import com.hybrid.whatsappbot.service.ExecutionForegroundService
import com.hybrid.whatsappbot.util.SystemPermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val taskId = intent?.getStringExtra(AlarmSchedulerHelper.EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) {
            Log.w(TAG, "AlarmReceiver triggered without a valid taskId.")
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HybridWhatsAppBot:AlarmReceiverWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(15_000L)
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                routeScheduledTask(context.applicationContext, taskId, powerManager)
            } catch (e: Exception) {
                Log.e(TAG, "Unhandled error in AlarmReceiver for taskId=$taskId", e)
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }

    private suspend fun routeScheduledTask(
        context: Context,
        taskId: String,
        powerManager: PowerManager
    ) {
        val dao = AppDatabase.getInstance(context).taskDao()
        val task = dao.getTaskById(taskId)
        if (task == null) {
            Log.w(TAG, "Task $taskId no longer exists in Room DB. Ignoring alarm.")
            return
        }

        if (task.status == TaskStatus.COMPLETED_UI || task.status == TaskStatus.COMPLETED_BACKGROUND) {
            Log.i(TAG, "Task $taskId is already completed (${task.status}). Skipping duplicate execution.")
            return
        }

        val imageFile = File(task.imageLocalPath)
        if (!imageFile.exists() || imageFile.length() == 0L) {
            dao.updateTaskStatus(
                taskId = taskId,
                newStatus = TaskStatus.FAILED,
                logMessage = "Scheduled image file missing on disk: ${task.imageLocalPath}",
                completedAt = System.currentTimeMillis()
            )
            return
        }

        // =========================================================================
        // SMART STATE DETECTOR (PowerManager.isInteractive + KeyguardManager)
        // =========================================================================
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isScreenInteractive = powerManager.isInteractive
        val isKeyguardLocked = keyguardManager.isKeyguardLocked
        val isAccessibilityEnabled = SystemPermissionHelper.isAccessibilityServiceEnabled(context)
        val whatsAppPackage = resolveInstalledWhatsAppPackage(context)

        val canRunForegroundUiEngine =
            isScreenInteractive && !isKeyguardLocked && isAccessibilityEnabled && whatsAppPackage != null

        Log.i(
            TAG,
            "Smart State Detector for task=$taskId -> " +
                "interactive=$isScreenInteractive, keyguardLocked=$isKeyguardLocked, " +
                "accessibilityEnabled=$isAccessibilityEnabled, whatsAppPkg=$whatsAppPackage " +
                "=> useUiEngine=$canRunForegroundUiEngine"
        )

        if (canRunForegroundUiEngine && whatsAppPackage != null) {
            // =====================================================================
            // ENGINE 1: FOREGROUND UI AUTOMATION + 15-SECOND FAIL-SAFE WATCHDOG
            // =====================================================================
            try {
                dao.updateTaskStatus(
                    taskId = taskId,
                    newStatus = TaskStatus.RUNNING_UI,
                    logMessage = "Phone UNLOCKED & Screen ON -> Running Engine 1 (UI Bot) with 15s Fail-Safe Watchdog."
                )

                // 1. Lock the 25-second window in AutomationStateManager
                AutomationStateManager.activateSession(
                    taskId = task.taskId,
                    targetGroupName = task.targetGroupName,
                    caption = task.caption,
                    windowDurationMs = 25_000L
                )

                // 2. Start 15-second fail-safe Watchdog in ExecutionForegroundService
                ExecutionForegroundService.startWatchdog(context, task.taskId)

                // 3. Build secure FileProvider URI & grant temporary read access to WhatsApp
                val authority = "${context.packageName}.fileprovider"
                val contentUri = FileProvider.getUriForFile(context, authority, imageFile)
                context.grantUriPermission(
                    whatsAppPackage,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                // 4. Launch WhatsApp ACTION_SEND Share Sheet directly
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    `package` = whatsAppPackage
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(Intent.EXTRA_TEXT, task.caption)
                    clipData = ClipData.newRawUri("scheduled_media", contentUri)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                context.startActivity(sendIntent)
                Log.i(TAG, "Launched WhatsApp UI share intent for taskId=$taskId")
            } catch (uiLaunchException: Exception) {
                Log.e(TAG, "Engine 1 failed to launch WhatsApp UI; immediately routing to Engine 2", uiLaunchException)
                AutomationStateManager.clearSession()
                ExecutionForegroundService.startBackgroundBot(
                    context = context,
                    taskId = task.taskId,
                    reason = "UI Launch exception (${uiLaunchException.message}) -> Fallback to Background Bot"
                )
            }
        } else {
            // =====================================================================
            // ENGINE 2: SILENT NODE.JS BACKGROUND BOT
            // =====================================================================
            val reason = when {
                !isScreenInteractive -> "Screen is OFF -> Routing to Engine 2 (Silent Background Bot)"
                isKeyguardLocked -> "Phone is LOCKED (Keyguard active) -> Routing to Engine 2 (Silent Background Bot)"
                !isAccessibilityEnabled -> "Accessibility Service disabled -> Routing to Engine 2 (Silent Background Bot)"
                else -> "WhatsApp APK not detected -> Routing to Engine 2 (Silent Background Bot)"
            }
            Log.i(TAG, reason)
            ExecutionForegroundService.startBackgroundBot(
                context = context,
                taskId = task.taskId,
                reason = reason
            )
        }
    }

    private fun resolveInstalledWhatsAppPackage(context: Context): String? {
        val candidates = listOf("com.whatsapp", "com.whatsapp.w4b")
        val pm = context.packageManager
        for (pkg in candidates) {
            try {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
                // Continue checking next candidate
            }
        }
        return null
    }

    companion object {
        private const val TAG = "AlarmReceiver"
    }
}
