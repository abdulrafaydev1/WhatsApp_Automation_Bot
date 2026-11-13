package com.hybrid.whatsappbot.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 5-State Sequential State Machine for WhatsApp Two-Stage Media Share Automation.
 */
enum class UiBotSubState {
    IDLE,
    STATE_1_CLICK_SEARCH,
    STATE_2_TYPE_QUERY,
    STATE_3_SELECT_GROUP,
    STATE_4_PICKER_OR_PREVIEW_SEND,
    STATE_5_DONE
}

data class ActiveAutomationSession(
    val taskId: String,
    val targetGroupName: String,
    val caption: String,
    val expiryTimestampMillis: Long,
    val subState: UiBotSubState,
    val stateEnteredAtMillis: Long = System.currentTimeMillis(),
    val attemptCountInState: Int = 0,
    val pickerFabClicked: Boolean = false
)

/**
 * Thread-safe singleton managing the 20-second automation lock window and single-shot send lock.
 */
object AutomationStateManager {

    private const val DEFAULT_WINDOW_DURATION_MS = 20_000L

    private val sessionRef = AtomicReference<ActiveAutomationSession?>(null)
    private val finalSendClicked = AtomicBoolean(false)
    private val lastActivityMillis = AtomicLong(0L)

    private val _sessionFlow = MutableStateFlow<ActiveAutomationSession?>(null)
    val sessionFlow: StateFlow<ActiveAutomationSession?> = _sessionFlow.asStateFlow()

    /**
     * Activates UI automation for a specific scheduled task with a strict 20-second expiry
     * and resets the atomic single-shot finalSendClicked lock to false.
     */
    fun activateSession(
        taskId: String,
        targetGroupName: String,
        caption: String,
        windowDurationMs: Long = DEFAULT_WINDOW_DURATION_MS
    ) {
        val now = System.currentTimeMillis()
        finalSendClicked.set(false)
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
     * Records a bot send event (UI or Background) so NotificationListenerService ignores
     * self-triggered notifications for the next 15 seconds.
     */
    fun markBotActivityNow() {
        lastActivityMillis.set(System.currentTimeMillis())
    }

    /**
     * Returns true if a task is currently active OR finished within the last [cooldownWindowMs].
     */
    fun wasRecentlyActive(cooldownWindowMs: Long = 15_000L): Boolean {
        if (isTaskActive()) return true
        val last = lastActivityMillis.get()
        if (last <= 0L) return false
        return (System.currentTimeMillis() - last) < cooldownWindowMs
    }

    /**
     * Returns the current session ONLY if it is active, not expired, finalSendClicked is false,
     * and subState is not STATE_5_DONE.
     */
    fun getValidSession(): ActiveAutomationSession? {
        if (finalSendClicked.get()) return null
        val current = sessionRef.get() ?: return null
        val now = System.currentTimeMillis()
        if (now >= current.expiryTimestampMillis || current.subState == UiBotSubState.STATE_5_DONE) {
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
        pickerFabClicked: Boolean = false
    ): Boolean {
        while (true) {
            val current = getValidSession() ?: return false
            if (current.taskId != taskId) return false
            if (current.subState == UiBotSubState.STATE_5_DONE) return false

            val now = System.currentTimeMillis()
            lastActivityMillis.set(now)
            val updated = current.copy(
                subState = newState,
                stateEnteredAtMillis = now,
                attemptCountInState = 0,
                pickerFabClicked = pickerFabClicked
            )
            if (sessionRef.compareAndSet(current, updated)) {
                _sessionFlow.value = updated
                return true
            }
        }
    }

    /**
     * Increments the attempt count within the current state.
     */
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
     * Uses AtomicBoolean.compareAndSet(false, true) and immediately clears the active session
     * BEFORE the Media Preview Send button click is dispatched.
     * Guarantees the Send button can never be clicked a second time.
     */
    fun markFinalSendClicked(): Boolean {
        if (!finalSendClicked.compareAndSet(false, true)) {
            return false
        }
        val now = System.currentTimeMillis()
        lastActivityMillis.set(now)
        val current = sessionRef.get()
        if (current != null) {
            val doneSession = current.copy(
                subState = UiBotSubState.STATE_5_DONE,
                stateEnteredAtMillis = now
            )
            sessionRef.set(doneSession)
        }
        _sessionFlow.value = null
        return true
    }

    /**
     * Completely clears the active automation state.
     */
    fun clearSession() {
        lastActivityMillis.set(System.currentTimeMillis())
        sessionRef.set(null)
        _sessionFlow.value = null
    }
}
