package com.hybrid.whatsappbot.automation

import android.content.Context
import com.hybrid.whatsappbot.data.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 6-State Sequential State Machine for WhatsApp Two-Stage Media Share + Auto-Delete Previous Post.
 */
enum class UiBotSubState {
    IDLE,
    STATE_1_CLICK_SEARCH,
    STATE_2_TYPE_QUERY,
    STATE_3_SELECT_GROUP,
    STATE_4_PICKER_OR_PREVIEW_SEND,
    STATE_5_DELETE_PREVIOUS_IF_EXISTS,
    STATE_6_DONE
}

data class ActiveAutomationSession(
    val taskId: String,
    val targetGroupName: String,
    val caption: String,
    val expiryTimestampMillis: Long,
    val subState: UiBotSubState,
    val stateEnteredAtMillis: Long = System.currentTimeMillis(),
    val attemptCountInState: Int = 0,
    val pickerFabClicked: Boolean = false,
    val previousCaptionSnippet: String = ""
)

/**
 * Thread-safe singleton managing the automation lock window, single-shot send lock,
 * and per-group previous post tracking for Auto-Delete Previous Post on Next Send.
 */
object AutomationStateManager {

    private const val DEFAULT_WINDOW_DURATION_MS = 25_000L

    private val sessionRef = AtomicReference<ActiveAutomationSession?>(null)
    private val finalSendClicked = AtomicBoolean(false)
    private val deletePreviousFlowStarted = AtomicBoolean(false)
    private val lastActivityMillis = AtomicLong(0L)

    // In-memory cache mirroring AppPreferences for fast lookups
    private val activeGroupPostsMap = ConcurrentHashMap<String, String>()

    private val _sessionFlow = MutableStateFlow<ActiveAutomationSession?>(null)
    val sessionFlow: StateFlow<ActiveAutomationSession?> = _sessionFlow.asStateFlow()

    /**
     * Activates UI automation for a specific scheduled task and resets single-shot locks.
     */
    fun activateSession(
        taskId: String,
        targetGroupName: String,
        caption: String,
        windowDurationMs: Long = DEFAULT_WINDOW_DURATION_MS
    ) {
        val now = System.currentTimeMillis()
        finalSendClicked.set(false)
        deletePreviousFlowStarted.set(false)
        lastActivityMillis.set(now)

        val session = ActiveAutomationSession(
            taskId = taskId,
            targetGroupName = targetGroupName.trim(),
            caption = caption,
            expiryTimestampMillis = now + windowDurationMs,
            subState = UiBotSubState.STATE_1_CLICK_SEARCH,
            stateEnteredAtMillis = now,
            attemptCountInState = 0,
            pickerFabClicked = false
        )
        sessionRef.set(session)
        _sessionFlow.value = session
    }

    /**
     * Checks if our bot has already sent a previous post in [groupName].
     */
    fun hasPreviousPostInGroup(context: Context, groupName: String): Boolean {
        val key = groupName.trim().lowercase()
        if (key.isEmpty()) return false
        if (activeGroupPostsMap.containsKey(key)) return true
        val persisted = AppPreferences(context).hasPreviousPostInGroup(groupName)
        if (persisted) {
            val snippet = AppPreferences(context).getLastSentCaptionSnippet(groupName)
            activeGroupPostsMap[key] = snippet
        }
        return persisted
    }

    /**
     * Returns the caption snippet of the last post sent by our bot in [groupName].
     */
    fun lastSentCaptionSnippet(context: Context, groupName: String): String {
        val key = groupName.trim().lowercase()
        return activeGroupPostsMap[key]
            ?: AppPreferences(context).getLastSentCaptionSnippet(groupName)
    }

    /**
     * Records that our bot has now sent a post in [groupName] with [caption].
     */
    fun markPostSentInGroup(context: Context, groupName: String, caption: String) {
        val key = groupName.trim().lowercase()
        if (key.isEmpty()) return
        val snippet = caption.trim().lineSequence().firstOrNull()?.take(40)?.trim()
            ?: caption.trim().take(40)
        activeGroupPostsMap[key] = snippet
        AppPreferences(context).markPostSentInGroup(groupName, caption)
    }

    fun markBotActivityNow() {
        lastActivityMillis.set(System.currentTimeMillis())
    }

    fun wasRecentlyActive(cooldownWindowMs: Long = 15_000L): Boolean {
        if (isTaskActive()) return true
        val last = lastActivityMillis.get()
        if (last <= 0L) return false
        return (System.currentTimeMillis() - last) < cooldownWindowMs
    }

    /**
     * Returns the current session ONLY if it is active, not expired, and not in STATE_6_DONE.
     */
    fun getValidSession(): ActiveAutomationSession? {
        val current = sessionRef.get() ?: return null
        val now = System.currentTimeMillis()
        if (now >= current.expiryTimestampMillis || current.subState == UiBotSubState.STATE_6_DONE) {
            if (now >= current.expiryTimestampMillis) {
                clearSession()
            }
            return null
        }
        return current
    }

    fun isTaskActive(): Boolean = getValidSession() != null

    /**
     * Transitions the active session to a new sequential state and resets attempt counters.
     */
    fun transitionToState(
        taskId: String,
        newState: UiBotSubState,
        pickerFabClicked: Boolean = false,
        previousCaptionSnippet: String = ""
    ): Boolean {
        while (true) {
            val current = getValidSession() ?: return false
            if (current.taskId != taskId) return false
            if (current.subState == UiBotSubState.STATE_6_DONE) return false

            val now = System.currentTimeMillis()
            lastActivityMillis.set(now)
            val updated = current.copy(
                subState = newState,
                stateEnteredAtMillis = now,
                attemptCountInState = 0,
                pickerFabClicked = pickerFabClicked,
                previousCaptionSnippet = previousCaptionSnippet.ifEmpty { current.previousCaptionSnippet }
            )
            if (sessionRef.compareAndSet(current, updated)) {
                _sessionFlow.value = updated
                return true
            }
        }
    }

    fun incrementAttempt(taskId: String, markPickerFabClicked: Boolean? = null): Int {
        while (true) {
            val current = getValidSession() ?: return 0
            if (current.taskId != taskId) return 0

            val nextAttempt = current.attemptCountInState + 1
            val updated = current.copy(
                attemptCountInState = nextAttempt,
                pickerFabClicked = markPickerFabClicked ?: current.pickerFabClicked
            )
            if (sessionRef.compareAndSet(current, updated)) {
                _sessionFlow.value = updated
                return nextAttempt
            }
        }
    }

    /**
     * CRITICAL ATOMIC SINGLE-SHOT SEND LOCK:
     * Ensures the Media Preview Send button is clicked at most once per task.
     */
    fun markFinalSendClicked(): Boolean {
        if (!finalSendClicked.compareAndSet(false, true)) {
            return false
        }
        lastActivityMillis.set(System.currentTimeMillis())
        return true
    }

    /**
     * Single-execution lock for STATE_5_DELETE_PREVIOUS_IF_EXISTS so the post-send
     * delete flow runs at most once per task.
     */
    fun tryLockDeletePreviousFlow(): Boolean {
        if (!deletePreviousFlowStarted.compareAndSet(false, true)) {
            return false
        }
        lastActivityMillis.set(System.currentTimeMillis())
        return true
    }

    /**
     * Completely clears the active automation state and marks STATE_6_DONE.
     */
    fun clearSession() {
        val now = System.currentTimeMillis()
        lastActivityMillis.set(now)
        val current = sessionRef.get()
        if (current != null && current.subState != UiBotSubState.STATE_6_DONE) {
            sessionRef.set(current.copy(subState = UiBotSubState.STATE_6_DONE, stateEnteredAtMillis = now))
        }
        sessionRef.set(null)
        _sessionFlow.value = null
    }
}
