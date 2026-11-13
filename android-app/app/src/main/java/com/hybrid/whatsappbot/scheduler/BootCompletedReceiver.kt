package com.hybrid.whatsappbot.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hybrid.whatsappbot.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-registers all PENDING future tasks with AlarmManager whenever the device reboots
 * or the app package is updated.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        Log.i(TAG, "Device boot or package update detected ($action). Re-registering pending alarms...")
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context.applicationContext).taskDao()
                val now = System.currentTimeMillis()
                val pendingTasks = dao.getPendingFutureTasks(now - 60_000L)
                for (task in pendingTasks) {
                    AlarmSchedulerHelper.scheduleTaskAlarm(
                        context = context.applicationContext,
                        taskId = task.taskId,
                        triggerAtMillis = task.scheduledTimeMillis
                    )
                }
                Log.i(TAG, "Successfully re-registered ${pendingTasks.size} pending task alarms.")
            } catch (e: Exception) {
                Log.e(TAG, "Error re-registering alarms after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
