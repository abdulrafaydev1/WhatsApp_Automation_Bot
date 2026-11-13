package com.hybrid.whatsappbot.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hybrid.whatsappbot.data.AppDatabase
import com.hybrid.whatsappbot.data.AppPreferences
import com.hybrid.whatsappbot.data.AutoReplyRuleEntity
import com.hybrid.whatsappbot.data.ScheduledTaskEntity
import com.hybrid.whatsappbot.data.TaskStatus
import com.hybrid.whatsappbot.network.RetrofitClient
import com.hybrid.whatsappbot.network.WhatsAppGroupDto
import com.hybrid.whatsappbot.scheduler.AlarmSchedulerHelper
import com.hybrid.whatsappbot.util.MediaStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val db = AppDatabase.getInstance(appContext)
    private val taskDao = db.taskDao()
    private val autoRuleDao = db.autoReplyRuleDao()
    private val preferences = AppPreferences(appContext)

    val tasksState: StateFlow<List<ScheduledTaskEntity>> = taskDao.observeAllTasks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    val autoRulesState: StateFlow<List<AutoReplyRuleEntity>> = autoRuleDao.observeAllRules()
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

    fun createAutoReplyRule(
        sourceImageUri: Uri,
        caption: String,
        targetGroupName: String,
        targetGroupJid: String?,
        matchAllGroups: Boolean,
        cooldownMillis: Long,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val copiedFile = MediaStorageHelper.copyUriToInternalStorage(appContext, sourceImageUri)
                val ruleId = UUID.randomUUID().toString()
                val effectiveGroupName = if (matchAllGroups) "ALL_GROUPS" else targetGroupName.trim()

                val rule = AutoReplyRuleEntity(
                    ruleId = ruleId,
                    imageLocalPath = copiedFile.absolutePath,
                    caption = caption.trim(),
                    targetGroupName = effectiveGroupName,
                    targetGroupJid = if (matchAllGroups) null else targetGroupJid?.trim()?.takeIf { it.isNotEmpty() },
                    matchAllGroups = matchAllGroups,
                    cooldownMillis = cooldownMillis,
                    isEnabled = true
                )

                autoRuleDao.upsertRule(rule)

                // Best-effort sync to Node.js backend in background
                syncRuleToBackendAsync(rule, includeImageFile = true)

                val modeDesc = if (matchAllGroups) "Any WhatsApp Group" else "\"$effectiveGroupName\""
                onComplete(true, "Auto-Responder rule activated for $modeDesc!")
            } catch (e: Exception) {
                onComplete(false, "Failed to create rule: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }
    }

    fun toggleAutoReplyRule(rule: AutoReplyRuleEntity, enabled: Boolean) {
        viewModelScope.launch {
            autoRuleDao.setRuleEnabled(rule.ruleId, enabled)
            val updated = rule.copy(isEnabled = enabled)
            syncRuleToBackendAsync(updated, includeImageFile = false)
        }
    }

    fun deleteAutoReplyRule(rule: AutoReplyRuleEntity) {
        viewModelScope.launch {
            runCatching {
                val file = File(rule.imageLocalPath)
                if (file.exists()) {
                    file.delete()
                }
            }
            autoRuleDao.deleteRuleById(rule.ruleId)
            launch(Dispatchers.IO) {
                runCatching {
                    RetrofitClient.getApiService(appContext).deleteAutoRule(rule.ruleId)
                }
            }
        }
    }

    private fun syncRuleToBackendAsync(rule: AutoReplyRuleEntity, includeImageFile: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val textType = "text/plain".toMediaTypeOrNull()
                val imagePart = if (includeImageFile) {
                    val imgFile = File(rule.imageLocalPath)
                    if (imgFile.exists()) {
                        MultipartBody.Part.createFormData(
                            "image",
                            imgFile.name,
                            imgFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                        )
                    } else null
                } else null

                RetrofitClient.getApiService(appContext).syncAutoRule(
                    ruleId = rule.ruleId.toRequestBody(textType),
                    targetGroupName = rule.targetGroupName.toRequestBody(textType),
                    targetGroupJid = (rule.targetGroupJid ?: "").toRequestBody(textType),
                    matchAllGroups = rule.matchAllGroups.toString().toRequestBody(textType),
                    caption = rule.caption.toRequestBody(textType),
                    cooldownMillis = rule.cooldownMillis.toString().toRequestBody(textType),
                    isEnabled = rule.isEnabled.toString().toRequestBody(textType),
                    image = imagePart
                )
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
