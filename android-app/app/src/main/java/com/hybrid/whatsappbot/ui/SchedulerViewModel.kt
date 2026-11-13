package com.hybrid.whatsappbot.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hybrid.whatsappbot.data.AppDatabase
import com.hybrid.whatsappbot.data.AppPreferences
import com.hybrid.whatsappbot.data.ScheduledTaskEntity
import com.hybrid.whatsappbot.data.TaskStatus
import com.hybrid.whatsappbot.network.RetrofitClient
import com.hybrid.whatsappbot.network.WhatsAppGroupDto
import com.hybrid.whatsappbot.scheduler.AlarmSchedulerHelper
import com.hybrid.whatsappbot.util.MediaStorageHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class ServerUiState(
    val backendUrl: String = AppPreferences.DEFAULT_BACKEND_URL,
    val connectionStatus: String = "UNKNOWN", // CONNECTED | QR_READY | DISCONNECTED | OFFLINE
    val qrCodeBase64: String? = null,
    val linkedUserName: String? = null,
    val availableGroups: List<WhatsAppGroupDto> = emptyList(),
    val isLoading: Boolean = false,
    val statusBannerMessage: String? = null
)

class SchedulerViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val taskDao = AppDatabase.getInstance(appContext).taskDao()
    private val preferences = AppPreferences(appContext)

    val tasksState: StateFlow<List<ScheduledTaskEntity>> = taskDao.observeAllTasks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    private val _serverUiState = MutableStateFlow(
        ServerUiState(backendUrl = preferences.backendBaseUrl)
    )
    val serverUiState: StateFlow<ServerUiState> = _serverUiState.asStateFlow()

    init {
        refreshServerStatus()
    }

    fun updateBackendUrl(newUrl: String) {
        val cleaned = newUrl.trim()
        preferences.backendBaseUrl = cleaned
        _serverUiState.update { it.copy(backendUrl = preferences.backendBaseUrl) }
        refreshServerStatus()
    }

    fun refreshServerStatus() {
        viewModelScope.launch {
            _serverUiState.update { it.copy(isLoading = true, statusBannerMessage = null) }
            try {
                val api = RetrofitClient.getApiService(appContext)
                val response = api.getServerStatus()
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    _serverUiState.update {
                        it.copy(
                            isLoading = false,
                            connectionStatus = body.status,
                            qrCodeBase64 = body.qrCode,
                            linkedUserName = body.user?.name ?: body.user?.id,
                            statusBannerMessage = "Server reachable (${body.status})"
                        )
                    }
                } else {
                    _serverUiState.update {
                        it.copy(
                            isLoading = false,
                            connectionStatus = "ERROR",
                            statusBannerMessage = "Server returned HTTP ${response.code()}"
                        )
                    }
                }
            } catch (e: Exception) {
                _serverUiState.update {
                    it.copy(
                        isLoading = false,
                        connectionStatus = "OFFLINE",
                        qrCodeBase64 = null,
                        statusBannerMessage = "Cannot reach Node.js server: ${e.localizedMessage ?: "Check IP/Port"}"
                    )
                }
            }
        }
    }

    fun syncGroupsFromServer(onGroupsLoaded: (List<WhatsAppGroupDto>) -> Unit = {}) {
        viewModelScope.launch {
            _serverUiState.update { it.copy(isLoading = true, statusBannerMessage = null) }
            try {
                val api = RetrofitClient.getApiService(appContext)
                val response = api.getParticipatingGroups()
                val body = response.body()
                if (response.isSuccessful && body != null && body.success) {
                    _serverUiState.update {
                        it.copy(
                            isLoading = false,
                            availableGroups = body.groups,
                            statusBannerMessage = "Synced ${body.groups.size} WhatsApp groups."
                        )
                    }
                    onGroupsLoaded(body.groups)
                } else {
                    val errorMsg = body?.error ?: "HTTP ${response.code()}"
                    _serverUiState.update {
                        it.copy(
                            isLoading = false,
                            statusBannerMessage = "Group sync failed: $errorMsg"
                        )
                    }
                }
            } catch (e: Exception) {
                _serverUiState.update {
                    it.copy(
                        isLoading = false,
                        statusBannerMessage = "Group sync error: ${e.localizedMessage ?: "Unreachable"}"
                    )
                }
            }
        }
    }

    fun scheduleTask(
        sourceImageUri: Uri,
        caption: String,
        targetGroupName: String,
        targetGroupJid: String?,
        scheduledTimeMillis: Long,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val copiedFile = MediaStorageHelper.copyUriToInternalStorage(appContext, sourceImageUri)
                val taskId = UUID.randomUUID().toString()

                val entity = ScheduledTaskEntity(
                    taskId = taskId,
                    imageLocalPath = copiedFile.absolutePath,
                    caption = caption.trim(),
                    targetGroupName = targetGroupName.trim(),
                    targetGroupJid = targetGroupJid?.trim()?.takeIf { it.isNotEmpty() },
                    scheduledTimeMillis = scheduledTimeMillis,
                    status = TaskStatus.PENDING,
                    executionLog = "Scheduled via AlarmManager (setExactAndAllowWhileIdle)"
                )

                taskDao.upsertTask(entity)
                AlarmSchedulerHelper.scheduleTaskAlarm(
                    context = appContext,
                    taskId = taskId,
                    triggerAtMillis = scheduledTimeMillis
                )
                onComplete(true, "Task scheduled for \"${entity.targetGroupName}\"!")
            } catch (e: Exception) {
                onComplete(false, "Failed to schedule task: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }
    }

    fun testTaskNow(task: ScheduledTaskEntity) {
        viewModelScope.launch {
            taskDao.updateTaskStatus(
                taskId = task.taskId,
                newStatus = TaskStatus.PENDING,
                logMessage = "Manual 'Test Now' triggered by user...",
                completedAt = null
            )
            AlarmSchedulerHelper.triggerTaskImmediately(appContext, task.taskId)
        }
    }

    fun deleteTask(task: ScheduledTaskEntity) {
        viewModelScope.launch {
            AlarmSchedulerHelper.cancelTaskAlarm(appContext, task.taskId)
            runCatching {
                val file = File(task.imageLocalPath)
                if (file.exists()) {
                    file.delete()
                }
            }
            taskDao.deleteTaskById(task.taskId)
        }
    }
}
