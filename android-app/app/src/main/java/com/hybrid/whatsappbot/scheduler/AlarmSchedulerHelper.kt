package com.hybrid.whatsappbot.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object AlarmSchedulerHelper {

    private const val TAG = "AlarmSchedulerHelper"
    const val EXTRA_TASK_ID = "extra_scheduled_task_id"

    /**
     * Schedules an exact wakeup alarm for the given taskId and timestamp using
     * AlarmManager.setExactAndAllowWhileIdle so it fires even in Doze mode.
     */
    fun scheduleTaskAlarm(context: Context, taskId: String, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, taskId)

        val safeTriggerTime = maxOf(triggerAtMillis, System.currentTimeMillis() + 1_000L)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        safeTriggerTime,
                        pendingIntent
                    )
                    Log.i(TAG, "Exact alarm scheduled for taskId=$taskId at $safeTriggerTime")
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        safeTriggerTime,
                        pendingIntent
                    )
                    Log.w(TAG, "Exact alarm permission missing; scheduled inexact alarm for taskId=$taskId")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    safeTriggerTime,
                    pendingIntent
                )
                Log.i(TAG, "Exact alarm scheduled (pre-S) for taskId=$taskId at $safeTriggerTime")
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException scheduling exact alarm, falling back to setAndAllowWhileIdle", se)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                safeTriggerTime,
                pendingIntent
            )
        }
    }

    /**
     * Cancels a previously scheduled alarm for the given taskId.
     */
    fun cancelTaskAlarm(context: Context, taskId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, taskId)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        Log.i(TAG, "Cancelled alarm for taskId=$taskId")
    }

    /**
     * Immediately triggers the AlarmReceiver for manual "Test Now" execution from the UI.
     */
    fun triggerTaskImmediately(context: Context, taskId: String) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
        }
        context.sendBroadcast(intent)
    }

    private fun buildPendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val requestCode = taskId.hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}
